package io.aegis.simulator.emitters;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.simulator.config.SimulatorProperties;
import io.aegis.simulator.kafka.EventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Heartbeat per instance. ingest-svc will track {@code last_seen} per
 * instance in Redis (Phase 1) so an absent heartbeat becomes the
 * "instance down" signal.
 */
@Component
public class HealthEmitter {

    private static final int INSTANCES_PER_SERVICE = 2;

    private final SimulatorProperties properties;
    private final EventPublisher publisher;

    public HealthEmitter(SimulatorProperties properties, EventPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${simulator.health-interval:10s}")
    public void emitHeartbeats() {
        for (String service : properties.services()) {
            for (int i = 1; i <= INSTANCES_PER_SERVICE; i++) {
                String instance = service + "-api-" + i;
                String source = service + ":" + instance;
                Map<String, Object> payload = Map.of(
                        "status", "UP",
                        "uptimeSeconds", 3600 + i * 137
                );
                publisher.publish(new RawEvent(
                        RawEvent.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        EventType.HEALTH,
                        Instant.now(),
                        source, service, instance, payload));
            }
        }
    }
}