package io.aegis.incident.action;

import io.aegis.incident.action.SimulatorClient.ServiceStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** rollback_deploy: the undo redeploys the version that was rolled back from. */
@Component
public class RollbackActionHandler implements ActionHandler {

    @Override
    public String actionType() {
        return "rollback_deploy";
    }

    @Override
    public UndoPlan undoPlan(Map<String, Object> params, ServiceStatus preState) {
        return new UndoPlan("rollback_deploy", Map.of("to", preState.version()),
                "redeploy " + preState.version());
    }
}