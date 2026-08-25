package com.jun.aicodehelper.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 鉴权失败响应需手动补 CORS 头（鉴权发生在 Spring MVC CORS 处理器之前，否则浏览器只看到 CORS 错）。
 * 与 CorsConfig.allowedOriginPatterns("*") + allowCredentials(true) 对齐：必须 echo Origin，不能用 *。
 */
public final class CorsHeaders {

    private CorsHeaders() {}

    public static void apply(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Max-Age", "3600");
        String requestHeaders = request.getHeader("Access-Control-Request-Headers");
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            response.setHeader("Access-Control-Allow-Headers", requestHeaders);
        }
    }
}