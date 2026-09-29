package io.aegis.incident.api;

import io.aegis.incident.config.JwtConfig;
import io.aegis.incident.config.SecurityProperties;
import io.aegis.incident.security.ControlPlaneAuthFilter;
import io.aegis.incident.security.JwtOperatorAuthenticator;
import io.aegis.incident.security.OperatorRegistry;
import io.aegis.incident.security.TokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session endpoint is the one place a long-lived credential becomes a
 * short-lived one. It is also the one place a mistake would be costly: if it
 * would mint a token for whoever asks, the two-person rule would be decorative.
 */
class AuthControllerTest {

    private static final String SECRET =
            "control-plane-test-secret-0123456789abcdef";
    private static final String ISSUER = "https://aegis.local/control-plane";

    @Test
    void anOperatorKeyIsExchangedForATokenInThatOperatorsName() {
        SecurityProperties properties = properties();
        AuthController controller = new AuthController(new TokenIssuer(properties));

        Map<String, Object> session = controller.signIn(
                request(new OperatorRegistry.Identity("alice", true)));

        assertEquals("Bearer", session.get("tokenType"));
        assertEquals("alice", session.get("subject"));
        assertEquals(Boolean.TRUE, session.get("attributable"));
        assertEquals(300L, session.get("expiresInSeconds"));

        // What the client is handed must verify, and must name the same person.
        String token = (String) session.get("token");
        assertNotNull(token);
        OperatorRegistry.Identity identity = new JwtOperatorAuthenticator(
                new JwtConfig().jwtDecoder(properties), properties.oidc())
                .authenticate(token).orElseThrow();
        assertEquals("alice", identity.name());
        assertTrue(identity.attributable());
    }

    @Test
    void theSharedClientCredentialCannotSignIn() {
        AuthController controller = new AuthController(new TokenIssuer(properties()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.signIn(request(new OperatorRegistry.Identity("dashboard", false))));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains(ControlPlaneAuthFilter.OPERATOR_HEADER),
                "the refusal should say how to do it properly: " + e.getReason());
    }

    @Test
    void withNoBuiltInIssuerTheCallerIsPointedAtTheProvider() {
        // Issuer-uri deployments mint tokens elsewhere; the endpoint must not
        // pretend to be an identity provider.
        SecurityProperties external = new SecurityProperties("shared", "dashboard",
                List.of(), true, 60, new SecurityProperties.Oidc(
                        "https://idp.example/realms/aegis", "aegis-control-plane",
                        ISSUER, null, 300, "aegis_attributable"));
        AuthController controller = new AuthController(new TokenIssuer(external));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.signIn(request(new OperatorRegistry.Identity("alice", true))));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, e.getStatusCode());
        assertTrue(e.getReason().contains("issuer-uri") || e.getReason().contains("OIDC"),
                e.getReason());
    }

    @Test
    void theCurrentSessionReportsHowTheCallerWasProven() {
        AuthController controller = new AuthController(new TokenIssuer(properties()));

        Map<String, Object> keySession = controller.current(
                request(new OperatorRegistry.Identity("alice", true)));
        assertEquals("alice", keySession.get("subject"));
        assertEquals(OperatorRegistry.Identity.METHOD_API_KEY, keySession.get("method"));
        assertEquals(Boolean.TRUE, keySession.get("tokenIssuerAvailable"));

        Map<String, Object> tokenSession = controller.current(request(new OperatorRegistry.Identity(
                "alice", true, OperatorRegistry.Identity.METHOD_JWT, "issuer", "jti-7")));
        assertEquals(OperatorRegistry.Identity.METHOD_JWT, tokenSession.get("method"));
        assertEquals("jti-7", tokenSession.get("tokenId"));
    }

    @Test
    void signingOutIsStatelessAndSaysSo() {
        AuthController controller = new AuthController(new TokenIssuer(properties()));

        assertEquals(Boolean.FALSE, controller.signOut().get("revoked"));
    }

    private static SecurityProperties properties() {
        return new SecurityProperties("shared", "dashboard", List.of(
                new SecurityProperties.Operator("alice", "alice-key")), true, 60,
                new SecurityProperties.Oidc(null, "aegis-control-plane", ISSUER,
                        SECRET, 300, "aegis_attributable"));
    }

    /** Mimics what the filter leaves on the request after it authenticated. */
    private static MockHttpServletRequest request(OperatorRegistry.Identity identity) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/session");
        request.setAttribute(ControlPlaneAuthFilter.OPERATOR_ATTRIBUTE, identity);
        return request;
    }
}
