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

    public void recordCount() {
        total.incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("total", total.get());
        return result;
    }
}