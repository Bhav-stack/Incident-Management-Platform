package io.aegis.incident.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates the control plane and records who is acting.
 *
 * <p>Everything under {@code /api} can change the world: approve a restart,
 * flip a kill switch, run the evaluation harness. This filter resolves a
 * verified identity for those paths and rejects anything else with 401 before
 * the request reaches a controller.
 *
 * <p>Three credentials are accepted, in this order:
 *
 * <ol>
 *   <li>{@code Authorization: Bearer <jwt>} — an operator token. The subject
 *       is the approver and the token id is the attestation. Checked first so
 *       a signed, subject-bound credential always wins over a shared secret
 *       that a proxy or a colleague might have attached.</li>
 *   <li>{@code X-Operator-Key: <key>} — a per-person key. A separate header
 *       from the shared one on purpose: the dashboard's proxy injects
 *       {@code X-API-Key} on every call, so an operator credential needs
 *       somewhere of its own to live.</li>
 *   <li>{@code X-API-Key: <key>} — the shared client credential the dashboard
 *       proxy holds. It authenticates a client, not a person.</li>
 * </ol>
 *
 * <p>If a bearer token is presented it must verify: the filter never falls
 * back from a bad token to a good key, because that would let a forged or
 * expired token ride on an unrelated credential instead of being refused.
 *
 * <p>On success the resulting {@link OperatorRegistry.Identity} is stored as a
 * request attribute. Controllers read the identity from there, never from the
 * request body, so an approval cannot claim to be someone it is not.
 *
 * <p>With nothing configured the filter is disabled and logs a warning at
 * startup. That is deliberate for local development and the Testcontainers
 * suite; every request is then attributed to {@code anonymous}, which is a
 * single identity and therefore cannot satisfy the two-person rule.
 */
public class ControlPlaneAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneAuthFilter.class);

    /** Shared client credential. */
    public static final String HEADER = "X-API-Key";

    /** Per-person credential. */
    public static final String OPERATOR_HEADER = "X-Operator-Key";

    public static final String AUTHORIZATION = "Authorization";

    public static final String BEARER_PREFIX = "Bearer ";

    private static final String CHALLENGE = "WWW-Authenticate";

    /** Request attribute holding the verified {@link OperatorRegistry.Identity}. */
    public static final String OPERATOR_ATTRIBUTE = "aegis.operator";

    /** Identity used when no credential is configured (local development). */
    public static final String ANONYMOUS = OperatorRegistry.Identity.ANONYMOUS.name();

    private final OperatorRegistry operators;
    private final JwtOperatorAuthenticator jwt;
    private final boolean authenticating;

    public ControlPlaneAuthFilter(OperatorRegistry operators) {
        this(operators, null);
    }

    public ControlPlaneAuthFilter(OperatorRegistry operators, JwtOperatorAuthenticator jwt) {
        this.operators = operators;
        // A verifier that has nowhere to get its issuer from refuses every
        // token, so treat it as absent: otherwise a keyless local stack would
        // start demanding credentials it can never accept.
        this.jwt = jwt != null && jwt.configured() ? jwt : null;
        this.authenticating = operators.enabled() || this.jwt != null;

        if (!authenticating) {
            log.warn("aegis.security is not configured: /api is UNAUTHENTICATED and every "
                    + "request is attributed to '{}'. Set AEGIS_API_KEY before exposing this "
                    + "service.", ANONYMOUS);
            return;
        }
        log.info("Control-plane authentication enabled: {} identit{}, {} attributable, "
                        + "bearer tokens {}",
                operators.identityCount(), operators.identityCount() == 1 ? "y" : "ies",
                operators.attributableIdentityCount(),
                this.jwt != null ? "accepted" : "not accepted");
    }

    /** Convenience for tests: one shared key attributed to a single operator. */
    public ControlPlaneAuthFilter(String sharedKey) {
        this(OperatorRegistry.ofSharedKey(sharedKey));
    }

    /**
     * The verified identity behind a request, or the anonymous / unattributed
     * identity when authentication is disabled. Never {@code null}.
     */
    public static OperatorRegistry.Identity identityOf(HttpServletRequest request) {
        Object identity = request.getAttribute(OPERATOR_ATTRIBUTE);
        return identity instanceof OperatorRegistry.Identity verified
                ? verified
                : OperatorRegistry.Identity.ANONYMOUS;
    }

    /** Convenience for callers that only need the name. */
    public static String operatorOf(HttpServletRequest request) {
        return identityOf(request).name();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!authenticating) {
            request.setAttribute(OPERATOR_ATTRIBUTE, OperatorRegistry.Identity.ANONYMOUS);
            chain.doFilter(request, response);
            return;
        }

        Optional<OperatorRegistry.Identity> identity = resolve(request);
        if (identity.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(CHALLENGE, "Bearer realm=\"aegis\", ApiKey realm=\"aegis\"");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid credential: present a "
                    + BEARER_PREFIX.trim() + " token, an operator key, or the shared key\"}");
            return;
        }

        request.setAttribute(OPERATOR_ATTRIBUTE, identity.get());
        chain.doFilter(request, response);
    }

    /**
     * Bearer token, then operator key, then shared key. The order is the
     * authority order: a credential that names a person outranks one that
     * names a client.
     */
    private Optional<OperatorRegistry.Identity> resolve(HttpServletRequest request) {
        if (jwt != null) {
            String header = request.getHeader(AUTHORIZATION);
            if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0,
                    BEARER_PREFIX.length())) {
                return jwt.authenticate(header.substring(BEARER_PREFIX.length()).trim());
            }
        }
        Optional<OperatorRegistry.Identity> operator =
                operators.authenticateNamed(request.getHeader(OPERATOR_HEADER));
        if (operator.isPresent()) {
            return operator;
        }
        return operators.authenticate(request.getHeader(HEADER));
    }
}
