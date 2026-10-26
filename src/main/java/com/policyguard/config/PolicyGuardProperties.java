package com.policyguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * All "policyguard.*" settings from application.properties, bound into typed records.
 * Typed config means a typo in a property name fails fast instead of silently using null.
 */
@ConfigurationProperties(prefix = "policyguard")
public record PolicyGuardProperties(
        @DefaultValue Retrieval retrieval,
        @DefaultValue Rag rag,
        @DefaultValue Chunking chunking,
        @DefaultValue Llm llm,
        @DefaultValue Security security,
        @DefaultValue Cache cache,
        @DefaultValue Seed seed,
        @DefaultValue Eval eval) {

    public record Retrieval(@DefaultValue("6") int topK,
                            @DefaultValue("15") int candidateK,
                            @DefaultValue("true") boolean hybrid,
                            @DefaultValue("0.30") double minSimilarity) {}

    public record Rag(@DefaultValue("3") int maxAttempts) {}

    public record Chunking(@DefaultValue("200") int sizeTokens,
                           @DefaultValue("150") int minChunkChars) {}

    public record Llm(@DefaultValue List<Provider> providers,
                      @DefaultValue("5") int connectTimeoutSeconds,
                      @DefaultValue("90") int readTimeoutSeconds) {}

    public record Provider(String name,
                           String baseUrl,
                           String apiKey,
                           String model,
                           @DefaultValue("/v1/chat/completions") String completionsPath,
                           @DefaultValue("true") boolean enabled) {

        /** A provider is only tried if it is enabled and actually has a key + endpoint. */
        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank() && baseUrl != null && model != null;
        }
    }

    public record Security(@DefaultValue("") String adminKey,
                           @DefaultValue("20") int chatRequestsPerMinute,
                           @DefaultValue("1000") int chatRequestsPerDay,
                           @DefaultValue("*") String corsAllowedOrigins) {}

    public record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("30") int ttlMinutes) {}

    public record Seed(@DefaultValue("true") boolean enabled) {}

    public record Eval(@DefaultValue("true") boolean enabled,
                       @DefaultValue("docs/EVALUATION.md") String outputFile) {}
}
