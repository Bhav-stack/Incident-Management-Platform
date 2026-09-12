package io.aegis.incident.api;

import io.aegis.incident.app.IncidentService;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public List<Incident> list() {
        return incidents.list();
    }

    @GetMapping("/{externalId}")
    public Incident get(@PathVariable UUID externalId) {
        return incidents.get(externalId);
    }

    @GetMapping("/{externalId}/events")
    public List<IncidentEvent> timeline(@PathVariable UUID externalId) {
        return incidents.timeline(externalId);
    }

    /**
     * Manual incident creation: opens an incident without waiting for the
     * ingest pipeline, which is what demos and the WebSocket end-to-end test
     * use. The automated path is still anomaly -> incident.
     */
    public record OpenRequest(
            @NotBlank String service,
            @NotNull Severity severity,
            @NotBlank String summary) {
    }

    @PostMapping
    public Incident open(@RequestBody OpenRequest request) {
        return incidents.openIncident(request.service(), request.severity(), request.summary());
    }
}