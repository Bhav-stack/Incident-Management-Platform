package io.aegis.incident.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry policy for listener failures.
 *
 * <p>Spring Kafka's default error handler gives up after a handful of
 * immediate retries and then <i>commits</i> the record, which for this
 * platform means an approved recovery command can be dropped after a short
 * outage of the simulator or the database. Every failure mode here is
 * transient (dependency unavailable), so the handler retries with a fixed
 * backoff and never skips: the offset is not committed until the listener
 * succeeds. Poison messages are not a concern because malformed payloads are
 * acknowledged in the consumers themselves.
 *
 * <p>The trade-off is deliberate: a partition stalls while its dependency is
 * down instead of losing a command. Because events are keyed by service
 * (incidents) and incident (commands), a stall only affects the keys sharing
 * that partition.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
