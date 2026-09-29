package io.aegis.incident.security;

import io.aegis.incident.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a presented credential into a verified identity.
 *
 * <p>The old approval API took the approver's name from the request body,
 * which meant a request could claim to be anyone. Here the identity is derived
 * from the credential and nothing else: the request body has no say.
 *
 * <p>Two kinds of identity come out of this, and the difference is enforced
 * downstream. The shared {@code api-key} always works, because it is what the
 * dashboard's server-side proxy holds, but it resolves to a single
 * non-attributable identity: it authenticates a client, not a person, so it
 * does not count towards the two-person rule. Per-operator keys resolve to
 * named, attributable identities, and those are what release high-risk
 * actions.
 *
 * <p>Comparison is constant-time per candidate so a key cannot be recovered
 * byte by byte from response timing. Keys are held as bytes and never logged.
 */
public class OperatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(OperatorRegistry.class);

    /**
     * A verified caller: its name, whether that name is a person, and how the
     * claim was proven. The last three fields are the attestation that gets
     * recorded against an approval, so an audit can tell an API key from a
     * signed token and, for a token, which document was presented.
     *
     * @param name         the identity, never taken from a request body
     * @param attributable whether this names a person (counts towards a
     *                     two-person rule)
     * @param method       how it was proven: {@code api-key}, {@code jwt} or
     *                     {@code none}
     * @param issuer       token issuer, when the credential was a token
     * @param tokenId      token id ({@code jti}), when the credential carried
     *                     one. This is what binds an approval to one signed
     *                     document rather than to a reusable secret.
     */
    public record Identity(String name, boolean attributable, String method,
                           String issuer, String tokenId) {

        /** Credential kind for the two non-token paths and for an unattributed call. */
        public static final String METHOD_API_KEY = "api-key";
        public static final String METHOD_JWT = "jwt";
        public static final String METHOD_NONE = "none";

        public static final Identity ANONYMOUS =
                new Identity("anonymous", false, METHOD_NONE, null, null);

        public Identity(String name, boolean attributable) {
            this(name, attributable, METHOD_API_KEY, null, null);
        }

        /**
         * The claims stored alongside an approval. Keys with no value are
         * omitted rather than stored as null, so the audit record reads as a
         * set of facts that are actually known.
         */
        public Map<String, String> attestation() {
            Map<String, String> claims = new LinkedHashMap<>();
            claims.put("method", method);
            if (issuer != null && !issuer.isBlank()) {
                claims.put("issuer", issuer);
            }
            if (tokenId != null && !tokenId.isBlank()) {
                claims.put("tokenId", tokenId);
            }
            return Collections.unmodifiableMap(claims);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record Credential(Identity identity, byte[] key) {
    }

    private final List<Credential> credentials;
    private final int attributableCount;

    public OperatorRegistry(SecurityProperties properties) {
        List<Credential> configured = new ArrayList<>();

        // The shared key goes first, so a credential reused across two
        // entries resolves deterministically to the client identity rather
        // than to whichever entry happened to be scanned first.
        String shared = properties.apiKey() == null ? "" : properties.apiKey().trim();
        if (!shared.isEmpty()) {
            configured.add(new Credential(
                    new Identity(properties.defaultOperator(), false),
                    shared.getBytes(StandardCharsets.UTF_8)));
        }

        Set<String> named = new HashSet<>();
        for (SecurityProperties.Operator operator : properties.operators()) {
            if (operator == null || isBlank(operator.name()) || isBlank(operator.key())) {
                continue;
            }
            String name = operator.name().trim();
            if (!named.add(name)) {
                log.warn("Duplicate operator name '{}' in aegis.security.operators: keeping the "
                        + "first credential and ignoring the rest", name);
                continue;
            }
            configured.add(new Credential(new Identity(name, true),
                    operator.key().trim().getBytes(StandardCharsets.UTF_8)));
        }

        this.credentials = List.copyOf(configured);
        this.attributableCount = (int) credentials.stream()
                .filter(c -> c.identity().attributable())
                .count();

        if (credentials.isEmpty()) {
            return;  // ControlPlaneAuthFilter logs the unauthenticated warning for this case.
        }
        log.info("Control-plane authentication enabled: {} identit{}, {} attributable {}",
                credentials.size(), credentials.size() == 1 ? "y" : "ies",
                attributableCount, attributableCount == 1 ? "operator" : "operators");
        if (attributableCount == 0) {
            log.warn("No aegis.security.operators configured: every request is attributed to the "
                    + "shared identity, which is not a person. Actions in the two-person tiers "
                    + "cannot be approved in this configuration, by design.");
        }
    }

    /** Single-credential registry, for tests and key-only deployments. */
    public static OperatorRegistry ofSharedKey(String sharedKey) {
        return new OperatorRegistry(
                new SecurityProperties(sharedKey, "operator", null, true, 60, null));
    }

    /**
     * @return the identity the credential belongs to, or empty when it matches
     *         nothing configured
     */
    public Optional<Identity> authenticate(String presentedKey) {
        return match(presentedKey, false);
    }

    /**
     * Named operators only. Used for the {@code X-Operator-Key} header: a
     * credential that is supposed to name a person must not silently resolve
     * to the shared client identity, or a two-person rule could be satisfied
     * by one person holding a key that happens to be shared.
     */
    public Optional<Identity> authenticateNamed(String presentedKey) {
        return match(presentedKey, true);
    }

    private Optional<Identity> match(String presentedKey, boolean attributableOnly) {
        if (presentedKey == null || presentedKey.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidate = presentedKey.getBytes(StandardCharsets.UTF_8);
        for (Credential credential : credentials) {
            if (MessageDigest.isEqual(credential.key(), candidate)) {
                if (attributableOnly && !credential.identity().attributable()) {
                    return Optional.empty();
                }
                return Optional.of(credential.identity());
            }
        }
        return Optional.empty();
    }

    /** False when no credential is configured at all: local development. */
    public boolean enabled() {
        return !credentials.isEmpty();
    }

    /**
     * Identities that can authenticate, including the shared client identity.
     * Not the same as the number available to a two-person rule.
     */
    public int identityCount() {
        return credentials.size();
    }

    /** Named operators that can sign off on a high-risk action. */
    public int attributableIdentityCount() {
        return attributableCount;
    }

    /** True when at least one identity names a person. */
    public boolean identitiesAreAttributable() {
        return attributableCount > 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
