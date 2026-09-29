package io.aegis.incident.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.aegis.incident.config.JwtConfig;
import io.aegis.incident.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A token is the only credential whose identity the service can verify without
 * having issued it, so every way it can be wrong is worth pinning: wrong
 * signature, wrong issuer, wrong audience, expired, and the one case that
 * matters most for the two-person rule — a token that says its subject is a
 * client rather than a person.
 */
class JwtTokenTest {

    private static final String SECRET =
            "control-plane-test-secret-0123456789abcdef";   // 44 bytes, HS256-safe

    /** A JWT issuer is a URL by specification; the decoder refuses anything else. */
    private static final String ISSUER = "https://aegis.local/control-plane";

    @Test
    void anIssuedTokenRoundTripsToItsSubject() {
        SecurityProperties properties = properties(SECRET, ISSUER);
        TokenIssuer issuer = new TokenIssuer(properties);

        TokenIssuer.IssuedToken issued = issuer.issue(new OperatorRegistry.Identity("alice", true));

        assertEquals("alice", issued.subject());
        assertTrue(issued.attributable());
        assertEquals(300, issued.expiresInSeconds());

        OperatorRegistry.Identity identity = authenticator(properties)
                .authenticate(issued.token()).orElseThrow();

        assertEquals("alice", identity.name());
        assertTrue(identity.attributable());
        assertEquals(OperatorRegistry.Identity.METHOD_JWT, identity.method());
        assertEquals(ISSUER, identity.issuer());
        assertNotNull(identity.tokenId());
    }

    @Test
    void everyTokenGetsItsOwnIdSoAnApprovalCanNameTheDocument() {
        TokenIssuer issuer = new TokenIssuer(properties(SECRET, ISSUER));

        String first = issuer.issue(new OperatorRegistry.Identity("alice", true)).token();
        String second = issuer.issue(new OperatorRegistry.Identity("alice", true)).token();

        assertNotEquals(first, second);
    }

    @Test
    void aClientTokenIsExplicitlyNotAPerson() {
        SecurityProperties properties = properties(SECRET, ISSUER);

        TokenIssuer.IssuedToken issued = new TokenIssuer(properties)
                .issue(new OperatorRegistry.Identity("dashboard", false));

        assertFalse(issued.attributable());
        OperatorRegistry.Identity identity = authenticator(properties)
                .authenticate(issued.token()).orElseThrow();
        assertEquals("dashboard", identity.name());
        assertFalse(identity.attributable(),
                "a machine credential must not count as one half of a two-person approval");
    }

    @Test
    void theAttestationOmitsClaimsThatAreNotKnown() {
        OperatorRegistry.Identity key = new OperatorRegistry.Identity("alice", true);

        assertEquals(java.util.Map.of("method", "api-key"), key.attestation());

        OperatorRegistry.Identity token = new OperatorRegistry.Identity(
                "alice", true, OperatorRegistry.Identity.METHOD_JWT, "issuer", "jti-1");
        assertEquals(java.util.Map.of("method", "jwt", "issuer", "issuer", "tokenId", "jti-1"),
                token.attestation());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        SecurityProperties issuing = properties(SECRET, ISSUER);
        String token = new TokenIssuer(issuing)
                .issue(new OperatorRegistry.Identity("alice", true)).token();

        SecurityProperties verifying =properties("a-completely-different-secret-0123456789abcdef", ISSUER);

        assertTrue(authenticator(verifying).authenticate(token).isEmpty(),
                "a signature nobody can check must not authenticate anyone");
    }

