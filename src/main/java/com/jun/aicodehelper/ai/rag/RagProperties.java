package com.jun.aicodehelper.ai.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 各阶段开关与阈值：默认走最稳的"向量+BM25 融合 + rerank + 压缩"全流程；
 * 任意阶段单独关掉即可降级回原版，省心调参。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 向量召回 topK，越大 rerank 越有空间 */
    private int vectorTopK = 20;

    /** 向量召回最小 COSINE 分数，低于该值的段直接丢弃 */
    private double vectorMinScore = 0.5;

    /** 关键词权重（RRF 后与向量分融合时的权重），1 - keywordWeight 给向量 */
    private double keywordWeight = 0.3;

    /** 最终进入主 LLM 的段数（rerank 后） */
    private int finalTopK = 5;

    /** 最终过滤最低综合分（向量+关键词加权后） */
    private double finalMinScore = 0.35;

    /** BM25 召回 topK */
    private int bm25TopK = 20;

    /** 是否启用 BM25 关键词召回；关掉即纯向量 */
    private boolean bm25Enabled = true;

    /** 是否启用 Rerank；关掉即按融合分排序直接截断 */
    private boolean rerankEnabled = true;

    /** Rerank 阶段使用的最小候选数，少于该值不重排序（避免浪费 LLM 调用） */
    private int rerankMinCandidates = 5;

    /** 是否启用上下文压缩；关掉即原段直送 LLM */
    private boolean compressEnabled = true;

    /** 压缩后单段目标字数上限 */
    private int compressMaxChars = 200;

    /** Query 改写：是否启用；关掉即原 query 检索 */
    private boolean queryRewriteEnabled = true;

    /** Query 改写：生成几个变体（含原 query） */
    private int queryRewriteVariants = 3;
}
