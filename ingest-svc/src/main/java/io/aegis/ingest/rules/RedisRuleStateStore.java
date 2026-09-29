package io.aegis.ingest.rules;

import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed {@link RuleStateStore} with a batched (pipelined) hot path.
 *
 * <p>Streak counters self-expire (60s) so a stalled stream forgets old
 * breaches; the fired flag keeps the episode claimed for 15 minutes, then a
 * sustained breach episode may re-fire (deliberate: the anomaly is stale
 * evidence by then). A healthy sample deletes both keys.
 *
 * <p>{@link #snapshot} (two GETs per key) and {@link #applyStreaks} each issue
 * one pipelined round trip for a whole ordered batch, replacing the previous
 * one-round-trip-per-sample cost that capped ingest at ~490 events/s.
 */
@Component
public class RedisRuleStateStore implements RuleStateStore {

    private static final String STREAK_PREFIX = "anomaly.streak:";
    private static final String FIRED_PREFIX = "anomaly.fired:";
    private static final Duration STREAK_TTL = Duration.ofSeconds(60);
    private static final Duration FIRED_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public RedisRuleStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, State> snapshot(java.util.Collection<String> keys) {
        Map<String, State> result = new HashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        List<String> ordered = List.copyOf(keys);
        List<Object> values = redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands commands = connection.stringCommands();
            for (String key : ordered) {
                commands.get(utf8(STREAK_PREFIX + key));
                commands.get(utf8(FIRED_PREFIX + key));
            }
            return null;
        });
        for (int i = 0; i < ordered.size(); i++) {
            int base = i * 2;
            long streak = base < values.size() ? asLong(values.get(base)) : 0L;
            boolean fired = base + 1 < values.size() && values.get(base + 1) != null;
            result.put(ordered.get(i), new State(streak, fired));
        }
        return result;
    }

    @Override
    public void applyStreaks(Map<String, Long> finalStreaks, Set<String> resetKeys) {
        if (finalStreaks.isEmpty() && resetKeys.isEmpty()) {
            return;
        }
        long streakTtl = STREAK_TTL.toSeconds();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands commands = connection.stringCommands();
            // Deletes first: a key that reset and then breached again in the
            // same batch is in both sets, and its fresh streak must survive.
            for (String key : resetKeys) {
                connection.del(utf8(STREAK_PREFIX + key));
                connection.del(utf8(FIRED_PREFIX + key));
            }
            for (Map.Entry<String, Long> entry : finalStreaks.entrySet()) {
                Long streak = entry.getValue();
                if (streak == null || streak <= 0) {
                    continue;
                }
                commands.set(utf8(STREAK_PREFIX + entry.getKey()),
                        utf8(Long.toString(streak)), Expiration.seconds(streakTtl),
                        RedisStringCommands.SetOption.upsert());
            }
            return null;
        });
    }

    @Override
    public boolean markFired(String key) {
        Boolean claimed = redis.opsForValue()
                .setIfAbsent(FIRED_PREFIX + key, "1", FIRED_TTL);
        return Boolean.TRUE.equals(claimed);
    }

    @Override
    public void clearFired(String key) {
        redis.delete(FIRED_PREFIX + key);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Pipeline results may arrive as byte[], String or Long depending on the serializer. */
    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return Long.parseLong(value.toString());
    }
}
