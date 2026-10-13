package io.aegis.incident.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a read-only call a bounded number of times.
 *
 * <p>Three deliberate limits, because an unbounded retry is how a slow
 * dependency turns into an outage:
 *
 * <ul>
 *   <li>it is capped. The default is two attempts beyond the first, so a
 *       dependency that is genuinely down costs a few hundred milliseconds and
 *       then reports a failure the caller can act on;</li>
 *   <li>it is jittered. A fixed backoff makes every caller retry in step, so a
 *       recovering dependency is hit by the whole fleet at the same instant.
 *       The delay is spread across its window instead;</li>
 *   <li>it never retries a definitive answer. A 4xx is the server saying the
 *       request was wrong, and repeating it produces the same rejection while
 *       adding load; only transport failures and 5xx are retried.</li>
 * </ul>
 *
 * <p>Only for reads. A write is deliberately excluded: {@code
 * SimulatorClient.execute} is not wrapped in this, because a request that timed
 * out on the wire may still have been applied, and a blind retry could restart
 * a service twice. That case needs an idempotency key and a reconciliation
 * read, not a retry loop — see the roadmap's outbox item.
 */
public final class BoundedRetry {

    private static final Logger log = LoggerFactory.getLogger(BoundedRetry.class);

    /** Total attempts, including the first. */
    private static final int MAX_ATTEMPTS = 3;

    private static final Duration BASE_DELAY = Duration.ofMillis(120);

    private BoundedRetry() {
    }

    /**
     * Runs {@code call}, retrying transport failures and 5xx responses.
     *
     * @param what description used in the log line, e.g. "simulator status for
     *             checkout-service"
     */
    public static <T> T read(String what, Supplier<T> call) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                // A definitive answer from the peer. Retrying cannot change it.
                throw e;
            } catch (RestClientException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                Duration delay = jitteredBackoff(attempt);
                log.warn("{} failed (attempt {}/{}): {} — retrying in {}ms",
                        what, attempt, MAX_ATTEMPTS, e.getMessage(), delay.toMillis());
                sleep(delay);
            }
        }
        log.error("{} failed after {} attempts", what, MAX_ATTEMPTS, last);
        throw last;
    }

    /** Exponential base with full jitter, so callers do not retry in lockstep. */
    private static Duration jitteredBackoff(int attempt) {
        long window = BASE_DELAY.toMillis() * (1L << (attempt - 1));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, window + 1));
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            // Restore the flag and stop retrying: something is shutting down.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
