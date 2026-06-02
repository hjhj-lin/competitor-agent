package com.competitor.agent.interceptor;

import com.competitor.agent.common.Result;
import com.competitor.agent.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = null;

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token == null) {
            token = request.getParameter("token");
        }

        if (token == null) {
            log.warn("[鉴权失败] uri={} reason=缺少token", request.getRequestURI());
            writeResponse(response, Result.error(401, "未登录，请先登录"));
            return false;
        }

        if (!jwtUtil.isTokenValid(token)) {
            log.warn("[鉴权失败] uri={} reason=token无效", request.getRequestURI());
            writeResponse(response, Result.error(401, "token无效或已过期"));
            return false;
        }

        String username = jwtUtil.getUsernameFromToken(token);
        Long userId = jwtUtil.getUserIdFromToken(token);
        request.setAttribute("currentUser", username);
        request.setAttribute("currentUserId", userId);
        log.info("[鉴权通过] uri={} user={} userId={}", request.getRequestURI(), username, userId);
        return true;
    }

    private void writeResponse(HttpServletResponse response, Result<?> result) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
