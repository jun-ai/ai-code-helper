package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 装饰器缓存：同文本二次调用不触发底层，被 trim 归一化命中
 */
class CachingEmbeddingModelTest {

    private AppMetrics metrics;

    private AtomicInteger delegateCalls;

    private CachingEmbeddingModel caching;

    @BeforeEach
    void setUp() {
        metrics = new AppMetrics(new SimpleMeterRegistry());
        delegateCalls = new AtomicInteger();
        EmbeddingModel delegate = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                delegateCalls.incrementAndGet();
                return Response.from(textSegments.stream()
                        .map(s -> Embedding.from(new float[]{s.text().length(), 0, 0})).toList());
            }
        };
        caching = new CachingEmbeddingModel(delegate, metrics);
    }

    @Test
    void 同一文本二次调用应直接命中缓存() {
        caching.embedAll(List.of(TextSegment.from("hello")));
        caching.embedAll(List.of(TextSegment.from("hello")));

        assertEquals(1, delegateCalls.get());
    }

    @Test
    void 前后空白不同应归一化命中缓存() {
        caching.embedAll(List.of(TextSegment.from("hello")));
        caching.embedAll(List.of(TextSegment.from("  hello  ")));

        assertEquals(1, delegateCalls.get());
    }

    @Test
    void 批量内已有缓存项应跳过底层只调未命中项() {
        caching.embedAll(List.of(TextSegment.from("a")));
        // 第二次混合：b 缓存未命中，a 命中 → delegate 只被调用 1 次（处理 b）
        List<Embedding> result = caching.embedAll(List.of(
                TextSegment.from("a"),
                TextSegment.from("b")
        )).content();
        assertEquals(2, delegateCalls.get());
        // 两条都有结果（一条来自缓存、一条来自新调用）
        assertEquals(2, result.size());
    }
}