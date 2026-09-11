package io.aegis.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Incident aggregate root. The only way to move between states is
 * {@link #transitionTo(IncidentStatus)}, which validates against the
 * transition table. {@code @Version} gives optimistic locking so concurrent
 * state changes (duplicate events racing) resolve to one winner.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID externalId;

    @Column(nullable = false)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Column(nullable = false)
    private String summary;

    @Column(nullable = false)
    private Instant openedAt;

    private Instant resolvedAt;

    @Version
    private long version;

    protected Incident() {
        // JPA
    }

    public Incident(String service, Severity severity, String summary) {
        this.externalId = UUID.randomUUID();
        this.service = service;
        this.severity = severity;
        this.summary = summary;
        this.status = IncidentStatus.OPEN;
        this.openedAt = Instant.now();
    }

    public void transitionTo(IncidentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal transition " + status + " -> " + target + " for incident " + externalId);
        }
        this.status = target;
        if (target == IncidentStatus.RESOLVED) {
            this.resolvedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getExternalId() {
        return externalId;
    }

    public String getService() {
        return service;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}