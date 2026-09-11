package io.aegis.incident.app;

import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.incident.action.ActionHandler;
import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.action.UndoPlan;
import io.aegis.incident.config.ActionProperties;
import io.aegis.incident.controls.KillSwitchStore;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.ProposalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The only execution authority in the platform. The agent can never reach
 * this service: commands arrive only from approved proposals on
 * {@code action.commands}, and the agent has no write tool.
 *
 * <p>Idempotency is layered: Redis lock (ActionLockStore, taken by the
 * consumer), proposal status guard (only APPROVED), and the unique
 * {@code actions.command_id} index as the durable backstop. The undo plan is
 * captured from the service's pre-execution state <b>before</b> the action
 * runs; verification then happens asynchronously in ActionVerifier.
 */
@Service
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final ProposalRepository proposals;
    private final ActionRepository actions;
    private final IncidentEventRepository events;
    private final IncidentStatePublisher publisher;
    private final SimulatorClient simulator;
    private final RollbackEngine rollback;
    private final List<ActionHandler> handlers;
    private final ActionProperties properties;
    private final KillSwitchStore killSwitches;
    private final MeterRegistry metrics;

    public ActionExecutor(ProposalRepository proposals, ActionRepository actions,
                          IncidentEventRepository events, IncidentStatePublisher publisher,
                          SimulatorClient simulator, RollbackEngine rollback,
                          List<ActionHandler> handlers, ActionProperties properties,
                          KillSwitchStore killSwitches, MeterRegistry metrics) {
        this.proposals = proposals;
        this.actions = actions;
        this.events = events;
        this.publisher = publisher;
        this.simulator = simulator;
        this.rollback = rollback;
        this.handlers = handlers;
        this.properties = properties;
        this.killSwitches = killSwitches;
        this.metrics = metrics;
    }

    @Transactional
    public void execute(ActionCommandEvent command) {
        Proposal proposal = proposals.findByExternalId(command.proposalId()).orElse(null);
        if (proposal == null || proposal.getStatus() != ProposalStatus.APPROVED) {
            log.info("Skipping command {}: proposal {} is {}", command.commandId(),
                    command.proposalId(), proposal == null ? "unknown" : proposal.getStatus());
            return;
        }
        if (actions.existsByCommandId(command.commandId())) {
            log.info("Command {} already executed, skipping", command.commandId());
            return;
        }

        Incident incident = proposal.getIncident();
        // Second kill-switch gate: an approved command can still be refused
        // if an operator killed recovery between approval and execution.
        if (killSwitches.isKilled(incident.getService())) {
            events.save(new IncidentEvent(incident, "KILL_SWITCH", Map.of(
                    "reason", "recovery disabled at execution time")));
            log.warn("Kill switch refuses command {} for {}", command.commandId(),
                    incident.getService());
            return;
        }
        Action action = new Action(command, incident, proposal);
        try {
            actions.saveAndFlush(action);
        } catch (DataIntegrityViolationException e) {
            log.info("Command {} raced and lost, skipping", command.commandId());
            return;
        }

        ActionHandler handler = handlers.stream()
                .filter(h -> h.actionType().equals(command.actionType()))
                .findFirst()
                .orElse(null);
        if (handler == null) {
            rollback.failAndRollback(action, incident,
                    "no handler for action type " + command.actionType());
            return;
        }

        // Capture the inverse BEFORE executing anything.
        SimulatorClient.ServiceStatus preState = simulator.status(incident.getService());
        UndoPlan undo = handler.undoPlan(command.params(), preState);
        action.setUndoPlan(undo);

        proposal.execute();
        incident.transitionTo(IncidentStatus.EXECUTING);
        SimulatorClient.ExecuteResult result = simulator.execute(command.actionType(),
                incident.getService(), command.params());
        if (!result.success()) {
            rollback.failAndRollback(action, incident,
                    "execution failed: " + result.detail());
            return;
        }

        metrics.counter("aegis_actions_executed", "type", command.actionType())
                .increment();
        action.markExecuted(Instant.now().plus(properties.verifyWindow()));
        incident.transitionTo(IncidentStatus.VERIFYING);
        events.save(new IncidentEvent(incident, "ACTION_EXECUTED", Map.of(
                "actionId", action.getExternalId().toString(),
                "actionType", command.actionType(),
                "undoType", undo.type(),
                "undoNote", undo.note(),
                "detail", result.detail())));
        publisher.publish(incident);
        log.info("Executed {} on {} (action {}), verifying until {}",
                command.actionType(), incident.getService(), action.getExternalId(),
                action.getVerifyDeadline());
    }
}