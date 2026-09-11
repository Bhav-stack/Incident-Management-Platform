package io.aegis.incident.action;

import io.aegis.incident.action.SimulatorClient.ServiceStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** clear_cache: the undo is cache warm-up; nothing to execute. */
@Component
public class ClearCacheActionHandler implements ActionHandler {

    @Override
    public String actionType() {
        return "clear_cache";
    }

    @Override
    public UndoPlan undoPlan(Map<String, Object> params, ServiceStatus preState) {
        return new UndoPlan(UndoPlan.NONE, Map.of(), "cache warm-up is the inverse");
    }
}