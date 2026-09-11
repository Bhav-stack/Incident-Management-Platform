package io.aegis.ingest.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.RawEvent;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the raw envelope. Rejects unknown schema versions and
 * events missing the idempotency key. Poison messages (unparseable) are
 * acknowledged and logged by the consumer so one bad payload cannot wedge the
 * partition; validation failures are treated the same way in the scaffold and
 * move to a dead-letter topic in Phase 1 hardening.
 */
@Component
public class EventNormalizer {

    private final ObjectMapper mapper;

    public EventNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RawEvent normalize(String json) {
        try {
            RawEvent event = mapper.readValue(json, RawEvent.class);
            if (event.schemaVersion() > RawEvent.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported schema version " + event.schemaVersion());
            }
            if (event.eventId() == null || event.eventTime() == null || event.source() == null) {
                throw new IllegalArgumentException("Event missing eventId/eventTime/source");
            }
            return event;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable raw event: " + e.getMessage(), e);
        }
    }
}