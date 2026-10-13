package io.aegis.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Cross-origin clients, bound from {@code aegis.cors.allowed-origins}.
 *
 * <p>Only one kind of client is ever cross-origin: the dashboard, when it is
 * served from somewhere other than this service (Vercel in production, or a
 * Vite dev server against a remote API). Everything else reaches the control
 * plane through the reverse proxy and is same-origin, which is why the default
 * is empty and not a wildcard.
 *
 * <p>An empty list means same-origin only: the filter still runs, but no
 * cross-origin request is granted a header, so the browser refuses it. That is
 * the correct posture for a service whose entire surface is behind a shared
 * secret.
 *
 * @param allowedOrigins exact origins, scheme and host and optional port, e.g.
 *                       {@code https://aegis-demo.vercel.app}. Patterns are
 *                       accepted by the framework but should be avoided here:
 *                       this list is the only thing separating a browser from
 *                       the control plane.
 */
@ConfigurationProperties(prefix = "aegis.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        // A trailing slash is never part of a browser Origin
                        // header, and Spring matches the header exactly, so a
                        // pasted "https://host/" would silently never match.
                        .map(origin -> origin.endsWith("/")
                                ? origin.substring(0, origin.length() - 1)
                                : origin)
                        .toList();
    }

    public boolean any() {
        return !allowedOrigins.isEmpty();
    }
}
