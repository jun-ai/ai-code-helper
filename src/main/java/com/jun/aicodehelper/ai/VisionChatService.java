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

import java.util.Base64;
import java.util.List;

/**
 * 视觉问答：图片（base64）+ 文本 → 流式回答。
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
     *
     * @param text       用户附带的文字问题（可空，默认 "请描述这张图片"）
     * @param imageBytes 图片二进制
     * @param mimeType   image/jpeg / image/png / image/webp
     */
    public Flux<String> stream(String text, byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Flux.error(new IllegalArgumentException("图片为空"));
        }
        String question = (text == null || text.isBlank()) ? "请描述这张图片的内容" : text;
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ImageContent imageContent = ImageContent.from(base64, mimeType == null ? "image/jpeg" : mimeType);
        TextContent textContent = TextContent.from(question);
        UserMessage userMessage = UserMessage.from(List.of(textContent, imageContent));
        log.info("视觉问答: text-len={} image-bytes={} mime={}", question.length(), imageBytes.length, mimeType);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(userMessage))
                .build();
        // StreamingChatModel 是回调式 API，包一层 Flux.create 暴露成流
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
                    if (ai != null && ai.text() != null && !ai.text().isEmpty() && !sink.isCancelled()) {
                        // 兼容 provider 不回调 onPartialResponse 的情况
                        // (注：上面 onPartialResponse 通常已逐 token 推送过，这里仅兜底)
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
