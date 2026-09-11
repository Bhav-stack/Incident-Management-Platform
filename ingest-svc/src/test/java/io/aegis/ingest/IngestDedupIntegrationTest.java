package io.aegis.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.contracts.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the at-least-once design: publish duplicate RawEvents
 * with the same eventId, and the anomalies topic must carry exactly one
 * AnomalyEvent for the triggering evidence — dedup (Redis) and the unique
 * index (Postgres) make redelivery harmless.
 *
 * <p>Requires Docker; skipped automatically when unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IngestDedupIntegrationTest {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("aegis_ingest")
                    .withUsername("aegis")
                    .withPassword("aegis");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KafkaTemplate<String, String> producer = producer();
    private final KafkaConsumer<String, String> anomaliesConsumer = anomaliesConsumer();

    @Test
    void duplicateEventsProduceExactlyOneAnomaly() throws Exception {
        publishBreach("a", 8.0);
        publishBreach("b", 8.5);
        publishBreach("b", 8.5);   // duplicate of b -> dedup must drop it

        List<AnomalyEvent> anomalies = awaitAnomalies(1, Duration.ofSeconds(30));

        assertEquals(1, anomalies.size(), "dedup + unique index must collapse duplicates");
        assertEquals("test-service", anomalies.get(0).service());
        assertEquals("error_rate", anomalies.get(0).signal());
    }

    @Test
    void recoveryResetsEpisodeAndAllowsRefire() throws Exception {
        publishBreach("c", 8.0);
        publishBreach("d", 8.5);          // episode 1 fires
        publishHealthy("e", 1.0);         // healthy -> reset
        publishBreach("f", 9.0);
        publishBreach("g", 9.5);          // episode 2 fires

        List<AnomalyEvent> anomalies = awaitAnomalies(2, Duration.ofSeconds(40));

        assertEquals(2, anomalies.size());
        assertTrue(anomalies.stream().anyMatch(a -> a.value() == 8.5));
        assertTrue(anomalies.stream().anyMatch(a -> a.value() == 9.5));
    }

    private void publishBreach(String marker, double value) {
        publish(marker, value);
    }

    private void publishHealthy(String marker, double value) {
        publish(marker, value);
    }

    private void publish(String marker, double value) {
        RawEvent event = new RawEvent(RawEvent.CURRENT_SCHEMA_VERSION,
                UUID.nameUUIDFromBytes(("test-event-" + marker).getBytes()),
                EventType.METRIC, Instant.now().minusSeconds(4),
                "test-service:test-api-1", "test-service", "test-api-1",
                Map.of("metric", "error_rate", "value", value, "unit", "percent"));
        try {
            producer.send(Topics.RAW_EVENTS, event.source(),
                    mapper.writeValueAsString(event)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<AnomalyEvent> awaitAnomalies(int expected, Duration timeout) {
        anomaliesConsumer.subscribe(List.of(Topics.ANOMALIES));
        List<AnomalyEvent> anomalies = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && anomalies.size() < expected) {
            anomaliesConsumer.poll(Duration.ofMillis(500)).forEach(record -> {
                try {
                    anomalies.add(mapper.readValue(record.value(), AnomalyEvent.class));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        return anomalies;
    }

    private KafkaTemplate<String, String> producer() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private KafkaConsumer<String, String> anomaliesConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-anomalies-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}