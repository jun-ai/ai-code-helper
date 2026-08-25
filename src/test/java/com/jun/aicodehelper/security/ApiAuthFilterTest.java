package com.jun.aicodehelper.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鉴权 + 限流过滤器：401 拒绝、放行、固定窗口 429、路径豁免
 */
class ApiAuthFilterTest {

    private static final String VALID_KEY = "test-key";

    private ApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiAuthFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "validApiKey", VALID_KEY);
        ReflectionTestUtils.setField(filter, "requestsPerMinute", 3);
    }

    private record Result(int status, boolean passedChain) {
    }

    private Result execute(String uri, String apiKey) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (apiKey != null) {
            request.addHeader("X-API-Key", apiKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response.getStatus(), chain.getRequest() != null);
    }

    private Result execute(String method, String uri, String apiKey) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (apiKey != null) {
            request.addHeader("X-API-Key", apiKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response.getStatus(), chain.getRequest() != null);
    }

    @Test
    void 无Key应返回401() throws Exception {
        Result r = execute("/ai/chat", null);
        assertEquals(401, r.status);
        assertTrue(!r.passedChain());
    }

    @Test
    void 错误Key应返回401() throws Exception {
        Result r = execute("/ai/chat", "wrong-key");
        assertEquals(401, r.status);
        assertTrue(!r.passedChain());
    }

    @Test
    void 正确Key应放行() throws Exception {
        Result r = execute("/ai/chat", VALID_KEY);
        assertEquals(200, r.status);
        assertTrue(r.passedChain());
    }

    @Test
    void 同一分钟超限应返回429() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(200, execute("/ai/chat", VALID_KEY).status);
        }
        assertEquals(429, execute("/ai/chat", VALID_KEY).status);
    }

    @Test
    void 非AI路径应跳过鉴权() throws Exception {
        Result r = execute("/other/health", null);
        assertTrue(r.passedChain());
    }

    @Test
    void OPTIONS预检应无条件放行() throws Exception {
        Result r = execute("OPTIONS", "/ai/chat", VALID_KEY);
        assertTrue(r.passedChain());
    }
}
