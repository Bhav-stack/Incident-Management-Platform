package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Autonomy policy thresholds, bound from {@code policy.*}. These are the
 * config-driven guardrails between "the agent proposed something" and "the
 * platform acts on it": per-action confidence minimums and a minimum number
 * of evidence signals. Runtime toggles (shadow mode, LOW auto-approve) live
 * in Redis so the Controls view can flip them without a restart.
 *
 * @param confidenceMinimums action type -> minimum confidence for action
 * @param minEvidenceSignals evidence entries a proposal must carry
 * @param autoApproveLow     default for the LOW-risk auto-approve toggle
 */
@ConfigurationProperties(prefix = "policy")
public record PolicyProperties(
        Map<String, Double> confidenceMinimums,
        int minEvidenceSignals,
        boolean autoApproveLow
) {

    public PolicyProperties {
        confidenceMinimums = confidenceMinimums == null || confidenceMinimums.isEmpty()
                ? Map.of(
                        "clear_cache", 0.70,
                        "restart_instance", 0.70,
                        "scale_replicas", 0.80,
                        "rollback_deploy", 0.90,
                        "kill_instance", 0.90)
                : confidenceMinimums;
        minEvidenceSignals = minEvidenceSignals <= 0 ? 2 : minEvidenceSignals;
    }
}