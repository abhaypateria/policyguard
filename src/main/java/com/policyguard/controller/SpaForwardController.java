package com.policyguard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the React build is bundled into the backend (single-container deployment), browser routes
 * like /documents must return index.html so React Router can take over.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/ask", "/documents", "/audit", "/evaluation", "/architecture"})
    public String forward() {
        return "forward:/index.html";
    }
}
