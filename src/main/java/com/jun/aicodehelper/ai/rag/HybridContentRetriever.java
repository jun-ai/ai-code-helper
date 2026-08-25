package com.jun.aicodehelper.ai.rag;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索主链路：query rewrite → 向量 + BM25 召回 → RRF 融合 → rerank → 上下文压缩。
 * 每一步都可由 RagProperties 关掉降级。
 */
@Slf4j
public class HybridContentRetriever implements ContentRetriever {

    private static final int RRF_K = 60;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Bm25Index bm25Index;
    private final QueryRewriter queryRewriter;
    private final Reranker reranker;
    private final ContextualCompressor compressor;
    private final RagProperties props;
    private final com.jun.aicodehelper.ai.metrics.AppMetrics metrics;

    public HybridContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                   EmbeddingModel embeddingModel,
                                   Bm25Index bm25Index,
                                   ChatModel chatModel,
                                   RagProperties props,
                                   com.jun.aicodehelper.ai.metrics.AppMetrics metrics) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.bm25Index = bm25Index;
        this.props = props;
        this.metrics = metrics;
        this.queryRewriter = props.isQueryRewriteEnabled() ? new QueryRewriter(chatModel, props.getQueryRewriteVariants()) : null;
        this.reranker = props.isRerankEnabled() ? new Reranker(chatModel, props.getFinalTopK()) : null;
        this.compressor = props.isCompressEnabled() ? new ContextualCompressor(chatModel, props.getCompressMaxChars()) : null;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return metrics.getRagRetrieveLatency().record(() -> doRetrieve(query));
    }

    private List<Content> doRetrieve(Query query) {
        String q = query.text();
        List<String> queries = rewrite(q);
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
        List<TextSegment> reranked;
        if (reranker != null && topCandidates.size() >= props.getRerankMinCandidates()) {
            reranked = reranker.rerank(q, topCandidates);
        } else {
            reranked = topCandidates.subList(0, Math.min(props.getFinalTopK(), topCandidates.size()));
        }
        List<TextSegment> finalList = compressor != null ? compressor.compress(q, reranked) : reranked;
        return finalList.stream().map(Content::from).toList();
    }

    private List<String> rewrite(String q) {
        if (queryRewriter == null) {
            return List.of(q);
        }
        return queryRewriter.rewrite(q);
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
