package io.aegis.ingest.stats;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters (scaffold). Replaced by Prometheus counters
 * (Micrometer) in Phase 5 — the REST endpoint stays for the dashboard.
 */
@Service
public class EventStatsService {

    private final AtomicLong total = new AtomicLong();

    /** Records a whole consumed batch; the endpoint reports the event total. */
    public void recordCount(long events) {
        total.addAndGet(events);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("total", total.get());
        return result;
    }
}