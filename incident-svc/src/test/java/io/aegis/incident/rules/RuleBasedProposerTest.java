package io.aegis.incident.rules;

import io.aegis.contracts.events.AnomalyEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedProposerTest {

    private final RuleBasedProposer proposer = new RuleBasedProposer();

    private static AnomalyEvent anomaly(String signal) {
        return new AnomalyEvent(UUID.randomUUID(), UUID.randomUUID(), "checkout-service",
                signal, 8.5, 5.0, "SEV2", Instant.parse("2026-09-08T10:00:00Z"));
    }

    @Test
    void errorRateAnomalyProposesRestartAtMediumRisk() {
        Optional<ProposalDraft> draft = proposer.propose(anomaly("error_rate"));

        assertTrue(draft.isPresent());
        assertEquals("restart_instance", draft.get().actionType());
        assertEquals("MEDIUM", draft.get().riskLevel());
        assertEquals(0.80, draft.get().confidence());
    }

    @Test
    void unknownSignalProducesNoProposal() {
        assertTrue(proposer.propose(anomaly("p99_latency")).isEmpty());
    }
}