package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite（MultiQuery）：用户原 query 经常表述模糊或带口语，
 * 让 LLM 扩成多个不同侧重的查询，分别检索后去重合并，提升召回覆盖率。
 *
 * 可选 history 上下文：当用户问"它呢？"这种代词指代时，传入最近 N 轮对话
 * 让改写模型正确解析指代关系，避免把无效 query 拿去检索。
 *
 * 失败/超时降级为只返回原 query，不阻塞主链路。
 */
@Slf4j
public class QueryRewriter {

    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*\\d+[\\.\\)\\)、]\\s*(.+)$");
    private static final Pattern QUOTED_LINE = Pattern.compile("^\\s*[\\-\\*]\\s*(.+)$");

    private final ChatModel chatModel;
    private final int variants;

    public QueryRewriter(ChatModel chatModel, int variants) {
        this.chatModel = chatModel;
        this.variants = Math.max(1, variants);
    }

    /**
     * 改写 query，history 可为空。
     * @param history 最近若干轮对话，每条格式 "用户：xxx\nAI：yyy"
     */
    public List<String> rewrite(String query, List<String> history) {
        if (variants <= 1) {
            return List.of(query);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是检索查询改写助手。请根据用户问题");
        if (history != null && !history.isEmpty()) {
            prompt.append("和上下文对话历史");
        }
        prompt.append("生成 %d 个不同表述的中文检索 query，用于在本地知识库中检索相关文档片段。\n");
        prompt.append("要求：\n");
        prompt.append("1. 第 1 个保留原意，后面的从不同角度、同义词、近义词、专业术语等维度改写\n");
        prompt.append("2. 每个 query 一行，纯文本，不要编号、不要引号、不要解释\n");
        prompt.append("3. 保持简洁，每条不超过 30 字\n");
        prompt.append("4. 如果问题已经很明确，至少返回原句\n");
        prompt.append("5. 用户问题中的代词（这/那/它/上面提到的）必须结合对话历史替换为明确指代\n");
        if (history != null && !history.isEmpty()) {
            prompt.append("\n对话历史（最近的 ").append(history.size()).append(" 轮）：\n");
            for (String turn : history) {
                prompt.append(turn).append("\n");
            }
        }
        prompt.append("\n用户问题：").append(query);

        try {
            String answer = chatModel.chat(prompt.toString());
            List<String> rewrites = parseLines(answer);
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            merged.add(query);
            merged.addAll(rewrites);
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

    /** 单参数版本，向后兼容 */
    public List<String> rewrite(String query) {
        return rewrite(query, List.of());
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