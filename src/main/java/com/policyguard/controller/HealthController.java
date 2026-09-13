package com.policyguard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check endpoint.
 *
 * Lets us (and later a load balancer / monitoring tool) confirm the backend is
 * up and serving requests, without touching the database or any business logic.
 *
 * Architecture position: this is the Controller layer — the entry point for HTTP
 * requests. Today it's the only layer we have; Service and Repository come later.
 */
@RestController
public class HealthController {

    // GET http://localhost:8080/health  ->  plain text "PolicyGuard is running"
    @GetMapping("/health")
    public String health() {
        return "PolicyGuard is running";
    }
}
