package io.aegis.incident.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The hand-rolled tool-calling loop. No agent framework: the conversation is
 * a plain list of messages, the model is reached through {@link LlmClient},
 * and each round works like this:
 *
 * <ol>
 *   <li>Send the accumulated conversation plus tool definitions.</li>
 *   <li>If the model asks for tools, execute each one, append the results as
 *       tool messages, and loop (bounded by max rounds).</li>
 *   <li>If the model answers directly, the answer must be the final JSON
 *       verdict; parse it and return the result with the full trace.</li>
 * </ol>
 *
 * <p>Safety properties: the loop itself has no write access to anything — all
 * tools are read-only investigation; a tool that throws becomes a text error
 * in the conversation instead of failing the run; and a model that never
 * answers is cut off at max rounds and produces no proposal (escalate, never
 * guess).
 */
public class ToolCallingAgent {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingAgent.class);

    private final LlmClient llm;
    private final List<Tool> tools;
    private final int maxRounds;
    private final String systemPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolCallingAgent(LlmClient llm, List<Tool> tools, int maxRounds, String systemPrompt) {
        this.llm = llm;
        this.tools = tools;
        this.maxRounds = maxRounds;
        this.systemPrompt = systemPrompt;
    }

    /** Runs the loop to a verdict. Throws if the model never answers. */
    public AgentResult run(String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("system", systemPrompt));
        messages.add(ChatMessage.text("user", userPrompt));

        List<TraceStep> trace = new ArrayList<>();
        for (int round = 0; round < maxRounds; round++) {
            LlmResponse response = llm.complete(messages, tools);
            if (!response.wantsTools()) {
                return parseVerdict(response.content(), trace);
            }
            messages.add(ChatMessage.assistantWithToolCalls(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                long start = System.nanoTime();
                String result = execute(call);
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                trace.add(new TraceStep(call.name(), call.arguments(), result, durationMs));
                messages.add(ChatMessage.toolResult(call.id(), call.name(), result));
            }
        }
        throw new IllegalStateException(
                "agent exceeded " + maxRounds + " rounds without a final answer");
    }

    private String execute(ToolCall call) {
        return tools.stream()
                .filter(t -> t.name().equals(call.name()))
                .findFirst()
                .map(t -> {
                    try {
                        return t.execute().apply(call.arguments());
                    } catch (Exception e) {
                        log.warn("Tool {} failed: {}", call.name(), e.getMessage());
                        return "tool error: " + e.getMessage();
                    }
                })
                .orElse("unknown tool: " + call.name());
    }

    /** The final answer is a JSON verdict; anything else means no proposal. */
    private AgentResult parseVerdict(String content, List<TraceStep> trace) {
        try {
            JsonNode node = mapper.readTree(content);
            return new AgentResult(
                    node.path("summary").asText(null),
                    node.path("actionType").asText(null),
                    node.path("confidence").asDouble(0.0),
                    mapper.convertValue(node.path("params"), Map.class),
                    trace);
        } catch (Exception e) {
            log.warn("Agent final answer was not a valid verdict: {}", e.getMessage());
            return new AgentResult(null, null, 0.0, null, trace);
        }
    }
}