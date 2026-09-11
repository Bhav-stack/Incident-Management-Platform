package io.aegis.incident.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket for the live dashboard, with SockJS fallback so the
 * browser works behind proxies and load balancers that do not speak the
 * WebSocket handshake.
 *
 * <p>Topology: the broker is the in-memory simple broker (single instance,
 * no fan-out requirement yet). Clients subscribe to {@code /topic/incidents}
 * and {@code /topic/proposals} from {@code /ws}. If the dashboard ever needs
 * cross-instance fan-out, swap the simple broker for a Redis relay — the
 * endpoints and destinations do not change.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WsConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // CORS is open because the dashboard runs on its own origin in dev
        // (vite proxy in prod) and credentials are never involved.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}