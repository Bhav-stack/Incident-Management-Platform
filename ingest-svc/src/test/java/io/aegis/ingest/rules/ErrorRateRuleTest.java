package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.config.IngestProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorRateRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private ErrorRateRule rule(InMemoryRuleStateStore state) {
        IngestProperties props = new IngestProperties(Duration.ofHours(1), null,
                new IngestProperties.Rule(5.0, 2, "error_rate", "SEV2"));
        return new ErrorRateRule(state, props);
    }

    private static MetricSample sample(double value) {
        return new MetricSample("checkout-service", "checkout-api-1", "error_rate",
                value, NOW, UUID.randomUUID());
    }

    @Test
    void confirmsOnlyAfterTwoConsecutiveBreaches() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        Optional<AnomalyEvent> first = rule.evaluate(sample(8.0));
        assertTrue(first.isEmpty(), "single breach sample must not confirm an anomaly");

        Optional<AnomalyEvent> second = rule.evaluate(sample(8.5));
        assertTrue(second.isPresent(), "second consecutive breach confirms");
        assertEquals("checkout-service", second.get().service());
        assertEquals(8.5, second.get().value());
        assertEquals("SEV2", second.get().severity());
    }

    @Test
    void firesOncePerEpisode() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        rule.evaluate(sample(8.0));
        assertTrue(rule.evaluate(sample(8.5)).isPresent());

        // Breach continues: the episode is already claimed -> no re-fire.
        assertTrue(rule.evaluate(sample(9.0)).isEmpty());
        assertTrue(rule.evaluate(sample(9.5)).isEmpty());
    }

    @Test
    void healthySampleResetsEpisodeAndAllowsRefire() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        rule.evaluate(sample(8.0));
        assertTrue(rule.evaluate(sample(8.5)).isPresent());

        rule.evaluate(sample(1.0));  // healthy -> reset
        rule.evaluate(sample(7.0));  // new episode
        assertTrue(rule.evaluate(sample(9.0)).isPresent(),
                "after recovery a new breach episode must re-fire");
    }

    @Test
    void belowThresholdNeverConfirms() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        assertTrue(rule.evaluate(sample(1.0)).isEmpty());
        assertTrue(rule.evaluate(sample(4.9)).isEmpty());
        assertFalse(state.hasState("checkout-service:error_rate"));
    }

    /** In-memory {@link RuleStateStore} double mirroring the Redis semantics. */
    static final class InMemoryRuleStateStore implements RuleStateStore {

        private final Map<String, Long> streaks = new ConcurrentHashMap<>();
        private final Map<String, Boolean> fired = new ConcurrentHashMap<>();

        @Override
        public long incrementStreak(String key) {
            return streaks.merge(key, 1L, Long::sum);
        }

        @Override
        public void reset(String key) {
            streaks.remove(key);
            fired.remove(key);
        }

        @Override
        public boolean markFired(String key) {
            return fired.putIfAbsent(key, true) == null;
        }

        boolean hasState(String key) {
            return streaks.containsKey(key) || fired.containsKey(key);
        }
    }
}