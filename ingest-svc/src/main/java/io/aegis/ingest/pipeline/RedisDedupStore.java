package io.aegis.ingest.pipeline;

import io.aegis.ingest.config.IngestProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * First line of duplicate defense: {@code SETNX dedup:{eventId}} with a TTL.
 * Producer retries and consumer redelivery both resurface the same
 * {@code eventId} — the first one wins, everything after is dropped.
 *
 * <p>Redis is the fast path; the durable backstop is the unique index on
 * {@code anomalies(source_event_id)} in Postgres (AnomalyRecordStore), so a
 * crash between the two layers cannot double-insert an anomaly.
 */
@Component
public class RedisDedupStore {

    private static final String KEY_PREFIX = "dedup:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisDedupStore(StringRedisTemplate redis, IngestProperties properties) {
        this.redis = redis;
        this.ttl = properties.dedupTtl();
    }

    /** @return true if this event id was already seen (duplicate) */
    public boolean isDuplicate(UUID eventId) {
        Boolean firstSeen = redis.opsForValue().setIfAbsent(KEY_PREFIX + eventId, "1", ttl);
        return !Boolean.TRUE.equals(firstSeen);
    }
}