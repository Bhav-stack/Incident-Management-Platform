package io.aegis.simulator.emitters;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.simulator.config.SimulatorProperties;
import io.aegis.simulator.kafka.EventPublisher;
import io.aegis.simulator.scenarios.ScenarioState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Emits an {@code error_rate} sample per instance every metrics interval.
 * Seeded {@link Random} keeps local runs reproducible; an active error-spike
 * scenario elevates the rate (with small jitter) so anomaly rules have a real
 * signal to confirm.
 */
@Component
public class MetricEmitter {

    private static final int INSTANCES_PER_SERVICE = 2;

    private final SimulatorProperties properties;
    private final EventPublisher publisher;
    private final ScenarioState scenarios;
    private final Random random = new Random(42);

    public MetricEmitter(SimulatorProperties properties, EventPublisher publisher,
                         ScenarioState scenarios) {
        this.properties = properties;
        this.publisher = publisher;
        this.scenarios = scenarios;
    }

    @Scheduled(fixedDelayString = "${simulator.metrics-interval:5s}")
    public void emitErrorRates() {
        for (String service : properties.services()) {
            for (int i = 1; i <= INSTANCES_PER_SERVICE; i++) {
                String instance = service + "-api-" + i;
                String source = service + ":" + instance;
                double rate = scenarios.elevatedErrorRate(service)
                        .map(base -> base * (0.95 + random.nextDouble() * 0.10))
                        .orElseGet(() -> random.nextDouble() * 2.0);  // healthy 0–2%
                Map<String, Object> payload = Map.of(
                        "metric", "error_rate",
                        "value", percent(rate),
                        "unit", "percent"
                );
                publisher.publish(new RawEvent(
                        RawEvent.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        EventType.METRIC,
                        Instant.now(),
                        source, service, instance, payload));
            }
        }
    }

    private static double percent(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}