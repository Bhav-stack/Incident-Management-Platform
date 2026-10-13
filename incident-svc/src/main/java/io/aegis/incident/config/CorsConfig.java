package io.aegis.incident.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Answers cross-origin requests from the dashboard, and — more importantly —
 * answers the browser's preflight before anything else sees it.
 *
 * <p>This exists because of a bug that only appears once the dashboard is
 * served from a different origin than the API. A browser sends {@code OPTIONS}
 * for any request carrying {@code Authorization} or {@code X-Operator-Key}, and
 * that preflight carries no credential by construction: it is the browser
 * asking permission, not the operator making the call. The control-plane filter
 * would answer it with 401, the browser would read that as "not allowed", and
 * every approve, reject and sign-in would fail before the real request was ever
 * sent.
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of
 * {@code ControlPlaneAuthFilter}, which registers itself at
 * {@code HIGHEST_PRECEDENCE + 10}. Spring's {@link CorsFilter} does not call
 * down the chain for a valid preflight — it writes the headers and returns —
 * so authentication is skipped for exactly the requests that must not need it.
 *
 * <p>Scope is limited to {@code /api/**} and {@code /ws/**}. The second is not
 * decoration: SockJS falls back to XHR polling when a WebSocket cannot be
 * established, and those requests are ordinary HTTP and therefore subject to
 * CORS. The WebSocket handshake itself is validated separately by origin in
 * {@code WsConfig}.
 *
 * <p>No credentials mode. The dashboard authenticates with a bearer token in a
 * header, not a cookie, so {@code Allow-Credentials} is off and the origin list
 * stays exact.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** Headers the dashboard actually sends, and no others. */
    private static final List<String> ALLOWED_HEADERS =
            List.of("Authorization", "Content-Type", "X-Operator-Key", "X-API-Key");

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Bean
    public FilterRegistrationBean<CorsFilter> dashboardCorsFilter(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        // Cache the preflight: one extra round trip per approve is otherwise
        // paid on every decision the dashboard makes.
        config.setMaxAge(3600L);
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("dashboardCorsFilter");

        if (properties.any()) {
            log.info("Cross-origin dashboard allowed from {}", properties.allowedOrigins());
        } else {
            log.info("aegis.cors.allowed-origins is empty: the dashboard must be served from "
                    + "this origin. Set it to the dashboard's URL (for example the Vercel "
                    + "deployment) to allow a browser elsewhere to reach the control plane.");
        }
        return registration;
    }
}