    @Test
    void anExpiredTokenIsRejected() {
        SecurityProperties properties = properties(SECRET, ISSUER);
        String expired = mint(properties, builder -> builder
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(300))));

        assertTrue(authenticator(properties).authenticate(expired).isEmpty());
    }

    @Test
    void anIssuerThatIsNotAUrlIsRefusedAtStartup() {
        // A bare name is the obvious thing to write, and the decoder's failure
        // for it says nothing about the cause. Fail early, in words.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TokenIssuer(properties(SECRET, "aegis-control-plane")));

        assertTrue(e.getMessage().contains("absolute http(s) URL"), e.getMessage());
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        SecurityProperties properties = properties(SECRET, ISSUER);
        String foreign = mint(properties, builder -> builder.issuer("https://evil.example"));

        assertTrue(authenticator(properties).authenticate(foreign).isEmpty());
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() {
        SecurityProperties properties = properties(SECRET, ISSUER);
        String foreign = mint(properties, builder -> builder.audience("some-other-service"));

        assertTrue(authenticator(properties).authenticate(foreign).isEmpty(),
                "a token the provider minted for a different client is not an approver here");
    }

    @Test
    void aSubjectlessTokenIsRejected() {
        SecurityProperties properties = properties(SECRET, ISSUER);
        String anonymous = mint(properties, builder -> builder.subject(null));

        assertTrue(authenticator(properties).authenticate(anonymous).isEmpty());
    }

    @Test
    void aMissingOrGarbageTokenIsRejected() {
        JwtOperatorAuthenticator authenticator =
                authenticator(properties(SECRET, ISSUER));

        assertTrue(authenticator.authenticate(null).isEmpty());
        assertTrue(authenticator.authenticate("   ").isEmpty());
        assertTrue(authenticator.authenticate("not.a.jwt").isEmpty());
        // A token signed with "none" must never be accepted as unsigned-but-valid.
        assertTrue(authenticator.authenticate("eyJhbGciOiJub25lIn0.e30.").isEmpty());
    }

    @Test
    void nothingIsAcceptedWhenNoVerifierIsConfigured() {
        // No issuer-uri, no secret: the decoder refuses everything.
        SecurityProperties bare = new SecurityProperties(
                "shared", "dashboard", List.of(), true, 60, null);
        JwtOperatorAuthenticator authenticator = new JwtOperatorAuthenticator(
                new JwtConfig().jwtDecoder(bare), bare.oidc());

        assertTrue(authenticator.authenticate("anything").isEmpty());
    }

    @Test
    void aSecretShorterThanTheAlgorithmNeedsIsRefusedAtStartup() {
        SecurityProperties weak = properties("too-short", ISSUER);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TokenIssuer(weak));

        assertTrue(e.getMessage().contains("at least 32 bytes"),
                "a brute-forceable signature is worse than no signature: " + e.getMessage());
    }

    @Test
    void theIssuerIsDisabledWithoutASecret() {
        TokenIssuer issuer = new TokenIssuer(new SecurityProperties(
                "shared", "dashboard", List.of(), true, 60, null));

        assertFalse(issuer.enabled());
        assertThrows(IllegalStateException.class,
                () -> issuer.issue(new OperatorRegistry.Identity("alice", true)));
    }

    @Test
    void theSecretIsTrimmedSoAPastedValueStillWorks() {
        SecurityProperties properties = properties("  " + SECRET + "\n", ISSUER);

        TokenIssuer.IssuedToken issued = new TokenIssuer(properties)
                .issue(new OperatorRegistry.Identity("alice", true));

        assertEquals("alice", authenticator(properties).authenticate(issued.token())
                .orElseThrow().name());
    }

    // --- helpers ----------------------------------------------------------

    private static SecurityProperties properties(String secret, String issuer) {
        return new SecurityProperties("shared", "dashboard", List.of(), true, 60,
                new SecurityProperties.Oidc(
                        null, ISSUER, issuer, secret, 300, "aegis_attributable"));
    }

    private static JwtOperatorAuthenticator authenticator(SecurityProperties properties) {
        return new JwtOperatorAuthenticator(new JwtConfig().jwtDecoder(properties),
                properties.oidc());
    }

    /** Signs a token with the configured secret but with claims of our choosing. */
    private static String mint(SecurityProperties properties,
                               java.util.function.Consumer<JWTClaimsSet.Builder> tweak) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(properties.oidc().selfIssuedIssuer())
                .audience(properties.oidc().audience())
                .subject("alice")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .jwtID("test-jti");
        tweak.accept(builder);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(properties.oidc().selfIssuedSecret()
                    .getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
