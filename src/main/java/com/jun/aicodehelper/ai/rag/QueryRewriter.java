package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite（MultiQuery）：用户原 query 经常表述模糊或带口语，
 * 让 LLM 扩成多个不同侧重的查询，分别检索后去重合并，提升召回覆盖率。
 * 失败/超时降级为只返回原 query，不阻塞主链路。
 */
@Slf4j
public class QueryRewriter {

    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*\\d+[\\.\\)、]\\s*(.+)$");
    private static final Pattern QUOTED_LINE = Pattern.compile("^\\s*[\\-\\*]\\s*(.+)$");

    private final ChatModel chatModel;
    private final int variants;

    public QueryRewriter(ChatModel chatModel, int variants) {
        this.chatModel = chatModel;
        this.variants = Math.max(1, variants);
    }

    public List<String> rewrite(String query) {
        if (variants <= 1) {
            return List.of(query);
        }
        String prompt = """
                你是检索查询改写助手。请根据用户问题生成 %d 个不同表述的中文检索 query，用于在本地知识库中检索相关文档片段。
                要求：
                1. 第 1 个保留原意，后面的从不同角度、同义词、近义词、专业术语等维度改写
                2. 每个 query 一行，纯文本，不要编号、不要引号、不要解释
                3. 保持简洁，每条不超过 30 字
                4. 如果问题已经很明确，至少返回原句

                用户问题：%s
                """.formatted(variants, query);
        try {
            String answer = chatModel.chat(prompt);
            List<String> rewrites = parseLines(answer);
            // 始终保留原 query 在最前，避免改写失败时丢召回
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            merged.add(query);
            merged.addAll(rewrites);
            // 截断到配置数量
            List<String> result = new ArrayList<>(merged);
            if (result.size() > variants) {
                return new ArrayList<>(result.subList(0, variants));
            }
            return result;
        } catch (Exception e) {
            log.warn("Query 改写失败，降级用原 query: err={}", e.getMessage());
            return List.of(query);
        }
    }

    private List<String> parseLines(String answer) {
        List<String> result = new ArrayList<>();
        for (String line : answer.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher m1 = NUMBERED_LINE.matcher(trimmed);
            if (m1.matches()) {
                trimmed = m1.group(1).trim();
            } else {
                Matcher m2 = QUOTED_LINE.matcher(trimmed);
                if (m2.matches()) {
                    trimmed = m2.group(1).trim();
                }
            }
            // 去引号
            trimmed = trimmed.replaceAll("^[\"「『]+|[\"」』]+$", "").trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // 测试用
    static List<String> parseLinesForTest(String answer) {
        return new QueryRewriter(null, 0).parseLines(answer);
    }
}
