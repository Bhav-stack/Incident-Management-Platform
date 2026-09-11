package io.aegis.incident.controls;

import io.aegis.incident.config.PolicyProperties;
import io.aegis.incident.rules.ProposalDraft;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The policy layer between "the proposer produced a draft" and "the platform
 * acts". Decides one of:
 *
 * <ul>
 *   <li>BLOCKED — kill switch or policy violation; nothing is persisted,
 *       the incident stays open and a timeline note records why.</li>
 *   <li>HUMAN_APPROVAL — the default; the proposal waits at the gate.</li>
 *   <li>AUTO_APPROVE — LOW-risk actions only, and only when the operator
 *       enabled auto-approval; confidence and evidence still must clear the
 *       per-action minimums.</li>
 * </ul>
 *
 * <p>Runtime toggles (shadow mode, LOW auto-approve) live in Redis so the
 * dashboard Controls view can flip them live; the config file is the
 * default for a fresh deployment.
 */
@Component
public class AutonomyPolicy {

    public static final String AUTO_APPROVE_KEY = "policy:auto-approve-low";
    public static final String SHADOW_MODE_KEY = "policy:shadow-mode";

    public enum Verdict {
        BLOCKED,
        HUMAN_APPROVAL,
        AUTO_APPROVE
    }

    public record Decision(Verdict verdict, String reason) {
    }

    private final PolicyProperties properties;
    private final StringRedisTemplate redis;

    public AutonomyPolicy(PolicyProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    public Decision evaluate(ProposalDraft draft) {
        double confidence = draft.confidence();
        double minimum = properties.confidenceMinimums()
                .getOrDefault(draft.actionType(), 0.90);
        if (confidence < minimum) {
            return new Decision(Verdict.BLOCKED, String.format(
                    "confidence %.2f below the %.2f minimum for %s",
                    confidence, minimum, draft.actionType()));
        }
        int signals = evidenceSignals(draft);
        if (signals < properties.minEvidenceSignals()) {
            return new Decision(Verdict.BLOCKED, String.format(
                    "evidence (%d signals) below the %d minimum",
                    signals, properties.minEvidenceSignals()));
        }
        if ("LOW".equals(draft.riskLevel()) && autoApproveLow()) {
            return new Decision(Verdict.AUTO_APPROVE, "LOW risk, confidence and evidence cleared");
        }
        return new Decision(Verdict.HUMAN_APPROVAL,
                "human approval required for " + draft.riskLevel() + " risk");
    }

    /** Evidence signals = entries in the reasoning evidence list. */
    public static int evidenceSignals(ProposalDraft draft) {
        Object evidence = draft.reasoning() == null ? null : draft.reasoning().get("evidence");
        if (evidence instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    public boolean shadowMode() {
        return flag(SHADOW_MODE_KEY);
    }

    public boolean autoApproveLow() {
        return flag(AUTO_APPROVE_KEY);
    }

    public void setShadowMode(boolean enabled) {
        set(SHADOW_MODE_KEY, enabled);
    }

    public void setAutoApproveLow(boolean enabled) {
        set(AUTO_APPROVE_KEY, enabled);
    }

    public Map<String, Double> confidenceMinimums() {
        return properties.confidenceMinimums();
    }

    public int minEvidenceSignals() {
        return properties.minEvidenceSignals();
    }

    private boolean flag(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return AUTO_APPROVE_KEY.equals(key) && properties.autoApproveLow();
        }
        return Boolean.parseBoolean(value);
    }

    private void set(String key, boolean enabled) {
        if (enabled) {
            redis.opsForValue().set(key, "true");
        } else {
            redis.delete(key);
        }
    }
}