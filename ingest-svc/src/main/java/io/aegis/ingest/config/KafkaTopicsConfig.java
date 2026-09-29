package io.aegis.ingest.config;

import io.aegis.contracts.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares {@code raw.events} with the partition count the consumer is sized
 * for. Without this the topic is auto-created with a single partition on first
 * produce, and the listener's concurrency has nothing to spread across.
 *
 * <p>Kafka only ever grows partition counts, never shrinks them, and changing
 * the count on an existing topic is an operator action, not something this
 * bean does implicitly — so a pre-existing single-partition topic is left
 * alone and logged. {@code docker compose down -v} gives a clean topic.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic rawEventsTopic(IngestProperties properties) {
        return TopicBuilder.name(Topics.RAW_EVENTS)
                .partitions(Math.max(1, properties.consumer().partitions()))
                .replicas(1)
                .build();
    }
}
