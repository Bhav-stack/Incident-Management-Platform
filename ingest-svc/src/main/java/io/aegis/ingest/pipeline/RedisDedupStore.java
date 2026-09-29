package io.aegis.ingest.pipeline;

import io.aegis.ingest.config.IngestProperties;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * First line of duplicate defense: {@code SETNX dedup:{eventId}} with a TTL.
 * Producer retries and consumer redelivery both resurface the same
 * {@code eventId} — the first one wins, everything after is dropped.
 *
 * <p>Redis is the fast path; the durable backstop is the unique index on
 * {@code anomalies(source_event_id)} in Postgres (AnomalyRecordStore), so a
 * crash between the two layers cannot double-insert an anomaly.
 *
 * <p>The consumer processes a whole Kafka poll batch at once, so dedup is
 * <b>pipelined</b>: one round trip claims every id in the batch. Measured
 * against the previous per-event {@code SETNX}, that is what removed the
 * round-trip-per-event ceiling on the consumer thread (see docs/LOADTEST.md).
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

    /**
     * Claims a whole batch of ids in a single pipelined round trip.
     *
     * @return the ids that were <b>not</b> seen before (the non-duplicates),
     *         in no particular order
     */
    public Set<UUID> filterNew(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ordered = List.copyOf(eventIds);
        RedisSerializer<String> keys = redis.getStringSerializer();
        byte[] marker = keys.serialize("1");
        Expiration expiration = Expiration.from(ttl);

        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands commands = connection.stringCommands();
            for (UUID id : ordered) {
                commands.set(keys.serialize(KEY_PREFIX + id), marker, expiration,
                        RedisStringCommands.SetOption.ifAbsent());
            }
            return null;   // results are collected by the pipeline, in issue order
        });

        Set<UUID> fresh = new HashSet<>(Math.max(16, ordered.size() * 2));
        int n = Math.min(ordered.size(), results.size());
        for (int i = 0; i < n; i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                fresh.add(ordered.get(i));
            }
        }
        return fresh;
    }
}
