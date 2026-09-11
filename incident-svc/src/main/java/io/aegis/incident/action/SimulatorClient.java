package io.aegis.incident.action;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Talks to the simulator, which owns "simulated reality": service status
 * (current error rate, replicas, version) and execution of recovery actions.
 * In production this would be the orchestration layer of the real target.
 */
@Component
public class SimulatorClient {

    private final RestClient rest;

    public SimulatorClient(@Value("${simulator.base-url:http://localhost:8080}") String baseUrl) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ServiceStatus status(String service) {
        return rest.get()
                .uri("/api/services/{service}", service)
                .retrieve()
                .body(ServiceStatus.class);
    }

    /** Recent synthetic log lines for a service (agent search_logs tool). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> logs(String service, int limit, String query) {
        return rest.get()
                .uri(uriBuilder -> uriBuilder.path("/api/services/{service}/logs")
                        .queryParam("limit", limit)
                        .queryParam("query", query == null ? "" : query)
                        .build(service))
                .retrieve()
                .body(Map.class);
    }

    public ExecuteResult execute(String actionType, String service, Map<String, Object> params) {
        return rest.post()
                .uri("/api/actions/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "actionType", actionType,
                        "service", service,
                        "params", params == null ? Map.of() : params))
                .retrieve()
                .body(ExecuteResult.class);
    }

    /** Snapshot of the simulated target used for undo planning and verification. */
    public record ServiceStatus(double errorRate, int replicas, String version,
                                Instant restartedAt) {
    }

    public record ExecuteResult(boolean success, String detail) {
    }
}