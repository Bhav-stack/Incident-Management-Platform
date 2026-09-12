package io.aegis.incident.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.incident.action.ActionLockStore;
import io.aegis.incident.app.ActionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery semantics for approved commands. The platform's promise is that an
 * approved recovery action is executed exactly once and is never silently
 * dropped, so each branch of the consumer is pinned here: execute, skip a real
 * duplicate, and hand anything else back to Kafka for redelivery.
 */
class ActionCommandConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ActionLockStore locks = mock(ActionLockStore.class);
    private final ActionExecutor executor = mock(ActionExecutor.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    private ActionCommandConsumer consumer;
    private String json;
    private UUID commandId;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new ActionCommandConsumer(mapper, locks, executor);
        commandId = UUID.randomUUID();
        ActionCommandEvent command = new ActionCommandEvent(commandId, UUID.randomUUID(),
                UUID.randomUUID(), "checkout-service", "restart_instance",
                Map.of("target", "checkout-service"), Instant.now());
        json = mapper.writeValueAsString(command);
    }

    @Test
    void aClaimedCommandIsExecutedAndAcknowledged() {
        when(locks.acquire(commandId)).thenReturn(true);

        consumer.consume(json, ack);

        verify(executor).execute(any(ActionCommandEvent.class));
        verify(ack).acknowledge();
    }

    @Test
    void aRedeliveryOfAnExecutedCommandIsAcknowledgedWithoutExecuting() {
        when(locks.acquire(commandId)).thenReturn(false);
        when(executor.alreadyExecuted(commandId)).thenReturn(true);

        consumer.consume(json, ack);

        verify(executor, never()).execute(any());
        verify(ack).acknowledge();
    }

    @Test
    void aClaimedButUnexecutedCommandIsRetriedRatherThanSkipped() {
        when(locks.acquire(commandId)).thenReturn(false);
        when(executor.alreadyExecuted(commandId)).thenReturn(false);

        assertThrows(RuntimeException.class, () -> consumer.consume(json, ack));

        verify(executor, never()).execute(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void aFailedExecutionReleasesTheClaimAndIsLeftForRedelivery() {
        when(locks.acquire(commandId)).thenReturn(true);
        doThrow(new IllegalStateException("simulator unreachable"))
                .when(executor).execute(any(ActionCommandEvent.class));

        assertThrows(RuntimeException.class, () -> consumer.consume(json, ack));

        verify(locks).release(commandId);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aPoisonMessageIsAcknowledgedSoThePartitionKeepsMoving() {
        consumer.consume("{ not a command", ack);

        verify(ack).acknowledge();
        verify(executor, never()).execute(any());
        verify(locks, never()).acquire(any());
    }
}
