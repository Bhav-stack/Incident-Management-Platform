package io.aegis.incident.config;

import io.aegis.incident.security.ControlPlaneAuthFilter;
import io.aegis.incident.security.JwtOperatorAuthenticator;
import io.aegis.incident.security.TokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT verification for the control plane.
 *
 * <p>Two configurations, one contract. With
 * {@code aegis.security.oidc.issuer-uri} set, tokens are verified against the
 * provider's published keys, discovered over OIDC
 * ({@code JwtDecoders.fromIssuerLocation}) and refreshed when the provider
 * rotates them. With {@code oidc.self-issued-secret} set instead, the service
 * verifies the tokens it issues itself (see {@link TokenIssuer}). Both apply
 * the same validators, so a token is judged the same way whatever minted it:
 * signature, {@code exp}, {@code iss} and {@code aud}.
 *
 * <p>Identity resolution deliberately stays in {@link ControlPlaneAuthFilter}
 * rather than a Spring Security filter chain: this service has two credential
 * types (machine API key, operator token) plus ticket-authenticated
 * WebSockets, and one explicit filter is easier to audit than a chain plus
 * custom converters for each. Spring Security supplies exactly the JOSE
 * verification core.
 *
 * <p>With neither issuer configured, no token is accepted. That is the honest
 * default: a signature nobody can check is not a credential.
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** Registered audience claim name. */
    private static final String AUD = "aud";

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties) {
        SecurityProperties.Oidc oidc = properties.oidc();

        if (oidc.externalIssuer()) {
            log.info("Validating control-plane tokens against OIDC issuer {} (audience {})",
                    oidc.issuerUri(), oidc.audience());
            NimbusJwtDecoder decoder = (NimbusJwtDecoder)
                    JwtDecoders.fromIssuerLocation(oidc.issuerUri());
            // Discovery supplies the timestamp and issuer validators; the
            // audience names this service and must be enforced here, or a
            // token minted for another client of the same provider would be
            // accepted as an approver.
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                    JwtValidators.createDefaultWithIssuer(oidc.issuerUri()),
                    audienceValidator(oidc.audience())));
            return decoder;
        }

        if (oidc.selfIssued()) {
            SecretKeySpec key = new SecretKeySpec(
                    oidc.selfIssuedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                    new JwtTimestampValidator(),
                    new JwtIssuerValidator(oidc.requireSelfIssuedIssuerUrl()),
                    audienceValidator(oidc.audience())));
            return decoder;
        }

        log.warn("Neither aegis.security.oidc.issuer-uri nor oidc.self-issued-secret is set: "
                + "bearer tokens are NOT accepted. Approvals must present an operator key.");
        return token -> {
            throw new JwtException("no JWT verification is configured for this service");
        };
    }

    @Bean
    public JwtOperatorAuthenticator jwtOperatorAuthenticator(JwtDecoder decoder,
                                                             SecurityProperties properties) {
        return new JwtOperatorAuthenticator(decoder, properties.oidc());
    }

    @Bean
    public TokenIssuer tokenIssuer(SecurityProperties properties) {
        return new TokenIssuer(properties);
    }

    /** A token is only useful to us if it was issued to us. */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return new JwtClaimValidator<List<String>>(AUD,
                claim -> claim != null && claim.contains(audience));
    }
}
