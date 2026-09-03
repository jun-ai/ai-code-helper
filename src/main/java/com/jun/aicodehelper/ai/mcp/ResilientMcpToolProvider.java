package com.jun.aicodehelper.ai.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 工具的加固版 Provider：智谱 web_search_prime 的内容风控（code 1301）
 * 对模糊搜索词是按调用随机命中的，单次失败不代表查询非法。
 * 这里对 1301/异常做最多 3 次退避重试，仍失败则返回友好提示让模型基于已有知识作答，
 * 避免把「风控误杀」直接暴露成「无法联网」。
 */
@Slf4j
public class ResilientMcpToolProvider implements ToolProvider {

    private static final int MAX_ATTEMPTS = 3;

    private final McpClient mcpClient;
    private final AtomicLong retryIdSeq = new AtomicLong();

    public ResilientMcpToolProvider(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @Override
    public ToolProviderResult provideTools(dev.langchain4j.service.tool.ToolProviderRequest request) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (ToolSpecification spec : mcpClient.listTools()) {
            tools.put(spec, (toolRequest, memoryId) -> executeWithRetry(toolRequest));
        }
        return new ToolProviderResult(tools);
    }

    private String executeWithRetry(ToolExecutionRequest request) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String result = mcpClient.executeTool(requestFor(request, attempt));
                if (result != null && (result.contains("1301") || result.contains("contentFilter"))) {
                    log.warn("MCP 搜索被风控拦截（第 {}/{} 次）: {}", attempt, MAX_ATTEMPTS, request.arguments());
                    sleep(400L * attempt);
                    continue;
                }
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("MCP 工具调用异常（第 {}/{} 次）: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleep(400L * attempt);
            }
        }
        log.warn("MCP 工具重试 {} 次仍失败: {}", MAX_ATTEMPTS,
                lastError == null ? "风控持续拦截" : lastError.getMessage());
        return "联网搜索暂时不可用（服务波动或风控拦截），请基于已有知识作答，并简短告知用户联网搜索暂不可用、可稍后再试。";
    }

    /** 重试时换新 id：部分 MCP 服务端会拒绝重复的 request id */
    private ToolExecutionRequest requestFor(ToolExecutionRequest original, int attempt) {
        if (attempt == 1) {
            return original;
        }
        return ToolExecutionRequest.builder()
                .id(original.id() + "-retry" + retryIdSeq.incrementAndGet())
                .name(original.name())
                .arguments(original.arguments())
                .build();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
