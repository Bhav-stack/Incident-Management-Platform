package io.aegis.incident.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallingAgentTest {

    private static Tool pingTool() {
        return new Tool("ping", "replies pong", Map.of("echo", "string"), List.of("echo"),
                args -> "pong:" + args);
    }

    @Test
    void executesToolCallsFeedsResultsBackAndParsesVerdict() {
        ToolCallingAgent agent = new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse(null, List.of(new ToolCall("c1", "ping", "{\"echo\":\"hi\"}"))),
                        new LlmResponse("{\"summary\":\"done\",\"actionType\":\"restart_instance\","
                                + "\"params\":{\"target\":\"checkout-service\"},\"confidence\":0.82}", null))),
                List.of(pingTool()), 4, "you are a test agent");

        AgentResult result = agent.run("service=checkout-service");

        assertEquals("restart_instance", result.actionType());
        assertEquals(0.82, result.confidence());
        assertEquals("checkout-service", result.params().get("target"));
        assertEquals(1, result.trace().size());
        assertEquals("ping", result.trace().get(0).tool());
        assertTrue(result.trace().get(0).output().contains("pong"), result.trace().get(0).output());
    }

    @Test
    void toolFailureBecomesConversationTextNotARunFailure() {
        Tool throwing = new Tool("boom", "always fails", Map.of(), List.of(),
                args -> {
                    throw new IllegalStateException("simulated outage");
                });
        ToolCallingAgent agent = new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse(null, List.of(new ToolCall("c1", "boom", "{}"))),
                        new LlmResponse("{\"summary\":\"s\",\"actionType\":\"clear_cache\","
                                + "\"params\":{},\"confidence\":0.5}", null))),
                List.of(throwing), 4, "test");

        AgentResult result = agent.run("service=x");

        assertTrue(result.trace().get(0).output().contains("tool error: simulated outage"),
                result.trace().get(0).output());
    }

    @Test
    void unknownToolNameIsReportedNotExecuted() {
        ToolCallingAgent agent = new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse(null, List.of(new ToolCall("c1", "nope", "{}"))),
                        new LlmResponse("{\"summary\":\"s\",\"actionType\":\"clear_cache\","
                                + "\"params\":{},\"confidence\":0.5}", null))),
                List.of(pingTool()), 4, "test");

        AgentResult result = agent.run("service=x");

        assertEquals("unknown tool: nope", result.trace().get(0).output());
    }

    @Test
    void runawayLoopIsCutOffAtMaxRounds() {
        FakeLlmClient alwaysCalls = new FakeLlmClient(List.of(
                new LlmResponse(null, List.of(new ToolCall("c1", "ping", "{}"))),
                new LlmResponse(null, List.of(new ToolCall("c2", "ping", "{}"))),
                new LlmResponse(null, List.of(new ToolCall("c3", "ping", "{}"))),
                new LlmResponse(null, List.of(new ToolCall("c4", "ping", "{}"))),
                new LlmResponse(null, List.of(new ToolCall("c5", "ping", "{}")))));
        ToolCallingAgent agent = new ToolCallingAgent(
                alwaysCalls, List.of(pingTool()), 3, "test");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> agent.run("service=x"));
        assertTrue(e.getMessage().contains("3 rounds"), e.getMessage());
    }

    @Test
    void unparseableVerdictProducesNoAction() {
        ToolCallingAgent agent = new ToolCallingAgent(
                new FakeLlmClient(List.of(
                        new LlmResponse("I am sorry, I cannot do that.", null))),
                List.of(pingTool()), 4, "test");

        AgentResult result = agent.run("service=x");

        assertEquals(null, result.actionType());
        assertEquals(0.0, result.confidence());
    }
}