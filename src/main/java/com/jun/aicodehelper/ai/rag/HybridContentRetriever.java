package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.model.RerankModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.rag.query.Metadata;

/**
 * RAG 检索主链路：query rewrite (+ history) → 向量 + BM25 召回 → RRF 融合 → rerank → （可选）父块回送。
 * 可选 HyDE：另起一路假设文档向量召回，弥补口语 query 与文档语义空间差距。
 * 每一步都可由 RagProperties 关掉降级；rerank 实现由外部注入（SiliconFlow / LLM）。
 */
@Slf4j
public class HybridContentRetriever implements ContentRetriever {

    private static final int RRF_K = 60;
    public static final String META_HISTORY = "history";
    public static final String META_PARENT_ID = "parent_id";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Bm25Index bm25Index;
    private final QueryRewriter queryRewriter;
    private final HydeQueryExpander hydeExpander;
    private final RerankModel rerankModel;
    private final ContextualCompressor compressor;
    private final RagProperties props;
    private final com.jun.aicodehelper.ai.metrics.AppMetrics metrics;

    public HybridContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                   EmbeddingModel embeddingModel,
                                   Bm25Index bm25Index,
                                   ChatModel chatModel,
                                   RerankModel rerankModel,
                                   RagProperties props,
                                   com.jun.aicodehelper.ai.metrics.AppMetrics metrics) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.bm25Index = bm25Index;
        this.props = props;
        this.metrics = metrics;
        this.queryRewriter = props.isQueryRewriteEnabled() ? new QueryRewriter(chatModel, props.getQueryRewriteVariants()) : null;
        this.hydeExpander = props.isHydeEnabled() ? new HydeQueryExpander(chatModel) : null;
        this.rerankModel = rerankModel;
        this.compressor = props.isCompressEnabled() ? new ContextualCompressor(chatModel, props.getCompressMaxChars()) : null;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return metrics.getRagRetrieveLatency().record(() -> doRetrieve(query));
    }

    private List<Content> doRetrieve(Query query) {
        String q = query.text();
        List<String> history = extractHistory(query.metadata());
        List<String> queries = rewrite(q, history);
        Map<TextSegment, double[]> fused = new LinkedHashMap<>();
        int vectorCount = 0, bm25Count = 0;
        for (String sub : queries) {
            List<EmbeddingMatch<TextSegment>> vMatches = vectorSearch(sub);
            vectorCount = Math.max(vectorCount, vMatches.size());
            mergeVector(fused, vMatches);
            if (props.isBm25Enabled()) {
                List<Bm25Index.Scored> bm = bm25Index.search(sub, props.getBm25TopK());
                bm25Count = Math.max(bm25Count, bm.size());
                mergeBm25(fused, bm);
            }
        }
        // HyDE：假设性文档只走向量（BM25 容易失真），与主路融合
        if (hydeExpander != null) {
            String hyp = hydeExpander.expand(q);
            if (!hyp.isBlank()) {
                List<EmbeddingMatch<TextSegment>> hVec = vectorSearch(hyp);
                vectorCount = Math.max(vectorCount, hVec.size());
                mergeVector(fused, hVec);
                log.info("RAG HyDE: query=\"{}\" 召回={}", q, hVec.size());
            }
        }
        final int fVectorCount = vectorCount;
        final int fBm25Count = bm25Count;
        List<Map.Entry<TextSegment, double[]>> ranked = fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        finalScore(b.getValue(), fVectorCount, fBm25Count),
                        finalScore(a.getValue(), fVectorCount, fBm25Count)))
                .toList();
        List<TextSegment> topCandidates = ranked.stream()
                .filter(e -> finalScore(e.getValue(), fVectorCount, fBm25Count) >= props.getFinalMinScore())
                .limit(Math.max(props.getFinalTopK() * 3, props.getFinalTopK()))
                .map(Map.Entry::getKey)
                .toList();
        log.info("RAG 检索: query=\"{}\" 改写={}路 向量召回={} BM25召回={} 融合={}",
                q, queries.size(), fVectorCount, fBm25Count, fused.size());

        if (topCandidates.isEmpty()) {
            metrics.getRagFallback().increment();
            return List.of();
        }
        // 父块回送开启时多取一倍子块：按 parent_id 去重后会缩水，保证父块数仍够 finalTopK
        int rerankTopN = props.isParentChunkEnabled() ? props.getFinalTopK() * 2 : props.getFinalTopK();
        List<TextSegment> reranked;
        if (rerankModel != null && topCandidates.size() >= props.getRerankMinCandidates()) {
            reranked = rerankModel.rerank(q, topCandidates, rerankTopN);
        } else {
            reranked = topCandidates.subList(0, Math.min(rerankTopN, topCandidates.size()));
        }
        List<TextSegment> afterParent = toParents(reranked);
        List<TextSegment> finalList = compressor != null ? compressor.compress(q, afterParent) : afterParent;
        return finalList.stream().map(Content::from).toList();
    }

    /**
     * Small-to-big：子块按 parent_id 去重、用 metadata 里的 parent_text 回送父块全文。
     * 无 parent_id 标记（旧数据/开关关闭）或 parent_text 缺失时原样返回子块。
     */
    private List<TextSegment> toParents(List<TextSegment> segments) {
        if (!props.isParentChunkEnabled()) {
            return segments;
        }
        List<TextSegment> out = new ArrayList<>();
        Set<String> seenParentIds = new HashSet<>();
        for (TextSegment seg : segments) {
            if (out.size() >= props.getFinalTopK()) break;
            String pid = seg.metadata().getString(META_PARENT_ID);
            String parentText = seg.metadata().getString(HierarchicalSplitter.META_PARENT_TEXT);
            if (pid == null || parentText == null) {
                out.add(seg);
                continue;
            }
            if (!seenParentIds.add(pid)) continue;
            out.add(TextSegment.from(parentText, seg.metadata()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractHistory(dev.langchain4j.rag.query.Metadata metadata) {
        if (metadata == null) return List.of();
        // langchain4j 1.1.0 的 Metadata 接口在不同小版本里暴露的方法名不同（get / getString / containsKey 等），
        // 用反射兜底，避免编译期 API 漂移阻断构建。
        Object h = null;
        for (String methodName : new String[]{"get", "getString", "asMap"}) {
            try {
                java.lang.reflect.Method m = metadata.getClass().getMethod(methodName, String.class);
                Object v = m.invoke(metadata, META_HISTORY);
                if (v != null) { h = v; break; }
            } catch (NoSuchMethodException ignore) {
            } catch (Throwable ignore) {
            }
        }
        if (h == null) {
            // 兜底：尝试无参 asMap()
            try {
                java.lang.reflect.Method m = metadata.getClass().getMethod("asMap");
                Object map = m.invoke(metadata);
                if (map instanceof Map<?, ?> mm) h = mm.get(META_HISTORY);
            } catch (Throwable ignore) {
            }
        }
        if (h == null) return List.of();
        if (h instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of(String.valueOf(h));
    }

    private List<String> rewrite(String q, List<String> history) {
        if (queryRewriter == null) {
            return List.of(q);
        }
        return queryRewriter.rewrite(q, history);
    }

    private List<EmbeddingMatch<TextSegment>> vectorSearch(String q) {
        Embedding emb = embeddingModel.embed(q).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(emb)
                .maxResults(props.getVectorTopK())
                .minScore(props.getVectorMinScore())
                .build());
        return result.matches();
    }

    private void mergeVector(Map<TextSegment, double[]> fused, List<EmbeddingMatch<TextSegment>> matches) {
        for (int i = 0; i < matches.size(); i++) {
            TextSegment seg = matches.get(i).embedded();
            double[] acc = fused.computeIfAbsent(seg, k -> new double[]{0, 0});
            acc[0] += 1.0 / (RRF_K + i + 1);
        }
    }

    private void mergeBm25(Map<TextSegment, double[]> fused, List<Bm25Index.Scored> scored) {
        for (int i = 0; i < scored.size(); i++) {
            TextSegment seg = scored.get(i).segment();
            double[] acc = fused.computeIfAbsent(seg, k -> new double[]{0, 0});
            acc[1] += 1.0 / (RRF_K + i + 1);
        }
    }

    private double normalize(double rrfScore, int totalHits) {
        if (totalHits == 0) return 0;
        return Math.min(1.0, rrfScore * (RRF_K + 1));
    }

    private double finalScore(double[] v, int vectorCount, int bm25Count) {
        return normalize(v[0], vectorCount) * (1 - props.getKeywordWeight())
                + normalize(v[1], bm25Count) * props.getKeywordWeight();
    }
}
