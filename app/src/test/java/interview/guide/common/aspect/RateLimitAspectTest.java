package interview.guide.common.aspect;

import interview.guide.common.annotation.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;

public class RateLimitAspectTest {
    @Test
    @DisplayName("当属性和标头同时存在时应使用请求属性用户 ID")
    void shouldUseRequestAttributeUserId_whenAttributeAndHeaderBothPresent() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RateLimitAspect aspect = new RateLimitAspect(redissonClient);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.setAttribute("userId", 42);
        request.addHeader("X-User-Id", "99");

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            Method method = RateLimitAspect.class.getDeclaredMethod(
                "generateKeys",
                String.class,
                String.class,
                RateLimit.Dimension[].class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) method.invoke(
                aspect,
                "ResumeController",
                "uploadAndAnalyze",
                new RateLimit.Dimension[] {
                    RateLimit.Dimension.GLOBAL,
                    RateLimit.Dimension.IP,
                    RateLimit.Dimension.USER
                }
            );

            assertIterableEquals(List.of(
                "ratelimit:{ResumeController:uploadAndAnalyze}:global",
                "ratelimit:{ResumeController:uploadAndAnalyze}:ip:10.0.0.8",
                "ratelimit:{ResumeController:uploadAndAnalyze}:user:42"
            ), keys);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("当属性不存在时应使用标头请求用户 ID")
    void shouldUseHeaderUserId_whenRequestAttributeMissing() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RateLimitAspect aspect = new RateLimitAspect(redissonClient);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-User-Id", "99");

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            Method method = RateLimitAspect.class.getDeclaredMethod(
                "generateKeys",
                String.class,
                String.class,
                RateLimit.Dimension[].class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) method.invoke(
                aspect,
                "ResumeController",
                "uploadAndAnalyze",
                new RateLimit.Dimension[] {
                    RateLimit.Dimension.GLOBAL,
                    RateLimit.Dimension.IP,
                    RateLimit.Dimension.USER
                }
            );

            assertIterableEquals(List.of(
                "ratelimit:{ResumeController:uploadAndAnalyze}:global",
                "ratelimit:{ResumeController:uploadAndAnalyze}:ip:10.0.0.8",
                "ratelimit:{ResumeController:uploadAndAnalyze}:user:99"
            ), keys);

        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("当属性和表头都不存在时应返回 Anonymous")
    void shouldUseAnonymousUserId_whenRequestHasNoUserIdentifier() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RateLimitAspect aspect = new RateLimitAspect(redissonClient);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            Method method = RateLimitAspect.class.getDeclaredMethod(
                "generateKeys",
                String.class,
                String.class,
                RateLimit.Dimension[].class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) method.invoke(
                aspect,
                "ResumeController",
                "uploadAndAnalyze",
                new RateLimit.Dimension[] {
                    RateLimit.Dimension.GLOBAL,
                    RateLimit.Dimension.IP,
                    RateLimit.Dimension.USER
                }
            );

            assertIterableEquals(List.of(
                "ratelimit:{ResumeController:uploadAndAnalyze}:global",
                "ratelimit:{ResumeController:uploadAndAnalyze}:ip:10.0.0.8",
                "ratelimit:{ResumeController:uploadAndAnalyze}:user:anonymous"
            ), keys);

        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("当请求上下文不存在时应使用 Unknown IP 和 Anonymous 的 User ID")
    void shouldUseUnknownIpAndAnonymousUserId_whenNoRequestContext() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RateLimitAspect aspect = new RateLimitAspect(redissonClient);

        RequestContextHolder.resetRequestAttributes();

        try {
            Method method = RateLimitAspect.class.getDeclaredMethod(
                "generateKeys",
                String.class,
                String.class,
                RateLimit.Dimension[].class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) method.invoke(
                aspect,
                "ResumeController",
                "uploadAndAnalyze",
                new RateLimit.Dimension[] {
                    RateLimit.Dimension.GLOBAL,
                    RateLimit.Dimension.IP,
                    RateLimit.Dimension.USER
                }
            );

            assertIterableEquals(List.of(
                "ratelimit:{ResumeController:uploadAndAnalyze}:global",
                "ratelimit:{ResumeController:uploadAndAnalyze}:ip:unknown",
                "ratelimit:{ResumeController:uploadAndAnalyze}:user:anonymous"
            ), keys);

        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

}
