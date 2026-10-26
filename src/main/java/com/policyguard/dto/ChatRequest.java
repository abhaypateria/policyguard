package com.policyguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * @param question the compliance question
 * @param asOfDate answer using the rules in force on this date (defaults to today)
 * @param mode     SELF_CORRECTING (default), PLAIN or UNGROUNDED
 * @param userId   who asked - recorded in the audit log (real deployments take this from SSO)
 */
public record ChatRequest(
        @NotBlank(message = "question must not be empty") @Size(max = 1000) String question,
        LocalDate asOfDate,
        AnswerMode mode,
        @Size(max = 100) String userId) {
}
