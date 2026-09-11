package io.aegis.incident.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One recorded tool invocation in an agent run: what was called, with what
 * arguments, what came back, and how long it took. The full trace is stored
 * on the proposal's reasoning map, which is what the dashboard renders as the
 * investigation timeline and auditors use for post-incident review.
 */
public record TraceStep(String tool, String input, String output, long durationMs) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tool", tool);
        map.put("input", input);
        map.put("output", output);
        map.put("durationMs", durationMs);
        return map;
    }
}