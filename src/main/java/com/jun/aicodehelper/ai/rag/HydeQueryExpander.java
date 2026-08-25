package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * HyDE（Hypothetical Document Embeddings）：
 * 让 LLM 写一段"假设性答案"——它是文档式语言，比用户口语 query 更接近文档语义空间。
 * 拿这个假设答案去做向量检索，常能捞到原 query 漏掉的段。
 * 失败/超时降级返回空串，由 HybridContentRetriever 跳过此路。
 */
@Slf4j
public class HydeQueryExpander {

    private final ChatModel chatModel;

    public HydeQueryExpander(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String expand(String query) {
        if (query == null || query.isBlank()) return "";
        String prompt = """
                你是知识库作者。请基于用户问题写一段 80~150 字的"假设性答案"——
                用文档式语言（陈述句、含专业术语），不必真实，但语气要像知识库条目。
                不要解释、不要编号、不要代码块，输出纯文本即可。

                用户问题：%s
                """.formatted(query);
        try {
            String answer = chatModel.chat(prompt);
            if (answer == null) return "";
            String trimmed = answer.trim();
            if (trimmed.length() > 400) {
                trimmed = trimmed.substring(0, 400);
            }
            return trimmed;
        } catch (Exception e) {
            log.warn("HyDE 生成失败: err={}", e.getMessage());
            return "";
        }
    }
}