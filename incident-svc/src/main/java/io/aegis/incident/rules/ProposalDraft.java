package io.aegis.incident.rules;

import java.util.Map;

/**
 * A proposed recovery action before it becomes a persisted {@code Proposal}.
 * Produced by the rule-based proposer today and by the agent (agent-svc) in
 * Phase 4 behind the same interface.
 *
 * @param actionType action type, e.g. restart_instance, scale_replicas
 * @param riskLevel  LOW / MEDIUM / HIGH, drives the approval gate
 * @param confidence 0..1, drives policy thresholds
 * @param params     execution parameters (target, replicas, version, ...)
 * @param reasoning  evidence summary for the dashboard and post-mortem
 */
public record ProposalDraft(
        String actionType,
        String riskLevel,
        double confidence,
        Map<String, Object> params,
        Map<String, Object> reasoning
) {
}