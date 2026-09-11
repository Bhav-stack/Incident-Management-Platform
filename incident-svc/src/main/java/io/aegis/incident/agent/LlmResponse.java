package io.aegis.incident.agent;

import java.util.List;

/**
 * A single completion from the model. Either the model answers directly
 * ({@code content} holds the final answer, no tool calls) or it requests one
 * or more tool calls. The loop treats an empty {@code toolCalls} list as a
 * final answer.
 */
public record LlmResponse(String content, List<ToolCall> toolCalls) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}