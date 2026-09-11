package io.aegis.incident.app;

import io.aegis.incident.config.ReplayProperties;
import io.aegis.incident.config.ReplayProperties.Scenario;
import io.aegis.incident.domain.Evaluation;
import io.aegis.incident.repo.EvaluationRepository;
import io.aegis.incident.rules.ProposalDraft;
import io.aegis.incident.rules.Proposer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowEvaluatorTest {

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("checkout-service", "error_rate", "restart_instance"),
            new Scenario("payment-service", "cpu_saturation", "scale_replicas"),
            new Scenario("catalog-service", "error_rate", "no_action"));

    private static ProposalDraft draft(String action) {
        return new ProposalDraft(action, "MEDIUM", 0.80,
                Map.of("target", "x"), Map.of("evidence", List.of("a", "b")));
    }

    @Test
    void replayScoresCorrectActionsAndPunishesFalsePositives() {
        EvaluationRepository repo = mock(EvaluationRepository.class);
        Proposer proposer = mock(Proposer.class);
        when(proposer.propose(any()))
                .thenReturn(Optional.of(draft("restart_instance")),   // checkout: correct
                        Optional.of(draft("rollback_deploy")),        // payment: wrong action (FN)
                        Optional.of(draft("restart_instance")));      // catalog: FP
        ShadowEvaluator evaluator = new ShadowEvaluator(proposer, repo,
                new ReplayProperties(SCENARIOS));

        ShadowEvaluator.ReplaySummary summary = evaluator.runReplay();

        assertEquals(3, summary.total());
        assertEquals(1, summary.correct());
        assertEquals(1, summary.tp());
        assertEquals(1, summary.fp());
        assertEquals(1, summary.fn());
        assertEquals(0.5, summary.precision(), 1e-9);
        assertEquals(0.5, summary.recall(), 1e-9);
        verify(repo).saveAll(anyList());
    }

    @Test
    void missingProposalOnExpectedActionCountsAsMiss() {
        EvaluationRepository repo = mock(EvaluationRepository.class);
        Proposer proposer = mock(Proposer.class);
        when(proposer.propose(any())).thenReturn(Optional.empty());
        ShadowEvaluator evaluator = new ShadowEvaluator(proposer, repo,
                new ReplayProperties(SCENARIOS));

        ShadowEvaluator.ReplaySummary summary = evaluator.runReplay();

        // Two expected actions, both missed; the no_action scenario is a true negative.
        assertEquals(2, summary.fn());
        assertEquals(0.0, summary.recall(), 1e-9);
    }

    @Test
    void liveShadowRecordsWithoutGroundTruth() {
        EvaluationRepository repo = mock(EvaluationRepository.class);
        ShadowEvaluator evaluator = new ShadowEvaluator(mock(Proposer.class), repo,
                new ReplayProperties(List.of()));

        evaluator.recordLive("checkout-service", "error_rate", draft("restart_instance"));

        verify(repo).save(any(Evaluation.class));
    }
}