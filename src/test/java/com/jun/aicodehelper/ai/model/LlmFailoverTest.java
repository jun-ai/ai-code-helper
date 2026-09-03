package com.jun.aicodehelper.ai.model;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 降级链路：think 剥离（跨 chunk）、同步/流式降级触发与透传规则
 */
class LlmFailoverTest {

    private final AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());

    private ChatRequest req() {
        return ChatRequest.builder().messages(List.of(dev.langchain4j.data.message.UserMessage.from("hi"))).build();
    }

    @Test
    void think标签跨chunk切分也应被完整剥离() {
        ThinkStripper s = new ThinkStripper();
        StringBuilder out = new StringBuilder();
        // </think> 被切成 "</thi" + "nk>"，且思考内容里也有干扰词
        out.append(s.feed("<think>让我想想</thi"));
        out.append(s.feed("nk>答案是 42<think>再想想</think>最终"));
        out.append(s.flush());
        assertEquals("答案是 42最终", out.toString());
    }

    @Test
    void 无think内容原样透传() {
        ThinkStripper s = new ThinkStripper();
        assertEquals("正常内容", s.feed("正常内容") + s.flush());
    }

    @Test
    void 同步主模型失败应降级并剥离think() {
        ChatModel failPrimary = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException("zhipu down");
            }
        };
        ChatModel okSecondary = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("<think>思考</think>降级回答")).build();
            }
        };
        FailoverChatModel m = new FailoverChatModel(failPrimary, okSecondary, metrics);

        String text = m.chat(req()).aiMessage().text();

        assertEquals("降级回答", text);
        assertEquals(1, metrics.getLlmFailover().count());
    }

    @Test
    void 流式首token前失败应降级且剥离think() {
        StreamingChatModel failPrimary = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onError(new RuntimeException("zhipu down"));
            }
        };
        StreamingChatModel okSecondary = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("<think>推理");
                handler.onPartialResponse("过程</think>降级");
                handler.onPartialResponse("回答");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("降级回答")).build());
            }
        };
        List<String> received = new ArrayList<>();
        List<Object> completed = new ArrayList<>();
        new FailoverStreamingChatModel(failPrimary, okSecondary, metrics)
                .chat(req(), new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String p) { received.add(p); }
                    @Override public void onCompleteResponse(ChatResponse c) { completed.add(c); }
                    @Override public void onError(Throwable e) { throw new RuntimeException(e); }
                });

        assertEquals("降级回答", String.join("", received));
        assertEquals(1, completed.size());
        assertEquals(1, metrics.getLlmFailover().count());
    }

    @Test
    void 流式中途断流应透传错误不降级() {
        StreamingChatModel midFailPrimary = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("已输出的");
                handler.onError(new RuntimeException("mid-stream down"));
            }
        };
        List<String> errors = new ArrayList<>();
        new FailoverStreamingChatModel(midFailPrimary, null, metrics)
                .chat(req(), new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String p) { }
                    @Override public void onCompleteResponse(ChatResponse c) { }
                    @Override public void onError(Throwable e) { errors.add(e.getMessage()); }
                });

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("mid-stream down"));
        assertEquals(0, metrics.getLlmFailover().count());
    }
}
