package io.aegis.ingest.rules;

import java.time.Instant;
import java.util.UUID;

/**
 * A single, ordering-restored metric observation ready for rule evaluation.
 */
public record MetricSample(
        String service,
        String instance,
        String metric,
        double value,
        Instant eventTime,
        UUID sourceEventId
) {
}