package io.aegis.ingest.anomalies;

import io.aegis.contracts.events.AnomalyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Durable backstop for anomaly idempotency. The unique index on
 * {@code anomalies(source_event_id)} means a redelivered rule evaluation can
 * never insert twice; the duplicate is swallowed and the publish is skipped,
 * keeping the {@code anomalies} topic exactly-once per evidence event even
 * under at-least-once delivery.
 *
 * <p>Known trade-off (interview material): insert-then-publish can lose an
 * anomaly if the publish fails after insert — the outbox pattern closes that,
 * deferred until the pipeline is stable.
 */
@Component
public class AnomalyRecordStore {

    private static final Logger log = LoggerFactory.getLogger(AnomalyRecordStore.class);

    private static final String INSERT = """
            INSERT INTO anomalies (event_id, source_event_id, service, signal, value,
                                   threshold, severity, window_start)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public AnomalyRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if the anomaly was newly inserted; false if it (or its
     *         source event) was already recorded — caller must skip publishing
     */
    public boolean insertIfAbsent(AnomalyEvent anomaly) {
        try {
            jdbc.update(INSERT,
                    anomaly.eventId(),
                    anomaly.sourceEventId(),
                    anomaly.service(),
                    anomaly.signal(),
                    anomaly.value(),
                    anomaly.threshold(),
                    anomaly.severity(),
                    Timestamp.from(anomaly.windowStart()));
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Anomaly already recorded (source event {}), skipping publish",
                    anomaly.sourceEventId());
            return false;
        }
    }
}