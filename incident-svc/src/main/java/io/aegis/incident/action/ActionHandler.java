package io.aegis.incident.action;

import io.aegis.incident.action.SimulatorClient.ServiceStatus;

import java.util.Map;

/**
 * Per-action-type behavior. Handlers declare the action they serve and how to
 * undo it given the pre-execution state of the target service; the executor
 * captures that plan before touching anything.
 */
public interface ActionHandler {

    String actionType();

    /** The inverse of this action given the service's pre-execution state. */
    UndoPlan undoPlan(Map<String, Object> params, ServiceStatus preState);
}