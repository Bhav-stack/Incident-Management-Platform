package io.aegis.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Tunable simulation cadence and service catalog.
 * Bound from {@code simulator.*} in application.yml — e.g. "break" scenarios
 * (Phase 1) will drive these values from a scenario definition instead.
 */
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
        List<String> services,
        Duration metricsInterval,
        Duration logInterval,
        Duration healthInterval
) {

    public SimulatorProperties {
        if (services == null || services.isEmpty()) {
            services = List.of("checkout-service", "payment-service", "search-service");
        }
        metricsInterval = metricsInterval == null ? Duration.ofSeconds(5) : metricsInterval;
        logInterval = logInterval == null ? Duration.ofSeconds(10) : logInterval;
        healthInterval = healthInterval == null ? Duration.ofSeconds(10) : healthInterval;
    }
}