package com.policyguard.dto;

/** One step of the reasoning pipeline (SCOPE, RETRIEVE, GRADE, REWRITE, GENERATE, VERIFY, AUDIT...). */
public record TraceStep(String step, String detail, long ms) {
}
