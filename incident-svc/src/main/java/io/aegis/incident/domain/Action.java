package io.aegis.incident.domain;

import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.incident.action.UndoPlan;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A recovery action execution. {@code commandId} is unique: the executor
 * never applies the same command twice. {@code undoType}/{@code undoParams}
 * are captured <b>before</b> execution (the inverse of what is about to
 * happen), so the rollback engine can restore pre-action state.
 */
@Entity
@Table(name = "actions")
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID externalId;

    @Column(nullable = false, unique = true)
    private UUID commandId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private Proposal proposal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Column(nullable = false)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params;

    /** Inverse action type captured before execution; "none" when no inverse. */
    @Column
    private String undoType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> undoParams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant executedAt;

    private Instant verifiedAt;

    private Instant verifyDeadline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rollback_action_id")
    private Action rollbackAction;

    protected Action() {
        // JPA
    }

    /** The primary execution: derived from an approved command. */
    public Action(ActionCommandEvent command, Incident incident, Proposal proposal) {
        this.externalId = UUID.randomUUID();
        this.commandId = command.commandId();
        this.proposal = proposal;
        this.incident = incident;
        this.actionType = command.actionType();
        this.params = command.params() == null ? Map.of() : command.params();
        this.undoType = null;
        this.undoParams = Map.of();
        this.status = ActionStatus.EXECUTING;
        this.createdAt = Instant.now();
    }

    /** A rollback action: undoes a failed action using its captured plan. */
    public Action(Incident incident, Proposal proposal, String undoType,
                  Map<String, Object> undoParams) {
        this.externalId = UUID.randomUUID();
        this.commandId = UUID.randomUUID();
        this.proposal = proposal;
        this.incident = incident;
        this.actionType = "rollback";
        this.params = undoParams == null ? Map.of() : undoParams;
        this.undoType = undoType;
        this.undoParams = Map.of();
        this.status = ActionStatus.ROLLING_BACK;
        this.createdAt = Instant.now();
    }

    public void setUndoPlan(UndoPlan plan) {
        this.undoType = plan.type();
        this.undoParams = plan.params() == null ? Map.of() : plan.params();
    }

    public UndoPlan getUndoPlan() {
        return new UndoPlan(undoType, undoParams, "");
    }

    public void markExecuted(Instant deadline) {
        requireStatus(ActionStatus.EXECUTING);
        this.status = ActionStatus.VERIFYING;
        this.executedAt = Instant.now();
        this.verifyDeadline = deadline;
    }

    public void markVerified() {
        requireStatus(ActionStatus.VERIFYING);
        this.status = ActionStatus.VERIFIED;
        this.verifiedAt = Instant.now();
    }

    public void markFailed() {
        // An action can fail before execution completes (EXECUTING) or while
        // its recovery is being verified (VERIFYING).
        if (status != ActionStatus.EXECUTING && status != ActionStatus.VERIFYING) {
            throw new IllegalStateException(
                    "Action " + externalId + " is " + status + ", expected EXECUTING or VERIFYING");
        }
        this.status = ActionStatus.FAILED;
    }

    public void markRolledBack(boolean success) {
        requireStatus(ActionStatus.ROLLING_BACK);
        this.status = ActionStatus.ROLLED_BACK;
        this.verifiedAt = Instant.now();
    }

    public void setRollbackAction(Action rollbackAction) {
        this.rollbackAction = rollbackAction;
    }

    private void requireStatus(ActionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Action " + externalId + " is " + status + ", expected " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getExternalId() {
        return externalId;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public Proposal getProposal() {
        return proposal;
    }

    public Incident getIncident() {
        return incident;
    }

    public String getActionType() {
        return actionType;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public String getUndoType() {
        return undoType;
    }

    public Map<String, Object> getUndoParams() {
        return undoParams;
    }

    public ActionStatus getStatus() {
        return status;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getVerifyDeadline() {
        return verifyDeadline;
    }

    public Action getRollbackAction() {
        return rollbackAction;
    }
}