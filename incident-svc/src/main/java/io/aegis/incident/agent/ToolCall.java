package io.aegis.incident.agent;

/**
 * A tool invocation requested by the LLM. {@code arguments} is a JSON object
 * string; the loop passes it through to the tool unchanged and the tool is
 * responsible for parsing it.
 */
public record ToolCall(String id, String name, String arguments) {
}