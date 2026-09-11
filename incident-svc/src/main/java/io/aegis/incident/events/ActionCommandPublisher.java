package io.aegis.incident.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.contracts.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes approved proposals onto {@code action.commands}, keyed by service. */
@Component
public class ActionCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(ActionCommandPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public ActionCommandPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publish(ActionCommandEvent command) {
        try {
            kafka.send(Topics.ACTION_COMMANDS, command.service(),
                    mapper.writeValueAsString(command))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Publish failed for command {}: {}", command.commandId(),
                                    error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Could not serialize command {}: {}", command.commandId(), e.getMessage());
        }
    }
}