package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual Compression：rerank 之后，让 LLM 把每段压成 1~2 句"事实摘要"，
 * 主 LLM 拿到的上下文信噪比明显提升，token 也省。
 * 失败/超时时保留原段。
 */
@Slf4j
public class ContextualCompressor {

    private final ChatModel chatModel;
    private final int maxChars;

    public ContextualCompressor(ChatModel chatModel, int maxChars) {
        this.chatModel = chatModel;
        this.maxChars = Math.max(80, maxChars);
    }

    public List<TextSegment> compress(String query, List<TextSegment> segments) {
        if (segments.isEmpty()) {
            return segments;
        }
        List<TextSegment> result = new ArrayList<>(segments.size());
        for (TextSegment seg : segments) {
            result.add(compressOne(query, seg));
        }
        return result;
    }

    private TextSegment compressOne(String query, TextSegment seg) {
        String original = seg.text();
        if (original.length() <= maxChars) {
            return seg;
        }
        String prompt = """
                你是信息压缩助手。请根据用户问题，从下列文档片段中抽取与问题直接相关的关键事实，浓缩成不超过 %d 字的中文摘要。
                要求：
                1. 只保留与问题相关的句子，删掉无关内容；
                2. 保留原文中的人名、数字、术语等关键信息；
                3. 不要解释、不要引言，直出摘要；
                4. 如果片段与问题无关，输出"[无关]"。

                用户问题：%s

                文档片段：
                %s
                """.formatted(maxChars, query, original);
        try {
            String compressed = chatModel.chat(prompt).trim();
            if (compressed.isEmpty() || compressed.startsWith("[无关]") || compressed.startsWith("[无关]")) {
                // 与问题无关就丢掉——但保留前缀以便来源标注
                String prefix = extractPrefix(original);
                return TextSegment.from(prefix + "\n[无关]", seg.metadata());
            }
            String prefix = extractPrefix(original);
            return TextSegment.from(prefix + "\n" + compressed, seg.metadata());
        } catch (Exception e) {
            log.warn("压缩失败保留原段: err={}", e.getMessage());
            return seg;
        }
    }

    /**
     * 提取「文件名 | 标题」前缀行（切分器写入），压缩后继续携带以保留来源标注。
     */
    static String extractPrefix(String text) {
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return "";
        }
        String first = text.substring(0, nl);
        if (first.contains(" | ")) {
            return first;
        }
        return "";
    }
}
