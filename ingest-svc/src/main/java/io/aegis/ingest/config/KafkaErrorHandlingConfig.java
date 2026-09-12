package io.aegis.ingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry policy for listener failures.
 *
 * <p>Spring Kafka's default error handler gives up after a handful of
 * immediate retries and then <i>commits</i> the record, so a database blip
 * could silently drop raw events and with them the anomalies they would have
 * produced. Events are deduplicated by {@code event_id} downstream, so
 * retrying is safe; dropping is not. Malformed payloads are acknowledged in
 * the consumer, so the handler only sees transient dependency failures.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
