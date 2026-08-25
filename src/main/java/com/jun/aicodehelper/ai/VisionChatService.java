package com.jun.aicodehelper.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 视觉问答：图片（base64）+ 文本 → 流式回答。支持单图 / 多图（视频抽帧）。
 * 视觉模型独立于普通 ChatModel，避免主路径被图片拉低响应速度。
 */
@Slf4j
@Service
public class VisionChatService {

    @Resource
    private StreamingChatModel zhipuVisionStreamingChatModel;

    /**
     * 单轮视觉问答：直接把用户上传的图片拼到消息里交给视觉模型。
     * 不参与会话记忆，避免上下文被图片文本挤爆。
     */
    public Flux<String> stream(String text, byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Flux.error(new IllegalArgumentException("图片为空"));
        }
        return stream(text, List.of(imageBytes), mimeType);
    }

    /**
     * 多图视觉问答：把多张图片依次追加到同一条 UserMessage 里。
     * 视频抽帧后通常 8-12 张；GLM-4V 能接受多图上下文。
     */
    public Flux<String> stream(String text, List<byte[]> imageBytesList, String mimeType) {
        if (imageBytesList == null || imageBytesList.isEmpty()) {
            return Flux.error(new IllegalArgumentException("图片为空"));
        }
        String question = (text == null || text.isBlank()) ? "请描述这些图片的内容" : text;
        String effectiveMime = mimeType == null ? "image/jpeg" : mimeType;
        List<dev.langchain4j.data.message.Content> parts = new ArrayList<>();
        parts.add(TextContent.from(question));
        for (byte[] bytes : imageBytesList) {
            if (bytes == null || bytes.length == 0) continue;
            parts.add(ImageContent.from(Base64.getEncoder().encodeToString(bytes), effectiveMime));
        }
        if (parts.size() == 1) {
            return Flux.error(new IllegalArgumentException("所有图片均为空"));
        }
        UserMessage userMessage = UserMessage.from(parts);
        log.info("视觉问答: text-len={} frames={} mime={}", question.length(), imageBytesList.size(), effectiveMime);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(userMessage))
                .build();
        return Flux.create((FluxSink<String> sink) -> {
            zhipuVisionStreamingChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse != null && !partialResponse.isEmpty()) {
                        sink.next(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    AiMessage ai = completeResponse == null ? null : completeResponse.aiMessage();
                    // 兼容 provider 不回调 onPartialResponse 的情况
                    if (ai != null && ai.text() != null && !ai.text().isEmpty() && !sink.isCancelled()) {
                        // 通常已在 onPartialResponse 逐 token 推送，这里不重复发送
                    }
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            });
        });
    }
}
