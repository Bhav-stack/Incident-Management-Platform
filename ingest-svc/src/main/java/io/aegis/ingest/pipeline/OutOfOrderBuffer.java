package io.aegis.ingest.pipeline;

import io.aegis.contracts.events.RawEvent;
import io.aegis.ingest.config.IngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Restores <b>event-time</b> ordering before rules run. Kafka guarantees
 * order per partition key, but events from different instances of the same
 * service land in different partitions; this buffer holds every event for
 * {@code window} and drains per-source queues in {@code eventTime} order once
 * the watermark passes them.
 *
 * <p>Policy, in event-time terms:
 * <ul>
 *   <li>watermark = now - window — anything at or before it is emitted in order;</li>
 *   <li>events arriving later than watermark - grace are <b>dropped</b> as
 *       stragglers (logged, never processed out of order);</li>
 *   <li>per-source queues are capped ({@code maxQueueSize}); oldest dropped first.</li>
 * </ul>
 *
 * <p>Trade-off (worth an interview question): a crash between {@code offer}
 * and {@code flush} loses the buffered window — the ack has already happened
 * upstream. That is a bounded, documented gap; closing it fully is what Kafka
 * Streams windowing would buy, which we deliberately avoid.
 */
@Component
public class OutOfOrderBuffer {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderBuffer.class);

    private final IngestProperties.Buffer config;
    private final Clock clock;
    private final ConcurrentHashMap<String, Queue<Entry>> queues = new ConcurrentHashMap<>();
    private volatile Consumer<RawEvent> downstream;

    public OutOfOrderBuffer(IngestProperties properties, Clock clock) {
        this.config = properties.buffer();
        this.clock = clock;
    }

    public void offer(RawEvent event, Consumer<RawEvent> downstream) {
        this.downstream = downstream;
        Instant watermark = clock.instant().minus(config.window());
        if (event.eventTime().isBefore(watermark.minus(config.grace()))) {
            log.warn("Dropping straggler {} from {}: eventTime {} is beyond the grace period",
                    event.eventId(), event.source(), event.eventTime());
            return;
        }
        Queue<Entry> queue = queues.computeIfAbsent(event.source(),
                k -> new PriorityQueue<>(Comparator
                        .comparing(Entry::eventTime)
                        .thenComparing(Entry::eventId)));
        synchronized (queue) {
            queue.add(new Entry(event.eventTime(), event.eventId(), event));
            trim(queue);
        }
    }

    @Scheduled(fixedDelayString = "${ingest.buffer.flush-interval:1s}")
    public void flush() {
        Consumer<RawEvent> sink = downstream;
        if (sink == null) {
            return;
        }
        Instant watermark = clock.instant().minus(config.window());
        queues.forEach((source, queue) -> {
            synchronized (queue) {
                Entry entry;
                while ((entry = queue.peek()) != null && !entry.eventTime().isAfter(watermark)) {
                    queue.poll();
                    sink.accept(entry.event());
                }
            }
        });
    }

    private void trim(Queue<Entry> queue) {
        while (queue.size() > config.maxQueueSize()) {
            Entry dropped = queue.poll();
            log.warn("Buffer overflow for {}, dropping oldest event {}", dropped.event().source(),
                    dropped.event().eventId());
        }
    }

    private record Entry(Instant eventTime, UUID eventId, RawEvent event) {
    }
}