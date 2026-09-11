package io.aegis.ingest.api;

import io.aegis.ingest.stats.EventStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final EventStatsService stats;

    public StatsController(EventStatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public Map<String, Long> stats() {
        return stats.snapshot();
    }
}