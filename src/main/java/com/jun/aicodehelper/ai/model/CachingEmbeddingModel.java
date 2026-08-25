package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * embedding 装饰器：同 query 5 分钟内复用结果，省 MiniMax 调用
 */
@Slf4j
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final EmbeddingModel delegate;

    private final AppMetrics metrics;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingEmbeddingModel(EmbeddingModel delegate, AppMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = textSegments.stream().map(TextSegment::text).toList();
        List<Embedding> result = new ArrayList<>(texts.size());
        List<TextSegment> toFetch = new ArrayList<>();
        List<Integer> toFetchIndex = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < texts.size(); i++) {
            String key = normalize(texts.get(i));
            CacheEntry entry = cache.get(key);
            if (entry != null && now - entry.timestamp < TTL.toMillis()) {
                result.add(entry.embedding);
                metrics.getEmbeddingCacheHits().increment();
            } else {
                result.add(null);
                toFetch.add(textSegments.get(i));
                toFetchIndex.add(i);
            }
        }
        if (!toFetch.isEmpty()) {
            metrics.getEmbeddingCalls().increment();
            Response<List<Embedding>> fetched = metrics.getEmbeddingLatency().record(() -> delegate.embedAll(toFetch));
            for (int j = 0; j < toFetch.size(); j++) {
                Embedding e = fetched.content().get(j);
                int idx = toFetchIndex.get(j);
                result.set(idx, e);
                cache.put(normalize(toFetch.get(j).text()), new CacheEntry(e, now));
            }
        }
        return Response.from(result);
    }

    private String normalize(String text) {
        return text.trim();
    }

    private record CacheEntry(Embedding embedding, long timestamp) {
    }
}