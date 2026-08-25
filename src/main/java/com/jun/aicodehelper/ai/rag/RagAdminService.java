package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 管理面：文档列表 / 单文件删除 / 全量重建。
 *
 * 设计取舍：Milvus SDK 2.5.x 在 langchain4j 1.1.0-beta7 中没有按 metadata 字段过滤删除的
 * 官方 API（langchain4j 只暴露 removeAll）。为了避免直接调 gRPC 客户端带来的版本兼容问题，
 * 这里采用「目录视图」方案：
 *   - 内置文档（启动期 src/main/resources/docs）：只读，禁止删；
 *   - 用户上传（upload.dir）：可在管理 UI 删；删除后调用 rebuildAll 把整个库重建，
 *     保证 Milvus 与磁盘视图一致（重操作，前端需二次确认）。
 *   - 重建：removeAll + 重新导入启动期 docs 目录 + 扫描 upload.dir 把现存用户文件也入一遍。
 */
@Slf4j
@Service
public class RagAdminService {

    private static final Path BUILTIN_DIR = Paths.get("src/main/resources/docs");

    @Value("${app.upload.dir:uploads}")
    private String uploadDirProp;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private RagConfig ragConfig;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private Bm25Index bm25Index;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private AppMetrics metrics;

    public List<DocSummary> list() {
        List<DocSummary> result = new ArrayList<>();
        // 内置
        if (Files.exists(BUILTIN_DIR)) {
            try (var stream = Files.walk(BUILTIN_DIR)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".md") || lower.endsWith(".txt")) {
                        DocSummary d = new DocSummary();
                        d.fileName = name;
                        d.source = "builtin";
                        d.path = p.toAbsolutePath().toString();
                        result.add(d);
                    }
                });
            } catch (IOException e) {
                log.warn("扫描内置 docs 目录失败: {}", e.getMessage());
            }
        }
        // 上传
        Path uploadDir = Paths.get(uploadDirProp).toAbsolutePath();
        if (Files.exists(uploadDir)) {
            try (var stream = Files.list(uploadDir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    // 跳过抽帧目录残留物
                    if (name.endsWith("_frames") || name.contains("_frames/")) return;
                    DocSummary d = new DocSummary();
                    d.fileName = name;
                    d.source = "uploaded";
                    d.path = p.toAbsolutePath().toString();
                    try {
                        d.sizeBytes = Files.size(p);
                    } catch (IOException ignore) {
                    }
                    result.add(d);
                });
            } catch (IOException e) {
                log.warn("扫描上传目录失败: {}", e.getMessage());
            }
        }
        return result;
    }

    public DeleteResult deleteUploaded(String savedName) {
        if (savedName == null || savedName.isEmpty()) {
            return new DeleteResult(false, "文件名无效");
        }
        Path uploadDir = Paths.get(uploadDirProp).toAbsolutePath();
        Path target = uploadDir.resolve(savedName).normalize();
        if (!target.startsWith(uploadDir)) {
            return new DeleteResult(false, "非法路径");
        }
        if (!Files.exists(target)) {
            return new DeleteResult(false, "文件不存在");
        }
        try {
            Files.delete(target);
            // 同步删除抽帧目录（视频上传产物）
            String stem = savedName;
            int dot = savedName.lastIndexOf('.');
            if (dot > 0) stem = savedName.substring(0, dot);
            Path framesDir = uploadDir.resolve(stem + "_frames");
            if (Files.exists(framesDir)) {
                try (var s = Files.walk(framesDir)) {
                    s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignore) {}
                    });
                }
            }
            // 重建 RAG：清库 + 重新 ingest 启动期目录 + 重新入库剩余上传文件
            rebuildAll();
            log.info("RAG 管理 - 删除上传文件: file={}", savedName);
            return new DeleteResult(true, "已删除并重建索引");
        } catch (IOException e) {
            log.warn("删除上传文件失败: file={} err={}", savedName, e.getMessage());
            return new DeleteResult(false, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 重建索引：清空 Milvus collection + 重新扫描启动期 docs 目录 + 重新入库现存上传文件。
     */
    public RebuildResult rebuildAll() {
        long t0 = System.nanoTime();
        int rebuilt = 0;
        try {
            embeddingStore.removeAll();
            if (ragProperties.isBm25Enabled()) {
                bm25Index.rebuild(List.of());
            }
            // 启动期内置 docs
            ragConfig.reingestFromDir(null);
            rebuilt++;
            // 上传目录现存文件逐个入库
            Path uploadDir = Paths.get(uploadDirProp).toAbsolutePath();
            if (Files.exists(uploadDir)) {
                try (var stream = Files.list(uploadDir)) {
                    var files = stream.filter(Files::isRegularFile).toList();
                    for (Path p : files) {
                        String name = p.getFileName().toString();
                        String lower = name.toLowerCase();
                        try {
                            if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md")) {
                                ragIngestService.ingestFile(p, name);
                                rebuilt++;
                            }
                        } catch (Exception e) {
                            log.warn("重建期上传文件入库失败: file={} err={}", name, e.getMessage());
                        }
                    }
                }
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.warn("RAG 管理 - 全量重建完成: 文档数={} elapsed={}ms", rebuilt, elapsedMs);
            return new RebuildResult(true, rebuilt, elapsedMs, "重建完成");
        } catch (Throwable e) {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.error("RAG 管理 - 重建失败: {}", e.getMessage(), e);
            return new RebuildResult(false, rebuilt, elapsedMs, "重建失败: " + e.getMessage());
        }
    }

    /**
     * 文档摘要：前端列表渲染用。
     */
    public static class DocSummary {
        public String fileName;
        public String source;     // builtin / uploaded
        public String path;
        public long sizeBytes;
    }

    public record DeleteResult(boolean ok, String message) {}

    public record RebuildResult(boolean ok, int rebuilt, long elapsedMs, String message) {}
}