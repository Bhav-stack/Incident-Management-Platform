package io.aegis.ingest.pipeline;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.ingest.config.IngestProperties;
import io.aegis.ingest.metrics.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutOfOrderBufferTest {

    private static final Instant START = Instant.parse("2026-09-08T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(5);
    private static final Duration GRACE = Duration.ofSeconds(15);

    private final MutableClock clock = new MutableClock(START);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private OutOfOrderBuffer buffer() {
        return buffer(1000);
    }

    private OutOfOrderBuffer buffer(int maxQueueSize) {
        IngestProperties props = new IngestProperties(Duration.ofHours(1),
                new IngestProperties.Buffer(WINDOW, GRACE, Duration.ofSeconds(1), maxQueueSize),
                null, null);
        return new OutOfOrderBuffer(props, clock, new IngestMetrics(metrics));
    }

    private static RawEvent event(Instant eventTime, String source) {
        return new RawEvent(RawEvent.CURRENT_SCHEMA_VERSION, UUID.randomUUID(),
                EventType.METRIC, eventTime, source, "svc", "inst", Map.of("value", 1.0));
    }

    @Test
    void emitsInEventTimeOrder() {
        OutOfOrderBuffer buffer = buffer();
        List<RawEvent> emitted = new ArrayList<>();

        // Arrive out of order: newest first, then oldest, then middle.
        RawEvent newest = event(START.plusSeconds(4), "s:1");
        RawEvent oldest = event(START.plusSeconds(1), "s:1");
        RawEvent middle = event(START.plusSeconds(2), "s:1");
        buffer.offer(newest, emitted::addAll);
        buffer.offer(oldest, emitted::addAll);
        buffer.offer(middle, emitted::addAll);

        // Nothing may be emitted while events are still inside the window.
        buffer.flush();
        assertTrue(emitted.isEmpty());

        // Advance past the newest event (+4s): watermark = now - 5s >= +4s.
        clock.advance(Duration.ofSeconds(9));
        buffer.flush();

        assertEquals(List.of(oldest, middle, newest), emitted);
    }

    @Test
    void dropsStragglersOlderThanGrace() {
        OutOfOrderBuffer buffer = buffer();
        List<RawEvent> emitted = new ArrayList<>();

        // > 20s behind the watermark (window 5s + grace 15s) -> too late.
        RawEvent straggler = event(START.minusSeconds(21), "s:1");
        buffer.offer(straggler, emitted::addAll);

        clock.advance(WINDOW.plusSeconds(1));
        buffer.flush();

        assertTrue(emitted.isEmpty());
        // The drop has to be countable, not just a line in the log: a silent
        // drop is indistinguishable from a pipeline that is keeping up.
        assertEquals(1.0, metrics.counter("aegis_events_dropped_straggler").count());
    }

    @Test
    void countsOverflowDrops() {
        OutOfOrderBuffer buffer = buffer(2);
        List<RawEvent> emitted = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            buffer.offer(event(START.plusSeconds(i), "s:1"), emitted::addAll);
        }

        assertEquals(3.0, metrics.counter("aegis_events_dropped_overflow").count());
    }

    @Test
    void lateButWithinGraceIsStillBuffered() {
        OutOfOrderBuffer buffer = buffer();
        List<RawEvent> emitted = new ArrayList<>();

        // 10s behind: inside grace, must be held then emitted in order.
        RawEvent late = event(START.minusSeconds(10), "s:1");
        RawEvent onTime = event(START, "s:1");
        buffer.offer(late, emitted::addAll);
        buffer.offer(onTime, emitted::addAll);

        clock.advance(WINDOW.plusSeconds(1));
        buffer.flush();

        assertEquals(List.of(late, onTime), emitted);
    }
}