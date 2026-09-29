package io.aegis.incident.ws;

import io.aegis.incident.config.SecurityProperties;
import io.aegis.incident.security.WsTicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates the live incident feed.
 *
 * <p>Without this, anyone who can reach the port can subscribe to
 * {@code /topic/incidents} and read every incident, service name and recovery
 * action in the platform. Access as a service account only.
 *
 * <p>Two gates, because STOMP is not HTTP:
 *
 * <ol>
 *   <li>{@code CONNECT} must carry a valid, unspent ticket. The ticket is
 *       spent here, so a captured frame cannot be replayed.</li>
 *   <li>{@code SUBSCRIBE} must come from a session that completed gate one.
 *       Spring's simple broker will serve a SUBSCRIBE frame that arrives
 *       without a preceding CONNECT, so the session id is tracked explicitly
 *       rather than assumed.</li>
 * </ol>
 *
 * <p>Sessions are tracked in memory and the broker is the in-process simple
 * broker, so this matches the single-instance topology documented in
 * {@link WsConfig}. A multi-instance deployment needs a relay and a shared
 * session store; tickets already live in Redis for that reason.
 */
@Configuration
public class WsAuthConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WsAuthConfig.class);

    /** STOMP CONNECT header carrying the ticket. */
    public static final String TICKET_HEADER = "X-Auth-Ticket";

    private final WsTicketStore tickets;
    private final SecurityProperties properties;
    private final Set<String> authenticatedSessions = ConcurrentHashMap.newKeySet();

    public WsAuthConfig(WsTicketStore tickets, SecurityProperties properties) {
        this.tickets = tickets;
        this.properties = properties;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }
                switch (accessor.getCommand()) {
                    case CONNECT -> authenticate(accessor);
                    case SUBSCRIBE -> requireAuthenticatedSession(accessor);
                    case DISCONNECT -> authenticatedSessions.remove(accessor.getSessionId());
                    default -> { }
                }
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor accessor) {
        if (!properties.wsRequireTicket()) {
            log.warn("aegis.security.ws-require-ticket is false: the incident feed is "
                    + "UNAUTHENTICATED.");
            return;
        }
        String ticket = accessor.getFirstNativeHeader(TICKET_HEADER);
        if (!tickets.consume(ticket)) {
            // Never log the ticket itself: it is a credential, and a leaked
            // one is usable for its remaining lifetime.
            log.warn("Rejected STOMP CONNECT from {}: {} absent={}, value={}",
                    accessor.getSessionId(), TICKET_HEADER, ticket == null,
                    ticket == null ? "none" : "present but not redeemable");
            // The interceptor throws, so Spring answers with an ERROR frame
            // and closes the connection: no ticket, no session.
            throw new MessagingException("Invalid, expired or already used " + TICKET_HEADER);
        }
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            authenticatedSessions.add(sessionId);
        }
        accessor.setUser((Principal) () -> "dashboard:" + sessionId);
        log.debug("Authenticated incident feed session {}", sessionId);
    }

    private void requireAuthenticatedSession(StompHeaderAccessor accessor) {
        if (!properties.wsRequireTicket()) {
            return;
        }
        if (accessor.getSessionId() == null
                || !authenticatedSessions.contains(accessor.getSessionId())) {
            log.warn("Rejected STOMP SUBSCRIBE from {}: session did not authenticate",
                    accessor.getSessionId());
            throw new MessagingException(
                    "SUBSCRIBE rejected: this session did not present a valid " + TICKET_HEADER);
        }
    }
}
