package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * 内存 BM25 全文索引：CJK 双字 gram + 英文/数字单词混合切词，
 * 给专有名词（Dubbo / HMAC-SHA256）兜底召回，纯向量容易漏的硬词这里捞。
 * 上传文档后通过 add() 增量追加，并发读由读写锁保护。
 */
@Slf4j
@Component
public class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** term -> docId -> tf */
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    /** docId -> doc length (token count) */
    private final Map<Integer, Integer> docLen = new HashMap<>();
    /** docId -> segment text */
    private final Map<Integer, TextSegment> docs = new HashMap<>();
    /** doc freq */
    private final Map<String, Integer> df = new HashMap<>();
    private int totalDocs = 0;
    private double avgDocLen = 0;
    private int nextDocId = 0;

    public void rebuild(List<TextSegment> segments) {
        lock.writeLock().lock();
        try {
            postings.clear();
            docLen.clear();
            docs.clear();
            df.clear();
            totalDocs = 0;
            avgDocLen = 0;
            nextDocId = 0;
            for (TextSegment seg : segments) {
                addLocked(seg);
            }
            log.info("BM25 索引重建: 段数={} 词项={}", totalDocs, postings.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void add(TextSegment seg) {
        lock.writeLock().lock();
        try {
            addLocked(seg);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addLocked(TextSegment seg) {
        int id = nextDocId++;
        List<String> tokens = tokenize(seg.text());
        docLen.put(id, tokens.size());
        docs.put(id, seg);
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            postings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(id, e.getValue());
        }
        for (String t : tf.keySet()) {
            df.merge(t, 1, Integer::sum);
        }
        totalDocs++;
        avgDocLen = ((avgDocLen * (totalDocs - 1)) + tokens.size()) / totalDocs;
    }

    public List<Scored> search(String query, int topK) {
        lock.readLock().lock();
        try {
            if (totalDocs == 0) {
                return List.of();
            }
            List<String> tokens = tokenize(query);
            if (tokens.isEmpty()) {
                return List.of();
            }
            Map<Integer, Double> scores = new HashMap<>();
            for (String term : tokens) {
                Map<Integer, Integer> posting = postings.get(term);
                if (posting == null) {
                    continue;
                }
                int n = df.get(term);
                double idf = Math.log(1 + (totalDocs - n + 0.5) / (n + 0.5));
                for (Map.Entry<Integer, Integer> e : posting.entrySet()) {
                    int docId = e.getKey();
                    int tf = e.getValue();
                    int dl = docLen.get(docId);
                    double norm = K1 * (1 - B + B * dl / Math.max(avgDocLen, 1));
                    double s = idf * (tf * (K1 + 1)) / (tf + norm);
                    scores.merge(docId, s, Double::sum);
                }
            }
            return scores.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(e -> new Scored(docs.get(e.getKey()), e.getValue()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return totalDocs;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 删除 file_name 匹配的所有段（与 Milvus 删除同步走）。
     * 返回被删段数。
     */
    public int removeByFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return 0;
        lock.writeLock().lock();
        try {
            // 收集要删的 docId
            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, TextSegment> e : docs.entrySet()) {
                String fn = e.getValue().metadata().getString("file_name");
                if (fileName.equals(fn)) {
                    toRemove.add(e.getKey());
                }
            }
            if (toRemove.isEmpty()) return 0;
            for (int id : toRemove) {
                TextSegment seg = docs.remove(id);
                docLen.remove(id);
                // 重建 tf，倒着删 postings 和 df
                List<String> tokens = tokenize(seg.text());
                Map<String, Integer> tf = new HashMap<>();
                for (String t : tokens) {
                    tf.merge(t, 1, Integer::sum);
                }
                for (String t : tf.keySet()) {
                    Map<Integer, Integer> post = postings.get(t);
                    if (post != null) {
                        post.remove(id);
                        if (post.isEmpty()) postings.remove(t);
                    }
                    int cur = df.getOrDefault(t, 0);
                    int next = cur - 1;
                    if (next <= 0) {
                        df.remove(t);
                    } else {
                        df.put(t, next);
                    }
                }
                totalDocs--;
            }
            if (totalDocs > 0) {
                int totalLen = docLen.values().stream().mapToInt(Integer::intValue).sum();
                avgDocLen = (double) totalLen / totalDocs;
            } else {
                avgDocLen = 0;
            }
            log.info("BM25 按文件删除: file={} 段数={}", fileName, toRemove.size());
            return toRemove.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 简易 tokenizer：CJK 按双字 gram 切，英文/数字按单词切，混合拼接。
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        // CJK 双字 gram
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk.append(c);
            } else {
                flushCjk(cjk, tokens);
                // 英文/数字：尽量吃一段连续字母数字
                int j = i;
                while (j < text.length() && TOKEN.matcher(text.substring(j, j + 1)).matches()) {
                    j++;
                }
                if (j > i) {
                    String word = text.substring(i, j).toLowerCase();
                    if (word.length() > 1) {
                        tokens.add(word);
                    }
                    i = j - 1;
                }
            }
        }
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushCjk(StringBuilder cjk, List<String> sink) {
        for (int i = 0; i + 1 < cjk.length(); i++) {
            sink.add(cjk.substring(i, i + 2));
        }
        cjk.setLength(0);
    }

    private static boolean isCjk(char c) {
        return (c >= '一' && c <= '鿿')
                || (c >= '㐀' && c <= '䶿')
                || (c >= '豈' && c <= '﫿');
    }

    public record Scored(TextSegment segment, double score) {
    }
}
