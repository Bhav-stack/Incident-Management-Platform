package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.config.IngestProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorRateRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private ErrorRateRule rule(InMemoryRuleStateStore state) {
        IngestProperties props = new IngestProperties(Duration.ofHours(1), null,
                new IngestProperties.Rule(5.0, 2, "error_rate", "SEV2"), null);
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

    /**
     * The batch path evaluates many samples at once, so the reset-then-refire
     * case can land entirely inside one batch. A healthy sample must still
     * clear the fired flag before the new episode claims, or the second
     * episode is lost.
     */
    @Test
    void resetAndRefireWithinOneBatchFiresTwice() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        List<AnomalyEvent> anomalies = rule.evaluateBatch(List.of(
                sample(8.0), sample(8.5),   // episode 1 fires
                sample(1.0),                // healthy -> reset
                sample(9.0), sample(9.5))); // episode 2 must fire again

        assertEquals(2, anomalies.size(), "both episodes must fire in one batch");
    }

    /** A sustained breach inside one batch must still fire only once. */
    @Test
    void sustainedBreachWithinOneBatchFiresOnce() {
        InMemoryRuleStateStore state = new InMemoryRuleStateStore();
        ErrorRateRule rule = rule(state);

        List<AnomalyEvent> anomalies = rule.evaluateBatch(List.of(
                sample(8.0), sample(8.5), sample(9.0), sample(9.5), sample(10.0)));

        assertEquals(1, anomalies.size(), "one episode, one anomaly");
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
        public Map<String, State> snapshot(Collection<String> keys) {
            Map<String, State> out = new HashMap<>();
            for (String key : keys) {
                out.put(key, new State(streaks.getOrDefault(key, 0L), fired.containsKey(key)));
            }
            return out;
        }

        @Override
        public void applyStreaks(Map<String, Long> finalStreaks, Set<String> resetKeys) {
            // Deletes first: a key that reset then breached again is in both
            // sets, and its fresh streak must survive.
            for (String key : resetKeys) {
                streaks.remove(key);
                fired.remove(key);
            }
            for (Map.Entry<String, Long> entry : finalStreaks.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    streaks.put(entry.getKey(), entry.getValue());
                } else {
                    streaks.remove(entry.getKey());
                }
            }
        }

        @Override
        public boolean markFired(String key) {
            return fired.putIfAbsent(key, true) == null;
        }

        @Override
        public void clearFired(String key) {
            fired.remove(key);
        }

        boolean hasState(String key) {
            return streaks.containsKey(key) || fired.containsKey(key);
        }
    }
}