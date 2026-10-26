package com.policyguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policyguard.entity.EvalRun;
import com.policyguard.exception.ApiException;
import com.policyguard.service.EvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Start an evaluation run and read the latest results. */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService eval;
    private final ObjectMapper mapper;

    public EvalController(EvalService eval, ObjectMapper mapper) {
        this.eval = eval;
        this.mapper = mapper;
    }

    @GetMapping("/gold")
    public List<EvalService.GoldItem> gold() {
        return eval.gold();
    }

    /** provider pins one LLM (e.g. "ollama") so both arms use the same model. limit = first N questions. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestParam(required = false) String provider,
                                                   @RequestParam(required = false) Integer limit) {
        EvalRun run = eval.start(provider, limit);
        return ResponseEntity.accepted().body(Map.of("runId", run.getId(), "status", run.getStatus()));
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() throws Exception {
        EvalRun run = eval.latest().orElseThrow(() -> ApiException.notFound("No completed evaluation run yet"));
        return body(run);
    }

    @GetMapping("/current")
    public Map<String, Object> current() throws Exception {
        EvalRun run = eval.current().orElseThrow(() -> ApiException.notFound("No evaluation run yet"));
        return body(run);
    }

    private Map<String, Object> body(EvalRun run) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", run.getId());
        b.put("status", run.getStatus());
        b.put("provider", run.getProvider());
        b.put("startedAt", run.getStartedAt());
        b.put("finishedAt", run.getFinishedAt());
        b.put("summary", run.getSummaryJson() == null ? null : mapper.readTree(run.getSummaryJson()));
        b.put("details", run.getDetailsJson() == null ? null : mapper.readTree(run.getDetailsJson()));
        b.put("markdown", run.getMarkdown());
        return b;
    }
}
