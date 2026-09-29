package io.aegis.incident.security;

import io.aegis.incident.config.SecurityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Short-lived, single-use tickets for the live incident feed.
 *
 * <p>The dashboard needs to authenticate its STOMP connection, but it cannot
 * be given the control-plane key: anything in a browser bundle is public, and
 * that key also authorises restarts and kill switches. Instead the dashboard
 * asks its own origin for a ticket over {@code POST /api/ws-ticket}. That call
 * goes through the reverse proxy, which injects the key server-side, so the
 * browser only ever holds a throwaway token that is valid for seconds and can
 * be spent once.
 *
 * <p>Tickets live in Redis, so they survive a service restart and work across
 * instances without sticky sessions. Consumption is a single atomic
 * {@code DEL}, which is what makes a ticket single-use even if two nodes race
 * to accept the same connection.
 */
@Component
public class WsTicketStore {

    private static final String PREFIX = "ws:ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public WsTicketStore(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(properties.wsTicketTtlSeconds());
    }

    /** Issues a new random ticket valid for the configured TTL. */
    public String issue() {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        redis.opsForValue().set(PREFIX + ticket, "1", ttl);
        return ticket;
    }

    /**
     * Spends a ticket. Returns true only for the first caller: the key is
     * deleted atomically, so a replay finds nothing.
     */
    public boolean consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.delete(PREFIX + ticket.trim()));
    }

    public Duration ttl() {
        return ttl;
    }
}
