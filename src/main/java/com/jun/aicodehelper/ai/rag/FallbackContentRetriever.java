package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * RAG 降级包装：底层抛异常时吞掉并返回空内容，让 LLM 继续基于通用知识作答，
 * 避免 Milvus / Embedding 故障拖死整条回复链路。
 *
 * 埋点：每次进入 retrieve 计数 rag.retrieves；异常兜底额外计数 rag.fallback（HybridContentRetriever 里空结果也计 rag.fallback）。
 */
@Slf4j
public class FallbackContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final AppMetrics metrics;

    public FallbackContentRetriever(ContentRetriever delegate, AppMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public List<Content> retrieve(Query query) {
        metrics.getRagRetrieves().increment();
        try {
            return delegate.retrieve(query);
        } catch (Exception e) {
            metrics.getRagFallback().increment();
            log.warn("RAG 检索失败，降级为空上下文: query=\"{}\" err={}", query.text(), e.getMessage());
            return Collections.emptyList();
        }
    }
}