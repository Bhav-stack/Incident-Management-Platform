package io.aegis.incident.api;

import io.aegis.incident.security.ControlPlaneAuthFilter;
import io.aegis.incident.security.OperatorRegistry;
import io.aegis.incident.security.TokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a long-lived operator credential into a short-lived signed token.
 *
 * <p>Why exchange at all? Because the thing that authorises an approval should
 * be attributable and expiring. An operator key is a secret with no expiry
 * that has to be stored somewhere; a token carries its subject, its issuer and
 * its own id, and it stops working in minutes. Approvals record the token id,
 * so the audit trail names the exact document that signed, not just the person
 * who could have.
 *
 * <p>The credential is presented in {@code X-Operator-Key} and is verified by
 * the same registry that guards {@code /api}; nothing here is trusted from a
 * body. This endpoint is only reachable with a credential the filter already
 * accepted, so a caller who presents no operator key at all (for example the
 * dashboard's shared proxy key) gets 403 rather than an anonymous token.
 *
 * <p>With an external OIDC issuer configured there is nothing to issue here:
 * the provider mints the tokens and this returns 501 pointing at it.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final TokenIssuer tokens;

    public AuthController(TokenIssuer tokens) {
        this.tokens = tokens;
    }

    /**
     * Reports the identity behind the current credential, including whether it
     * came from a token. Purely informational: the dashboard uses it to show
     * who is signed in rather than to make a decision.
     */
    @GetMapping("/session")
    public Map<String, Object> current(HttpServletRequest request) {
        OperatorRegistry.Identity identity = ControlPlaneAuthFilter.identityOf(request);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subject", identity.name());
        response.put("attributable", identity.attributable());
        response.put("method", identity.method());
        response.put("issuer", identity.issuer());
        response.put("tokenId", identity.tokenId());
        response.put("tokenIssuerAvailable", tokens.enabled());
        return response;
    }

    /**
     * Signs the caller in: exchanges the verified operator identity for a
     * token whose subject is that operator. The identity is taken from the
     * credential, so a caller cannot ask for a token in someone else's name.
     */
    @PostMapping("/session")
    public Map<String, Object> signIn(HttpServletRequest request) {
        OperatorRegistry.Identity identity = ControlPlaneAuthFilter.identityOf(request);

        if (!identity.attributable()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The shared client credential cannot sign in as an operator. Present a "
                            + ControlPlaneAuthFilter.OPERATOR_HEADER + " header with an operator "
                            + "key to obtain an attributable token.");
        }
        if (!tokens.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    identity.method().equals(OperatorRegistry.Identity.METHOD_JWT)
                            ? "This request was already authenticated by a token."
                            : "No built-in token issuer is configured. Set "
                                    + "aegis.security.oidc.self-issued-secret, or obtain tokens "
                                    + "from the configured OIDC provider (aegis.security.oidc."
                                    + "issuer-uri) and send them as Authorization: Bearer.");
        }

        TokenIssuer.IssuedToken issued = tokens.issue(identity);
        log.info("Issued an operator token to {} ({}s, attestation method {})",
                issued.subject(), issued.expiresInSeconds(), identity.method());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", issued.token());
        response.put("tokenType", "Bearer");
        response.put("subject", issued.subject());
        response.put("attributable", issued.attributable());
        response.put("issuer", issued.issuer());
        response.put("expiresInSeconds", issued.expiresInSeconds());
        response.put("method", identity.method());
        return response;
    }

    /**
     * Tokens are stateless, so there is nothing to revoke server-side: the
     * client discards it and it expires on its own. An explicit endpoint keeps
     * that behaviour visible instead of implied.
     */
    @DeleteMapping("/session")
    public Map<String, Object> signOut() {
        return Map.of("revoked", false, "detail",
                "tokens are stateless; the client discards it and it expires on its own");
    }
}
