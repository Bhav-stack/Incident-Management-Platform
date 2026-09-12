package io.aegis.simulator.config;

import io.aegis.simulator.security.ApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Guards {@code /api/*} on the simulator with {@link ApiKeyFilter}.
 * {@code /actuator/health} and {@code /actuator/prometheus} stay open: health
 * checks, the E2E script, and the scrape job all run without a credential,
 * and neither exposes control over the target services.
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
