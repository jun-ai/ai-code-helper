package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * 流式对话降级：智谱失败 → MiniMax-M2（剥离 &lt;think&gt; 推理块）。
 * 仅在「一个字都没吐出」时才切换（首 token 前失败），中途断流直接透传错误，
 * 由前端 SSE 重试逻辑处理，避免降级重答导致内容重复拼接。
 */
@Slf4j
public class FailoverStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel primary;
    private final StreamingChatModel secondary;
    private final AppMetrics metrics;

    public FailoverStreamingChatModel(StreamingChatModel primary, StreamingChatModel secondary,
                                      AppMetrics metrics) {
        this.primary = primary;
        this.secondary = secondary;
        this.metrics = metrics;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        primary.chat(chatRequest, new StreamingChatResponseHandler() {
            private boolean emittedAny = false;

            @Override
            public void onPartialResponse(String partialResponse) {
                emittedAny = true;
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                if (emittedAny) {
                    // 中途断流：透传，前端 chatApi 会按重试策略处理
                    handler.onError(error);
                    return;
                }
                log.warn("主模型流式失败，降级 MiniMax: {}", error.getMessage());
                metrics.getLlmFailover().increment();
                ThinkStripper stripper = new ThinkStripper();
                secondary.chat(chatRequest, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        String out = stripper.feed(partialResponse);
                        if (!out.isEmpty()) {
                            handler.onPartialResponse(out);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String tail = stripper.flush();
                        if (!tail.isEmpty()) {
                            handler.onPartialResponse(tail);
                        }
                        handler.onCompleteResponse(completeResponse);
                    }

                    @Override
                    public void onError(Throwable secondaryError) {
                        // 备胎也挂：抛原始主模型错误，保留第一现场
                        handler.onError(error);
                    }
                });
            }
        });
    }
}
