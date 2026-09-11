package io.aegis.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only timeline entry for an incident (evidence, transitions, agent
 * steps, approval decisions). Backs the dashboard timeline and post-mortem.
 */
@Entity
@Table(name = "incident_events")
public class IncidentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Column(nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    /** Idempotency key (e.g. the source anomaly eventId); unique when set. */
    @Column
    private String dedupKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected IncidentEvent() {
        // JPA
    }

    public IncidentEvent(Incident incident, String eventType, Map<String, Object> payload) {
        this(incident, eventType, payload, null);
    }

    public IncidentEvent(Incident incident, String eventType, Map<String, Object> payload,
                         String dedupKey) {
        this.incident = incident;
        this.eventType = eventType;
        this.payload = payload == null ? Map.of() : payload;
        this.dedupKey = dedupKey;
        this.createdAt = Instant.now();
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Long getId() {
        return id;
    }

    public Incident getIncident() {
        return incident;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}