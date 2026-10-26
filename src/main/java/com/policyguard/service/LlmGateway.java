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

    private record Client(String name, String model, OpenAiChatModel chatModel) {}

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

            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(p.model())
                            .temperature(0.0)   // deterministic: compliance answers should not be "creative"
                            .maxTokens(900)
                            .build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()) // fail fast -> next provider
                    .build();
            clients.add(new Client(p.name(), p.model(), model));
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

    public LlmResult call(String system, String user) {
        List<String> errors = new ArrayList<>();
        String pin = pinned.get();
        for (Client c : clients) {
            if (pin != null && !c.name().equals(pin)) continue;
            Instant until = cooldownUntil.get(c.name());
            if (pin == null && until != null && Instant.now().isBefore(until)) {
                errors.add(c.name() + ": cooling down");
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                var response = c.chatModel().call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
                String text = response.getResult().getOutput().getText();
                if (text == null || text.isBlank()) throw new IllegalStateException("empty response");
                cooldownUntil.remove(c.name());
                return new LlmResult(text.trim(), c.name() + "/" + c.model(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                boolean rateLimited = msg.contains("429") || msg.toLowerCase(Locale.ROOT).contains("rate limit");
                cooldownUntil.put(c.name(), Instant.now().plusSeconds(rateLimited ? 60 : 20));
                errors.add(c.name() + ": " + (msg.length() > 160 ? msg.substring(0, 160) : msg));
                log.warn("LLM provider '{}' failed ({}), trying next", c.name(), rateLimited ? "rate limited" : msg);
            }
        }
        throw ApiException.unavailable("No language model is available right now. Tried: " + String.join(" | ", errors));
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
