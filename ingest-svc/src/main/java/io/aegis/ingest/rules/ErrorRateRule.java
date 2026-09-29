package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.config.IngestProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Confirms an {@code error_rate} anomaly only after {@code requiredStreaks}
 * <b>consecutive</b> breach samples (the "confirmation window" that filters
 * transient spikes). Fires once per episode; a healthy sample resets the
 * episode so the next breach can re-fire.
 *
 * <p>Anti-flapping properties: the streak is keyed per service, the fired
 * flag is claimed atomically (two racing samples cannot double-fire), and a
 * healthy sample clears both — a returning metric never keeps an old episode
 * alive.
 *
 * <p>Evaluation is <b>batched</b>: the flush path hands over a whole ordered
 * batch, read in one pipelined snapshot (streak + fired per key) and written
 * back in one pipelined apply. That replaces the per-sample Redis round trips
 * that became the ceiling once dedup was pipelined. The atomic fired claim
 * still happens per firing candidate, but a candidate is only produced once
 * per episode per batch.
 */
@Component
public class ErrorRateRule implements AnomalyRule {

    /** A sample that crosses the confirmation threshold and must be claimed. */
    private record Candidate(MetricSample sample, boolean clearFiredFirst) {
    }

    private final RuleStateStore state;
    private final IngestProperties.Rule config;

    public ErrorRateRule(RuleStateStore state, IngestProperties properties) {
        this.state = state;
        this.config = properties.rule();
    }

    @Override
    public String signal() {
        return config.signal();
    }

    /** Single-sample path: a batch of one, so behaviour is identical. */
    @Override
    public Optional<AnomalyEvent> evaluate(MetricSample sample) {
        return evaluateBatch(List.of(sample)).stream().findFirst();
    }

    @Override
    public List<AnomalyEvent> evaluateBatch(List<MetricSample> samples) {
        if (samples.isEmpty()) {
            return List.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        for (MetricSample sample : samples) {
            keys.add(stateKey(sample));
        }
        Map<String, RuleStateStore.State> snapshot = new HashMap<>(state.snapshot(keys));

        Map<String, Long> finalStreaks = new HashMap<>();
        // Keys that saw a healthy sample: their streak and fired flag are
        // cleared before any fresh streak is written back.
        Set<String> resets = new LinkedHashSet<>();
        // Keys whose episode reopened since the last claim in this batch.
        Set<String> reopened = new LinkedHashSet<>();
        List<Candidate> candidates = new ArrayList<>();

        for (MetricSample sample : samples) {
            String key = stateKey(sample);
            RuleStateStore.State current = snapshot.getOrDefault(key, RuleStateStore.State.EMPTY);
            if (sample.value() < config.errorRateThreshold()) {
                snapshot.put(key, RuleStateStore.State.EMPTY);
                resets.add(key);
                reopened.add(key);
                continue;
            }
            long streak = current.streak() + 1;
            boolean fired = current.fired();
            if (streak >= config.requiredStreaks() && !fired) {
                snapshot.put(key, new RuleStateStore.State(streak, true));
                candidates.add(new Candidate(sample, reopened.remove(key)));
            } else {
                snapshot.put(key, new RuleStateStore.State(streak, fired));
            }
        }

        for (Map.Entry<String, RuleStateStore.State> entry : snapshot.entrySet()) {
            long streak = entry.getValue().streak();
            if (streak > 0) {
                finalStreaks.put(entry.getKey(), streak);
            }
        }
        state.applyStreaks(finalStreaks, resets);

        List<AnomalyEvent> anomalies = new ArrayList<>();
        for (Candidate candidate : candidates) {
            MetricSample sample = candidate.sample();
            String key = stateKey(sample);
            if (candidate.clearFiredFirst()) {
                // The previous episode in this same batch already claimed the
                // flag; the reset reopened the episode, so drop it and claim
                // again for the new one.
                state.clearFired(key);
            }
            if (state.markFired(key)) {
                anomalies.add(new AnomalyEvent(
                        UUID.randomUUID(),
                        sample.sourceEventId(),
                        sample.service(),
                        config.signal(),
                        sample.value(),
                        config.errorRateThreshold(),
                        config.severity(),
                        sample.eventTime()));
            }
        }
        return anomalies;
    }

    private static String stateKey(MetricSample sample) {
        return sample.service() + ":" + sample.metric();
    }
}
