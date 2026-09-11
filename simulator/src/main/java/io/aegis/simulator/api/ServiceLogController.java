package io.aegis.simulator.api;

import io.aegis.simulator.scenarios.LogBuffer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/services/{service}/logs} — recent synthetic log lines for a
 * service. The agent's {@code search_logs} tool reads this to find error
 * patterns (connection pool timeouts, deploy failures) during investigation.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceLogController {

    private final LogBuffer logs;

    public ServiceLogController(LogBuffer logs) {
        this.logs = logs;
    }

    @GetMapping("/{service}/logs")
    public Map<String, Object> logs(@PathVariable String service,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String query) {
        return Map.of(
                "service", service,
                "lines", logs.recent(service, Math.min(limit, 200), query));
    }
}