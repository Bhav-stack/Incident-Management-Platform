package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.config.IngestProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;
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
 */
@Component
public class ErrorRateRule implements AnomalyRule {

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

    @Override
    public Optional<AnomalyEvent> evaluate(MetricSample sample) {
        if (sample.value() < config.errorRateThreshold()) {
            state.reset(stateKey(sample));
            return Optional.empty();
        }

        long streak = state.incrementStreak(stateKey(sample));
        if (streak >= config.requiredStreaks() && state.markFired(stateKey(sample))) {
            return Optional.of(new AnomalyEvent(
                    UUID.randomUUID(),
                    sample.sourceEventId(),
                    sample.service(),
                    config.signal(),
                    sample.value(),
                    config.errorRateThreshold(),
                    config.severity(),
                    sample.eventTime()));
        }
        return Optional.empty();
    }

    private static String stateKey(MetricSample sample) {
        return sample.service() + ":" + sample.metric();
    }
}