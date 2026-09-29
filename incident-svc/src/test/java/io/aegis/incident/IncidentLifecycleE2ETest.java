package io.aegis.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.contracts.events.Topics;
import io.aegis.incident.action.SimulatorClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the Phase 2 loop against real Kafka, Redis, and
 * PostgreSQL in containers. Only the SimulatorClient is stubbed (the
 * simulator is a separate deployable); everything else is the production
 * code: anomaly consumer -> correlation -> rule-based proposer -> approval
 * gate -> action.commands -> idempotent executor -> verifier sweep.
 *
 * <p>Verification windows are shrunk via test properties
 * ({@code action.verify-window=2s}) so both paths finish in seconds.
 * Requires Docker; skipped automatically when unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentLifecycleE2ETest {

    // org.testcontainers.kafka.KafkaContainer is the container built for the
    // apache/kafka image this project runs everywhere else. The older
    // org.testcontainers.containers.KafkaContainer targets Confluent images
    // and refuses apache/kafka outright.
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("aegis")
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
        // Shrink the verify window so both paths finish in seconds.
        registry.add("action.verify-window", () -> "2s");
        registry.add("action.verify-poll-interval", () -> "1s");
        registry.add("proposal.ttl", () -> "10m");
        registry.add("proposal.sweep-interval", () -> "1h");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, String> producer;

    @MockBean
    private SimulatorClient simulator;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void approvalLeadsToExecutionVerificationAndResolution() throws Exception {
        // Two reads of the target's health, in order: the executor captures
        // the pre-execution state to build the undo plan (still broken), then
        // the verifier polls and finds the restart worked. Stubbing a single
        // unrecovered state here would drive the rollback path instead, which
        // is what stubbornFailureIsRolledBackAndEscalated covers.
        when(simulator.status(anyString()))
                .thenReturn(new SimulatorClient.ServiceStatus(8.0, 2, "v2.4.0", null))
                .thenReturn(new SimulatorClient.ServiceStatus(0.4, 2, "v2.4.0", null));
        when(simulator.execute(anyString(), anyString(), any()))
                .thenReturn(new SimulatorClient.ExecuteResult(true, "instance restarted"));

        publishAnomaly("checkout-service");

        String incidentId = awaitIncidentStatus("AWAITING_APPROVAL");
        String proposalId = firstProposalId(incidentId);

        post("/api/proposals/" + proposalId + "/approve");

        awaitIncidentStatus("RESOLVED");
        List<String> timeline = timelineTypes(incidentId);
        // Full loop, in order: anomaly -> incident -> proposal -> approve ->
        // execute -> verify -> resolve. VERIFYING is an incident *state*
        // streamed to the dashboard, not a timeline entry, so the timeline
        // proves the action ran and was verified; RESOLVED is only reachable
        // from VERIFYING (see IncidentStatus), so awaiting it proves the
        // verification step ran rather than being skipped.
        int executed = timeline.indexOf("ACTION_EXECUTED");
        int verified = timeline.indexOf("VERIFIED");
        assertTrue(executed >= 0 && verified > executed,
                "timeline must show the action execute then verify: " + timeline);
        assertEquals("EXECUTED", proposalStatus(proposalId),
                "approved proposal must be marked EXECUTED");
    }

    @Test
    void stubbornFailureIsRolledBackAndEscalated() throws Exception {
        // Simulated reality: the error rate never recovers, no matter what.
        when(simulator.status(anyString()))
                .thenReturn(new SimulatorClient.ServiceStatus(8.0, 2, "v2.4.0", null));
        when(simulator.execute(anyString(), anyString(), any()))
                .thenReturn(new SimulatorClient.ExecuteResult(true, "instance restarted"));

        publishAnomaly("payment-service");

        String incidentId = awaitIncidentStatus("AWAITING_APPROVAL");
        String proposalId = firstProposalId(incidentId);

        post("/api/proposals/" + proposalId + "/approve");

        awaitIncidentStatus("RESOLVED");   // resolved through the rollback path
        List<String> timeline = timelineTypes(incidentId);
        assertTrue(timeline.contains("ACTION_FAILED"),
                "verification must fail when metrics never recover: " + timeline);
        assertTrue(timeline.contains("ESCALATED"),
                "failed recovery must escalate to a human: " + timeline);
    }

    // --- helpers ------------------------------------------------------------

    private void publishAnomaly(String service) throws Exception {
        AnomalyEvent anomaly = new AnomalyEvent(UUID.randomUUID(), UUID.randomUUID(),
                service, "error_rate", 8.5, 5.0, "SEV2", Instant.now());
        producer.send(Topics.ANOMALIES, service, mapper.writeValueAsString(anomaly)).get();
    }

    /**
     * Posts with an empty body: the approver is no longer a request field, it
     * is the verified identity behind the caller's credential.
     */
    private void post(String path) {
        rest.postForEntity(path, null, String.class);
    }

    private JsonNode getJson(String path) throws Exception {
        return mapper.readTree(rest.getForEntity(path, String.class).getBody());
    }

    private String awaitIncidentStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + 45_000_000_000L;
        while (System.nanoTime() < deadline) {
            JsonNode incidents = getJson("/api/incidents");
            if (incidents.size() > 0 && expected.equals(incidents.get(0).get("status").asText())) {
                return incidents.get(0).get("externalId").asText();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("incident never reached " + expected);
    }

    private String firstProposalId(String incidentId) throws Exception {
        JsonNode proposals = getJson("/api/incidents/" + incidentId + "/proposals");
        assertTrue(proposals.size() > 0, "expected at least one proposal");
        return proposals.get(0).get("externalId").asText();
    }

    private String proposalStatus(String proposalId) throws Exception {
        return getJson("/api/proposals/" + proposalId).get("status").asText();
    }

    private List<String> timelineTypes(String incidentId) throws Exception {
        JsonNode events = getJson("/api/incidents/" + incidentId + "/events");
        List<String> types = new ArrayList<>();
        events.forEach(e -> types.add(e.get("eventType").asText()));
        return types;
    }
}