package io.aegis.incident.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted LLM used for CI and local development without an API key. Each
 * call consumes the next scripted response; placeholders like {@code {{service}}}
 * are filled from the user message so the script stays deterministic while
 * still reflecting the real incident.
 *
 * <p>This is deliberately not a "clever" fake: it proves the loop mechanics
 * (tool calls executed, results fed back, verdict parsed, trace recorded)
 * and gives the demo a reproducible agent run. The real model is
 * {@link OpenAiLlmClient}.
 */
public class FakeLlmClient implements LlmClient {

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("service[\"']?\\s*[:=]\\s*[\"']?([a-z0-9-]+)", Pattern.CASE_INSENSITIVE);

    private final List<LlmResponse> script;
    private int callIndex = 0;

    public FakeLlmClient(List<LlmResponse> script) {
        this.script = script;
    }

    /** Default investigation script: health, then metrics, then a verdict. */
    public static FakeLlmClient investigatingAgent() {
        return new FakeLlmClient(List.of(
                new LlmResponse(null, List.of(new ToolCall("c1", "get_service_health",
                        "{\"service\":\"{{service}}\"}"))),
                new LlmResponse(null, List.of(new ToolCall("c2", "get_metrics",
                        "{\"service\":\"{{service}}\",\"metric\":\"error_rate\"}"))),
                new LlmResponse("{\"summary\":\"Service is degraded with an elevated error rate. "
                        + "Health probe confirms the service is unhealthy and metrics confirm the "
                        + "spike. A restart is the lowest-risk recovery.\","
                        + "\"actionType\":\"restart_instance\","
                        + "\"params\":{\"target\":\"{{service}}\"},"
                        + "\"confidence\":0.82}", null)
        ));
    }

    @Override
    public synchronized LlmResponse complete(List<ChatMessage> messages, List<Tool> tools) {
        // A fresh run starts with exactly system+user; restart the script so
        // the singleton fake bean serves every incident, not just the first.
        if (messages.size() <= 2) {
            callIndex = 0;
        }
        if (callIndex >= script.size()) {
            throw new IllegalStateException("fake LLM script exhausted after " + callIndex + " calls");
        }
        String service = findService(messages);
        LlmResponse response = script.get(callIndex++);
        if (service == null) {
            return response;
        }
        return new LlmResponse(
                fill(response.content(), service),
                response.toolCalls() == null ? null
                        : response.toolCalls().stream()
                                .map(c -> new ToolCall(c.id(), c.name(), fill(c.arguments(), service)))
                                .toList());
    }

    private static String findService(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if ("user".equals(message.role()) && message.content() != null) {
                Matcher matcher = SERVICE_PATTERN.matcher(message.content());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    private static String fill(String template, String service) {
        if (template == null) {
            return null;
        }
        return template.replace("{{service}}", service.toLowerCase(Locale.ROOT));
    }
}