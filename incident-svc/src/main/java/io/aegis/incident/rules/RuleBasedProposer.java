
package io.aegis.incident.rules;

import io.aegis.contracts.events.AnomalyEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic proposer for the Aegis demo scenarios.
 * Proposes recovery actions but never executes them.
 */
public class RuleBasedProposer implements Proposer {

    @Override
    public Optional<ProposalDraft> propose(AnomalyEvent anomaly) {
        String signal = anomaly.signal();
        String service = anomaly.service();

        // The configured catalog demo scenario is explicitly no-action.
        if ("catalog-service".equals(service)
                && "error_rate".equals(signal)) {
            return Optional.empty();
        }

        String action;
        String risk;
        double confidence;

        switch (signal) {
            case "error_rate" -> {
                action = "restart_instance";
                risk = "MEDIUM";
                confidence = 0.80;
            }
            case "cpu_saturation" -> {
                action = "scale_replicas";
                risk = "MEDIUM";
                confidence = 0.85;
            }
            case "deploy_regression" -> {
                action = "rollback_deploy";
                risk = "HIGH";
                confidence = 0.95;
            }
            case "cache_stampede" -> {
                action = "clear_cache";
                risk = "LOW";
                confidence = 0.80;
            }
            default -> {
                return Optional.empty();
            }
        }

        return Optional.of(new ProposalDraft(
                action,
                risk,
                confidence,
                Map.of("target", service),
                Map.of("evidence", List.of(
                        "signal=" + signal,
                        "value=" + anomaly.value(),
                        "threshold=" + anomaly.threshold(),
                        "severity=" + anomaly.severity()
                ))
        ));
    }
}
