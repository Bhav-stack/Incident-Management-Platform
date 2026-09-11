package io.aegis.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed anomaly emitted by ingest-svc onto {@code anomalies}.
 *
 * <p>Two idempotency layers protect downstream consumers:
 * <ul>
 *   <li>{@code eventId} — unique per anomaly; consumers can dedup on it.</li>
 *   <li>{@code sourceEventId} — the raw telemetry event that triggered it;
 *       persisted with a unique index so a redelivered pipeline step can never
 *       produce two anomaly rows for the same evidence.</li>
 * </ul>
 *
 * @param eventId       unique anomaly id
 * @param sourceEventId triggering RawEvent's id
 * @param service       affected service
 * @param signal        signal type (e.g. error_rate)
 * @param value         observed value at the confirmation window
 * @param threshold     rule threshold it breached
 * @param severity      suggested impact (drives incident severity downstream)
 * @param windowStart   event time of the triggering sample
 */
public record AnomalyEvent(
        UUID eventId,
        UUID sourceEventId,
        String service,
        String signal,
        double value,
        double threshold,
        String severity,
        Instant windowStart
) {
}