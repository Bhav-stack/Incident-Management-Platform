package io.aegis.incident.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible chat completions client (works with OpenAI and any
 * OpenAI-compatible endpoint, e.g. a local gateway). No SDK, just the REST
 * API, so the transport is a single {@link RestClient} call and the request
 * shape is visible in one place.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String model;
    private final double temperature;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, double temperature) {
        this.model = model;
        this.temperature = temperature;
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public LlmResponse complete(List<ChatMessage> messages, List<Tool> tools) {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("model", model);
            request.put("temperature", temperature);
            request.set("messages", toMessages(messages));
            request.set("tools", toToolDefinitions(tools));

            String body = rest.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode message = mapper.readTree(body).path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : toolCalls) {
                calls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
            }
            String content = message.path("content").isNull() ? null : message.path("content").asText();
            return new LlmResponse(content, calls.isEmpty() ? null : calls);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private ArrayNode toMessages(List<ChatMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCalls() != null) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode fn = callNode.putObject("function");
                    fn.put("name", call.name());
                    fn.put("arguments", call.arguments());
                }
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
                node.put("name", message.toolName());
            }
            array.add(node);
        }
        return array;
    }

    private ArrayNode toToolDefinitions(List<Tool> tools) {
        ArrayNode array = mapper.createArrayNode();
        for (Tool tool : tools) {
            ObjectNode def = array.addObject();
            def.put("type", "function");
            ObjectNode fn = def.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            try {
                fn.set("parameters", mapper.readTree(tool.parametersSchema()));
            } catch (Exception e) {
                fn.put("parameters", "{}");
            }
        }
        return array;
    }

    /** Marker so callers can distinguish model failure from no-verdict. */
    public static class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}