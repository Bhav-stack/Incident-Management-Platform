package io.aegis.ingest.rules;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed {@link RuleStateStore}.
 *
 * <p>Streak counters self-expire (60s) so a stalled stream forgets old
 * breaches; the fired flag keeps the episode claimed for 15 minutes, then a
 * sustained breach episode may re-fire (deliberate: the anomaly is stale
 * evidence by then). Both keys are deleted by {@link #reset(String)} when a
 * healthy sample arrives.
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
    public long incrementStreak(String key) {
        String k = STREAK_PREFIX + key;
        Long streak = redis.opsForValue().increment(k);
        redis.expire(k, STREAK_TTL);
        return streak == null ? 0 : streak;
    }

    @Override
    public void reset(String key) {
        redis.delete(List.of(STREAK_PREFIX + key, FIRED_PREFIX + key));
    }

    @Override
    public boolean markFired(String key) {
        Boolean claimed = redis.opsForValue().setIfAbsent(FIRED_PREFIX + key, "1", FIRED_TTL);
        return Boolean.TRUE.equals(claimed);
    }
}