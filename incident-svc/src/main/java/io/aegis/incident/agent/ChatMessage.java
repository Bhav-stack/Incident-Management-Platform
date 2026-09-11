package io.aegis.incident.agent;

import java.util.List;

/**
 * One message in the agent loop's conversation. Covers the three shapes the
 * OpenAI tool-calling API uses: plain roles (system/user/assistant text),
 * assistant messages carrying tool calls, and tool role messages carrying a
 * tool result for one call id.
 */
public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName
) {
    public static ChatMessage text(String role, String content) {
        return new ChatMessage(role, content, null, null, null);
    }

    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> calls) {
        return new ChatMessage("assistant", content, calls, null, null);
    }

    public static ChatMessage toolResult(String toolCallId, String toolName, String result) {
        return new ChatMessage("tool", result, null, toolCallId, toolName);
    }
}