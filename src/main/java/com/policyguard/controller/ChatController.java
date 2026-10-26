package com.policyguard.controller;

import com.policyguard.dto.AnswerMode;
import com.policyguard.dto.ChatRequest;
import com.policyguard.dto.ChatResponse;
import com.policyguard.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ask a compliance question. Every call is written to the audit log. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService rag;

    public ChatController(RagService rag) {
        this.rag = rag;
    }

    @PostMapping
    public ChatResponse ask(@Valid @RequestBody ChatRequest request,
                            @RequestHeader(value = "X-User", required = false) String user) {
        return rag.ask(withUser(request, request.mode(), user));
    }

    /**
     * Same question, two systems, side by side: PolicyGuard vs an ungrounded LLM.
     * This is the live answer to "can't ChatGPT already do this?".
     */
    @PostMapping("/compare")
    public Map<String, ChatResponse> compare(@Valid @RequestBody ChatRequest request,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, ChatResponse> result = new LinkedHashMap<>();
        result.put("policyGuard", rag.ask(withUser(request, AnswerMode.SELF_CORRECTING, user)));
        result.put("ungrounded", rag.ask(withUser(request, AnswerMode.UNGROUNDED, user)));
        return result;
    }

    private static ChatRequest withUser(ChatRequest r, AnswerMode mode, String headerUser) {
        String user = r.userId() != null && !r.userId().isBlank() ? r.userId() : headerUser;
        return new ChatRequest(r.question(), r.asOfDate(), mode, user);
    }
}
