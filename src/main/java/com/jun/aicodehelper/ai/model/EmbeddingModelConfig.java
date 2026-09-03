package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 装配：按 rag.embedding-provider 二选一，统一包 CachingEmbeddingModel。
 * siliconflow 被选中但 api-key 为空时回退 minimax，保证服务能起来（启动不阻塞哲学）。
 */
@Slf4j
@Configuration
public class EmbeddingModelConfig {

    @Value("${rag.embedding-provider:minimax}")
    private String provider;

    @Value("${minimax.api-key:}")
    private String minimaxApiKey;

    @Value("${minimax.base-url:https://api.minimaxi.com/v1}")
    private String minimaxBaseUrl;

    @Value("${minimax.embedding-model:embo-01}")
    private String minimaxEmbeddingModel;

    private final SiliconFlowProperties siliconFlowProperties;

    public EmbeddingModelConfig(SiliconFlowProperties siliconFlowProperties) {
        this.siliconFlowProperties = siliconFlowProperties;
    }

    @Bean
    public EmbeddingModel embeddingModel(AppMetrics metrics) {
        boolean siliconflow = "siliconflow".equalsIgnoreCase(provider);
        if (siliconflow && (siliconFlowProperties.getApiKey() == null || siliconFlowProperties.getApiKey().isBlank())) {
            log.warn("rag.embedding-provider=siliconflow 但 siliconflow.api-key 为空，回退 minimax（embo-01）。"
                    + "注意：回退时 Milvus 集合维度必须与所选模型一致！");
            siliconflow = false;
        }
        if (siliconflow) {
            log.info("Embedding provider: siliconflow model={}", siliconFlowProperties.getEmbeddingModel());
            return new CachingEmbeddingModel(new SiliconFlowEmbeddingModel(
                    siliconFlowProperties.getBaseUrl(),
                    siliconFlowProperties.getApiKey(),
                    siliconFlowProperties.getEmbeddingModel()), metrics);
        }
        log.info("Embedding provider: minimax model={}", minimaxEmbeddingModel);
        return new CachingEmbeddingModel(new MinimaxEmbeddingModel(
                minimaxBaseUrl, minimaxApiKey, minimaxEmbeddingModel), metrics);
    }
}
