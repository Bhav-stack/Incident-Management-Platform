package io.aegis.simulator.scenarios;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of actively simulated failures, driven by the scenario API.
 * Emitters consult it to elevate their output; scenarios self-expire so a
 * forgotten "break" can never wedge the demo.
 */
@Service
public class ScenarioState {

    public record ActiveScenario(String type, double errorRate, Instant until) {
    }

    private final ConcurrentHashMap<String, ActiveScenario> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> stubborn = new ConcurrentHashMap<>();

    public void startErrorSpike(String service, double errorRate, Duration duration) {
        active.put(service, new ActiveScenario("error-spike", errorRate,
                Instant.now().plus(duration)));
    }

    /**
     * A stubborn failure ignores recovery actions: error rate stays elevated
     * even after a restart or scale. Used to demo the verify -> rollback path.
     */
    public void startStubborn(String service, Duration duration) {
        stubborn.put(service, Instant.now().plus(duration));
    }

    public boolean isStubborn(String service) {
        Instant until = stubborn.get(service);
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            stubborn.remove(service);
            return false;
        }
        return true;
    }

    /** Healing side of recovery actions: clears an active error spike. */
    public void clearErrorSpike(String service) {
        active.remove(service);
    }

    /** Elevated error rate if an error-spike is active for the service. */
    public Optional<Double> elevatedErrorRate(String service) {
        ActiveScenario scenario = active.get(service);
        if (scenario == null) {
            return Optional.empty();
        }
        if (scenario.until().isBefore(Instant.now())) {
            active.remove(service);
            return Optional.empty();
        }
        return Optional.of(scenario.errorRate());
    }

    public Map<String, ActiveScenario> snapshot() {
        active.entrySet().removeIf(e -> e.getValue().until().isBefore(Instant.now()));
        return Map.copyOf(active);
    }

    public void clear() {
        active.clear();
    }
}