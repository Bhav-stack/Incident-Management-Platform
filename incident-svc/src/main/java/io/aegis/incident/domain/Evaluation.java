package io.aegis.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One scored run of the proposer: either an offline replay against a
 * ground-truth scenario, or a live shadow evaluation (no proposal persisted,
 * nothing executed). {@code correct} is null for live shadow rows (no ground
 * truth); precision/recall statistics are computed over rows with a verdict.
 */
@Entity
@Table(name = "evaluations")
public class Evaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID externalId;

    @Column(nullable = false)
    private String service;

    @Column(nullable = false)
    private String signal;

    /** Ground-truth action for replay rows; null for live shadow rows. */
    @Column
    private String expectedAction;

    /** What the proposer actually proposed; null means no proposal. */
    @Column
    private String proposedAction;

    /** Whether the proposal matched ground truth; null when no ground truth. */
    @Column
    private Boolean correct;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private boolean shadow;

    @Column(nullable = false)
    private Instant createdAt;

    protected Evaluation() {
        // JPA
    }

    public Evaluation(String service, String signal, String expectedAction,
                      String proposedAction, Boolean correct, double confidence,
                      boolean shadow) {
        this.externalId = UUID.randomUUID();
        this.service = service;
        this.signal = signal;
        this.expectedAction = expectedAction;
        this.proposedAction = proposedAction;
        this.correct = correct;
        this.confidence = confidence;
        this.shadow = shadow;
        this.createdAt = Instant.now();
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

    public String getSignal() {
        return signal;
    }

    public String getExpectedAction() {
        return expectedAction;
    }

    public String getProposedAction() {
        return proposedAction;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public double getConfidence() {
        return confidence;
    }

    public boolean isShadow() {
        return shadow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}