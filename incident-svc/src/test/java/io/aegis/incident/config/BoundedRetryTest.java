package io.aegis.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour that matters is what it refuses to do.
 *
 * <p>An unbounded retry against a dead dependency is how a slow service becomes
 * an outage, and retrying a 4xx turns a clear rejection into three identical
 * rejections. Both are asserted here, along with the obvious happy path.
 */
class BoundedRetryTest {

    @Test
    void returnsTheFirstSuccessfulResultWithoutRetrying() {
        AtomicInteger calls = new AtomicInteger();

        String result = BoundedRetry.read("test read", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void recoversWhenATransportFailureIsFollowedBySuccess() {
        AtomicInteger calls = new AtomicInteger();

        String result = BoundedRetry.read("test read", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResourceAccessException("connection reset");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    void givesUpAfterABoundedNumberOfAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> BoundedRetry.read("test read", () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("still down");
        })).isInstanceOf(ResourceAccessException.class);

        // Three attempts total, including the first: a dead dependency costs
        // bounded time rather than an unbounded thread.
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryADefinitiveClientError() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> BoundedRetry.read("test read", () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                    null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(calls).hasValue(1);
    }
}
