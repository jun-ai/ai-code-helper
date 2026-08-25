package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MiniMax 模型配置（当前用于 RAG 向量）
 */
@Configuration
@ConfigurationProperties(prefix = "minimax")
@Data
public class MinimaxModelConfig {

    private String apiKey;

    private String baseUrl;

    private String embeddingModel;

    @Bean
    public EmbeddingModel minimaxEmbeddingModel(AppMetrics metrics) {
        return new CachingEmbeddingModel(new MinimaxEmbeddingModel(baseUrl, apiKey, embeddingModel), metrics);
    }
}
