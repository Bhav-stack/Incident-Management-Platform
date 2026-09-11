package io.aegis.incident.agent;

import java.util.List;
import java.util.Map;

/**
 * The loop's verdict: a recommended action plus the evidence trail that led
 * to it. {@code confidence} 0..1 and {@code params} feed the proposal draft
 * unchanged; {@code trace} becomes the audit trail on the proposal.
 */
public record AgentResult(
        String summary,
        String actionType,
        double confidence,
        Map<String, Object> params,
        List<TraceStep> trace
) {
}