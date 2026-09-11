package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Replay harness configuration: the ground-truth scenarios the shadow
 * evaluator scores the proposer against. These are demo ground truths (the
 * same ones the Controls view's evaluation harness card reports), defined in
 * config so a real deployment can replace them with recorded incidents.
 *
 * @param scenarios list of {service, signal, expected-action}; expected
 *                  action "no_action" is the negative class (false-positive
 *                  detection)
 */
@ConfigurationProperties(prefix = "replay")
public record ReplayProperties(List<Scenario> scenarios) {

    public record Scenario(String service, String signal, String expectedAction) {
    }

    public ReplayProperties {
        scenarios = scenarios == null ? List.of() : scenarios;
    }
}