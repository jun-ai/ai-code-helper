package com.jun.aicodehelper.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 智谱 GLM 对话/流式模型，走 OpenAI 兼容端点
 */
@Configuration
@ConfigurationProperties(prefix = "zhipu")
@Data
public class ZhipuModelConfig {

    private String apiKey;

    private String baseUrl;

    private String chatModel;

    /**
     * 视觉模型名称（glm-4v-flash / glm-4v-plus）。图片上传时自动切换。
     */
    private String visionModel = "glm-4v-flash";

    @Resource
    private ChatModelListener chatModelListener;

    @Bean
    public ChatModel zhipuChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(chatModel)
                .timeout(Duration.ofSeconds(60))
                .listeners(List.of(chatModelListener))
                .build();
    }

    @Bean
    public StreamingChatModel zhipuStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(chatModel)
                // 流式整段回答可能持续较久，放宽到 2 分钟
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(chatModelListener))
                .build();
    }

    @Bean
    public StreamingChatModel zhipuVisionStreamingChatModel() {
        // 视觉模型独立 bean，图片消息走这个；同 baseUrl+apiKey
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(visionModel)
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(chatModelListener))
                .build();
    }

    @Bean
    public ChatModel zhipuVisionChatModel() {
        // 同步视觉模型：图片入库 caption 用，要求一次性返回
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(visionModel)
                .timeout(Duration.ofSeconds(60))
                .listeners(List.of(chatModelListener))
                .build();
    }
}
