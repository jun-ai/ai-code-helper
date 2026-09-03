package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 对话模型降级装配：主（智谱 glm-4-flash）→ 备（MiniMax-M2）。
 * llm.failover-enabled=false 时直接透传主模型。仅主聊天链路（AiCodeHelperService）
 * 走降级；改写/摘要/视觉等内部调用失败时各自有独立降级，不包这层。
 */
@Slf4j
@Configuration
public class LlmFailoverConfig {

    @Value("${llm.failover-enabled:true}")
    private boolean failoverEnabled;

    @Value("${minimax.api-key:}")
    private String minimaxApiKey;

    @Value("${minimax.base-url:https://api.minimaxi.com/v1}")
    private String minimaxBaseUrl;

    @Value("${minimax.chat-model:MiniMax-M2}")
    private String minimaxChatModel;

    @Bean
    public ChatModel failoverChatModel(@Qualifier("zhipuChatModel") ChatModel primary,
                                       AppMetrics metrics) {
        if (!failoverEnabled) {
            return primary;
        }
        log.info("LLM 降级开启: 主=智谱 备=MiniMax({})", minimaxChatModel);
        return new FailoverChatModel(primary, minimaxChatModel(metrics), metrics);
    }

    @Bean
    public StreamingChatModel failoverStreamingChatModel(@Qualifier("zhipuStreamingChatModel") StreamingChatModel primary,
                                                         AppMetrics metrics) {
        if (!failoverEnabled) {
            return primary;
        }
        return new FailoverStreamingChatModel(primary, minimaxStreamingChatModel(metrics), metrics);
    }

    private ChatModel minimaxChatModel(AppMetrics metrics) {
        return OpenAiChatModel.builder()
                .baseUrl(minimaxBaseUrl)
                .apiKey(minimaxApiKey)
                .modelName(minimaxChatModel)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private StreamingChatModel minimaxStreamingChatModel(AppMetrics metrics) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(minimaxBaseUrl)
                .apiKey(minimaxApiKey)
                .modelName(minimaxChatModel)
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}
