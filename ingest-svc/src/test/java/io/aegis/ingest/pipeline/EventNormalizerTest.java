package io.aegis.ingest.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventNormalizerTest {

    private final EventNormalizer normalizer =
            new EventNormalizer(new ObjectMapper().registerModule(new JavaTimeModule()));

    private static RawEvent validEvent() {
        return new RawEvent(RawEvent.CURRENT_SCHEMA_VERSION, UUID.randomUUID(),
                EventType.METRIC, Instant.parse("2026-09-08T10:00:00Z"),
                "checkout-service:checkout-api-1", "checkout-service", "checkout-api-1",
                Map.of("value", 1.0));
    }

    private String toJson(RawEvent event) throws Exception {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(event);
    }

    @Test
    void roundTripsValidEvent() throws Exception {
        RawEvent event = validEvent();
        RawEvent normalized = normalizer.normalize(toJson(event));
        assertEquals(event, normalized);
    }

    @Test
    void rejectsFutureSchemaVersion() throws Exception {
        RawEvent event = new RawEvent(RawEvent.CURRENT_SCHEMA_VERSION + 1,
                validEvent().eventId(), EventType.METRIC, validEvent().eventTime(),
                "s:i", "s", "i", Map.of());
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(toJson(event)));
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        RawEvent event = new RawEvent(RawEvent.CURRENT_SCHEMA_VERSION, null,
                EventType.METRIC, validEvent().eventTime(), "s:i", "s", "i", Map.of());
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(toJson(event)));
    }

    @Test
    void rejectsUnparseableJson() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("{not json"));
    }
}