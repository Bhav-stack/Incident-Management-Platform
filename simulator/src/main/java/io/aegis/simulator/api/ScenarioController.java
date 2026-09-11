package io.aegis.simulator.api;

import io.aegis.simulator.scenarios.ScenarioState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The demo control surface: {@code POST /api/scenarios/error-spike} makes a
 * service emit an elevated error rate (and 500s) for a duration, driving the
 * whole pipeline — ingest anomaly rules → incident → agent → approval.
 *
 * <pre>
 * curl -X POST localhost:8080/api/scenarios/error-spike \
 *   -H 'Content-Type: application/json' \
 *   -d '{"service":"checkout-service","errorRate":8.5,"durationSeconds":60}'
 * </pre>
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioState state;

    public ScenarioController(ScenarioState state) {
        this.state = state;
    }

    public record SpikeRequest(
            @NotBlank String service,
            @NotNull @DecimalMin("0") Double errorRate,
            @NotNull @Positive Integer durationSeconds) {
    }

    @PostMapping("/error-spike")
    public void errorSpike(@Valid @RequestBody SpikeRequest request) {
        state.startErrorSpike(request.service(), request.errorRate(),
                Duration.ofSeconds(request.durationSeconds()));
    }

    public record StubbornRequest(
            @NotBlank String service,
            @NotNull @Positive Integer durationSeconds) {
    }

    /**
     * Makes the service ignore recovery actions: error rate stays elevated
     * after restart/scale, so verification fails and the rollback path runs.
     */
    @PostMapping("/stubborn")
    public void stubborn(@Valid @RequestBody StubbornRequest request) {
        state.startStubborn(request.service(), Duration.ofSeconds(request.durationSeconds()));
    }

    @GetMapping
    public Map<String, ScenarioState.ActiveScenario> list() {
        return state.snapshot();
    }

    @DeleteMapping
    public void clear() {
        state.clear();
    }
}