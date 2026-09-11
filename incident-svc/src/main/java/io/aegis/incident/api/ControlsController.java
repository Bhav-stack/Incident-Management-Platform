package io.aegis.incident.api;

import io.aegis.incident.controls.AutonomyPolicy;
import io.aegis.incident.controls.KillSwitchStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator control surface (backing the dashboard Controls view):
 * kill switches (global + per service, checked in the propose and execute
 * paths) and the autonomy policy toggles (shadow mode, LOW-risk
 * auto-approve). All state is Redis-backed, so a toggle applies instantly
 * across instances.
 */
@RestController
@RequestMapping("/api/controls")
public class ControlsController {

    private final KillSwitchStore killSwitches;
    private final AutonomyPolicy policy;
    private final List<String> services;

    public ControlsController(KillSwitchStore killSwitches, AutonomyPolicy policy,
                              @Value("${controls.services:checkout-service,payment-service,search-service,auth-service,inventory-service}") List<String> services) {
        this.killSwitches = killSwitches;
        this.policy = policy;
        this.services = services;
    }

    public record ToggleRequest(boolean killed) {
    }

    public record FlagRequest(boolean enabled) {
    }

    @GetMapping("/kill-switches")
    public Map<String, Object> killSwitches() {
        List<Map<String, Object>> perService = services.stream()
                .map(s -> Map.<String, Object>of(
                        "service", s,
                        "killed", killSwitches.isServiceKilled(s)))
                .toList();
        return Map.of(
                "global", killSwitches.isGlobalKilled(),
                "services", perService);
    }

    @PutMapping("/kill-switches/global")
    public void setGlobal(@RequestBody ToggleRequest request) {
        killSwitches.setGlobalKilled(request.killed());
    }

    @PutMapping("/kill-switches/{service}")
    public void setService(@PathVariable String service, @RequestBody ToggleRequest request) {
        killSwitches.setServiceKilled(service, request.killed());
    }

    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of(
                "shadowMode", policy.shadowMode(),
                "autoApproveLow", policy.autoApproveLow(),
                "confidenceMinimums", policy.confidenceMinimums(),
                "minEvidenceSignals", policy.minEvidenceSignals());
    }

    @PutMapping("/policy/shadow-mode")
    public void setShadowMode(@RequestBody FlagRequest request) {
        policy.setShadowMode(request.enabled());
    }

    @PutMapping("/policy/auto-approve-low")
    public void setAutoApproveLow(@RequestBody FlagRequest request) {
        policy.setAutoApproveLow(request.enabled());
    }
}