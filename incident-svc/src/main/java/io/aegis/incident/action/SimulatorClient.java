package io.aegis.incident.action;

import io.aegis.incident.config.BoundedRetry;
import io.aegis.incident.config.OutboundHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Talks to the simulator, which owns "simulated reality": service status
 * (current error rate, replicas, version) and execution of recovery actions.
 * In production this would be the orchestration layer of the real target.
 */
@Component
public class SimulatorClient {

    /** The simulator guards its own API; this is that credential's header. */
    private static final String API_KEY_HEADER = "X-API-Key";

    private final RestClient rest;

    public SimulatorClient(@Value("${simulator.base-url:http://localhost:8080}") String baseUrl,
                           @Value("${aegis.security.api-key:}") String apiKey,
                           @Value("${simulator.connect-timeout:2s}") Duration connectTimeout,
                           @Value("${simulator.read-timeout:5s}") Duration readTimeout) {
        RestClient.Builder builder =
                OutboundHttp.builder(baseUrl, connectTimeout, readTimeout);
        if (StringUtils.hasText(apiKey)) {
            // The simulator guards its API with the same shared secret.
            builder = builder.defaultHeader(API_KEY_HEADER, apiKey.trim());
        }
        this.rest = builder.build();
    }

    /**
     * Current simulated state. Retried: it is a read, and the caller is often
     * the verifier, which polls it repeatedly and can absorb a transient miss.
     */
    public ServiceStatus status(String service) {
        return BoundedRetry.read("simulator status for " + service, () -> rest.get()
                .uri("/api/services/{service}", service)
                .retrieve()
                .body(ServiceStatus.class));
    }

    /** Recent synthetic log lines for a service (agent search_logs tool). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> logs(String service, int limit, String query) {
        return BoundedRetry.read("simulator logs for " + service, () -> rest.get()
                .uri(uriBuilder -> uriBuilder.path("/api/services/{service}/logs")
                        .queryParam("limit", limit)
                        .queryParam("query", query == null ? "" : query)
                        .build(service))
                .retrieve()
                .body(Map.class));
    }

    /**
     * Executes a recovery action.
     *
     * <p>Not retried, on purpose. If this call times out the action may still
     * have been applied, and a second attempt could restart a service twice;
     * the executor's idempotency key and the verification read are what make a
     * retry safe, and that belongs in one place rather than in a transport
     * helper. A timeout here surfaces as a failed execution, which the pipeline
     * already handles as a first-class outcome.
     */
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