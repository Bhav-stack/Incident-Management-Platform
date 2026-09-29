package io.aegis.incident.security;

import io.aegis.incident.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A ticket is the only credential the browser ever holds, so its two
 * properties matter: it expires quickly, and it can be spent exactly once.
 */
class WsTicketStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private WsTicketStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        store = new WsTicketStore(redis,
                new SecurityProperties("key", "operator", null, true, 45, null));
    }

    @Test
    void issuedTicketsAreUnpredictableAndStoredWithATtl() {
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(issued.add(store.issue()), "tickets must not repeat");
        }

        verify(ops, org.mockito.Mockito.times(50))
                .set(anyString(), eq("1"), eq(Duration.ofSeconds(45)));
    }

    @Test
    void aTicketIsSpentOnFirstUse() {
        when(redis.delete("ws:ticket:abc")).thenReturn(true, false);

        assertTrue(store.consume("abc"), "first use spends the ticket");
        assertFalse(store.consume("abc"), "the same ticket cannot be replayed");
    }

    @Test
    void unknownBlankAndNullTicketsAreRejectedWithoutHittingRedis() {
        assertFalse(store.consume(null));
        assertFalse(store.consume(""));
        assertFalse(store.consume("   "));
    }

    @Test
    void ttlComesFromConfiguration() {
        assertEquals(Duration.ofSeconds(45), store.ttl());
        assertNotEquals(Duration.ofSeconds(45),
                new WsTicketStore(redis, new SecurityProperties("k", "o", null, true, null, null)).ttl());
    }
}
