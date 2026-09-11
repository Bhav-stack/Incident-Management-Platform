package io.aegis.incident.agent;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.action.SimulatorClient.ServiceStatus;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent behind the {@link io.aegis.incident.rules.Proposer} interface,
 * driven by the scripted fake LLM: two investigation tool calls, then a
 * verdict. This is the CI path — no network, no API key.
 */
class AgentProposerTest {

    private static final AnomalyEvent ANOMALY = new AnomalyEvent(
            UUID.randomUUID(), UUID.randomUUID(), "checkout-service",
            "error_rate", 8.5, 5.0, "SEV2", Instant.parse("2026-09-08T10:00:00Z"));

    private AgentProposer agentProposer(SimulatorClient simulator) {
        return new AgentProposer(new ToolCallingAgent(
                FakeLlmClient.investigatingAgent(),
                InvestigationTools.forSimulator(simulator),
                6, "test system prompt"));
    }

    @Test
    void investigatesWithToolsThenProposes() {
        SimulatorClient simulator = mock(SimulatorClient.class);
        when(simulator.status(anyString()))
                .thenReturn(new ServiceStatus(8.0, 2, "v2.4.0", Instant.now()));

        Optional<ProposalDraft> draft = agentProposer(simulator).propose(ANOMALY);

        assertTrue(draft.isPresent());
        ProposalDraft d = draft.get();
        assertEquals("restart_instance", d.actionType());
        assertEquals("MEDIUM", d.riskLevel());
        assertEquals(0.82, d.confidence());
        assertEquals("checkout-service", d.params().get("target"));
        // The full investigation trace is on the reasoning map for audit.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace =
                (List<Map<String, Object>>) d.reasoning().get("trace");
        assertEquals(2, trace.size());
        assertEquals("get_service_health", trace.get(0).get("tool"));
        assertEquals("get_metrics", trace.get(1).get("tool"));
        assertTrue(trace.get(0).get("output").toString().contains("8.00"),
                trace.get(0).get("output").toString());
    }

    @Test
    void unknownActionTypeProducesNoProposal() {
        SimulatorClient simulator = mock(SimulatorClient.class);
        when(simulator.status(anyString()))
                .thenReturn(new ServiceStatus(8.0, 2, "v2.4.0", Instant.now()));
        AgentProposer proposer = new AgentProposer(new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse("{\"summary\":\"s\",\"actionType\":\"delete_production\","
                                + "\"params\":{},\"confidence\":0.99}", null))),
                InvestigationTools.forSimulator(simulator), 4, "test"));

        assertFalse(proposer.propose(ANOMALY).isPresent());
    }

    @Test
    void modelFailureProducesNoProposalInsteadOfGuessing() {
        SimulatorClient simulator = mock(SimulatorClient.class);
        AgentProposer proposer = new AgentProposer(new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse(null, List.of(new ToolCall("c1", "get_service_health",
                                "{\"service\":\"checkout-service\"}"))))),
                InvestigationTools.forSimulator(simulator), 4, "test"));

        // Script exhausted mid-run -> the loop throws -> no proposal.
        assertFalse(proposer.propose(ANOMALY).isPresent());
    }

    @Test
    void confidenceIsClampedIntoRange() {
        SimulatorClient simulator = mock(SimulatorClient.class);
        when(simulator.status(anyString()))
                .thenReturn(new ServiceStatus(8.0, 2, "v2.4.0", Instant.now()));
        AgentProposer proposer = new AgentProposer(new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse("{\"summary\":\"s\",\"actionType\":\"clear_cache\","
                                + "\"params\":{},\"confidence\":1.7}", null))),
                InvestigationTools.forSimulator(simulator), 4, "test"));

        Optional<ProposalDraft> draft = proposer.propose(ANOMALY);

        assertTrue(draft.isPresent());
        assertEquals(1.0, draft.get().confidence());
        assertEquals("LOW", draft.get().riskLevel());
    }
}