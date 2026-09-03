package com.jun.aicodehelper.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SiliconFlow embedding（OpenAI 兼容 /embeddings）：BAAI/bge-m3，1024 维。
 * 客户端骨架与 MinimaxEmbeddingModel 一致：批 16 + 3 次退避重试。
 */
@Slf4j
public class SiliconFlowEmbeddingModel implements EmbeddingModel {

    private static final int BATCH_SIZE = 16;
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final String modelName;

    public SiliconFlowEmbeddingModel(String baseUrl, String apiKey, String modelName) {
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
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = textSegments.stream().map(TextSegment::text).toList();
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            EmbeddingResponse response = embedBatchWithRetry(batch);
            // OpenAI 兼容格式 data[].index 标记原始位置，按 index 排序还原批次内顺序
            response.data().sort(Comparator.comparingInt(EmbeddingItem::index));
            for (EmbeddingItem item : response.data()) {
                embeddings.add(Embedding.from(item.embedding()));
            }
        }
        return Response.from(embeddings);
    }

    private EmbeddingResponse embedBatchWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                EmbeddingResponse response = restClient.post()
                        .uri("/embeddings")
                        .body(new EmbeddingRequest(modelName, batch))
                        .retrieve()
                        .body(EmbeddingResponse.class);
                if (response == null || response.data() == null || response.data().size() != batch.size()) {
                    throw new RuntimeException("SiliconFlow embedding 返回数量不符: 期望 "
                            + batch.size() + " 实际 " + (response == null || response.data() == null ? 0 : response.data().size()));
                }
                return response;
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("SiliconFlow embedding 第 {} 次调用失败，即将重试: {}", attempt, last.getMessage());
                sleep(Duration.ofSeconds(attempt));
            }
        }
        throw new RuntimeException("SiliconFlow embedding 调用失败（已重试 " + MAX_ATTEMPTS + " 次）", last);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }

    record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingItem(int index, float[] embedding) {
    }
}
