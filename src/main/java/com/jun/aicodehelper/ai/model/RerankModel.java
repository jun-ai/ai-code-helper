package com.jun.aicodehelper.ai.model;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Rerank 抽象：实现方按与 query 的相关度对候选段重排并截取 topN。
 * 实现必须自带降级（失败退原序），不允许向调用方抛异常中断检索。
 */
public interface RerankModel {

    List<TextSegment> rerank(String query, List<TextSegment> candidates, int topN);
}
