package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Rerank：用轻量模型（glm-4-flash）对候选段重打分排序。
 * 解析失败时按原顺序返回，保证降级可用。
 */
@Slf4j
public class Reranker {

    private static final Pattern SCORE_LINE = Pattern.compile(
            "(?:^|\\s|\\[|\\(|#)\\s*(\\d+)\\s*(?:分|\\]\\s*$|\\)\\s*$|\\.|:|$)");

    private final ChatModel chatModel;
    private final int topN;

    public Reranker(ChatModel chatModel, int topN) {
        this.chatModel = chatModel;
        this.topN = topN;
    }

    public List<TextSegment> rerank(String query, List<TextSegment> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        if (candidates.size() <= 1) {
            return candidates;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是相关性打分员。请根据用户问题，给每段候选文档打 0~10 整数分，10=最相关。\n");
        prompt.append("要求：\n");
        prompt.append("1. 严格只输出 N 行编号打分，第 i 行对应第 i 段；\n");
        prompt.append("2. 严禁任何解释、严禁额外空行；\n");
        prompt.append("3. 与问题无关的段打 0~3 分；\n");
        prompt.append("用户问题：").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).text();
            if (text.length() > 600) {
                text = text.substring(0, 600) + "…";
            }
            prompt.append("[").append(i + 1).append("] ").append(text).append("\n");
        }
        try {
            String answer = chatModel.chat(prompt.toString());
            double[] scores = parseScores(answer, candidates.size());
            List<Ranked> ranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(new Ranked(candidates.get(i), scores[i]));
            }
            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
            return ranked.stream().limit(topN).map(Ranked::segment).toList();
        } catch (Exception e) {
            log.warn("Rerank 失败，保留原顺序: err={}", e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    static double[] parseScores(String answer, int expected) {
        double[] scores = new double[expected];
        // 默认给候选一个"中位分"，避免极端值
        java.util.Arrays.fill(scores, 5.0);
        String[] lines = answer.split("\\n");
        int idx = 0;
        for (String line : lines) {
            if (idx >= expected) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 优先匹配开头的 "1." "1)" "[1]" "(1)" 等编号
            int num = -1;
            Matcher m = SCORE_LINE.matcher(trimmed);
            if (m.find()) {
                num = Integer.parseInt(m.group(1));
            } else {
                // 退化：取第一个纯数字
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
        return scores;
    }

    private record Ranked(TextSegment segment, double score) {
    }

    // 测试用
    static double[] parseScoresForTest(String answer, int expected) {
        return new Reranker(null, 0).parseScores(answer, expected);
    }
}
