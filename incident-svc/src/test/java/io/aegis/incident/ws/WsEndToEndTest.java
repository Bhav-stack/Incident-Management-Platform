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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the live feed end to end over SockJS (the browser fallback path): a
 * subscribed dashboard receives the state event for an incident opened over
 * REST, and a client that cannot present a ticket never gets a session at all.
 *
 * <p>Requires Docker; skipped automatically when unavailable.
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
    void incidentStateArrivesOverSockJsStompWithATicket() throws Exception {
        WebSocketStompClient client = stompClient();

        session = connect(client, ticket());
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

        // The SUBSCRIBE frame is sent asynchronously, so an event published in
        // the same instant can be delivered before the broker has registered
        // the subscription and is then lost. Open incidents until one arrives
        // rather than sleeping a fixed amount and hoping the race went well.
        String summary = "ws-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode frame = null;
        Map<?, ?> opened = null;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (frame == null && System.nanoTime() < deadline) {
            opened = rest.postForObject("/api/incidents",
                    new OpenRequest("ws-e2e-service", Severity.SEV3, summary), Map.class);
            frame = received.poll(3, TimeUnit.SECONDS);
        }

        assertNotNull(frame, "no STOMP frame received within 30s");
        assertEquals(summary, frame.get("summary").asText());
        assertEquals("ws-e2e-service", frame.get("service").asText());
        assertNotNull(frame.get("incidentId").asText());
        assertNotNull(opened, "the incident must be accepted over REST");
    }

    @Test
    void aConnectionWithoutATicketIsRefused() {
        assertFalse(didConnect(stompClient(), null),
                "the incident feed must not be readable without a ticket");
    }

    @Test
    void aTicketCannotBeRedeemedTwice() {
        WebSocketStompClient client = stompClient();
        String once = ticket();

        assertTrue(didConnect(client, once), "the first use of a fresh ticket must succeed");
        assertFalse(didConnect(client, once), "a spent ticket must not open a second session");
    }

    /**
     * Connects the way the dashboard does. The explicit handshake-headers and
     * connect-headers overload is deliberate: the shorter
     * {@code connectAsync(url, handler, connectHeaders)} call resolves to the
     * {@code Object...} URI-variable overload instead, which silently drops the
     * connect headers and makes every connection look unauthenticated.
     */
    private StompSession connect(WebSocketStompClient client, String ticket) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WsAuthConfig.TICKET_HEADER, ticket);
        return client.connectAsync(url(), new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }

    /** True when a session was established; a rejected CONNECT is an error. */
    private boolean didConnect(WebSocketStompClient client, String ticket) {
        StompSession established = null;
        try {
            established = connect(client, ticket);
            return established.isConnected();
        } catch (Exception expected) {
            return false;
        } finally {
            if (established != null && established.isConnected()) {
                established.disconnect();
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /** Issues a ticket the way the dashboard proxy does, through the same API. */
    private String ticket() {
        Map<?, ?> body = rest.postForObject("/api/ws-ticket", null, Map.class);
        assertNotNull(body, "the ticket endpoint must answer");
        return (String) body.get("ticket");
    }

    private String url() {
        int port = Integer.parseInt(rest.getRootUri().replaceAll("^.*:(\\d+)$", "$1"));
        return "http://localhost:" + port + "/ws";
    }

    private WebSocketStompClient stompClient() {
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }
}
