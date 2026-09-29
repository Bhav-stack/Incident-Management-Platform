
package io.aegis.incident.domain;

import java.time.Instant;
import java.util.List;
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
 *
 * <p>High-risk actions require two distinct verified operators. The first
 * approval is recorded and the proposal stays PENDING; the second, from a
 * different identity, is what releases the command. A single operator can
 * always reject: a veto needs one signature, an irreversible change needs two.
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

    /** One entry per approving identity, in order: operator name and time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, String>> approvals = List.of();

    @Column(name = "approvals_required", nullable = false)
    private int approvalsRequired = 1;

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

    /**
     * Records an approval from a verified operator identity.
     *
     * @return true when this was the approval that released the proposal,
     *         false when more signatures are still required
     * @throws IllegalStateException when the proposal is not PENDING, or when
     *         this operator has already approved it. The two-person rule counts
     *         people, so approving twice as the same person is one vote, not two.
     */
    public boolean approve(String operator) {
        return approve(operator, Map.of());
    }

    /**
     * Records an approval together with the attestation that backs it: how the
     * identity was proven, and — when the caller presented a signed token —
     * which issuer signed it and which token id it was. Storing the token id is
     * what makes this an attestation rather than a note: the signature can be
     * traced back to one expiring document instead of a reusable secret.
     *
     * @param attestation claims from the credential, already verified; empty
     *                    for a credential that carries no token
     */
    public boolean approve(String operator, Map<String, String> attestation) {
        requirePending();
        if (operator == null || operator.isBlank()) {
            throw new IllegalStateException(
                    "Proposal " + externalId + " needs a verified operator identity");
        }
        if (approvalOperators().contains(operator)) {
            throw new IllegalStateException(
                    "Proposal " + externalId + " was already approved by " + operator);
        }
        List<Map<String, String>> updated = new java.util.ArrayList<>(approvals);
        Map<String, String> signature = new java.util.LinkedHashMap<>();
        signature.put("operator", operator);
        signature.put("at", Instant.now().toString());
        if (attestation != null) {
            attestation.forEach((claim, value) -> {
                if (value != null && !value.isBlank()) {
                    signature.put(claim, value);
                }
            });
        }
        // Insertion order is preserved so the stored JSON reads in a stable,
        // human-diffable order: who, when, and how it was proven.
        updated.add(java.util.Collections.unmodifiableMap(signature));
        this.approvals = List.copyOf(updated);

        if (approvals.size() < approvalsRequired) {
            return false;
        }
        this.status = ProposalStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = operator;
        return true;
    }

    /** Operator names that have approved, in order. */
    public List<String> approvalOperators() {
        return approvals.stream().map(a -> a.get("operator")).toList();
    }

    public void setApprovalsRequired(int approvalsRequired) {
        this.approvalsRequired = Math.max(1, approvalsRequired);
    }

    public int getApprovalsRequired() {
        return approvalsRequired;
    }

    public List<Map<String, String>> getApprovals() {
        return approvals;
    }

    /** True while the proposal is still short of its required signatures. */
    public boolean awaitingFurtherApproval() {
        return status == ProposalStatus.PENDING && approvals.size() < approvalsRequired;
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

