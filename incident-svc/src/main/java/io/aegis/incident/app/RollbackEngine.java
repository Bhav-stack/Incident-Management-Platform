package io.aegis.incident.app;

import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.action.UndoPlan;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Owns the failure path: mark the action FAILED, walk the incident through
 * FAILED -> ROLLING_BACK -> RESOLVED, and execute the undo plan captured
 * before execution. Actions without an inverse (restart, clear-cache) skip
 * the simulator and escalate; everything is recorded in the audit trail.
 */
@Service
public class RollbackEngine {

    private static final Logger log = LoggerFactory.getLogger(RollbackEngine.class);

    private final ActionRepository actions;
    private final IncidentEventRepository events;
    private final IncidentStatePublisher publisher;
    private final SimulatorClient simulator;
    private final MeterRegistry metrics;

    public RollbackEngine(ActionRepository actions, IncidentEventRepository events,
                          IncidentStatePublisher publisher, SimulatorClient simulator,
                          MeterRegistry metrics) {
        this.actions = actions;
        this.events = events;
        this.publisher = publisher;
        this.simulator = simulator;
        this.metrics = metrics;
    }

    @Transactional
    public void failAndRollback(Action action, Incident incident, String reason) {
        action.markFailed();
        incident.transitionTo(IncidentStatus.FAILED);
        events.save(new IncidentEvent(incident, "ACTION_FAILED", Map.of(
                "actionId", action.getExternalId().toString(),
                "reason", reason)));
        publisher.publish(incident);
        rollback(action, reason);
    }

    /**
     * Only reachable after the incident has been moved to FAILED; the state
     * machine refuses ROLLING_BACK from any other state.
     */
    @Transactional
    private void rollback(Action failedAction, String reason) {
        Incident incident = failedAction.getIncident();
        incident.transitionTo(IncidentStatus.ROLLING_BACK);

        UndoPlan undo = failedAction.getUndoPlan();
        if (UndoPlan.NONE.equals(undo.type())) {
            events.save(new IncidentEvent(incident, "ROLLBACK_SKIPPED", Map.of(
                    "actionId", failedAction.getExternalId().toString(),
                    "note", undo.note())));
            log.info("No inverse for {} on {}: {}", failedAction.getActionType(),
                    incident.getService(), undo.note());
        } else {
            metrics.counter("aegis_actions_rolled_back").increment();
            Action rollbackAction = new Action(incident, failedAction.getProposal(),
                    undo.type(), undo.params());
            actions.save(rollbackAction);
            SimulatorClient.ExecuteResult result = simulator.execute(undo.type(),
                    incident.getService(), undo.params());
            rollbackAction.markRolledBack(result.success());
            failedAction.setRollbackAction(rollbackAction);
            events.save(new IncidentEvent(incident, "ROLLED_BACK", Map.of(
                    "actionId", failedAction.getExternalId().toString(),
                    "undoActionId", rollbackAction.getExternalId().toString(),
                    "undoType", undo.type(),
                    "success", result.success(),
                    "detail", result.detail())));
        }

        metrics.counter("aegis_actions_escalated").increment();
        events.save(new IncidentEvent(incident, "ESCALATED", Map.of("reason", reason)));
        incident.transitionTo(IncidentStatus.RESOLVED);
        publisher.publish(incident);
        log.info("Rolled back action {} on {}: {}", failedAction.getExternalId(),
                incident.getService(), reason);
    }
}