package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import com.jun.aicodehelper.oss.OssProperties;
import com.jun.aicodehelper.oss.OssService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 管理面（OSS 化）：文档列表 / 单文件删除 / 全量重建。
 *
 * 上传文件已迁移到 OSS，本服务改用 OssService 操作对象 key；
 * 内置文档仍走 src/main/resources/docs（启动期只读）。
 *
 * 注意：单个文档的 metadata.file_name 现在保存的是 OSS key，删除时按 key 精确定位。
 */
@Slf4j
@Service
public class RagAdminService {

    private static final Path BUILTIN_DIR = Paths.get("src/main/resources/docs");

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

    @Resource
    private OssService ossService;

    @Resource
    private OssProperties ossProperties;

    public List<DocSummary> list() {
        List<DocSummary> result = new ArrayList<>();
        // 1. 内置文档
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
        // 2. 上传文档（OSS）：OSS 未启用时降级为只返回内置，不抛 500
        if (!ossService.isAvailable()) {
            log.debug("OSS 未启用，跳过上传文档列表");
            return result;
        }
        try {
            String prefix = ossProperties.getKeyPrefix() + "/";
            for (OssService.OssObject obj : ossService.listByPrefix(prefix)) {
                String key = obj.getKey();
                // 跳过非文本类（图片、视频帧等）
                String lower = key.toLowerCase();
                if (!(lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".md") || lower.endsWith(".txt"))) {
                    continue;
                }
                DocSummary d = new DocSummary();
                d.fileName = key;  // 删除按 key 定位
                d.source = "uploaded";
                d.path = key;
                d.sizeBytes = obj.getSize();
                result.add(d);
            }
        } catch (Exception e) {
            log.warn("OSS 列表拉取失败，降级返回内置文档: {}", e.getMessage());
        }
        return result;
    }

    public DeleteResult deleteUploaded(String savedName) {
        if (savedName == null || savedName.isEmpty()) {
            return new DeleteResult(false, "文件名无效");
        }
        if (!ossService.isAvailable()) {
            return new DeleteResult(false, "OSS 未启用，无法删除上传文件");
        }
        String key = resolveKey(savedName);
        try {
            ossService.delete(key);
            // 同步删除抽帧目录（视频上传产物，键在 public/ 前缀下）
            String base = key.substring(key.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                String stem = base.substring(0, dot);
                String framePrefix = ossProperties.getPublicPrefix() + "/" + stem + "_frames/";
                for (OssService.OssObject obj : ossService.listByPrefix(framePrefix)) {
                    ossService.delete(obj.getKey());
                }
            }
            rebuildAll();
            log.info("RAG 管理 - 删除上传文件: key={}", key);
            return new DeleteResult(true, "已删除并重建索引");
        } catch (Exception e) {
            log.warn("删除上传文件失败: key={} err={}", key, e.getMessage());
            return new DeleteResult(false, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 重建索引：清空 Milvus collection + 重新扫描启动期 docs 目录 + 重新入库现存 OSS 对象。
     * OSS 未启用时只重建内置文档。
     */
    public RebuildResult rebuildAll() {
        long t0 = System.nanoTime();
        int rebuilt = 0;
        try {
            embeddingStore.removeAll();
            if (ragProperties.isBm25Enabled()) {
                bm25Index.rebuild(List.of());
            }
            ragConfig.reingestFromDir(null);
            rebuilt++;
            if (!ossService.isAvailable()) {
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                log.warn("RAG 管理 - 重建完成（OSS 未启用，仅内置文档）: 文档数={} elapsed={}ms", rebuilt, elapsedMs);
                return new RebuildResult(true, rebuilt, elapsedMs, "重建完成（OSS 未启用，仅重建内置文档）");
            }
            String prefix = ossProperties.getKeyPrefix() + "/";
            for (OssService.OssObject obj : ossService.listByPrefix(prefix)) {
                String key = obj.getKey();
                String lower = key.toLowerCase();
                if (!(lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md"))) {
                    continue;
                }
                try (InputStream in = ossService.openStream(key)) {
                    ragIngestService.ingestFile(in, key);
                    rebuilt++;
                } catch (Exception e) {
                    log.warn("重建期上传文件入库失败: key={} err={}", key, e.getMessage());
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

    /** 兼容旧传参：纯文件名自动补 key-prefix 前缀；含 / 则视为完整 key */
    private String resolveKey(String savedName) {
        if (savedName.contains("/")) return savedName;
        return ossProperties.getKeyPrefix() + "/" + savedName;
    }

    public static class DocSummary {
        public String fileName;
        public String source;     // builtin / uploaded
        public String path;
        public long sizeBytes;
    }

    public record DeleteResult(boolean ok, String message) {}

    public record RebuildResult(boolean ok, int rebuilt, long elapsedMs, String message) {}
}