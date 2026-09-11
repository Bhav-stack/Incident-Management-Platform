package io.aegis.incident.agent;

import java.util.Map;
import java.util.function.Function;

/**
 * A capability the agent can call. {@code parameters} is a JSON object schema
 * (name -> type) sent to the model; {@code execute} receives the JSON
 * arguments string the model produced and returns a text result that is fed
 * back into the conversation. Tools must be side-effect-safe unless the
 * action executor is involved: investigation tools read, only the approval
 * gate writes.
 *
 * @param name        tool identifier, snake_case
 * @param description what the tool does, for the model's planner
 * @param parameters  JSON schema: property name -> type ("string", "number")
 * @param required    parameter names the model must supply
 * @param execute     applies the tool to its JSON arguments
 */
public record Tool(
        String name,
        String description,
        Map<String, String> parameters,
        java.util.List<String> required,
        Function<String, String> execute
) {

    /** JSON Schema object for OpenAI-style function calling. */
    public String parametersSchema() {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        int i = 0;
        for (Map.Entry<String, String> p : parameters.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(p.getKey()).append("\":{\"type\":\"").append(p.getValue()).append("\"}");
        }
        sb.append("},\"required\":[");
        for (int j = 0; j < required.size(); j++) {
            if (j > 0) {
                sb.append(',');
            }
            sb.append('"').append(required.get(j)).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }
}