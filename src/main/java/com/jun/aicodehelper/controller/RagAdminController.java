package com.jun.aicodehelper.controller;

import com.jun.aicodehelper.ai.rag.RagAdminService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API：文档列表 / 单文件删除 / 全量重建。
 * 走 /api/rag 前缀；前端 SettingsDrawer 的"知识库"面板调用。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
public class RagAdminController {

    @Resource
    private RagAdminService ragAdminService;

    @GetMapping("/docs")
    public Map<String, Object> list() {
        long t0 = System.nanoTime();
        List<RagAdminService.DocSummary> docs = ragAdminService.list();
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> resp = new HashMap<>();
        resp.put("docs", docs);
        resp.put("total", docs.size());
        resp.put("elapsedMs", elapsed);
        log.info("RAG 管理 - 文档列表: total={} elapsed={}ms", docs.size(), elapsed);
        return resp;
    }

    @DeleteMapping("/docs/{fileName}")
    public Map<String, Object> delete(@PathVariable String fileName) {
        long t0 = System.nanoTime();
        RagAdminService.DeleteResult result = ragAdminService.deleteUploaded(fileName);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> resp = new HashMap<>();
        resp.put("fileName", fileName);
        resp.put("ok", result.ok());
        resp.put("message", result.message());
        resp.put("elapsedMs", elapsed);
        log.info("RAG 管理 - 删除: file={} ok={} elapsed={}ms", fileName, result.ok(), elapsed);
        return resp;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        RagAdminService.RebuildResult result = ragAdminService.rebuildAll();
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", result.ok());
        resp.put("rebuilt", result.rebuilt());
        resp.put("elapsedMs", result.elapsedMs());
        resp.put("message", result.message());
        log.warn("RAG 管理 - 重建接口响应: ok={} rebuilt={} elapsed={}ms",
                result.ok(), result.rebuilt(), result.elapsedMs());
        return resp;
    }
}