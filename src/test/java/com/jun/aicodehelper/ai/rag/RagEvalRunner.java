package com.jun.aicodehelper.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jun.aicodehelper.ai.metrics.AppMetrics;
import com.jun.aicodehelper.ai.model.LlmReranker;
import com.jun.aicodehelper.ai.model.MinimaxEmbeddingModel;
import com.jun.aicodehelper.ai.model.RerankModel;
import com.jun.aicodehelper.ai.model.SiliconFlowEmbeddingModel;
import com.jun.aicodehelper.ai.model.SiliconFlowReranker;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.MetricType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索评测：RAG_EVAL=1 时手动执行。
 *   mvn test -Dtest=RagEvalRunner -DRAG_EVAL=1 [-Drag.xxx=yyy ...]
 * 配置读取：application.yml + application-local.yml 合并，System property（-Drag.embedding-provider=minimax）可覆盖，
 * 便于同一份代码跑「基线（minimax + 旧集合 + 旧阈值）」和「终测（siliconflow + 新集合）」对比。
 * 指标：hit@5（期望文件出现在前 5 段）与 MRR（首个命中的倒数排名）。
 */
@EnabledIfSystemProperty(named = "RAG_EVAL", matches = "1")
class RagEvalRunner {

    private static Map<String, Object> config;
    private static List<EvalQuestion> questions;
    private static HybridContentRetriever retriever;
    private static int topKForMetrics = 5;

    record EvalQuestion(int id, String question, String expectFile) {
    }

    record QuestionList(List<EvalQuestion> questions) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        config = loadConfig();
        questions = new ObjectMapper().readValue(
                Files.readString(Paths.get("src/test/resources/rag-eval/eval-questions.json")),
                QuestionList.class).questions();

        RagProperties props = buildProps();
        EmbeddingModel embeddingModel = buildEmbeddingModel();
        ChatModel chatModel = buildChatModel();
        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .host(str("milvus.host", "localhost"))
                .port(num("milvus.port", 19530))
                .collectionName(str("milvus.collection-name", "ai_code_helper"))
                .dimension(num("milvus.dimension", 1024))
                .metricType(MetricType.COSINE)
                .build();

        // BM25 从 docs 目录现场重建（评测不依赖后端进程的内存态）
        Bm25Index bm25 = new Bm25Index();
        HierarchicalSplitter splitter = new HierarchicalSplitter(props);
        List<TextSegment> all = new ArrayList<>();
        for (Document doc : loadDocs()) {
            all.addAll(splitter.split(doc));
        }
        bm25.rebuild(all);

