package io.aegis.incident.action;

import io.aegis.incident.config.ActionProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * First line of execution idempotency: {@code SETNX lock:action:{commandId}}.
 * Two consumers racing on the same command: one wins, the other sees the lock
 * and skips. The durable backstops are the unique {@code actions.command_id}
 * index and the proposal status guard (only APPROVED proposals execute).
 */
@Component
public class ActionLockStore {

    private static final String KEY_PREFIX = "lock:action:";

    private final StringRedisTemplate redis;
    private final ActionProperties properties;

    public ActionLockStore(StringRedisTemplate redis, ActionProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** @return true if this command may proceed; false if already claimed */
    public boolean acquire(UUID commandId) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
                KEY_PREFIX + commandId, "1", properties.lockTtl());
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Gives the claim back after a failed attempt so the redelivered command
     * can be retried instead of being skipped until the lock TTL expires.
     * Without this, one transient failure (simulator or database blip) would
     * silently strand an approved command forever.
     */
    public void release(UUID commandId) {
        redis.delete(KEY_PREFIX + commandId);
    }
}