package io.aegis.simulator.emitters;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.simulator.config.SimulatorProperties;
import io.aegis.simulator.kafka.EventPublisher;
import io.aegis.simulator.scenarios.LogBuffer;
import io.aegis.simulator.scenarios.ScenarioState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Emits a structured log line per service every log interval. A small
 * percentage are error-level with an HTTP 500, so ingest has real signals to
 * correlate once anomaly rules land (Phase 1).
 */
@Component
public class LogEmitter {

    private static final double ERROR_PROBABILITY = 0.04;

    private final SimulatorProperties properties;
    private final EventPublisher publisher;
    private final ScenarioState scenarios;
    private final LogBuffer logs;
    private final Random random = new Random(7);

    public LogEmitter(SimulatorProperties properties, EventPublisher publisher,
                      ScenarioState scenarios, LogBuffer logs) {
        this.properties = properties;
        this.publisher = publisher;
        this.scenarios = scenarios;
        this.logs = logs;
    }

    @Scheduled(fixedDelayString = "${simulator.log-interval:10s}")
    public void emitLogs() {
        for (String service : properties.services()) {
            String instance = service + "-api-1";
            String source = service + ":" + instance;

            // Active failure scenarios make 500s the norm, not the exception.
            double errorProbability = scenarios.elevatedErrorRate(service).isPresent()
                    ? 0.35
                    : ERROR_PROBABILITY;
            boolean isError = random.nextDouble() < errorProbability;
            Map<String, Object> payload = Map.of(
                    "level", isError ? "ERROR" : "INFO",
                    "message", isError ? "internal server error: connection pool timeout" : "request completed",
                    "path", "/api/" + service.split("-")[0],
                    "status", isError ? 500 : 200,
                    "durationMs", random.nextInt(250)
            );
            Instant now = Instant.now();
            publisher.publish(new RawEvent(
                    RawEvent.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID(),
                    EventType.LOG,
                    now,
                    source, service, instance, payload));
            // Keep a per-service ring buffer so the agent's search_logs tool
            // has something real to query.
            logs.record(service, now, String.valueOf(payload.get("level")),
                    String.valueOf(payload.get("message")), payload);
        }
    }
}