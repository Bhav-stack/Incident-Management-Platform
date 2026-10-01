package io.aegis.ingest.anomalies;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.rules.AnomalyRule;
import io.aegis.ingest.rules.MetricSample;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs every ordered metric batch through the rule set. Rules are Spring beans
 * implementing {@link AnomalyRule} — adding a signal is adding a class, not
 * touching the pipeline. The write path is:
 *
 * <pre>rule confirms → durable insert (unique source_event_id) → publish</pre>
 *
 * so duplicates are impossible even if the consumer redelivers.
 *
 * <p>The batch form is what lets a rule pipeline its Redis state for the whole
 * flush batch; see {@code RuleStateStore}.
 *
 * <p>The durable insert is the only place the ordered path touches the
 * database, and it sits <em>inside</em> the flush batch, so its failure mode
 * matters more than its happy path. Observed on a 10k/s ladder with the ingest
 * schema missing (the volume had been wiped under a running service): the
 * unguarded version threw out of {@code OutOfOrderBuffer.flush()} once per
 * confirmed anomaly and took the whole ordered batch with it, and those events
 * vanished without being counted as ordered <em>or</em> dropped. A failed
 * backstop write is therefore recorded and swallowed: the anomaly is lost (and
 * counted), the other events in the batch are not.
 */
@Component
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final List<AnomalyRule> rules;
    private final AnomalyRecordStore recordStore;
    private final AnomalyPublisher publisher;
    private final MeterRegistry metrics;

    public AnomalyDetector(List<AnomalyRule> rules, AnomalyRecordStore recordStore,
                           AnomalyPublisher publisher, MeterRegistry metrics) {
        this.rules = rules;
        this.recordStore = recordStore;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public void onMetric(MetricSample sample) {
        onMetrics(List.of(sample));
    }

    /** Evaluates one ordered flush batch; a rule may pipeline its state once. */
    public void onMetrics(List<MetricSample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        for (AnomalyRule rule : rules) {
            for (AnomalyEvent anomaly : rule.evaluateBatch(samples)) {
                persistAndPublish(anomaly);
            }
        }
    }

    /**
     * Never throws: a backstop failure must cost at most the anomaly that hit
     * it, never the ordered batch it was found in. The loss is counted rather
     * than only logged, so an outage shows up as
     * {@code aegis_anomalies_failed} next to {@code aegis_anomalies_emitted}
     * instead of as a throughput dip.
     */
    private void persistAndPublish(AnomalyEvent anomaly) {
        try {
            if (recordStore.insertIfAbsent(anomaly)) {
                publisher.publish(anomaly);
            }
        } catch (RuntimeException e) {
            metrics.counter("aegis_anomalies_failed").increment();
            log.error("Anomaly {} ({}/{} on {}) was not recorded or published: {}",
                    anomaly.eventId(), anomaly.signal(), anomaly.severity(),
                    anomaly.service(), e.getMessage());
        }
    }
}
