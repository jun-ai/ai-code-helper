package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合检索：召回过滤、bigram 重排、空结果兜底
 */
class HybridContentRetrieverTest {

    private InMemoryEmbeddingStore<TextSegment> store;

    private HybridContentRetriever retriever;

    /** 固定返回 (1,0,0) 的查询向量模型 */
    private final EmbeddingModel fakeModel = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1, 0, 0})).toList());
        }
    };

    /** 不做事的 ChatModel：rerank/compress/rewrite 关掉后根本不会被调用 */
    private final ChatModel noopChat = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
        }
    };

    @BeforeEach
    void setUp() {
        store = new InMemoryEmbeddingStore<>();
        AppMetrics metrics = new AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        RagProperties props = new RagProperties();
        // 关掉需要 ChatModel 的三个阶段，避免 fakeChat 干扰断言
        props.setQueryRewriteEnabled(false);
        props.setRerankEnabled(false);
        props.setCompressEnabled(false);
        retriever = new HybridContentRetriever(store, fakeModel, new Bm25Index(), noopChat, props, metrics);
    }

    private void add(String text, float[] vector) {
        store.add(Embedding.from(vector), TextSegment.from(text, new Metadata()));
    }

    @Test
    void 字面命中的切片应排在纯向量高分切片之前() {
        // 与查询向量完全同向（cos=1.0）但字面无关 vs 同向且字面大量命中
        add("MySQL 索引优化经验分享", new float[]{1, 0, 0});
        add("Redis 面试题 Redis 持久化与面试题精选", new float[]{1, 0, 0});

        List<Content> result = retriever.retrieve(Query.from("Redis 面试题"));

        assertEquals(2, result.size());
        assertTrue(result.get(0).textSegment().text().contains("Redis 面试题"));
    }

    @Test
    void 语义分数低于硬门槛的切片应被过滤() {
        // relevance=(cos+1)/2：cos=0 得 0.5，低于语义门槛 0.85；
        // 对照条目 cos=1.0（relevance=1.0）可保留
        add("向量无关文档", new float[]{0, 1, 0});
        add("数据库连接池配置详解", new float[]{1, 0, 0});

        List<Content> result = retriever.retrieve(Query.from("Redis 面试题"));

        assertEquals(1, result.size());
        assertTrue(result.get(0).textSegment().text().contains("连接池"));
    }

    @Test
    void 召回通过但语义不足的切片也应被过滤() {
        // cos=0.35 → relevance=0.675 过召回阈值 0.5，但低于语义门槛 0.85；
        // 对照条目 relevance=1.0 可保留
        add("架构设计的通用原则", new float[]{0.35f, 0.93675f, 0});
        add("数据库连接池配置详解", new float[]{1, 0, 0});

        List<Content> result = retriever.retrieve(Query.from("Redis 面试题"));

        assertEquals(1, result.size());
        assertTrue(result.get(0).textSegment().text().contains("连接池"));
    }

    @Test
    void 空结果时应返回空列表由系统提示兜底() {
        List<Content> result = retriever.retrieve(Query.from("Redis 面试题"));

        assertTrue(result.isEmpty());
    }

    @Test
    void 无字面命中但向量高分的切片仍可通过() {
        // cos = 1.0，字面 0 分：0.5*0 + 0.5*1.0 = 0.5 >= 0.35，保留
        add("数据库连接池配置详解", new float[]{1, 0, 0});

        List<Content> result = retriever.retrieve(Query.from("Redis 面试题"));

        assertEquals(1, result.size());
        assertFalse(result.get(0).textSegment().text().contains("知识库未检索到"));
    }
}
