package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 同步对话降级：智谱失败 → MiniMax-M2，并剥离 <think> 推理块。
 * 备胎也失败时抛原始异常，由上层各自的降级逻辑兜底。
 */
@Slf4j
public class FailoverChatModel implements ChatModel {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>");

    private final ChatModel primary;
    private final ChatModel secondary;
    private final AppMetrics metrics;

    public FailoverChatModel(ChatModel primary, ChatModel secondary, AppMetrics metrics) {
        this.primary = primary;
        this.secondary = secondary;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            return primary.chat(chatRequest);
        } catch (RuntimeException e) {
            log.warn("主模型调用失败，降级 MiniMax: {}", e.getMessage());
            metrics.getLlmFailover().increment();
            ChatResponse resp = secondary.chat(chatRequest);
            return stripThink(resp);
        }
    }

    private ChatResponse stripThink(ChatResponse resp) {
        AiMessage ai = resp == null ? null : resp.aiMessage();
        if (ai == null || ai.text() == null || !ai.text().contains("<think>")) {
            return resp;
        }
        String clean = THINK_BLOCK.matcher(ai.text()).replaceAll("").trim();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(clean))
                .metadata(resp.metadata())
                .build();
    }
}
