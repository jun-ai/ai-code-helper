package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.model.MinimaxEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.MetricType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/**
 * 检索分数诊断（手动执行）：MINIMAX_API_KEY=xxx mvn test -Dtest=RagScoreDiagnosticTest
 * 用于观察相关/不相关问题在 Milvus 上的真实分数分布，为阈值调优提供依据
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
class RagScoreDiagnosticTest {

    @Test
    void printSearchScores() {
        EmbeddingModel model = new MinimaxEmbeddingModel(
                "https://api.minimaxi.com/v1", System.getenv("MINIMAX_API_KEY"), "embo-01");
        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .host("localhost").port(19530)
                .collectionName("ai_code_helper")
                .dimension(1536)
                .metricType(MetricType.COSINE)
                .build();
        List<String> questions = List.of(
                "Redis 面试题有哪些？",
                "Java 学习路线阶段1学什么",
                "亿级流量点赞系统的技术栈是什么？",
                "写简历有什么技巧？",
                "量子力学的基本原理是什么？"
        );
        for (String q : questions) {
            var result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(model.embed(q).content())
                    .maxResults(3)
                    .build());
            System.out.println("Q: " + q);
            result.matches().forEach(m -> {
                String text = m.embedded().text().replace('\n', ' ');
                System.out.printf("  score=%.3f | %s%n", m.score(),
                        text.substring(0, Math.min(70, text.length())));
            });
            // 端到端走 HybridContentRetriever，观察线上同路径行为
            var props = new RagProperties();
            props.setQueryRewriteEnabled(false);
            props.setRerankEnabled(false);
            props.setCompressEnabled(false);
            var hybrid = new HybridContentRetriever(store, model, new Bm25Index(),
                    new dev.langchain4j.model.chat.ChatModel() {
                        @Override
                        public dev.langchain4j.model.chat.response.ChatResponse chat(
                                dev.langchain4j.model.chat.request.ChatRequest request) {
                            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(""))
                                    .build();
                        }
                    },
                    null,
                    props,
                    new com.jun.aicodehelper.ai.metrics.AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                    .retrieve(dev.langchain4j.rag.query.Query.from(q));
            System.out.printf("  [hybrid] %d 条%n", hybrid.size());
        }
    }
}
