package io.aegis.incident.agent;

import java.util.List;

/**
 * Transport boundary of the agent loop. Anything that can take a message list
 * plus tool definitions and return a completion qualifies: OpenAI, a local
 * model, or the scripted fake used in CI. Keeping this interface small is
 * what makes the loop testable without a network.
 */
public interface LlmClient {

    LlmResponse complete(List<ChatMessage> messages, List<Tool> tools);
}