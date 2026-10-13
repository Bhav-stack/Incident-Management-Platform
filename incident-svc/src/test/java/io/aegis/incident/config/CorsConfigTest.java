package io.aegis.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression that matters here is order, not header values.
 *
 * <p>Once the dashboard is served from its own origin, the browser sends an
 * unauthenticated preflight for every request that carries a credential. If
 * that preflight reaches {@code ControlPlaneAuthFilter} it comes back 401 and
 * the real request is never sent — sign-in, approve and reject all fail with no
 * useful error. So the assertions below are: the preflight is answered by the
 * CORS filter alone (the chain is never entered), and only configured origins
 * get an answer.
 *
 * <p>No containers and no Spring context: this is a filter and a request.
 */
class CorsConfigTest {

    private static final String DASHBOARD = "https://aegis-demo.vercel.app";
    private static final String APPROVE = "/api/proposals/1/approve";

    private CorsFilter filterFor(String... origins) {
        return new CorsConfig().dashboardCorsFilter(new CorsProperties(List.of(origins))).getFilter();
    }

    @Test
    void preflightForAnAuthenticatedApproveIsAnsweredWithoutReachingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", APPROVE);
        request.addHeader("Origin", DASHBOARD);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainEntered = new AtomicBoolean();

        filterFor(DASHBOARD).doFilter(request, response, (req, res) -> chainEntered.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.getHeader("Access-Control-Allow-Headers").toLowerCase())
                .contains("authorization");
        // The decisive assertion: the auth filter never sees this request, so it
        // cannot answer it with 401.
        assertThat(chainEntered).isFalse();
    }

    @Test
    void preflightForOperatorSignInAllowsTheOperatorHeader() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/api/auth/session");
        request.addHeader("Origin", DASHBOARD);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "x-operator-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterFor(DASHBOARD).doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers").toLowerCase())
                .contains("x-operator-key");
    }

    @Test
    void anActualRequestFromAnAllowedOriginGetsTheHeaderAndStillRunsTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/incidents");
        request.addHeader("Origin", DASHBOARD);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainEntered = new AtomicBoolean();

        filterFor(DASHBOARD).doFilter(request, response, (req, res) -> chainEntered.set(true));

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(DASHBOARD);
        // A non-preflight request must reach the controller (and the auth filter)
        // as it always did.
        assertThat(chainEntered).isTrue();
    }

    @Test
    void anOriginThatIsNotConfiguredGetsNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", APPROVE);
        request.addHeader("Origin", "https://evil.example");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterFor(DASHBOARD).doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getStatus()).isNotEqualTo(200);
    }

    @Test
    void withNoOriginsConfiguredTheDeploymentIsSameOriginOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", APPROVE);
        request.addHeader("Origin", DASHBOARD);
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterFor().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void aPastedTrailingSlashStillMatchesTheBrowserOrigin() {
        // Browsers never send a trailing slash, so a configuration value with
        // one would silently never match.
        assertThat(new CorsProperties(List.of(DASHBOARD + "/", "  ")).allowedOrigins())
                .containsExactly(DASHBOARD);
        assertThat(new CorsProperties(List.of(DASHBOARD + "/")).any()).isTrue();
        assertThat(new CorsProperties(List.of()).any()).isFalse();
    }
}
