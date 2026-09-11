package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Execution and verification tuning, bound from {@code action.*}. */
@ConfigurationProperties(prefix = "action")
public record ActionProperties(
        Duration verifyWindow,
        Duration verifyPollInterval,
        double verifyThreshold,
        Duration lockTtl
) {

    public ActionProperties {
        verifyWindow = verifyWindow == null ? Duration.ofSeconds(60) : verifyWindow;
        verifyPollInterval = verifyPollInterval == null ? Duration.ofSeconds(10) : verifyPollInterval;
        verifyThreshold = verifyThreshold <= 0 ? 5.0 : verifyThreshold;  // match ingest rule threshold
        lockTtl = lockTtl == null ? Duration.ofMinutes(5) : lockTtl;
    }
}