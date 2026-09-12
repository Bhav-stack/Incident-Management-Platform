package io.aegis.simulator.security;

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
 * Authenticates the simulator's API.
 *
 * <p>The simulator stands in for the target infrastructure, which makes
 * {@code POST /api/actions/execute} and the scenario endpoints the most
 * powerful calls in the platform: they break services and restart them.
 * Requests must carry the shared secret in the {@code X-API-Key} header.
 *
 * <p>Comparison is constant-time ({@link MessageDigest#isEqual}) so the key
 * cannot be recovered from response timing. An empty configured key disables
 * the filter and logs a warning; that is for local development and tests
 * only, and the incident service's {@code SimulatorClient} sends the same key
 * from {@code AEGIS_API_KEY}.
 *
 * <p>This is the same filter as in incident-svc. It is duplicated rather than
 * shared because the only common module ({@code contracts}) is deliberately
 * dependency-free: roughly forty lines of self-contained code are cheaper
 * than a shared framework module.
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
            log.warn("aegis.security.api-key is not set: the simulator API (fault injection, "
                    + "action execution) is UNAUTHENTICATED. Set AEGIS_API_KEY before exposing it.");
        } else {
            log.info("Simulator API key authentication is enabled");
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
        response.setHeader("WWW-Authenticate", "ApiKey realm=\"aegis-simulator\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid "
                + HEADER + "\"}");
    }
}
