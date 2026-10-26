package com.policyguard.dto;

import java.util.List;

/** Two or more sources that state different rules for the same thing (e.g. different time limits). */
public record Conflict(List<String> sources, String description) {
}