        // -Ddrop.collection=1：先清空目标集合（切分 schema 变更后重建用）
        if (Boolean.parseBoolean(System.getProperty("drop.collection", "false"))) {
            System.out.println("=== 清空集合 " + str("milvus.collection-name", "") + " ===");
            store.removeAll();
        }
        // 目标集合为空时现场入库（终测第一次跑新集合用；基线集合已有数据不触发）
        if (store.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed("probe").content())
                .maxResults(1).build()).matches().isEmpty()) {
            System.out.println("=== Milvus 集合为空，现场入库 " + all.size() + " 切片 ===");
            store.addAll(embeddingModel.embedAll(all).content(), all);
        }

        RerankModel rerank = buildRerankModel(chatModel);
        retriever = new HybridContentRetriever(store, embeddingModel, bm25, chatModel,
                rerank, props,
                new AppMetrics(new SimpleMeterRegistry()));
        System.out.printf("=== 评测环境: embedding=%s rerank=%s collection=%s dim=%d 切片=%d 题数=%d ===%n",
                str("rag.embedding-provider", "minimax"),
                str("rag.rerank-provider", "llm"),
                str("milvus.collection-name", "ai_code_helper"),
                num("milvus.dimension", 1024),
                all.size(), questions.size());
    }

    @Test
    void runEval() {
        int hits = 0;
        double mrrSum = 0;
        StringBuilder table = new StringBuilder();
        for (EvalQuestion q : questions) {
            List<Content> contents = retriever.retrieve(Query.from(q.question()));
            int rank = -1;
            for (int i = 0; i < contents.size() && i < topKForMetrics; i++) {
                String file = extractFileName(contents.get(i).textSegment());
                if (file != null && file.contains(q.expectFile())) {
                    rank = i + 1;
                    break;
                }
            }
            boolean hit = rank > 0;
            double rr = hit ? 1.0 / rank : 0;
            hits += hit ? 1 : 0;
            mrrSum += rr;
            table.append(String.format("#%-2d %-6s rank=%-2s | %s%n",
                    q.id(), hit ? "✓" : "✗", rank == -1 ? "-" : rank, q.question()));
        }
        System.out.println(table);
        System.out.printf("====== hit@%d = %d/%d (%.1f%%) | MRR = %.3f ======%n",
                topKForMetrics, hits, questions.size(), 100.0 * hits / questions.size(), mrrSum / questions.size());
    }

    /** metadata.file_name 优先，缺失时回落解析「文件名 | 标题」前缀行 */
    private static String extractFileName(TextSegment seg) {
        String fn = seg.metadata().getString("file_name");
        if (fn != null && !fn.isBlank()) return fn;
        String text = seg.text();
        int nl = text.indexOf('\n');
        String first = nl > 0 ? text.substring(0, nl) : text;
        return first.contains(" | ") ? first : null;
    }

    // ---------- 配置 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() throws Exception {
        Map<String, Object> merged = new java.util.HashMap<>();
        for (String file : List.of("src/main/resources/application.yml", "src/main/resources/application-local.yml")) {
            Path p = Paths.get(file);
            if (Files.exists(p)) {
                Object loaded = new Yaml().load(Files.readString(p));
                if (loaded instanceof Map<?, ?> m) {
                    deepMerge(merged, (Map<String, Object>) m);
                }
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> into, Map<String, Object> from) {
        for (Map.Entry<String, Object> e : from.entrySet()) {
            if (e.getValue() instanceof Map && into.get(e.getKey()) instanceof Map) {
                deepMerge((Map<String, Object>) into.get(e.getKey()), (Map<String, Object>) e.getValue());
            } else {
                into.put(e.getKey(), e.getValue());
            }
        }
    }

    /** 点路径取值；System property 同名优先 */
    private static Object raw(String key) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        Object cur = config;
        for (String part : key.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
        }
        return cur;
    }

    private static String str(String key, String def) {
        Object v = raw(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int num(String key, int def) {
        try {
            return Integer.parseInt(str(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(String key, boolean def) {
        Object v = raw(key);
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    private static double dbl(String key, double def) {
        try {
            return Double.parseDouble(str(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static RagProperties buildProps() {
        RagProperties p = new RagProperties();
        p.setVectorTopK(num("rag.vector-top-k", 20));
        p.setVectorMinScore(dbl("rag.vector-min-score", 0.4));
        p.setKeywordWeight(dbl("rag.keyword-weight", 0.3));
        p.setFinalTopK(num("rag.final-top-k", 5));
        p.setFinalMinScore(dbl("rag.final-min-score", 0.2));
        p.setBm25TopK(num("rag.bm25-top-k", 20));
        p.setBm25Enabled(bool("rag.bm25-enabled", true));
        p.setRerankEnabled(bool("rag.rerank-enabled", true));
        p.setRerankMinCandidates(num("rag.rerank-min-candidates", 5));
        p.setCompressEnabled(bool("rag.compress-enabled", false));
        p.setCompressMaxChars(num("rag.compress-max-chars", 200));
        p.setQueryRewriteEnabled(bool("rag.query-rewrite-enabled", true));
        p.setQueryRewriteVariants(num("rag.query-rewrite-variants", 3));
        p.setHydeEnabled(bool("rag.hyde-enabled", false));
        p.setQueryRewriteHistoryTurns(num("rag.query-rewrite-history-turns", 2));
        p.setParentChunkEnabled(bool("rag.parent-chunk-enabled", false));
        p.setParentMaxChars(num("rag.parent-max-chars", 1000));
        p.setChildMaxChars(num("rag.child-max-chars", 300));
        return p;
    }

    private static EmbeddingModel buildEmbeddingModel() {
        if ("siliconflow".equalsIgnoreCase(str("rag.embedding-provider", "minimax"))) {
            return new SiliconFlowEmbeddingModel(str("siliconflow.base-url", ""),
                    str("siliconflow.api-key", ""), str("siliconflow.embedding-model", "BAAI/bge-m3"));
        }
        return new MinimaxEmbeddingModel(str("minimax.base-url", ""),
                str("minimax.api-key", ""), str("minimax.embedding-model", "embo-01"));
    }

    private static ChatModel buildChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(str("zhipu.base-url", "https://open.bigmodel.cn/api/paas/v4/"))
                .apiKey(str("zhipu.api-key", ""))
                .modelName(str("zhipu.chat-model", "glm-4-flash"))
                .build();
    }

    private static RerankModel buildRerankModel(ChatModel chatModel) {
        String apiKey = str("siliconflow.api-key", "");
        if ("siliconflow".equalsIgnoreCase(str("rag.rerank-provider", "llm")) && !apiKey.isBlank()) {
            return new SiliconFlowReranker(str("siliconflow.base-url", ""), apiKey,
                    str("siliconflow.rerank-model", "BAAI/bge-reranker-v2-m3"));
        }
        return new LlmReranker(chatModel);
    }

    // ---------- 文档加载（docs 目录现为 md，评测取 txt/md） ----------

    private static List<Document> loadDocs() throws Exception {
        List<Document> docs = new ArrayList<>();
        Path dir = Paths.get("src/main/resources/docs");
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase();
                if (name.endsWith(".md") || name.endsWith(".txt")) {
                    Document doc = Document.from(Files.readString(p));
                    doc.metadata().put("file_name", p.getFileName().toString());
                    docs.add(doc);
                }
            }
        }
        return docs;
    }
}
