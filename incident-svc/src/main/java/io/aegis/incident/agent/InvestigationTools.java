package io.aegis.incident.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.action.SimulatorClient.ServiceStatus;

import java.util.List;
import java.util.Map;

/**
 * The investigation tool set the agent is allowed to call. Every tool is
 * read-only (health, metrics, logs, deployments) — the agent can look and
 * propose, it can never execute. Recovery writes happen exclusively through
 * the approval gate and the action executor.
 */
public final class InvestigationTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InvestigationTools() {
    }

    public static List<Tool> forSimulator(SimulatorClient simulator) {
        return List.of(
                new Tool("get_service_health",
                        "Check the current health of a service: error rate, replicas, and version.",
                        Map.of("service", "string"), List.of("service"),
                        args -> health(simulator, args)),
                new Tool("get_metrics",
                        "Read a metric for a service. The only supported metric is error_rate.",
                        Map.of("service", "string", "metric", "string"),
                        List.of("service", "metric"),
                        args -> metrics(simulator, args)),
                new Tool("search_logs",
                        "Search recent log lines of a service, optionally for a substring (e.g. connection pool).",
                        Map.of("service", "string", "query", "string", "limit", "number"),
                        List.of("service"),
                        args -> logs(simulator, args)),
                new Tool("get_deployments",
                        "Check the deployed version of a service and when it was last restarted.",
                        Map.of("service", "string"), List.of("service"),
                        args -> deployments(simulator, args))
        );
    }

    private static String health(SimulatorClient simulator, String arguments) {
        Map<String, Object> args = parse(arguments);
        ServiceStatus status = simulator.status(service(args));
        boolean degraded = status.errorRate() >= 5.0;
        return "service=" + service(args)
                + " errorRate=" + String.format("%.2f", status.errorRate()) + "%"
                + " replicas=" + status.replicas()
                + " version=" + status.version()
                + (status.restartedAt() != null ? " restartedAt=" + status.restartedAt() : " restartedAt=none")
                + " -> " + (degraded ? "UNHEALTHY (error rate above 5%)" : "HEALTHY");
    }

    private static String metrics(SimulatorClient simulator, String arguments) {
        Map<String, Object> args = parse(arguments);
        String metric = String.valueOf(args.getOrDefault("metric", "error_rate"));
        if (!"error_rate".equals(metric)) {
            return "metric=" + metric + " not available from the simulator (supported: error_rate)";
        }
        ServiceStatus status = simulator.status(service(args));
        return "service=" + service(args) + " metric=error_rate value="
                + String.format("%.2f", status.errorRate()) + "%";
    }

    private static String logs(SimulatorClient simulator, String arguments) {
        Map<String, Object> args = parse(arguments);
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
        int limit = args.get("limit") == null ? 30 : ((Number) args.get("limit")).intValue();
        Map<String, Object> body = simulator.logs(service(args), limit, query);
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) body.getOrDefault("lines", List.of());
        if (lines.isEmpty()) {
            return "no log lines" + (query.isBlank() ? "" : " matching \"" + query + "\"")
                    + " for service=" + service(args);
        }
        return "found " + lines.size() + " line(s):\n" + String.join("\n", lines);
    }

    private static String deployments(SimulatorClient simulator, String arguments) {
        Map<String, Object> args = parse(arguments);
        ServiceStatus status = simulator.status(service(args));
        return "service=" + service(args)
                + " currentVersion=" + status.version()
                + " replicas=" + status.replicas()
                + (status.restartedAt() != null
                        ? " lastRestart=" + status.restartedAt()
                        : " lastRestart=none")
                + " (deploy history is not tracked by the simulator)";
    }

    private static Map<String, Object> parse(String arguments) {
        try {
            JsonNode node = MAPPER.readTree(arguments);
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad tool arguments: " + arguments, e);
        }
    }

    private static String service(Map<String, Object> args) {
        return String.valueOf(args.get("service"));
    }
}