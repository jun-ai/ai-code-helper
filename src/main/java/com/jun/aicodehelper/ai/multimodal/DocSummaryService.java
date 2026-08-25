package com.jun.aicodehelper.ai.multimodal;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档摘要：上传成功后用主 LLM 对前若干段做 ≤200 字总结。
 * 让前端可以直接展示「让 AI 总结刚刚上传的文档？」chip，省去用户再发问。
 */
@Slf4j
@Service
public class DocSummaryService {

    private static final String PROMPT = "请用一段不超过 200 字的中文总结以下文档的核心要点（不要寒暄、不要 Markdown 标题）：\n\n";

    @Resource
    private ChatModel zhipuChatModel;

    /**
     * @param text 文档提取出来的纯文本（已合并）
     * @return summary；失败返回 null
     */
    public String summarize(String text) {
        if (text == null || text.isBlank()) return null;
        // 取前 3000 字，避免长文档把主 LLM 上下文打爆
        String clipped = text.length() > 3000 ? text.substring(0, 3000) : text;
        try {
            var resp = zhipuChatModel.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(PROMPT + clipped)))
                    .build());
            String out = resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (out == null) return null;
            out = out.trim();
            if (out.length() > 400) out = out.substring(0, 400);
            return out;
        } catch (Exception e) {
            log.warn("文档摘要失败: err={}", e.getMessage());
            return null;
        }
    }
}