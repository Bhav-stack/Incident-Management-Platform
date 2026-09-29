package io.aegis.incident.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.aegis.incident.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * The control plane's built-in token issuer.
 *
 * <p>A hosted deployment points {@code aegis.security.oidc.issuer-uri} at a real
 * OIDC provider and this class stays switched off. It exists so that the same
 * approval path can be exercised locally and in the demo without standing up
 * an identity provider: an operator presents their credential once and gets
 * back a short-lived, subject-bound token to sign with.
 *
 * <p>The token is deliberately small and boring: {@code iss}, {@code aud},
 * {@code sub}, {@code exp}, {@code jti}, and the attribution claim. No roles,
 * no refresh, no session state — it authorises exactly one thing (acting as
 * that subject on this control plane) for a few minutes.
 *
 * <p>HS256 is used because the issuer and the verifier are the same process.
 * The minimum key length is enforced rather than assumed: a short secret would
 * be brute-forceable offline, and a signature nobody can trust is worse than
 * no signature at all.
 */
public class TokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(TokenIssuer.class);

    /** HS256 needs at least 256 bits of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecurityProperties.Oidc oidc;
    private final String issuer;
    private final Duration ttl;
    private final MACSigner signer;

    public TokenIssuer(SecurityProperties properties) {
        this.oidc = properties.oidc();
        this.ttl = Duration.ofSeconds(oidc.tokenTtlSeconds());
        if (!oidc.selfIssued()) {
            this.issuer = null;
            this.signer = null;
            return;
        }
        this.issuer = oidc.requireSelfIssuedIssuerUrl();
        byte[] secret = oidc.selfIssuedSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "aegis.security.oidc.self-issued-secret must be at least "
                            + MIN_SECRET_BYTES + " bytes for HS256 but is " + secret.length
                            + ". Generate one with: openssl rand -base64 48");
        }
        try {
            this.signer = new MACSigner(secret);
        } catch (KeyLengthException e) {
            // Unreachable after the length check above; the library insists on
            // declaring it, so it is translated rather than swallowed.
            throw new IllegalStateException("invalid signing key", e);
        }
        log.info("Built-in token issuer enabled: iss={} aud={} ttl={}s",
                issuer, oidc.audience(), oidc.tokenTtlSeconds());
    }

    /** False when tokens come from an external provider, or from nowhere. */
    public boolean enabled() {
        return signer != null;
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * Mints a token that attests to a verified identity.
     *
     * @param identity identity already proven from the credential the caller
     *                 presented; this method vouches for it, it does not
     *                 establish it
     */
    public IssuedToken issue(OperatorRegistry.Identity identity) {
        if (signer == null) {
            throw new IllegalStateException("the built-in token issuer is not configured");
        }
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(oidc.audience())
                .subject(identity.name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                .claim(oidc.attributableClaim(), identity.attributable())
                .claim("method", identity.method())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return new IssuedToken(jwt.serialize(), identity.name(), identity.attributable(),
                    ttl.toSeconds(), issuer);
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign an operator token", e);
        }
    }

    /** What the caller gets back: the token, and what it says they are. */
    public record IssuedToken(String token, String subject, boolean attributable,
                              long expiresInSeconds, String issuer) {
    }
}
