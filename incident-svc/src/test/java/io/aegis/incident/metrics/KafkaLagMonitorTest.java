package io.aegis.incident.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lag sampler must never take the service down: with an unreachable
 * broker it logs at debug, registers no gauge, and returns. This is the
 * behavior the scheduled sample depends on during Kafka outages.
 */
class KafkaLagMonitorTest {

    @Test
    void samplingWithUnreachableBrokerDoesNotThrow() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",  // nothing listens here
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaLagMonitor monitor = new KafkaLagMonitor(factory, registry,
                List.of("anomalies", "action.commands"), "test-group");

        monitor.sample();  // must not throw

        assertTrue(registry.getMeters().isEmpty(),
                "no gauges should be registered when the broker is unreachable");
    }
}