package io.aegis.incident.action;

import io.aegis.incident.action.SimulatorClient.ServiceStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** scale_replicas: the undo is scaling back to the pre-action replica count. */
@Component
public class ScaleActionHandler implements ActionHandler {

    @Override
    public String actionType() {
        return "scale_replicas";
    }

    @Override
    public UndoPlan undoPlan(Map<String, Object> params, ServiceStatus preState) {
        return new UndoPlan("scale_replicas", Map.of("to", preState.replicas()),
                "scale back to " + preState.replicas() + " replicas");
    }
}