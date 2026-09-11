package io.aegis.incident.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.incident.api.IncidentController.OpenRequest;
import io.aegis.incident.domain.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Phase 3 wire-up end to end: a STOMP client over SockJS (the
 * browser fallback path) subscribes to {@code /topic/incidents} and receives
 * the state event for an incident opened over REST. Requires Docker; skipped
 * automatically when unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WsEndToEndTest {

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("proposal.sweep-interval", () -> "1h");
    }

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();
    private StompSession session;

    @AfterEach
    void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void incidentStateArrivesOverSockJsStomp() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(sockJsClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        int port = Integer.parseInt(rest.getRootUri().replaceAll("^.*:(\\d+)$", "$1"));

        String url = "http://localhost:" + port + "/ws";
        session = client.connectAsync(url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        assertTrue(session.isConnected(), "STOMP session must be established over SockJS");

        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/incidents", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(mapper.valueToTree(payload));
            }
        });

        // Opening an incident over REST publishes a state change, which must
        // appear on /topic/incidents for every subscribed dashboard.
        String summary = "ws-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        var opened = rest.postForObject("/api/incidents",
                new OpenRequest("ws-e2e-service", Severity.SEV3, summary), Map.class);

        JsonNode frame = received.poll(15, TimeUnit.SECONDS);
        assertNotNull(frame, "no STOMP frame received within 15s");
        assertEquals(summary, frame.get("summary").asText());
        assertEquals("ws-e2e-service", frame.get("service").asText());
        assertNotNull(frame.get("incidentId").asText());
        assertNotNull(opened);
    }

    private SockJsClient sockJsClient() {
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient()));
        return new SockJsClient(transports);
    }
}