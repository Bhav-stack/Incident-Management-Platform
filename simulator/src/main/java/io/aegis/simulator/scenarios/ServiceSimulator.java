package io.aegis.simulator.scenarios;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The simulator's model of "reality": per-service error rate, replica count,
 * and version. Recovery actions (from the action executor) mutate this state;
 * the error rate resolves from stubborn failure > error spike > baseline.
 * Verification reads this same state, so a stubborn failure demonstrably
 * never recovers until the scenario expires.
 */
@Service
public class ServiceSimulator {

    private static final double STUBBORN_ERROR_RATE = 8.0;
    private static final double BASELINE_ERROR_RATE = 1.0;
    private static final int DEFAULT_REPLICAS = 2;
    private static final String DEFAULT_VERSION = "v2.4.0";

    private final ScenarioState scenarios;
    private final Map<String, ServiceState> states = new ConcurrentHashMap<>();

    public ServiceSimulator(ScenarioState scenarios) {
        this.scenarios = scenarios;
    }

    public record ServiceState(int replicas, String version, Instant restartedAt) {
    }

    public ServiceStatus status(String service) {
        ServiceState state = states.getOrDefault(service, defaultState());
        double errorRate = scenarios.isStubborn(service)
                ? STUBBORN_ERROR_RATE
                : scenarios.elevatedErrorRate(service).orElse(BASELINE_ERROR_RATE);
        return new ServiceStatus(errorRate, state.replicas(), state.version(),
                state.restartedAt());
    }

    public ExecuteResult execute(String actionType, String service, Map<String, Object> params) {
        ServiceState state = states.getOrDefault(service, defaultState());
        return switch (actionType) {
            case "restart_instance" -> {
                scenarios.clearErrorSpike(service);
                states.put(service, new ServiceState(state.replicas(), state.version(),
                        Instant.now()));
                yield new ExecuteResult(true, "instance restarted");
            }
            case "scale_replicas" -> {
                int to = params == null
                        ? state.replicas()
                        : ((Number) params.getOrDefault("to", state.replicas())).intValue();
                states.put(service, new ServiceState(to, state.version(), state.restartedAt()));
                yield new ExecuteResult(true, "scaled to " + to + " replicas");
            }
            case "rollback_deploy" -> {
                String to = params == null
                        ? "v2.3.9"
                        : String.valueOf(params.getOrDefault("to", "v2.3.9"));
                states.put(service, new ServiceState(state.replicas(), to, state.restartedAt()));
                yield new ExecuteResult(true, "deploy rolled back to " + to);
            }
            case "clear_cache" -> new ExecuteResult(true, "cache cleared");
            default -> new ExecuteResult(false, "unknown action type " + actionType);
        };
    }

    private static ServiceState defaultState() {
        return new ServiceState(DEFAULT_REPLICAS, DEFAULT_VERSION, null);
    }

    public record ServiceStatus(double errorRate, int replicas, String version,
                                Instant restartedAt) {
    }

    public record ExecuteResult(boolean success, String detail) {
    }
}