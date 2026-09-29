package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Control-plane credentials, bound from {@code aegis.security.*}.
 *
 * <p>Three kinds of credential exist, and the difference matters for audit:
 *
 * <ul>
 *   <li>{@code api-key} is the shared secret the dashboard's server-side
 *       proxy holds. It authenticates the <em>client</em>, not a person, so
 *       every request it carries is attributed to {@code default-operator}.</li>
 *   <li>{@code operators} are per-person keys. A request carrying one (in
 *       {@code X-Operator-Key}) is attributed to that named operator, which is
 *       what makes an approval attributable and what the two-person rule
 *       counts.</li>
 *   <li>a signed JWT ({@code Authorization: Bearer}), whose subject is the
 *       approver. Tokens come from an external OIDC provider
 *       ({@code oidc.issuer-uri}) or, for local and demo runs, from the
 *       built-in issuer ({@code oidc.self-issued-secret}) which exchanges an
 *       operator credential for a short-lived, subject-bound token.</li>
 * </ul>
 *
 * @param apiKey             shared control-plane key (unset disables auth)
 * @param defaultOperator    identity given to the shared key
 * @param operators          per-person credentials
 * @param wsRequireTicket    whether the STOMP feed needs a fresh ticket
 * @param wsTicketTtlSeconds lifetime of a WebSocket ticket
 * @param oidc               JWT/OIDC validation settings; may be {@code null},
 *                           in which case the defaults in {@link Oidc} apply
 *
 * <p>There is deliberately only one constructor. A record bound with
 * {@code @ConfigurationProperties} is instantiated through its canonical
 * constructor, and a second one makes Spring report "no default constructor
 * found" at startup instead of binding anything at all.
 */
@ConfigurationProperties(prefix = "aegis.security")
public record SecurityProperties(
        String apiKey,
        String defaultOperator,
        List<Operator> operators,
        Boolean wsRequireTicket,
        Integer wsTicketTtlSeconds,
        Oidc oidc
) {

    public record Operator(String name, String key) {
    }

    /**
     * Identity of bearer tokens.
     *
     * <p>Exactly one of {@code issuerUri} and {@code selfIssuedSecret} is
     * expected. With {@code issuerUri} set the issuer is the authority and
     * tokens are verified against the provider's published keys (OIDC
     * discovery, RS256). With {@code selfIssuedSecret} set the service issues
     * its own short-lived HS256 tokens and the operator registry stays the
     * authority.
     *
     * @param issuerUri          OIDC issuer to validate against (production)
     * @param audience           required {@code aud} claim
     * @param selfIssuedIssuer   {@code iss} this service stamps on its tokens;
     *                           must be an absolute http(s) URL, because a JWT
     *                           {@code iss} is a URL and the decoder refuses
     *                           anything else
     * @param selfIssuedSecret   HMAC key for the built-in issuer (>= 32 bytes)
     * @param tokenTtlSeconds    lifetime of a token from the built-in issuer
     * @param attributableClaim  claim that says the subject is a person; when
     *                           absent a subject is treated as one
     */
    public record Oidc(
            String issuerUri,
            String audience,
            String selfIssuedIssuer,
            String selfIssuedSecret,
            Integer tokenTtlSeconds,
            String attributableClaim
    ) {

        public Oidc {
            issuerUri = blankToNull(issuerUri);
            audience = blankToNull(audience) == null ? "aegis-control-plane" : audience.trim();
            selfIssuedIssuer = blankToNull(selfIssuedIssuer) == null
                    ? "https://aegis.local/control-plane"
                    : selfIssuedIssuer.trim();
            selfIssuedSecret = blankToNull(selfIssuedSecret) == null
                    ? null
                    : selfIssuedSecret.trim();
            tokenTtlSeconds = tokenTtlSeconds == null || tokenTtlSeconds <= 0
                    ? 300
                    : tokenTtlSeconds;
            attributableClaim = blankToNull(attributableClaim) == null
                    ? "aegis_attributable"
                    : attributableClaim.trim();
        }

        /** True when an external OIDC provider is configured. */
        public boolean externalIssuer() {
            return issuerUri != null;
        }

        /** True when this service can mint its own operator tokens. */
        public boolean selfIssued() {
            return selfIssuedSecret != null;
        }

        /**
         * The self-issued issuer, checked to be usable as a JWT {@code iss}.
         *
         * <p>A JWT issuer is by specification a URL, and the decoder enforces
         * it: a bare name like {@code aegis-control-plane} fails to convert at
         * verification time with an error that says nothing about the cause.
         * Rejecting it here, at startup, turns that into a one-line fix.
         */
        public String requireSelfIssuedIssuerUrl() {
            URI uri;
            try {
                uri = new URI(selfIssuedIssuer);
            } catch (URISyntaxException e) {
                throw new IllegalStateException(selfIssuedIssuerError(), e);
            }
            boolean httpUrl = uri.isAbsolute() && uri.getHost() != null
                    && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
            if (!httpUrl) {
                throw new IllegalStateException(selfIssuedIssuerError());
            }
            return selfIssuedIssuer;
        }

        private String selfIssuedIssuerError() {
            return "aegis.security.oidc.self-issued-issuer must be an absolute http(s) URL "
                    + "because a JWT 'iss' claim is a URL, but it is '" + selfIssuedIssuer
                    + "'. For example: https://aegis.local/control-plane";
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public SecurityProperties {
        defaultOperator = defaultOperator == null || defaultOperator.isBlank()
                ? "operator"
                : defaultOperator.trim();
        operators = operators == null ? List.of() : List.copyOf(operators);
        wsRequireTicket = wsRequireTicket == null || wsRequireTicket;
        wsTicketTtlSeconds = wsTicketTtlSeconds == null || wsTicketTtlSeconds <= 0
                ? 60
                : wsTicketTtlSeconds;
        oidc = oidc == null
                ? new Oidc(null, null, null, null, null, null)
                : oidc;
    }

}
