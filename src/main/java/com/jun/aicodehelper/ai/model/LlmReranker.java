package com.jun.aicodehelper.ai.model;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Rerank（glm-4-flash 打分）：SiliconFlow rerank 不可用时的降级路径。
 * 解析行数不足视为整批失败退原序，避免错位打分污染排序。
 */
@Slf4j
public class LlmReranker implements RerankModel {

    private static final Pattern SCORE_LINE = Pattern.compile(
            "(?:^|\\s|\\[|\\(|#)\\s*(\\d+)\\s*(?:分|\\]\\s*$|\\)\\s*$|\\.|:|$)");

    /** 与切分器父块上限一致：截断过短会让长段关键信息丢失 */
    private static final int MAX_DOC_CHARS = 1000;

    private final ChatModel chatModel;

    public LlmReranker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<TextSegment> rerank(String query, List<TextSegment> candidates, int topN) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是相关性打分员。请根据用户问题，给每段候选文档打 0~10 整数分，10=最相关。\n");
        prompt.append("要求：\n");
        prompt.append("1. 严格只输出 ").append(candidates.size()).append(" 行打分，第 i 行形如「i. 分数」，与第 i 段一一对应；\n");
        prompt.append("2. 严禁任何解释、严禁额外空行；\n");
        prompt.append("3. 与问题无关的段打 0~3 分；\n");
        prompt.append("用户问题：").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).text();
            if (text.length() > MAX_DOC_CHARS) {
                text = text.substring(0, MAX_DOC_CHARS) + "…";
            }
            prompt.append("[").append(i + 1).append("] ").append(text).append("\n");
        }
        try {
            String answer = chatModel.chat(prompt.toString());
            double[] scores = parseScores(answer, candidates.size());
            if (scores == null) {
                log.warn("LlmRerank 解析行数不足，退原序前 {}", topN);
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }
            List<Ranked> ranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(new Ranked(candidates.get(i), scores[i]));
            }
            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
            return ranked.stream().limit(topN).map(Ranked::segment).toList();
        } catch (Exception e) {
            log.warn("LlmRerank 失败，保留原顺序: err={}", e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    /**
     * 解析打分行。解析出的行数不足 8 成视为整批失败返回 null（退原序），
     * 防止部分解析导致编号错位、整批排序被污染。
     */
    static double[] parseScores(String answer, int expected) {
        double[] scores = new double[expected];
        java.util.Arrays.fill(scores, 5.0);
        int idx = 0;
        for (String line : answer.split("\\n")) {
            if (idx >= expected) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int num = -1;
            Matcher m = SCORE_LINE.matcher(trimmed);
            if (m.find()) {
                num = Integer.parseInt(m.group(1));
            } else {
                Matcher m2 = Pattern.compile("(\\d+)").matcher(trimmed);
                if (m2.find()) {
                    num = Integer.parseInt(m2.group(1));
                }
            }
            if (num >= 0 && num <= 10) {
                scores[idx] = num;
                idx++;
            }
        }
        return idx >= Math.max(1, expected * 4 / 5) ? scores : null;
    }

    private record Ranked(TextSegment segment, double score) {
    }

    // 测试用
    static double[] parseScoresForTest(String answer, int expected) {
        return new LlmReranker(null).parseScores(answer, expected);
    }
}
