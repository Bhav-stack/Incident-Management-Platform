package io.aegis.incident.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two properties the scheduled sampler must have:
 *
 * <ol>
 *   <li>the gauge exists from startup, tagged per topic, so dashboards have a
 *       series to plot before the first successful broker call;</li>
 *   <li>an unreachable broker never takes the service down and never
 *       overwrites the last known sample with a fake zero.</li>
 * </ol>
 */
class KafkaLagMonitorTest {

    private static final List<String> TOPICS = List.of("anomalies", "action.commands");

    private SimpleMeterRegistry registry;
    private KafkaLagMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        monitor = new KafkaLagMonitor(unreachableBrokerFactory(), registry, TOPICS, "incident");
    }

    @AfterEach
    void tearDown() {
        monitor.destroy();
        registry.close();
    }

    @Test
    void registersAGaugePerTopicTaggedByGroupAndTopic() {
        assertEquals(2, registry.get("aegis_kafka_lag").gauges().size());
        assertEquals(0.0, valueOf("anomalies"));
        assertEquals(0.0, valueOf("action.commands"));
    }

    @Test
    void samplingWithAnUnreachableBrokerIsANoOp() {
        monitor.sample();  // must not throw

        assertEquals(0.0, valueOf("anomalies"));
        assertEquals(0.0, valueOf("action.commands"));
    }

    private double valueOf(String topic) {
        return registry.get("aegis_kafka_lag")
                .tag("group", "incident")
                .tag("topic", topic)
                .gauge()
                .value();
    }

    private static ConsumerFactory<String, String> unreachableBrokerFactory() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",  // nothing listens here
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
