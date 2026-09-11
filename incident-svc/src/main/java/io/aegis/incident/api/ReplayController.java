package io.aegis.incident.api;

import io.aegis.incident.app.ShadowEvaluator;
import io.aegis.incident.domain.Evaluation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Evaluation harness API: run the current proposer against ground-truth
 * scenarios ({@code POST /api/replay/run}), read recent scores, and read the
 * aggregate precision/recall the dashboard's Evaluation Harness card shows.
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final ShadowEvaluator evaluator;

    public ReplayController(ShadowEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @PostMapping("/run")
    public ShadowEvaluator.ReplaySummary run() {
        return evaluator.runReplay();
    }

    @GetMapping("/evaluations")
    public List<Evaluation> evaluations() {
        return evaluator.recent();
    }

    @GetMapping("/stats")
    public ShadowEvaluator.Stats stats() {
        return evaluator.stats();
    }
}