package com.jun.aicodehelper.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 统一指标定义：请求、检索、embedding、兜底、token 用量
 */
@Getter
@Component
public class AppMetrics {

    private final Counter chatRequests;
    private final Counter chatErrors;
    private final Counter ragRetrieves;
    private final Counter ragFallback;
    private final Counter embeddingCalls;
    private final Counter embeddingCacheHits;
    private final Counter llmInputTokens;
    private final Counter llmOutputTokens;
    private final Counter mcpHeartbeatSuccess;
    private final Counter mcpHeartbeatFailure;
    private final Timer embeddingLatency;
    private final Timer ragRetrieveLatency;
    private final Timer mcpHeartbeatLatency;

    public AppMetrics(MeterRegistry registry) {
        this.chatRequests = Counter.builder("chat.requests").description("聊天请求总数").register(registry);
        this.chatErrors = Counter.builder("chat.errors").description("聊天请求失败数").register(registry);
        this.ragRetrieves = Counter.builder("rag.retrieves").description("RAG 检索请求总数（含失败）").register(registry);
        this.ragFallback = Counter.builder("rag.fallback").description("RAG 检索触发兜底的次数（空结果或异常）").register(registry);
        this.embeddingCalls = Counter.builder("embedding.calls").description("MiniMax embedding 调用次数（缓存未命中）").register(registry);
        this.embeddingCacheHits = Counter.builder("embedding.cache.hits").description("embedding 缓存命中次数").register(registry);
        this.llmInputTokens = Counter.builder("llm.tokens").tag("direction", "input").description("LLM 输入 token 用量").register(registry);
        this.llmOutputTokens = Counter.builder("llm.tokens").tag("direction", "output").description("LLM 输出 token 用量").register(registry);
        this.embeddingLatency = Timer.builder("embedding.latency").description("MiniMax embedding 调用延迟").register(registry);
        this.ragRetrieveLatency = Timer.builder("rag.retrieve.latency").description("RAG 检索延迟").register(registry);
        this.mcpHeartbeatSuccess = Counter.builder("mcp.heartbeat").tag("result", "success").description("MCP 心跳成功次数").register(registry);
        this.mcpHeartbeatFailure = Counter.builder("mcp.heartbeat").tag("result", "failure").description("MCP 心跳失败次数").register(registry);
        this.mcpHeartbeatLatency = Timer.builder("mcp.heartbeat.latency").description("MCP 心跳调用延迟").register(registry);
    }
}