package io.aegis.incident.config;

import io.aegis.incident.security.ApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires {@link ApiKeyFilter} onto {@code /api/*} and nothing else:
 *
 * <ul>
 *   <li>{@code /actuator/health} stays open so container health checks and
 *       the E2E script work without a credential;</li>
 *   <li>{@code /actuator/prometheus} stays open for the scrape job. In a
 *       hosted deployment Prometheus and the service share a private network,
 *       and the port should not be published publicly;</li>
 *   <li>{@code /ws/**} is not covered by this filter. See the security
 *       section of {@code docs/AUDIT.md} for the residual risk and the two
 *       supported ways to close it.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            @Value("${aegis.security.api-key:}") String apiKey) {

        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(apiKey));
        registration.addUrlPatterns("/api/*");
        registration.setName("apiKeyFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
