package io.aegis.incident.controls;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed kill switches, checked in both the propose path (no proposal is
 * even generated for a killed service) and the execute path (an approved
 * command is refused). Two layers: a global switch and per-service overrides.
 * Redis is the right store: the check must be fast, shared across instances,
 * and independently reversible by an operator even if the database is down.
 */
@Component
public class KillSwitchStore {

    public static final String GLOBAL_KEY = "killswitch:global";
    public static final String SERVICE_PREFIX = "killswitch:service:";

    private final StringRedisTemplate redis;

    public KillSwitchStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True when recovery is disabled for this service (global or per-service). */
    public boolean isKilled(String service) {
        return isGlobalKilled() || isServiceKilled(service);
    }

    public boolean isGlobalKilled() {
        return Boolean.TRUE.equals(redis.hasKey(GLOBAL_KEY));
    }

    public boolean isServiceKilled(String service) {
        return Boolean.TRUE.equals(redis.hasKey(SERVICE_PREFIX + service));
    }

    public void setGlobalKilled(boolean killed) {
        set(GLOBAL_KEY, killed);
    }

    public void setServiceKilled(String service, boolean killed) {
        set(SERVICE_PREFIX + service, killed);
    }

    private void set(String key, boolean killed) {
        if (killed) {
            redis.opsForValue().set(key, "1");
        } else {
            redis.delete(key);
        }
    }
}