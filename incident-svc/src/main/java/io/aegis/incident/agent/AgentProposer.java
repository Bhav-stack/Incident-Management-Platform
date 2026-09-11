package io.aegis.incident.agent;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.rules.ProposalDraft;
import io.aegis.incident.rules.Proposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tool-calling agent occupying the {@link Proposer} slot: it investigates
 * the incident with read-only tools, then produces a proposal draft exactly
 * like the rule-based proposer did. The rest of the pipeline — persistence,
 * approval gate, executor, verification — is unaware of the swap.
 *
 * <p>Safety posture: the agent proposes, never executes; a model failure or
 * an answer that is not a valid verdict produces no proposal (the incident
 * stays open and the TTL sweep escalates); the verdict's action type must be
 * one the executor knows; confidence and params are validated before they
 * reach the gate.
 */
public class AgentProposer implements Proposer {

    private static final Logger log = LoggerFactory.getLogger(AgentProposer.class);

    private static final Set<String> KNOWN_ACTIONS = Set.of(
            "restart_instance", "scale_replicas", "rollback_deploy", "clear_cache");

    private final ToolCallingAgent agent;

    public AgentProposer(ToolCallingAgent agent) {
        this.agent = agent;
    }

    @Override
    public Optional<ProposalDraft> propose(AnomalyEvent anomaly) {
        try {
            AgentResult result = agent.run(buildPrompt(anomaly));
            if (result.actionType() == null || !KNOWN_ACTIONS.contains(result.actionType())) {
                log.warn("Agent verdict for {} had no known action ({}); no proposal",
                        anomaly.service(), result.actionType());
                return Optional.empty();
            }
            return Optional.of(toDraft(result, anomaly));
        } catch (Exception e) {
            log.warn("Agent run failed for {}: {}", anomaly.service(), e.getMessage());
            return Optional.empty();
        }
    }

    private ProposalDraft toDraft(AgentResult result, AnomalyEvent anomaly) {
        Map<String, Object> params = result.params() == null
                ? Map.of("target", anomaly.service())
                : new LinkedHashMap<>(result.params());
        params.putIfAbsent("target", anomaly.service());

        double confidence = Math.max(0.0, Math.min(1.0, result.confidence()));

        Map<String, Object> reasoning = new LinkedHashMap<>();
        reasoning.put("summary", result.summary() == null ? "" : result.summary());
        reasoning.put("evidence", List.of(
                "signal=" + anomaly.signal(),
                "value=" + anomaly.value(),
                "threshold=" + anomaly.threshold(),
                "severity=" + anomaly.severity()));
        List<Map<String, Object>> trace = new ArrayList<>();
        result.trace().forEach(step -> trace.add(step.toMap()));
        reasoning.put("trace", trace);

        return new ProposalDraft(
                result.actionType(),
                riskOf(result.actionType()),
                confidence,
                params,
                reasoning);
    }

    private static String riskOf(String actionType) {
        return switch (actionType) {
            case "clear_cache" -> "LOW";
            case "rollback_deploy" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    private static String buildPrompt(AnomalyEvent anomaly) {
        return "Investigate an anomaly and propose exactly one recovery action.\n"
                + "service=" + anomaly.service() + "\n"
                + "signal=" + anomaly.signal() + "\n"
                + "value=" + anomaly.value() + "\n"
                + "threshold=" + anomaly.threshold() + "\n"
                + "severity=" + anomaly.severity() + "\n"
                + "Use the investigation tools to confirm the state of the service.\n"
                + "Then answer with ONLY a JSON object:\n"
                + "{\"summary\": \"<plain-language explanation>\", "
                + "\"actionType\": \"restart_instance|scale_replicas|rollback_deploy|clear_cache\", "
                + "\"params\": {\"target\": \"<service>\", ...}, \"confidence\": <0..1>}\n"
                + "Confidence must reflect how many independent signals support the action. "
                + "If the evidence is weak or contradictory, still answer with the JSON, "
                + "but use a confidence below 0.5.";
    }
}