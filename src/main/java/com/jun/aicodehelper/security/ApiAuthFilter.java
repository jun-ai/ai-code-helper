package com.jun.aicodehelper.security;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 鉴权 + 限流：校验 X-API-Key（未配置密钥则全部拒绝），
 * 同一 Key 固定窗口限流，超限返回 429；老分钟桶定期清理避免内存漏。
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${api.security.enabled:true}")
    private boolean enabled;

    @Value("${api.security.api-key:}")
    private String validApiKey;

    @Value("${api.rate-limit.requests-per-minute:10}")
    private int requestsPerMinute;

    @Resource
    private SseConcurrencyGuard sseConcurrencyGuard;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled
                || !(request.getRequestURI().startsWith(request.getContextPath() + "/ai")
                || isUploadEndpoint(request))) {
            chain.doFilter(request, response);
            return;
        }
        // CORS 预检不携带业务头，必须无条件放行让 Spring MVC 处理 CORS 头
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (validApiKey.isEmpty() || !MessageDigest.isEqual(
                validApiKey.getBytes(StandardCharsets.UTF_8),
                String.valueOf(apiKey).getBytes(StandardCharsets.UTF_8))) {
            sendError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "无效的 API Key");
            return;
        }
        if (!tryAcquire(request.getRemoteAddr(), apiKey)) {
            response.setHeader("Retry-After", "60");
            sendError(request, response, 429, "请求过于频繁，请稍后再试");
            return;
        }
        // SSE 端点额外校验并发上限
        if (request.getRequestURI().endsWith("/ai/chat")
                && !sseConcurrencyGuard.tryAcquire(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "5");
            sendError(request, response, 429, "同时进行的对话过多，请关闭部分会话");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 双维度限流：IP+Key 复合 key，多用户共享 Key 不会互挤
     */
    private boolean tryAcquire(String ip, String apiKey) {
        String bucketKey = ip + "|" + apiKey;
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(bucketKey, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        return window.count().incrementAndGet() <= requestsPerMinute;
    }

    /**
     * 仅 POST /api/upload 受鉴权，/api/uploads/ 静态文件放行（避免 startsWith 把 /uploads 也吃掉）
     */
    private boolean isUploadEndpoint(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/upload";
        String uri = request.getRequestURI();
        if (!uri.startsWith(prefix)) {
            return false;
        }
        char next = uri.length() > prefix.length() ? uri.charAt(prefix.length()) : '\0';
        return next == '/' || next == '\0';
    }

    /**
     * 每分钟扫一次，移除上一分钟之前的桶，避免长期运行 map 持续膨胀
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void evictExpiredWindows() {
        long currentMinute = System.currentTimeMillis() / 60_000;
        int before = windows.size();
        windows.entrySet().removeIf(entry -> entry.getValue().minute() < currentMinute);
        int removed = before - windows.size();
        if (removed > 0) {
            // 命中清理才打 info，避免每分钟都刷日志
            // 已删 N 个过期限流桶
            // (略去日志刷屏)
        }
    }

    private void sendError(HttpServletRequest request, HttpServletResponse response, int status, String message) throws IOException {
        // 鉴权失败发生在 Spring MVC CORS 处理器之前，需手动补 CORS 头，否则浏览器只看到 CORS 错
        CorsHeaders.apply(request, response);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
