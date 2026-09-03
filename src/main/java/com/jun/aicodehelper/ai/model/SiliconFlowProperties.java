package com.jun.aicodehelper.ai.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SiliconFlow（硅基流动）配置：免费 bge-m3 embedding + bge-reranker-v2-m3。
 */
@Data
@Component
@ConfigurationProperties(prefix = "siliconflow")
public class SiliconFlowProperties {

    private String apiKey;

    private String baseUrl = "https://api.siliconflow.cn/v1";

    private String embeddingModel = "BAAI/bge-m3";

    private String rerankModel = "BAAI/bge-reranker-v2-m3";
}
