package io.aegis.simulator.api;

import io.aegis.simulator.scenarios.ServiceSimulator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The executor's interface into simulated reality. Recovery actions are
 * applied here; status is what verification polls.
 */
@RestController
@RequestMapping("/api")
public class ActionController {

    private final ServiceSimulator simulator;

    public ActionController(ServiceSimulator simulator) {
        this.simulator = simulator;
    }

    public record ActionRequest(
            @NotBlank String actionType,
            @NotBlank String service,
            Map<String, Object> params) {
    }

    @PostMapping("/actions/execute")
    public ServiceSimulator.ExecuteResult execute(@Valid @RequestBody ActionRequest request) {
        return simulator.execute(request.actionType(), request.service(), request.params());
    }

    @GetMapping("/services/{service}")
    public ServiceSimulator.ServiceStatus status(@PathVariable String service) {
        return simulator.status(service);
    }
}