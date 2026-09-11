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
     * Scaffold-only convenience to seed incidents by hand; replaced by the
     * ingest -> anomaly -> incident pipeline in Phase 2.
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