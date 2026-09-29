package io.aegis.ingest.anomalies;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.ingest.rules.AnomalyRule;
import io.aegis.ingest.rules.MetricSample;
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
 */
@Component
public class AnomalyDetector {

    private final List<AnomalyRule> rules;
    private final AnomalyRecordStore recordStore;
    private final AnomalyPublisher publisher;

    public AnomalyDetector(List<AnomalyRule> rules, AnomalyRecordStore recordStore,
                           AnomalyPublisher publisher) {
        this.rules = rules;
        this.recordStore = recordStore;
        this.publisher = publisher;
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

    private void persistAndPublish(AnomalyEvent anomaly) {
        if (recordStore.insertIfAbsent(anomaly)) {
            publisher.publish(anomaly);
        }
    }
}
