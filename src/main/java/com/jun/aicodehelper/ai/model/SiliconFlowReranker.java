package com.jun.aicodehelper.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SiliconFlow bge-reranker-v2-m3：一次 API 对全部候选打分重排。
 * 失败退原序前 topN（与 LlmReranker 降级语义一致），不抛异常中断检索。
 */
@Slf4j
public class SiliconFlowReranker implements RerankModel {

    private static final int MAX_ATTEMPTS = 3;
    /** 单文档送入长度上限：rerank 模型截断比 LLM 宽，但仍防超长 */
    private static final int MAX_DOC_CHARS = 2000;

    private final RestClient restClient;
    private final String modelName;

    public SiliconFlowReranker(String baseUrl, String apiKey, String modelName) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.modelName = modelName;
    }

    @Override
    public List<TextSegment> rerank(String query, List<TextSegment> candidates, int topN) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<String> documents = new ArrayList<>(candidates.size());
        for (TextSegment seg : candidates) {
            String text = seg.text();
            documents.add(text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text);
        }
        try {
            RerankResponse resp = rerankWithRetry(new RerankRequest(modelName, query, documents, Math.min(topN, documents.size())));
            if (resp == null || resp.results() == null || resp.results().isEmpty()) {
                log.warn("SiliconFlow rerank 空结果，退原序前 {}", topN);
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }
            List<TextSegment> out = new ArrayList<>(resp.results().size());
            for (ResultItem item : resp.results()) {
                if (item.index() >= 0 && item.index() < candidates.size()) {
                    out.add(candidates.get(item.index()));
                }
            }
            // 接口保证按分数降序；若不足 topN 用剩余候选按原序补齐
            if (out.size() < Math.min(topN, candidates.size())) {
                for (TextSegment seg : candidates) {
                    if (out.size() >= topN) break;
                    if (!out.contains(seg)) out.add(seg);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("SiliconFlow rerank 失败，退原序前 {}: err={}", topN, e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    private RerankResponse rerankWithRetry(RerankRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri("/rerank")
                        .body(request)
                        .retrieve()
                        .body(RerankResponse.class);
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(Duration.ofSeconds(attempt));
            }
        }
        throw new RuntimeException("SiliconFlow rerank 调用失败（已重试 " + MAX_ATTEMPTS + " 次）", last);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }

    record RerankRequest(String model, String query, List<String> documents,
                         @JsonProperty("top_n") Integer topN) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<ResultItem> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultItem(int index, @JsonProperty("relevance_score") double relevanceScore) {
    }
}
