package com.jun.aicodehelper.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;

/**
 * MiniMax embo-01 向量模型（原生 texts 接口，非 OpenAI 兼容格式）。
 * type 统一用 db，入库和查询共用一种向量。
 */
@Slf4j
public class MinimaxEmbeddingModel implements EmbeddingModel {

    private static final int BATCH_SIZE = 16;

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;

    private final String modelName;

    public MinimaxEmbeddingModel(String baseUrl, String apiKey, String modelName) {
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
            for (float[] vector : embedBatchWithRetry(batch)) {
                embeddings.add(Embedding.from(vector));
            }
        }
        return Response.from(embeddings);
    }

    /**
     * 单批次调用，失败按 1s/2s 退避重试（MiniMax 偶发连接重置）
     */
    private float[][] embedBatchWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return embedBatch(batch);
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("MiniMax embedding 第 {} 次调用失败，即将重试: {}", attempt, last.getMessage());
                sleep(Duration.ofSeconds(attempt));
            }
        }
        throw new RuntimeException("MiniMax embedding 调用失败（已重试 " + MAX_ATTEMPTS + " 次）", last);
    }

    private float[][] embedBatch(List<String> batch) {
        log.debug("MiniMax embed request: model={} type=db batch_size={}", modelName, batch.size());
        MinimaxEmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .body(new MinimaxEmbeddingRequest(modelName, batch, "db"))
                .retrieve()
                .body(MinimaxEmbeddingResponse.class);
        if (response == null || response.vectors() == null) {
            String message = response != null && response.baseResp() != null
                    ? response.baseResp().statusMsg()
                    : "empty response";
            throw new RuntimeException("MiniMax embedding 调用失败: " + message);
        }
        return response.vectors();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }

    record MinimaxEmbeddingRequest(String model, List<String> texts, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MinimaxEmbeddingResponse(float[][] vectors, BaseResp baseResp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BaseResp(@JsonProperty("status_code") int statusCode,
                    @JsonProperty("status_msg") String statusMsg) {
    }
}
