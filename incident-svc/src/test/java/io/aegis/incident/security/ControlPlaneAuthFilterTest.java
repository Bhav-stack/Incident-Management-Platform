package io.aegis.incident.security;

import io.aegis.incident.config.JwtConfig;
import io.aegis.incident.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control plane (approvals, kill switches) must be unreachable without a
 * configured credential, and reachable with one. "Unreachable" is asserted
 * against the filter chain itself, not just the status code.
 *
 * <p>Precedence is part of the contract: a signed token that names a person
 * outranks a per-person key, which outranks the shared client secret, because
 * the strongest available evidence should be what an approval is recorded
 * against.
 */
class ControlPlaneAuthFilterTest {

    private static final String KEY = "s3cret-key";

    @Test
    void requestWithoutAnyCredentialIsRejectedBeforeTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneAuthFilter(KEY).doFilter(request(null), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "the request must not reach the controller");
        String challenge = response.getHeader("WWW-Authenticate");
        assertNotNull(challenge);
        assertTrue(challenge.contains("ApiKey"), "the challenge must name the key scheme");
        assertTrue(challenge.contains("Bearer"), "and the token scheme");
    }

    @Test
    void requestWithTheWrongKeyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneAuthFilter(KEY).doFilter(request("wrong"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void requestWithTheKeyReachesTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneAuthFilter(KEY).doFilter(request(KEY), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void anEmptyKeyDisablesTheFilterForLocalDevelopment() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(null);

        new ControlPlaneAuthFilter("  ").doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals(OperatorRegistry.Identity.ANONYMOUS,
                request.getAttribute(ControlPlaneAuthFilter.OPERATOR_ATTRIBUTE),
                "a keyless stack has exactly one identity, which cannot satisfy a "
                        + "two-person rule");
        assertFalse(ControlPlaneAuthFilter.identityOf(request).attributable());
    }

    @Test
    void theKeyDecidesTheOperatorIdentityNotTheRequest() throws Exception {
        MockHttpServletRequest request = request("oncall-key");

        new ControlPlaneAuthFilter(namedOperators()).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("oncall", ControlPlaneAuthFilter.operatorOf(request));
        assertTrue(ControlPlaneAuthFilter.identityOf(request).attributable());
    }

    @Test
    void anUnverifiedRequestHasNoIdentityAtAll() throws Exception {
        MockHttpServletRequest request = request(null);

        new ControlPlaneAuthFilter(KEY).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain());

        assertNull(request.getAttribute(ControlPlaneAuthFilter.OPERATOR_ATTRIBUTE),
                "a rejected request must never be attributed to an operator");
    }

    // --- operator key path ------------------------------------------------

    @Test
    void anOperatorKeyNamesAPersonWithoutAnyToken() throws Exception {
        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.OPERATOR_HEADER, "oncall-key");

        new ControlPlaneAuthFilter(namedOperators()).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        OperatorRegistry.Identity identity = ControlPlaneAuthFilter.identityOf(request);
        assertEquals("oncall", identity.name());
        assertTrue(identity.attributable());
        assertEquals(OperatorRegistry.Identity.METHOD_API_KEY, identity.method());
        assertNull(identity.tokenId(), "a key has no document to attest to");
    }

    @Test
    void theSharedKeyInTheOperatorHeaderIsNotAnOperator() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.OPERATOR_HEADER, "shared");

        new ControlPlaneAuthFilter(namedOperators()).doFilter(request, response, chain);

        assertEquals(401, response.getStatus(),
                "a credential that is supposed to name a person must not quietly resolve to "
                        + "the shared client identity");
        assertNull(chain.getRequest());
    }

    // --- bearer token path ------------------------------------------------

    @Test
    void aBearerTokenAuthenticatesAsItsSubjectAndCarriesAttestation() throws Exception {
        OperatorRegistry.Identity alice = new OperatorRegistry.Identity("alice", true);
        String token = issuer().issue(alice).token();

        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.AUTHORIZATION, "Bearer " + token);

        new ControlPlaneAuthFilter(namedOperators(), authenticator()).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        OperatorRegistry.Identity identity = ControlPlaneAuthFilter.identityOf(request);
        assertEquals("alice", identity.name(), "the token subject is the approver");
        assertTrue(identity.attributable());
        assertEquals(OperatorRegistry.Identity.METHOD_JWT, identity.method());
        assertEquals(ISSUER, identity.issuer());
        assertNotNull(identity.tokenId(), "an approval must record which token signed it");
    }

    @Test
    void aTokenOutranksAnOperatorKeyWhenBothArePresent() throws Exception {
        String token = issuer().issue(new OperatorRegistry.Identity("alice", true)).token();

        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.OPERATOR_HEADER, "oncall-key");
        request.addHeader(ControlPlaneAuthFilter.AUTHORIZATION, "Bearer " + token);

        new ControlPlaneAuthFilter(namedOperators(), authenticator()).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("alice", ControlPlaneAuthFilter.operatorOf(request),
                "the signed credential is the stronger evidence and must be the one recorded");
        assertEquals(OperatorRegistry.Identity.METHOD_JWT,
                ControlPlaneAuthFilter.identityOf(request).method());
    }

    @Test
    void anInvalidTokenIsNotSilentlyDowngradedToAKey() throws Exception {
        // Presenting a bearer token is a claim about how you authenticated. If
        // it does not verify, falling back to a weaker credential would let a
        // broken or forged token ride an unrelated key: refuse instead.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.OPERATOR_HEADER, "oncall-key");
        request.addHeader(ControlPlaneAuthFilter.AUTHORIZATION, "Bearer not-a-jwt");

        new ControlPlaneAuthFilter(namedOperators(), authenticator()).doFilter(request,
                response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void aVerifierWithNoIssuerDoesNotTurnOnAuthentication() throws Exception {
        // Regression: the verifier bean exists in every deployment, but a
        // deployment that named no issuer and no key must stay open rather
        // than start rejecting calls it can never authenticate.
        SecurityProperties bare = new SecurityProperties("", "operator", List.of(), true, 60,
                null);
        JwtOperatorAuthenticator unconfigured = new JwtOperatorAuthenticator(
                new JwtConfig().jwtDecoder(bare), bare.oidc());
        assertFalse(unconfigured.configured());

        MockHttpServletRequest request = request(null);
        new ControlPlaneAuthFilter(new OperatorRegistry(bare), unconfigured).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(OperatorRegistry.Identity.ANONYMOUS,
                request.getAttribute(ControlPlaneAuthFilter.OPERATOR_ATTRIBUTE));
    }

    @Test
    void aTokenIsNotEnoughWhenNoVerifierIsConfigured() throws Exception {
        // Same registry, no JwtOperatorAuthenticator: this is the deployment
        // where nobody configured an issuer, so no token may be trusted.
        MockHttpServletRequest request = request(null);
        request.addHeader(ControlPlaneAuthFilter.AUTHORIZATION, "Bearer " + KEY);

        new ControlPlaneAuthFilter(namedOperators()).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());

        assertNull(request.getAttribute(ControlPlaneAuthFilter.OPERATOR_ATTRIBUTE));
    }

    // --- helpers ----------------------------------------------------------

    private static OperatorRegistry namedOperators() {
        return new OperatorRegistry(new SecurityProperties(
                "shared", "dashboard", List.of(
                        new SecurityProperties.Operator("bhav", "bhav-key"),
                        new SecurityProperties.Operator("oncall", "oncall-key")), true, 60,
                null));
    }

    private static final String SECRET =
            "control-plane-test-secret-0123456789abcdef";   // 44 bytes, HS256-safe
    private static final String ISSUER = "https://aegis.local/control-plane";

    private static SecurityProperties withSelfIssuer() {
        return new SecurityProperties("shared", "dashboard", List.of(), true, 60,
                new SecurityProperties.Oidc(null, ISSUER, ISSUER,
                        SECRET, 300, "aegis_attributable"));
    }

    private static TokenIssuer issuer() {
        return new TokenIssuer(withSelfIssuer());
    }

    private static JwtOperatorAuthenticator authenticator() {
        SecurityProperties properties = withSelfIssuer();
        return new JwtOperatorAuthenticator(new JwtConfig().jwtDecoder(properties),
                properties.oidc());
    }

    private static MockHttpServletRequest request(String apiKey) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/incidents");
        if (apiKey != null) {
            request.addHeader(ControlPlaneAuthFilter.HEADER, apiKey);
        }
        return request;
    }
}
