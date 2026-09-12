package io.aegis.incident.app;

import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.config.ActionProperties;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.ActionStatus;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

/**
 * Post-execution verification: polls the target's current error rate until it
 * drops below the threshold (recovery) or the verify deadline passes (failure
 * -> automatic rollback via RollbackEngine). A recovery action is only ever
 * complete when the metrics say so.
 */
@Service
public class ActionVerifier {

    private static final Logger log = LoggerFactory.getLogger(ActionVerifier.class);

    private final ActionRepository actions;
    private final IncidentEventRepository events;
    private final IncidentStatePublisher publisher;
    private final SimulatorClient simulator;
    private final RollbackEngine rollback;
    private final ActionProperties properties;
    private final MeterRegistry metrics;

    public ActionVerifier(ActionRepository actions, IncidentEventRepository events,
                          IncidentStatePublisher publisher, SimulatorClient simulator,
                          RollbackEngine rollback, ActionProperties properties,
                          MeterRegistry metrics) {
        this.actions = actions;
        this.events = events;
        this.publisher = publisher;
        this.simulator = simulator;
        this.rollback = rollback;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${action.verify-poll-interval:10s}")
    @Transactional
    public void verifyPending() {
        for (Action action : actions.findByStatus(ActionStatus.VERIFYING)) {
            try {
                verify(action);
            } catch (Exception e) {
                // One unreachable target must not stop the other incidents
                // in flight from being verified. The action stays VERIFYING
                // and is retried on the next poll.
                log.warn("Verification poll failed for action {}: {} (retrying next cycle)",
                        action.getExternalId(), e.getMessage());
                if (TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionAspectSupport.currentTransactionStatus().isRollbackOnly()) {
                    throw e;  // the transaction is already poisoned; let it roll back
                }
            }
        }
    }

    private void verify(Action action) {
        Incident incident = action.getIncident();
        double rate = simulator.status(incident.getService()).errorRate();
        if (rate < properties.verifyThreshold()) {
            metrics.counter("aegis_actions_verified").increment();
            action.markVerified();
            incident.transitionTo(IncidentStatus.RESOLVED);
            events.save(new IncidentEvent(incident, "VERIFIED", Map.of(
                    "actionId", action.getExternalId().toString(),
                    "finalErrorRate", rate)));
            publisher.publish(incident);
            log.info("Action {} verified, incident {} resolved (error_rate {})",
                    action.getExternalId(), incident.getExternalId(), rate);
        } else if (Instant.now().isAfter(action.getVerifyDeadline())) {
            metrics.counter("aegis_actions_verification_failed").increment();
            rollback.failAndRollback(action, incident,
                    "metrics did not recover within " + properties.verifyWindow().toSeconds()
                            + "s (last error_rate " + rate + ")");
        }
    }
}