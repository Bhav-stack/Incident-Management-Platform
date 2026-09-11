package io.aegis.contracts.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An approved proposal, issued onto {@code action.commands} for execution.
 *
 * <p>This topic is the single execution authority: only the action executor
 * consumes it, and only approved proposals may be executed. {@code commandId}
 * is the executor's idempotency key (Redis lock + unique index), so
 * redelivery of this command can never execute the action twice.
 *
 * @param commandId  idempotency key for the executor
 * @param proposalId the approved proposal this command carries
 * @param incidentId incident being recovered
 * @param service    target service
 * @param actionType action type (restart_instance, scale_replicas, ...)
 * @param params     execution parameters
 * @param issuedAt   when the approval was recorded
 */
public record ActionCommandEvent(
        UUID commandId,
        UUID proposalId,
        UUID incidentId,
        String service,
        String actionType,
        Map<String, Object> params,
        Instant issuedAt
) {
}