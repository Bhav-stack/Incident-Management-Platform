package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Approval gate tuning, bound from {@code proposal.*}. */
@ConfigurationProperties(prefix = "proposal")
public record ProposalProperties(Duration ttl, Duration sweepInterval) {

    public ProposalProperties {
        ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        sweepInterval = sweepInterval == null ? Duration.ofSeconds(30) : sweepInterval;
    }
}