package io.aegis.incident.api;

import io.aegis.incident.security.WsTicketStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hands the dashboard a short-lived ticket for the STOMP feed. Sits under
 * {@code /api}, so the control-plane filter authenticates the caller and the
 * reverse proxy injects the key: the browser never receives it.
 *
 * <p>This endpoint is only reachable through the proxy. A browser calling a
 * hosted incident-svc directly gets a 401, which is the point.
 */
@RestController
@RequestMapping("/api")
public class WsTicketController {

    private final WsTicketStore tickets;

    public WsTicketController(WsTicketStore tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/ws-ticket")
    public Map<String, Object> issue() {
        return Map.of(
                "ticket", tickets.issue(),
                "expiresInSeconds", tickets.ttl().toSeconds());
    }
}
