package io.aegis.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Incident state change published by incident-svc onto {@code incidents}.
 * Consumers (dashboard, later agent-svc) treat this as the live feed: every
 * transition of an incident produces one event.
 *
 * @param eventId    unique id of this state change
 * @param incidentId incident external id
 * @param service    affected service
 * @param severity   severity at the time of the change
 * @param status     new status
 * @param summary    human-readable summary
 * @param openedAt   when the incident was opened
 * @param occurredAt when this change happened
 */
public record IncidentStateEvent(
        UUID eventId,
        UUID incidentId,
        String service,
        String severity,
        String status,
        String summary,
        Instant openedAt,
        Instant occurredAt
) {
}