package io.aegis.incident.config;

import io.aegis.incident.security.ControlPlaneAuthFilter;
import io.aegis.incident.security.JwtOperatorAuthenticator;
import io.aegis.incident.security.OperatorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the control-plane authentication filter onto {@code /api/*} and
 * nothing else:
 *
 * <ul>
 *   <li>{@code /actuator/health} stays open so container health checks and
 *       the E2E script work without a credential;</li>
 *   <li>{@code /actuator/prometheus} stays open for the scrape job. In a
 *       hosted deployment Prometheus and the service share a private network,
 *       and the port should not be published publicly;</li>
 *   <li>{@code /ws/**} is authenticated separately, by a single-use ticket
 *       (see {@code WsAuthConfig}). It is not exempt.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public OperatorRegistry operatorRegistry(SecurityProperties properties) {
        return new OperatorRegistry(properties);
    }

    @Bean
    public FilterRegistrationBean<ControlPlaneAuthFilter> controlPlaneAuthFilter(
            OperatorRegistry operators, ObjectProvider<JwtOperatorAuthenticator> jwt) {
        FilterRegistrationBean<ControlPlaneAuthFilter> registration =
                new FilterRegistrationBean<>(new ControlPlaneAuthFilter(operators, jwt.getIfAvailable()));
        registration.addUrlPatterns("/api/*");
        registration.setName("controlPlaneAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
