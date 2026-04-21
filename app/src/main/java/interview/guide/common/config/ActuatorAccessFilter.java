package interview.guide.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Actuator 访问保护。
 * 默认仅允许本机访问，避免 metrics 在无额外安全层时被直接暴露。
 */
@Component
public class ActuatorAccessFilter extends OncePerRequestFilter {

    private final String basePath;
    private final boolean allowPublicAccess;

    public ActuatorAccessFilter(
        @Value("${management.endpoints.web.base-path:/actuator}") String basePath,
        @Value("${app.actuator.allow-public-access:false}") boolean allowPublicAccess
    ) {
        this.basePath = normalizeBasePath(basePath);
        this.allowPublicAccess = allowPublicAccess;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (allowPublicAccess || !isActuatorRequest(request) || isLocalRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean isActuatorRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return requestUri != null && (requestUri.equals(basePath) || requestUri.startsWith(basePath + "/"));
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr)
            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
            || "::1".equals(remoteAddr);
    }

    private String normalizeBasePath(String configuredBasePath) {
        if (configuredBasePath == null || configuredBasePath.isBlank()) {
            return "/actuator";
        }
        String normalized = configuredBasePath.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
