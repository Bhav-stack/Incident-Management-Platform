package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Agent layer configuration. The proposer slot is shared: {@code mode=rule}
 * runs the deterministic proposer (the safe default), {@code mode=agent} runs
 * the tool-calling loop. The fake LLM makes the agent mode runnable in CI and
 * in local demos without an API key.
 *
 * @param mode        rule (default) or agent
 * @param model       model identifier for the real LLM client
 * @param apiKey      API key (usually from the OPENAI_API_KEY env var)
 * @param baseUrl     OpenAI-compatible base URL
 * @param fake        true to use the scripted fake LLM (CI, no key needed)
 * @param maxRounds   cap on loop rounds per incident (runaway protection)
 * @param temperature sampling temperature for the real client
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String mode,
        String model,
        String apiKey,
        String baseUrl,
        boolean fake,
        int maxRounds,
        double temperature
) {

    /**
     * True when the base URL points at a hosted provider that authenticates
     * every request.
     *
     * <p>It matters because the interesting free configuration is a model on
     * localhost or on the Compose network, which needs no key at all. Demanding
     * one there would make the cheapest way to run the agent the one the code
     * refuses to start.
     */
    public boolean hostedProvider() {
        return baseUrl == null
                || baseUrl.toLowerCase(Locale.ROOT).contains("api.openai.com")
                || baseUrl.toLowerCase(Locale.ROOT).contains("api.anthropic.com");
    }
}