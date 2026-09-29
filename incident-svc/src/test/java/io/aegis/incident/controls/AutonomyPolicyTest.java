package io.aegis.incident.controls;

import io.aegis.incident.config.PolicyProperties;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutonomyPolicyTest {

    private ValueOperations<String, String> ops;
    private StringRedisTemplate redis;
    private AutonomyPolicy policy;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);  // no runtime overrides
        policy = new AutonomyPolicy(new PolicyProperties(null, 2, false, List.of("HIGH")), redis);
    }

    private static ProposalDraft draft(String action, String risk, double confidence,
                                       int evidenceSignals) {
        return new ProposalDraft(action, risk, confidence, Map.of("target", "x"),
                Map.of("evidence", Collections.nCopies(evidenceSignals, "signal")));
    }

    @Test
    void mediumRiskWithClearConfidenceAndEvidenceWaitsForHuman() {
        AutonomyPolicy.Decision decision = policy.evaluate(draft("restart_instance", "MEDIUM", 0.80, 4));

        assertEquals(AutonomyPolicy.Verdict.HUMAN_APPROVAL, decision.verdict());
    }

    @Test
    void confidenceBelowMinimumIsBlocked() {
        AutonomyPolicy.Decision decision = policy.evaluate(draft("rollback_deploy", "HIGH", 0.85, 4));

        assertEquals(AutonomyPolicy.Verdict.BLOCKED, decision.verdict());
        assertEquals(true, decision.reason().contains("0.85"));
    }

    @Test
    void insufficientEvidenceIsBlocked() {
        AutonomyPolicy.Decision decision = policy.evaluate(draft("restart_instance", "MEDIUM", 0.99, 1));

        assertEquals(AutonomyPolicy.Verdict.BLOCKED, decision.verdict());
    }

    @Test
    void lowRiskNeverAutoApprovesUnlessEnabled() {
        assertEquals(AutonomyPolicy.Verdict.HUMAN_APPROVAL,
                policy.evaluate(draft("clear_cache", "LOW", 0.90, 3)).verdict());
    }

    @Test
    void lowRiskAutoApprovesWhenToggleIsOn() {
        when(ops.get(AutonomyPolicy.AUTO_APPROVE_KEY)).thenReturn("true");

        AutonomyPolicy.Decision decision = policy.evaluate(draft("clear_cache", "LOW", 0.90, 3));

        assertEquals(AutonomyPolicy.Verdict.AUTO_APPROVE, decision.verdict());
    }

    @Test
    void unknownActionDefaultsToStrictestThreshold() {
        // No config entry -> 0.90 minimum -> 0.80 is blocked.
        AutonomyPolicy.Decision decision = policy.evaluate(draft("kill_instance", "HIGH", 0.80, 4));
        assertEquals(AutonomyPolicy.Verdict.BLOCKED, decision.verdict());
    }

    @Test
    void highRiskRequiresTwoSignatures() {
        assertTrue(policy.requiresTwoPersonApproval("HIGH"));
        assertEquals(2, policy.requiredApprovals("HIGH"));
        assertEquals(1, policy.requiredApprovals("MEDIUM"));
        assertEquals(1, policy.requiredApprovals(null));
    }

    @Test
    void twoPersonTierIsNeverAutoApprovedEvenWhenTheToggleIsOn() {
        // Auto-approve exists to skip the gate for cheap, reversible actions.
        // A tier that exists because two humans must sign off can never be
        // skipped, whatever the LOW-risk toggle says.
        when(ops.get(AutonomyPolicy.AUTO_APPROVE_KEY)).thenReturn("true");
        AutonomyPolicy permissive = new AutonomyPolicy(
                new PolicyProperties(null, 2, true, List.of("LOW", "HIGH")), redis);

        AutonomyPolicy.Decision decision = permissive.evaluate(draft("clear_cache", "HIGH", 0.95, 4));

        assertEquals(AutonomyPolicy.Verdict.HUMAN_APPROVAL, decision.verdict());
        assertTrue(decision.reason().contains("two operators"));
    }
}