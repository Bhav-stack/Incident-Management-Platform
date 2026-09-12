package io.aegis.simulator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@code io.aegis.incident.security.ApiKeyFilterTest}. The filter is
 * duplicated between the two HTTP services, so each copy carries its own
 * tests: fault injection and action execution on the simulator are the most
 * powerful endpoints in the platform.
 */
class ApiKeyFilterTest {

    private static final String KEY = "s3cret-key";

    @Test
    void requestWithoutTheHeaderIsRejectedBeforeTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyFilter(KEY).doFilter(request(null), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "the request must not reach the controller");
        assertTrue(response.getHeader("WWW-Authenticate").contains("ApiKey"));
    }

    @Test
    void requestWithTheWrongKeyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyFilter(KEY).doFilter(request("wrong"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void requestWithTheKeyReachesTheController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyFilter(KEY).doFilter(request(KEY), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void anEmptyKeyDisablesTheFilterForLocalDevelopment() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        new ApiKeyFilter("  ").doFilter(request(null), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    private static MockHttpServletRequest request(String apiKey) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/actions/execute");
        if (apiKey != null) {
            request.addHeader(ApiKeyFilter.HEADER, apiKey);
        }
        return request;
    }
}
