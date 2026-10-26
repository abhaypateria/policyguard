package com.policyguard.service;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to the LLM through a FAILOVER CHAIN (default order: Groq -> Gemini -> local Ollama).
 *
 * All three expose the same OpenAI-compatible API, so one Spring AI OpenAiChatModel class
 * serves all of them - only the base URL, key and model name differ. If a provider errors or
 * hits its rate limit (HTTP 429), it is put on a short cooldown and the next one is tried.
 * A free-tier quota running out therefore degrades to a slower model instead of an outage.
 *
 * We call the ChatModel with pre-built System/User messages (instead of ChatClient's template
 * strings) because our prompts contain literal JSON braces, which templates would try to parse.
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    public record LlmResult(String text, String provider, long latencyMs) {}

    public record ProviderStatus(String name, String model, boolean configured, boolean coolingDown) {}

    private record Client(String name, String model, OpenAiChatModel chatModel, boolean lastResort) {}

    private final List<Client> clients = new ArrayList<>();
    private final List<ProviderStatus> declared = new ArrayList<>();
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    /** The evaluation pins ONE provider so plain and self-correcting RAG are compared on the same model. */
    private final ThreadLocal<String> pinned = new ThreadLocal<>();

    public LlmGateway(PolicyGuardProperties props) {
        PolicyGuardProperties.Llm llm = props.llm();
        for (PolicyGuardProperties.Provider p : llm.providers()) {
            declared.add(new ProviderStatus(p.name(), p.model(), p.usable(), false));
            if (!p.usable()) continue;

            HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(llm.connectTimeoutSeconds()))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
            requestFactory.setReadTimeout(Duration.ofSeconds(llm.readTimeoutSeconds()));

            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(p.baseUrl())
                    .apiKey(p.apiKey())
                    .completionsPath(p.completionsPath())
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build();

            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(p.model())
                    .temperature(0.0)   // deterministic: compliance answers should not be "creative"
                    .maxTokens(1600);   // headroom: reasoning models spend hidden tokens before answering
            if (p.reasoningEffort() != null && !p.reasoningEffort().isBlank()) {
                options.reasoningEffort(p.reasoningEffort());
            }
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options.build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()) // fail fast -> next provider
                    .build();
            clients.add(new Client(p.name(), p.model(), model, p.lastResort()));
            log.info("LLM provider '{}' enabled with model '{}'", p.name(), p.model());
        }
        if (clients.isEmpty()) {
            log.warn("No LLM provider configured - set GROQ_API_KEY / GEMINI_API_KEY or run Ollama locally");
        }
    }

    /** Run body with every LLM call forced onto one named provider (no failover). */
    public <T> T withProvider(String providerName, java.util.function.Supplier<T> body) {
        String previous = pinned.get();
        pinned.set(providerName);
        try {
            return body.get();
        } finally {
            if (previous == null) pinned.remove();
            else pinned.set(previous);
        }
    }

    /**
     * 1. Try the fast cloud providers in order, skipping any on cooldown.
     * 2. If they are all briefly rate-limited and one frees up within 15 s, wait for it -
     *    a few seconds' wait beats a minute on a CPU-only local model.
     * 3. Only then use the last-resort providers (local Ollama).
     * When pinned (evaluation) only the pinned provider is used, and rate limits are waited out,
     * because both arms of the comparison must use the same model.
     */
    public LlmResult call(String system, String user) {
        List<String> errors = new ArrayList<>();
        String pin = pinned.get();
        if (pin != null) {
            for (Client c : clients) {
                if (c.name().equals(pin)) {
                    LlmResult r = attempt(c, system, user, errors, 4);
                    if (r != null) return r;
                }
            }
            throw ApiException.unavailable("Pinned provider '" + pin + "' failed: " + String.join(" | ", errors));
        }

        List<Client> fast = clients.stream().filter(c -> !c.lastResort()).toList();
        LlmResult r = firstAvailable(fast, system, user, errors);
        if (r != null) return r;

        long waitMs = soonestCooldownMs(fast);
        if (waitMs > 0 && waitMs <= 15_000) {
            log.info("All fast providers briefly rate-limited; waiting {} ms instead of falling back to a slow model", waitMs);
            sleep(waitMs);
            r = firstAvailable(fast, system, user, errors);
            if (r != null) return r;
        }

        r = firstAvailable(clients.stream().filter(Client::lastResort).toList(), system, user, errors);
        if (r != null) return r;
        throw ApiException.unavailable("No language model is available right now. Tried: " + String.join(" | ", errors));
    }

    private LlmResult firstAvailable(List<Client> candidates, String system, String user, List<String> errors) {
        for (Client c : candidates) {
            Instant until = cooldownUntil.get(c.name());
            if (until != null && Instant.now().isBefore(until)) {
                errors.add(c.name() + ": cooling down");
                continue;
            }
            LlmResult r = attempt(c, system, user, errors, 1);
            if (r != null) return r;
        }
        return null;
    }

    private LlmResult attempt(Client c, String system, String user, List<String> errors, int tries) {
        long start = System.currentTimeMillis();
        for (int i = 1; i <= tries; i++) {
            try {
                var response = c.chatModel().call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
                String text = response.getResult().getOutput().getText();
                if (text == null || text.isBlank()) throw new IllegalStateException("empty response");
                cooldownUntil.remove(c.name());
                return new LlmResult(text.trim(), c.name() + "/" + c.model(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                boolean rateLimited = msg.contains("429") || msg.toLowerCase(Locale.ROOT).contains("rate limit");
                long waitMs = rateLimited ? retryAfterMs(msg) : 20_000;
                if (rateLimited && i < tries && !msg.contains("per day")) {
                    log.info("Provider '{}' rate-limited; waiting {} ms as instructed", c.name(), waitMs);
                    sleep(waitMs);
                    continue;
                }
                cooldownUntil.put(c.name(), Instant.now().plusMillis(waitMs));
                errors.add(c.name() + ": " + (msg.length() > 160 ? msg.substring(0, 160) : msg));
                log.warn("LLM provider '{}' failed ({})", c.name(), rateLimited ? "rate limited for " + waitMs + " ms" : msg);
                return null;
            }
        }
        return null;
    }

    private long soonestCooldownMs(List<Client> candidates) {
        long now = System.currentTimeMillis();
        return candidates.stream()
                .map(c -> cooldownUntil.get(c.name()))
                .filter(java.util.Objects::nonNull)
                .mapToLong(i -> i.toEpochMilli() - now)
                .filter(ms -> ms > 0)
                .min().orElse(0);
    }

    private static final java.util.regex.Pattern RETRY_AFTER =
            java.util.regex.Pattern.compile("try again in (?:(\\d+)m)?(?:([\\d.]+)s|(\\d+)ms)");

    /** Groq says exactly how long to wait ("Please try again in 7.66s" / "1m2.5s" / "520ms"); respect it. */
    public static long retryAfterMs(String message) {
        java.util.regex.Matcher m = RETRY_AFTER.matcher(message);
        if (!m.find()) return 30_000;
        double ms = (m.group(1) == null ? 0 : Long.parseLong(m.group(1)) * 60_000)
                + (m.group(2) == null ? 0 : Double.parseDouble(m.group(2)) * 1000)
                + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
        return Math.max(1_000, Math.min(90_000, (long) ms + 500));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean hasProviders() {
        return !clients.isEmpty();
    }

    public List<ProviderStatus> status() {
        Instant now = Instant.now();
        return declared.stream()
                .map(p -> new ProviderStatus(p.name(), p.model(), p.configured(),
                        cooldownUntil.getOrDefault(p.name(), Instant.MIN).isAfter(now)))
                .toList();
    }
}
