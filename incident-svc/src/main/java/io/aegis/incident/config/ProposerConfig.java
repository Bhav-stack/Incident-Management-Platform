package io.aegis.incident.config;

import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.agent.AgentProposer;
import io.aegis.incident.agent.FakeLlmClient;
import io.aegis.incident.agent.InvestigationTools;
import io.aegis.incident.agent.LlmClient;
import io.aegis.incident.agent.OpenAiLlmClient;
import io.aegis.incident.agent.ToolCallingAgent;
import io.aegis.incident.rules.Proposer;
import io.aegis.incident.rules.RuleBasedProposer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Chooses who occupies the {@link Proposer} slot: the deterministic
 * rule-based proposer by default, the tool-calling agent when
 * {@code agent.mode=agent}. Both implement the same interface, so the
 * anomaly-to-incident flow, approval gate, and executor never know which one
 * produced the draft. The switch is a demo-time environment variable, not a
 * code change.
 */
@Configuration
public class ProposerConfig {

    private static final String SYSTEM_PROMPT = """
            You are the recovery agent for the Aegis incident platform.
            You investigate incidents using read-only tools and propose exactly
            one recovery action. You can look, you can propose, you never
            execute: execution happens only after a human approves.
            Rules:
            - Always verify the service state with tools before proposing.
            - Prefer the lowest-risk action that addresses the evidence.
            - restart_instance for degraded health, scale_replicas for capacity
              pressure, rollback_deploy when a recent deploy is suspect,
              clear_cache for cache stampedes.
            - Be honest about uncertainty in the confidence value.
            """;

    @Bean
    public Proposer proposer(AgentProperties properties, SimulatorClient simulator) {
        if ("agent".equalsIgnoreCase(properties.mode())) {
            return new AgentProposer(toolCallingAgent(llmClient(properties), simulator, properties));
        }
        return new RuleBasedProposer();
    }

    @Bean
    public LlmClient llmClient(AgentProperties properties) {
        if (properties.fake()) {
            return FakeLlmClient.investigatingAgent();
        }
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException(
                    "agent.mode=agent requires OPENAI_API_KEY or agent.fake=true");
        }
        return new OpenAiLlmClient(properties.baseUrl(), properties.apiKey(),
                properties.model(), properties.temperature());
    }

    @Bean
    public ToolCallingAgent toolCallingAgent(LlmClient llm, SimulatorClient simulator,
                                             AgentProperties properties) {
        return new ToolCallingAgent(llm,
                InvestigationTools.forSimulator(simulator),
                properties.maxRounds(), SYSTEM_PROMPT);
    }
}