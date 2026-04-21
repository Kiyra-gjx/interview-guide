package interview.guide.common.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ActuatorAccessFilterTest {

    @Test
    @DisplayName("should block remote actuator requests by default")
    void shouldBlockRemoteActuatorRequestsByDefault() throws Exception {
        ActuatorAccessFilter filter = new ActuatorAccessFilter("/actuator", false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setRemoteAddr("8.8.8.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(MockHttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("should allow local actuator requests")
    void shouldAllowLocalActuatorRequests() throws Exception {
        ActuatorAccessFilter filter = new ActuatorAccessFilter("/actuator", false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("should allow public actuator access when explicitly enabled")
    void shouldAllowPublicActuatorAccessWhenExplicitlyEnabled() throws Exception {
        ActuatorAccessFilter filter = new ActuatorAccessFilter("/actuator", true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setRemoteAddr("8.8.8.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
