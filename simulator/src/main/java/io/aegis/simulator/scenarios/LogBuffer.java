package io.aegis.simulator.scenarios;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ring buffer of the log lines the simulator emits, per service.
 * This is the surface the agent's {@code search_logs} tool reads; the lines
 * themselves are the same structured payloads that go to Kafka. Capped so a
 * long demo cannot grow unbounded.
 */
@Component
public class LogBuffer {

    private static final int CAPACITY_PER_SERVICE = 500;

    private final Map<String, Deque<String>> lines = new ConcurrentHashMap<>();

    public void record(String service, Instant timestamp, String level, String message,
                       Map<String, Object> payload) {
        StringBuilder line = new StringBuilder()
                .append(timestamp.toString())
                .append(" ").append(level)
                .append(" ").append(message);
        payload.forEach((k, v) -> line.append(" ").append(k).append("=").append(v));
        lines.computeIfAbsent(service, s -> new ArrayDeque<>()).addLast(line.toString());
        Deque<String> deque = lines.get(service);
        while (deque.size() > CAPACITY_PER_SERVICE) {
            deque.pollFirst();
        }
    }

    /** Most recent {@code limit} lines, newest first, optionally filtered. */
    public List<String> recent(String service, int limit, String query) {
        Deque<String> deque = lines.get(service);
        if (deque == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        synchronized (deque) {
            var it = deque.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                String line = it.next();
                if (query == null || query.isBlank() || line.toLowerCase().contains(query.toLowerCase())) {
                    result.add(line);
                }
            }
        }
        return result;
    }
}