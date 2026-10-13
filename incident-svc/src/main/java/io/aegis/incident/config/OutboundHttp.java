package io.aegis.incident.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds outbound {@link RestClient}s that cannot hang forever.
 *
 * <p>This exists because of what "no timeout" actually means here. The JDK HTTP
 * client's defaults are both infinite: a connection attempt waits as long as
 * the OS will let it, and a read waits for the peer to say something. Callers
 * of these clients hold real resources while they wait — {@code
 * SimulatorClient} is called from the action executor while a transaction and a
 * per-service lock are open, and from the verifier inside its polling window.
 * One unresponsive peer therefore does not slow the platform down, it wedges a
 * thread that other work is queued behind, and on a two-core host that is the
 * difference between a slow demo and a frozen one.
 *
 * <p>A timeout is the prerequisite for every other resilience measure: without
 * one, retries never trigger and a circuit breaker never opens, because both
 * need an attempt to end first.
 *
 * <p>Deliberately not a bean and not configurable by type: it is a factory for
 * two specific clients, each with its own budget (a local simulator should
 * answer in milliseconds; a language model legitimately takes tens of seconds),
 * and the values are passed explicitly rather than discovered.
 */
public final class OutboundHttp {

    private OutboundHttp() {
    }

    /**
     * A client bound to {@code baseUrl} that fails fast on connect and gives up
     * on a silent peer after {@code readTimeout}.
     *
     * @param baseUrl        scheme, host and any base path
     * @param connectTimeout how long to wait for the TCP handshake
     * @param readTimeout    how long to wait for a response once connected
     */
    public static RestClient.Builder builder(String baseUrl, Duration connectTimeout,
                                             Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // A redirect on a read-only call is harmless; on a write it is
                // not, so nothing here follows one. NORMAL is the safe middle
                // and every endpoint used here answers directly.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
    }
}
