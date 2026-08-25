package com.jun.aicodehelper.ai.multimodal;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 图片 caption：调 GLM-4V 一次性生成 ≤200 字中文描述，
 * 入 RAG 库用。失败时 fallback 到文件名占位，避免阻塞入库。
 */
@Slf4j
@Service
public class ImageCaptionService {

    private static final String PROMPT = "请用一段不超过 200 字的中文描述这张图片的关键内容：物体、文字、场景、用途等可检索要素。不要寒暄，不要 Markdown。";

    @Resource
    private ChatModel zhipuVisionChatModel;

    /**
     * @return caption 文本；失败返回 null
     */
    public String caption(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        try {
            ChatResponse resp = zhipuVisionChatModel.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(List.of(
                            TextContent.from(PROMPT),
                            ImageContent.from(base64, mimeType == null ? "image/jpeg" : mimeType)
                    ))))
                    .build());
            String text = resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (text == null) return null;
            text = text.trim();
            if (text.length() > 400) text = text.substring(0, 400);
            return text;
        } catch (Exception e) {
            log.warn("图片 caption 生成失败: bytes={} mime={} err={}", imageBytes.length, mimeType, e.getMessage());
            return null;
        }
    }
}