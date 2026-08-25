package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缓存检索器：同问命中缓存、LRU 超量淘汰
 */
class CachingContentRetrieverTest {

    private final AtomicInteger delegateCalls = new AtomicInteger();

    private final CachingContentRetriever caching = new CachingContentRetriever(new ContentRetriever() {
        @Override
        public List<Content> retrieve(Query query) {
            delegateCalls.incrementAndGet();
            return List.of(Content.from(TextSegment.from("delegate:" + query.text())));
        }
    });

    @Test
    void 相同问题第二次查询不应触发底层检索() {
        caching.retrieve(Query.from("Java 学习路线"));
        caching.retrieve(Query.from("Java 学习路线"));

        assertEquals(1, delegateCalls.get());
    }

    @Test
    void 问题前后空白应归一化后命中缓存() {
        caching.retrieve(Query.from("Java 学习路线"));
        caching.retrieve(Query.from("  Java 学习路线  "));

        assertEquals(1, delegateCalls.get());
    }

    @Test
    void 超过缓存容量后最旧的条目应被LRU淘汰() {
        // 首条在第 129 个不同问题写入后被淘汰，再问需重新检索
        caching.retrieve(Query.from("q0"));
        for (int i = 1; i <= 129; i++) {
            caching.retrieve(Query.from("q" + i));
        }
        caching.retrieve(Query.from("q0"));

        // q0 首查 1 次 + 淘汰后再查 1 次，其余 129 问各 1 次
        assertEquals(131, delegateCalls.get());
    }
}
