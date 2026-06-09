package com.competitor.agent.interceptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.competitor.agent.annotation.RateLimit;
import com.competitor.agent.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 限流拦截器 - 基于Caffeine滑动窗口
 * 每个用户+方法组合维护一个1分钟窗口内的请求计数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    /** key: "userId:method", value: 请求计数器 */
    private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        Long userId = (Long) request.getAttribute("currentUserId");
        if (userId == null) {
            return true; // 未登录用户交给JwtInterceptor处理
        }

        String key = userId + ":" + handlerMethod.getMethod().getName();
        AtomicInteger counter = rateLimitCache.get(key, k -> new AtomicInteger(0));
        int currentCount = counter.incrementAndGet();

        if (currentCount > rateLimit.permits()) {
            log.warn("[限流] userId={} method={} count={}/{} 请求被拒绝",
                    userId, handlerMethod.getMethod().getName(), currentCount, rateLimit.permits());
            writeResponse(response, Result.error(429, rateLimit.message()));
            return false;
        }

        log.debug("[限流检查] userId={} method={} count={}/{}", userId, handlerMethod.getMethod().getName(), currentCount, rateLimit.permits());
        return true;
    }

    private void writeResponse(HttpServletResponse response, Result<?> result) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
