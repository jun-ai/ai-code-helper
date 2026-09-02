package com.jun.aicodehelper.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Admin 鉴权：覆盖 /api/rag/** 和 /api/admin/**，
 * 校验请求头 X-Admin-Key；未配置则拒绝所有（fail-closed）。
 *
 * 顺序：HIGHEST_PRECEDENCE + 20，置于 TraceIdFilter 之后、ApiAuthFilter 之前，
 * 保证鉴权失败日志能带上 traceId。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    @Value("${api.security.admin-key:}")
    private String validAdminKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (!isAdminPath(ctx, uri)) {
            chain.doFilter(request, response);
            return;
        }
        // CORS 预检无条件放行给 Spring MVC 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String adminKey = request.getHeader(ADMIN_KEY_HEADER);
        if (validAdminKey.isEmpty()) {
            log.warn("Admin 路径 {} 被拒：admin-key 未配置（fail-closed）", uri);
            sendError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Admin Key 未配置");
            return;
        }
        if (adminKey == null
                || !MessageDigest.isEqual(
                        validAdminKey.getBytes(StandardCharsets.UTF_8),
                        adminKey.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Admin 路径 {} 鉴权失败（key 不匹配）", uri);
            sendError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的 Admin Key");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 路径前缀匹配：/api/rag、/api/admin、/api/actuator 都归 AdminAuthFilter 管 */
    private boolean isAdminPath(String ctx, String uri) {
        if (uri == null || ctx == null) return false;
        return matchesPrefix(uri, ctx + "/rag")
                || matchesPrefix(uri, ctx + "/admin")
                || matchesPrefix(uri, ctx + "/actuator");
    }

    /** 段级精确前缀：避免 /api/ragxxx 被误判 */
    private boolean matchesPrefix(String uri, String prefix) {
        if (!uri.startsWith(prefix)) return false;
        if (uri.length() == prefix.length()) return true;
        char c = uri.charAt(prefix.length());
        return c == '/' || c == '\0';
    }

    private void sendError(HttpServletRequest request, HttpServletResponse response, int status, String message) throws IOException {
        CorsHeaders.apply(request, response);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}