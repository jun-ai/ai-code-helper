package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 相同问题直接命中上次检索结果，跳过向量查询
 */
public class CachingContentRetriever implements ContentRetriever {

    private static final int MAX_CACHE_SIZE = 128;

    private final ContentRetriever delegate;

    private final Map<String, List<Content>> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Content>> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    public CachingContentRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String key = query.text().trim();
        List<Content> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Content> contents = delegate.retrieve(query);
        cache.put(key, contents);
        return contents;
    }
}
