package io.aegis.ingest.pipeline;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.ingest.config.IngestProperties;
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

    private OutOfOrderBuffer buffer() {
        IngestProperties props = new IngestProperties(Duration.ofHours(1),
                new IngestProperties.Buffer(WINDOW, GRACE, Duration.ofSeconds(1), 1000),
                null);
        return new OutOfOrderBuffer(props, clock);
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
        buffer.offer(newest, emitted::add);
        buffer.offer(oldest, emitted::add);
        buffer.offer(middle, emitted::add);

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
        buffer.offer(straggler, emitted::add);

        clock.advance(WINDOW.plusSeconds(1));
        buffer.flush();

        assertTrue(emitted.isEmpty());
    }

    @Test
    void lateButWithinGraceIsStillBuffered() {
        OutOfOrderBuffer buffer = buffer();
        List<RawEvent> emitted = new ArrayList<>();

        // 10s behind: inside grace, must be held then emitted in order.
        RawEvent late = event(START.minusSeconds(10), "s:1");
        RawEvent onTime = event(START, "s:1");
        buffer.offer(late, emitted::add);
        buffer.offer(onTime, emitted::add);

        clock.advance(WINDOW.plusSeconds(1));
        buffer.flush();

        assertEquals(List.of(late, onTime), emitted);
    }
}