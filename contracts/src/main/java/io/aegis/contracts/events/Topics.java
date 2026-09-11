package io.aegis.contracts.events;

/** Kafka topic names — the platform's only inter-service contract. */
public final class Topics {

    /** Raw telemetry out of the simulator (metrics, logs, health). */
    public static final String RAW_EVENTS = "raw.events";

    /** Normalized, deduplicated anomalies emitted by ingest-svc. */
    public static final String ANOMALIES = "anomalies";

    /** Incident state changes from incident-svc. */
    public static final String INCIDENTS = "incidents";

    /** Agent proposals from agent-svc. */
    public static final String PROPOSALS = "proposals";

    /** Approved actions to execute (action-svc is the only consumer). */
    public static final String ACTION_COMMANDS = "action.commands";

    /** Execution / verification / rollback outcomes. */
    public static final String ACTION_RESULTS = "action.results";

    /** Append-only audit stream. */
    public static final String AUDIT = "audit";

    private Topics() {
    }
}