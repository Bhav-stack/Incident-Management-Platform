package io.aegis.incident.action;

import io.aegis.incident.action.SimulatorClient.ServiceStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** restart_instance: non-destructive; verification is the undo. */
@Component
public class RestartActionHandler implements ActionHandler {

    @Override
    public String actionType() {
        return "restart_instance";
    }

    @Override
    public UndoPlan undoPlan(Map<String, Object> params, ServiceStatus preState) {
        return new UndoPlan(UndoPlan.NONE, Map.of(),
                "restart is verification-gated; no inverse beyond escalation");
    }
}