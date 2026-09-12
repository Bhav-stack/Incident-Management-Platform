package io.aegis.incident.app;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.config.ReplayProperties;
import io.aegis.incident.domain.Evaluation;
import io.aegis.incident.repo.EvaluationRepository;
import io.aegis.incident.rules.ProposalDraft;
import io.aegis.incident.rules.Proposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The evaluation harness. Two modes:
 *
 * <ul>
 *   <li><b>Replay</b> ({@link #runReplay()}): runs the current proposer over
 *       the ground-truth scenarios and scores every verdict. Precision and
 *       recall treat {@code no_action} as the negative class, so a proposer
 *       that fires on healthy services is punished as a false positive.</li>
 *   <li><b>Live shadow</b> ({@link #recordLive}): called by the proposal flow
 *       when shadow mode is on — the proposer still runs, the draft is scored
 *       and stored, but nothing is persisted as a proposal and nothing
 *       executes. This is the safe way to measure an untrusted proposer
 *       against real incidents.</li>
 * </ul>
 */
@Service
public class ShadowEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ShadowEvaluator.class);

    private final Proposer proposer;
    private final EvaluationRepository evaluations;
    private final ReplayProperties properties;

    public ShadowEvaluator(Proposer proposer, EvaluationRepository evaluations,
                           ReplayProperties properties) {
        this.proposer = proposer;
        this.evaluations = evaluations;
        this.properties = properties;
    }

    /** Scores the current proposer against all ground-truth scenarios. */
    @Transactional
    public ReplaySummary runReplay() {
        List<Evaluation> rows = new ArrayList<>();
        int correct = 0, tp = 0, fp = 0, fn = 0;
        for (ReplayProperties.Scenario scenario : properties.scenarios()) {
            AnomalyEvent anomaly = anomalyOf(scenario);
            ProposalDraft draft = proposer.propose(anomaly).orElse(null);
            String proposed = draft == null ? null : draft.actionType();
            boolean expectedAction = !"no_action".equals(scenario.expectedAction());

            boolean isCorrect = expectedAction
                    ? scenario.expectedAction().equals(proposed)
                    : proposed == null;
            if (isCorrect) {
                correct++;
            }
            if (expectedAction && scenario.expectedAction().equals(proposed)) {
                tp++;
            } else if (!expectedAction && proposed != null) {
                fp++;
            } else if (expectedAction) {
                fn++;
            }

            rows.add(new Evaluation(scenario.service(), scenario.signal(),
                    scenario.expectedAction(), proposed, isCorrect,
                    draft == null ? 0.0 : draft.confidence(), false));
        }
        evaluations.saveAll(rows);
        int total = rows.size();
        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        ReplaySummary summary = new ReplaySummary(total, correct, tp, fp, fn, precision, recall);
        log.info("Replay run: {} correct of {} (precision {}, recall {})",
                correct, total, precision, recall);
        return summary;
    }

    /** Shadow scoring of a live anomaly; the draft is not persisted anywhere else. */
    @Transactional
    public void recordLive(String service, String signal, ProposalDraft draft) {
        evaluations.save(new Evaluation(service, signal, null,
                draft.actionType(), null, draft.confidence(), true));
        log.info("Shadow evaluation for {}: {} at confidence {}",
                service, draft.actionType(), draft.confidence());
    }

    @Transactional(readOnly = true)
    public List<Evaluation> recent() {
        return evaluations.findTop50ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        long[] counts = evaluations.verdictCounts();
        long tp = counts[0];
        long fp = counts[1];
        long fn = counts[2];
        long tn = counts[3];
        long total = counts[4];
        long correct = tp + tn;
        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        return new Stats(total, correct, tp, fp, fn, precision, recall);
    }

    private static AnomalyEvent anomalyOf(ReplayProperties.Scenario scenario) {
        return new AnomalyEvent(UUID.randomUUID(), UUID.randomUUID(),
                scenario.service(), scenario.signal(), 8.5, 5.0, "SEV2", Instant.now());
    }

    public record ReplaySummary(int total, int correct, long tp, long fp, long fn,
                                double precision, double recall) {
    }

    public record Stats(long total, long correct, long tp, long fp, long fn,
                        double precision, double recall) {
    }
}