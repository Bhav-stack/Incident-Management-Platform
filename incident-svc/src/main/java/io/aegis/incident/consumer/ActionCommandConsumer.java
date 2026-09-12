package io.aegis.incident.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.contracts.events.Topics;
import io.aegis.incident.action.ActionLockStore;
import io.aegis.incident.app.ActionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code action.commands} at-least-once and decides what a delivery
 * means:
 *
 * <ul>
 *   <li><b>Lock acquired</b> — this worker owns the command; execute it.</li>
 *   <li><b>Lock held, command already has an {@code actions} row</b> — a real
 *       duplicate, acknowledge it.</li>
 *   <li><b>Lock held, no row</b> — either another worker is executing it right
 *       now or a previous attempt failed and rolled back. Do not acknowledge:
 *       leave it for redelivery so an approved command is never lost.</li>
 * </ul>
 *
 * <p>The claim is released when execution throws, so the retry path cannot be
 * blocked until the lock TTL expires. The durable backstops remain the unique
 * {@code actions.command_id} index and the proposal status guard.
 */
@Component
public class ActionCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(ActionCommandConsumer.class);

    private final ObjectMapper mapper;
    private final ActionLockStore locks;
    private final ActionExecutor executor;

    public ActionCommandConsumer(ObjectMapper mapper, ActionLockStore locks,
                                 ActionExecutor executor) {
        this.mapper = mapper;
        this.locks = locks;
        this.executor = executor;
    }

    @KafkaListener(topics = Topics.ACTION_COMMANDS)
    public void consume(String json, Acknowledgment ack) {
        ActionCommandEvent command;
        try {
            command = mapper.readValue(json, ActionCommandEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Poison message: acknowledge so the partition keeps moving.
            log.warn("Skipping invalid command: {}", e.getMessage());
            ack.acknowledge();
            return;
        }

        if (!locks.acquire(command.commandId())) {
            if (executor.alreadyExecuted(command.commandId())) {
                log.info("Command {} already executed, acknowledging duplicate",
                        command.commandId());
                ack.acknowledge();
                return;
            }
            throw new IllegalStateException(
                    "command " + command.commandId() + " is claimed but not executed");
        }

        try {
            executor.execute(command);
            ack.acknowledge();
        } catch (Exception e) {
            locks.release(command.commandId());
            log.error("Command {} failed, releasing the claim for redelivery",
                    command.commandId(), e);
            throw new RuntimeException(e);
        }
    }
}
