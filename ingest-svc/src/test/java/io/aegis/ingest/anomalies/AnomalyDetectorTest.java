package io.aegis.ingest.anomalies;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.rules.AnomalyRule;
import io.aegis.ingest.rules.MetricSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The durable backstop write is the only database call inside the ordered
 * flush batch, so how it fails is a throughput property, not an error-handling
 * detail: an exception that escapes here aborts the batch the anomaly was
 * found in, and those events have already left the out-of-order queues.
 *
 * <p>Both cases below were observed on a 10k/s ladder against an ingest schema
 * that had been wiped under a running service: every confirmed anomaly threw
 * {@code relation "anomalies" does not exist} out of the scheduled flush, and
 * roughly a thousand ordered events per failure disappeared without being
 * counted as ordered or as dropped.
 */
class AnomalyDetectorTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AnomalyRecordStore store = mock(AnomalyRecordStore.class);
    private final AnomalyPublisher publisher = mock(AnomalyPublisher.class);

    private static AnomalyEvent anomaly(String signal) {
        return new AnomalyEvent(UUID.randomUUID(), UUID.randomUUID(), "checkout",
                signal, 0.34, 0.10, "HIGH", T0);
    }

    private AnomalyDetector detectorReturning(AnomalyEvent... anomalies) {
        AnomalyRule rule = new AnomalyRule() {
            @Override
            public String signal() {
                return "error_rate";
            }

            @Override
            public Optional<AnomalyEvent> evaluate(MetricSample sample) {
                return Optional.empty();
            }

            @Override
            public List<AnomalyEvent> evaluateBatch(List<MetricSample> samples) {
                return List.of(anomalies);
            }
        };
        return new AnomalyDetector(List.of(rule), store, publisher, metrics);
    }

    private static MetricSample sample() {
        return new MetricSample("checkout", "checkout-1", "error_rate", 0.34, T0, UUID.randomUUID());
    }

    @Test
    void failedBackstopWriteCostsOnlyItsOwnAnomaly() {
        AnomalyEvent unreachable = anomaly("error_rate");
        AnomalyEvent healthy = anomaly("latency_p99");
        when(store.insertIfAbsent(unreachable)).thenThrow(
                new DataAccessResourceFailureException("relation \"anomalies\" does not exist"));
        when(store.insertIfAbsent(healthy)).thenReturn(true);

        AnomalyDetector detector = detectorReturning(unreachable, healthy);

        assertDoesNotThrow(() -> detector.onMetrics(List.of(sample())));
        verify(store).insertIfAbsent(healthy);
        verify(publisher).publish(healthy);
        verify(publisher, never()).publish(unreachable);
        assertEquals(1.0, metrics.counter("aegis_anomalies_failed").count());
    }

    @Test
    void alreadyRecordedAnomalyIsNotPublished() {
        AnomalyEvent duplicate = anomaly("error_rate");
        when(store.insertIfAbsent(duplicate)).thenReturn(false);

        detectorReturning(duplicate).onMetrics(List.of(sample()));

        verify(publisher, never()).publish(duplicate);
        assertEquals(0.0, metrics.counter("aegis_anomalies_failed").count());
    }
}
