package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * RAG 降级包装：底层抛异常时吞掉并返回空内容，让 LLM 继续基于通用知识作答，
 * 避免 Milvus / Embedding 故障拖死整条回复链路。
 */
@Slf4j
public class FallbackContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;

    public FallbackContentRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        try {
            return delegate.retrieve(query);
        } catch (Exception e) {
            log.warn("RAG 检索失败，降级为空上下文: query=\"{}\" err={}", query.text(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
