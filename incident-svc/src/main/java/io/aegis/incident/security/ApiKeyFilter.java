package io.aegis.incident.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the control plane.
 *
 * <p>Everything under {@code /api} can change the world: approve a restart,
 * flip a kill switch, run the evaluation harness. This filter requires a
 * shared secret in the {@code X-API-Key} header for those paths and rejects
 * anything else with 401 before the request reaches a controller.
 *
 * <p>Constant-time comparison ({@link MessageDigest#isEqual}) so the header
 * cannot be recovered byte by byte from response timing.
 *
 * <p>An empty configured key disables the filter and logs a warning at
 * startup. That is deliberate for local development and the Testcontainers
 * suite; {@code .env.example} and the deployment runbook both set a key.
 *
 * <p>Scope, stated plainly: this protects the API, not the WebSocket feed
 * (see {@code /ws/**} in the docs) and not the message broker. A hosted
 * deployment should terminate authentication at the load balancer or put an
 * OAuth2 resource server in front and keep this filter as defence in depth.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    public static final String HEADER = "X-API-Key";

    private final byte[] expected;
    private final boolean enabled;

    public ApiKeyFilter(String apiKey) {
        byte[] key = apiKey == null ? new byte[0] : apiKey.trim().getBytes(StandardCharsets.UTF_8);
        this.expected = key;
        this.enabled = key.length > 0;
        if (!enabled) {
            log.warn("aegis.security.api-key is not set: /api is UNAUTHENTICATED. "
                    + "Set AEGIS_API_KEY before exposing this service.");
        } else {
            log.info("Control-plane API key authentication is enabled");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "ApiKey realm=\"aegis\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid "
                + HEADER + "\"}");
    }
}
