package com.policyguard;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.service.Grade;
import com.policyguard.service.LlmGateway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Talks to a REAL local Ollama through the failover gateway (skipped automatically if Ollama is not running).
 * Also checks the failover: the first provider points at a dead port and must be skipped.
 */
class LlmGatewayLocalTest {

    @Test
    void failsOverToOllamaAndGradesEvidence() {
        Assumptions.assumeTrue(ollamaUp(), "Ollama not running - skipping");
        PolicyGuardProperties.Provider dead = new PolicyGuardProperties.Provider(
                "dead", "http://localhost:9", "x", "none", "/v1/chat/completions", true);
        PolicyGuardProperties.Provider ollama = new PolicyGuardProperties.Provider(
                "ollama", "http://localhost:11434", "ollama", "qwen2.5:7b", "/v1/chat/completions", true);
        LlmGateway gateway = new LlmGateway(new PolicyGuardProperties(null, null, null,
                new PolicyGuardProperties.Llm(List.of(dead, ollama), 2, 120), null, null, null, null));

        LlmGateway.LlmResult result = gateway.call(
                graderPrompt(),
                """
                QUESTION: Within how many days must KYC records be uploaded to CKYCR?

                SOURCES:
                [S1] Reserve Bank of India - Master Direction KYC | page 58
                REs shall capture customer's KYC records and upload onto CKYCR within 10 days of commencement of an account-based relationship.

                [S2] Demo Bank Ltd. - Internal SOP | page 2
                The Central Account Opening Cell shall upload the KYC records of every new customer to the CKYCR within 15 days of opening of the account.
                """);

        assertTrue(result.provider().startsWith("ollama"), "dead provider must be skipped, got " + result.provider());
        Grade grade = Grade.parse(result.text());
        System.out.println("Grader reply (" + result.latencyMs() + " ms): " + result.text());
        assertTrue(grade.parsed(), "grader reply should be parseable JSON");
        assertTrue(grade.sufficient(), "conflicting sources are still SUFFICIENT evidence");
        assertFalse(grade.conflicts().isEmpty(), "10 days vs 15 days must be reported as a conflict");
    }

    /** The production grader prompt (package-private in Prompts), read reflectively to test the real thing. */
    private static String graderPrompt() {
        try {
            var f = Class.forName("com.policyguard.service.Prompts").getDeclaredField("GRADER_SYSTEM");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean ollamaUp() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://localhost:11434/api/tags").toURL().openConnection();
            c.setConnectTimeout(1500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
