package io.aegis.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Self-driving demo traffic, bound from {@code simulator.demo.*}.
 *
 * <p>Without this the simulator is silent until someone calls the scenario API,
 * which is correct for tests and wrong for a hosted demo: a visitor who opens
 * the link at an arbitrary moment would find an empty incident board and no way
 * to tell whether the platform works.
 *
 * <p>It is off by default. The scheduler that reads it ({@code
 * DemoScenarioScheduler}) injects one fault at a time and never stacks a second
 * on top of an active one, so the board fills at a readable pace instead of
 * flooding.
 *
 * @param enabled         whether to inject faults on a schedule
 * @param intervalSeconds how often to check whether a fault should be injected
 * @param durationSeconds how long an injected fault lasts before it heals
 * @param errorRate       error rate in percent; must exceed the ingest rule's
 *                        threshold of 5% or no anomaly is ever confirmed
 */
@ConfigurationProperties(prefix = "simulator.demo")
public record DemoProperties(
        Boolean enabled,
        Integer intervalSeconds,
        Integer durationSeconds,
        Double errorRate
) {

    public DemoProperties {
        enabled = enabled != null && enabled;
        intervalSeconds = intervalSeconds == null || intervalSeconds <= 0 ? 180 : intervalSeconds;
        durationSeconds = durationSeconds == null || durationSeconds <= 0 ? 90 : durationSeconds;
        // 8.5% sits well clear of the 5% confirmation threshold while still
        // leaving the sample plausible: a spike, not a hard failure.
        errorRate = errorRate == null || errorRate <= 0 ? 8.5 : errorRate;
    }
}
