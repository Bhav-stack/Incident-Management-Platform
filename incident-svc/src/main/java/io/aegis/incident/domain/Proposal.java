
package io.aegis.incident.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.aegis.incident.rules.ProposalDraft;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A persisted recovery proposal awaiting the approval gate. Status changes are
 * guarded: only a PENDING proposal can be approved, rejected, or expired, so
 * a duplicate approve/reject request is rejected, not applied twice.
 */
@Entity
@Table(name = "proposals")
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID externalId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Column(nullable = false)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params;

    @Column(nullable = false)
    private String riskLevel;

    @Column(nullable = false)
    private double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant decidedAt;

    private String decidedBy;

    protected Proposal() {
        // JPA
    }

    public Proposal(Incident incident, ProposalDraft draft) {
        this.externalId = UUID.randomUUID();
        this.incident = incident;
        this.actionType = draft.actionType();
        this.params = draft.params() == null ? Map.of() : draft.params();
        this.riskLevel = draft.riskLevel();
        this.confidence = draft.confidence();
        this.reasoning = draft.reasoning() == null ? Map.of() : draft.reasoning();
        this.status = ProposalStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void approve(String approver) {
        requirePending();
        this.status = ProposalStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = approver;
    }

    public void reject(String approver) {
        requirePending();
        this.status = ProposalStatus.REJECTED;
        this.decidedAt = Instant.now();
        this.decidedBy = approver;
    }

    public void expire() {
        requirePending();
        this.status = ProposalStatus.EXPIRED;
        this.decidedAt = Instant.now();
    }

    /** Marks the proposal executed once its action ran (approved only). */
    public void execute() {
        if (status != ProposalStatus.APPROVED) {
            throw new IllegalStateException(
                    "Proposal " + externalId + " must be APPROVED before execution, is " + status);
        }
        this.status = ProposalStatus.EXECUTED;
    }

    private void requirePending() {
        if (status != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + externalId + " is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getExternalId() {
        return externalId;
    }

  @JsonIgnore
public Incident getIncident() {
    return incident;
}
    public String getActionType() {
        return actionType;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public double getConfidence() {
        return confidence;
    }

    public Map<String, Object> getReasoning() {
        return reasoning;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }
}

