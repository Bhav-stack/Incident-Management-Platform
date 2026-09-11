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
 * Consumes {@code action.commands} at-least-once. The Redis lock
 * ({@link ActionLockStore}) makes concurrent redeliveries harmless; the
 * executor's proposal guard and the unique command index are the durable
 * backstops.
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
        try {
            ActionCommandEvent command = mapper.readValue(json, ActionCommandEvent.class);
            if (locks.acquire(command.commandId())) {
                executor.execute(command);
            }
            ack.acknowledge();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Skipping invalid command: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Processing failed, leaving unacked for redelivery", e);
            throw new RuntimeException(e);
        }
    }
}