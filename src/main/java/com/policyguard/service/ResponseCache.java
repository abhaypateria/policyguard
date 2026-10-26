package com.policyguard.service;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.dto.ChatResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact-match answer cache with a time-to-live. The same question asked twice within 30 minutes
 * costs zero LLM calls. Cleared whenever any document is indexed or deleted, so it can never
 * serve an answer built from an outdated corpus. (Every ask is still written to the audit log.)
 */
@Component
public class ResponseCache {

    private record Entry(ChatResponse response, Instant expiresAt) {}

    private static final int MAX_ENTRIES = 500;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public ResponseCache(PolicyGuardProperties props) {
        this.ttl = Duration.ofMinutes(props.cache().ttlMinutes());
    }

    public ChatResponse get(String key) {
        Entry e = entries.get(key);
        if (e == null) return null;
        if (Instant.now().isAfter(e.expiresAt())) {
            entries.remove(key);
            return null;
        }
        return e.response();
    }

    public void put(String key, ChatResponse response) {
        if (entries.size() >= MAX_ENTRIES) entries.clear();
        entries.put(key, new Entry(response, Instant.now().plus(ttl)));
    }

    public void invalidateAll() {
        entries.clear();
    }
}
