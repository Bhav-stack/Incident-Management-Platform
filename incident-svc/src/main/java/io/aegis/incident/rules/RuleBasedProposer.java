package io.aegis.incident.rules;

import io.aegis.contracts.events.AnomalyEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic placeholder intelligence so the full loop (anomaly -> incident
 * -> proposal -> approval -> execution) runs before any LLM exists. The agent
 * occupies this slot in Phase 4.
 *
 * <p>Mapping: a confirmed {@code error_rate} anomaly proposes
 * {@code restart_instance} at MEDIUM risk. No hard-coded numbers here that
 * policy owns: risk tier and confidence are draft values validated by the
 * policy layer downstream.
 */
public class RuleBasedProposer implements Proposer {

    @Override
    public Optional<ProposalDraft> propose(AnomalyEvent anomaly) {
        if (!"error_rate".equals(anomaly.signal())) {
            return Optional.empty();
        }
        return Optional.of(new ProposalDraft(
                "restart_instance",
                "MEDIUM",
                0.80,
                Map.of("target", anomaly.service()),
                Map.of("evidence", List.of(
                        "signal=" + anomaly.signal(),
                        "value=" + anomaly.value(),
                        "threshold=" + anomaly.threshold(),
                        "severity=" + anomaly.severity()))));
    }
}