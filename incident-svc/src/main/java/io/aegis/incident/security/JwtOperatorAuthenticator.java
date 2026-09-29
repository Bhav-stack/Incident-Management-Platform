package io.aegis.incident.security;

import io.aegis.incident.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Optional;

/**
 * Turns a bearer token into a verified identity.
 *
 * <p>The subject claim <em>is</em> the approver: the token is signed by an
 * issuer this service trusts, so unlike an API key the identity cannot be
 * borrowed by anyone who merely observes a request. Signature, issuer,
 * audience and expiry are all checked by the {@link JwtDecoder} before any of
 * this runs; a token that fails any of them never reaches the caller.
 *
 * <p>Attribution: a subject is a person unless the token explicitly says
 * otherwise through the configured claim. That default is deliberate. With an
 * external issuer the provider decides who gets a token, so a subject is a
 * named human; the built-in issuer sets the claim to {@code false} when it
 * mints a token for the shared client credential, which keeps a machine
 * credential from counting as one half of a two-person approval.
 *
 * <p>The {@code jti} is carried through into the identity so an approval can
 * record which signed document authorised it. A token is not a session that
 * can be replayed indefinitely: it is short-lived and individually
 * identifiable, and the audit trail says which one was used.
 */
public class JwtOperatorAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(JwtOperatorAuthenticator.class);

    private final JwtDecoder decoder;
    private final String attributableClaim;
    private final boolean configured;

    public JwtOperatorAuthenticator(JwtDecoder decoder, SecurityProperties.Oidc oidc) {
        this.decoder = decoder;
        this.attributableClaim = oidc.attributableClaim();
        this.configured = oidc.externalIssuer() || oidc.selfIssued();
    }

    /**
     * True when tokens can actually be verified. A decoder that refuses
     * everything still exists as a bean, and a bean existing is not the same
     * as a deployment having said where its tokens come from.
     */
    public boolean configured() {
        return configured;
    }

    /**
     * @param bearerToken the token without the {@code Bearer } prefix
     * @return the identity the token attests to, or empty when the token is
     *         not valid for this service
     */
    public Optional<OperatorRegistry.Identity> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(bearerToken);
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                log.warn("Rejected a valid token with no subject claim: it cannot name an approver");
                return Optional.empty();
            }
            // Missing claim means "a subject is a person"; only an explicit
            // false downgrades the identity.
            Boolean attributable = jwt.getClaim(attributableClaim);
            boolean person = attributable == null || attributable;
            String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
            return Optional.of(new OperatorRegistry.Identity(
                    subject.trim(), person,
                    OperatorRegistry.Identity.METHOD_JWT, issuer, jwt.getId()));
        } catch (JwtException | IllegalArgumentException e) {
            // Never echo the token: it is a credential, and the log line would
            // outlive it. The reason is enough to tell expiry from a forgery.
            log.debug("Rejected bearer token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
