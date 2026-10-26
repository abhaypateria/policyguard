package com.policyguard.dto;

/**
 * SELF_CORRECTING = full PolicyGuard: grade evidence, rewrite + retry, refuse when weak.
 * PLAIN           = baseline RAG: retrieve once, answer once (used for the A/B evaluation).
 * UNGROUNDED      = the LLM alone with no documents (what a public chatbot would say) - for comparison.
 */
public enum AnswerMode { SELF_CORRECTING, PLAIN, UNGROUNDED }
