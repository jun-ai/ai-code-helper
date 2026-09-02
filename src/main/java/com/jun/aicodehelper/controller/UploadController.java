package com.jun.aicodehelper.controller;

import com.jun.aicodehelper.ai.multimodal.DocSummaryService;
import com.jun.aicodehelper.ai.multimodal.VideoFrameExtractor;
import com.jun.aicodehelper.ai.rag.RagIngestService;
import com.jun.aicodehelper.oss.OssProperties;
import com.jun.aicodehelper.oss.OssService;
import com.jun.aicodehelper.oss.PresignKeyRegistry;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OSS 签名直传链路：
 *   1) POST /api/upload/presign?fileName=&size=&contentType= → {uploadUrl, key, publicUrl}
 *   2) 前端 PUT 文件到 uploadUrl
 *   3) POST /api/upload/finish body={key, fileName, size, mimeType} → 触发 ingestion，返回兼容旧 res 字段
 *
 * 文档类走私有 uploads/ 前缀（需签名 GET），图片/视频帧走 public/ 前缀（直接公网 URL）。
 */
@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final long MAX_FILE_SIZE = 50 * 1024L * 1024L;

    private static final Set<String> INDEXABLE = Set.of(".pdf", ".docx", ".txt", ".md");
    private static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif");
    private static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".mkv", ".avi", ".webm", ".flv");

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy/MM");

    @Resource
    private OssService ossService;

    @Resource
    private OssProperties ossProperties;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private VideoFrameExtractor videoFrameExtractor;

    @Resource
    private DocSummaryService docSummaryService;

    @Resource
    private PresignKeyRegistry presignKeyRegistry;

    // ---------- 1. 签名 ----------

    @PostMapping("/presign")
    public ResponseEntity<Map<String, Object>> presign(
            @RequestParam("fileName") String fileName,
            @RequestParam("size") long size,
            @RequestParam(value = "contentType", required = false) String contentType) {
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(error("fileName 不能为空"));
        }
        if (size <= 0 || size > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(error("文件大小非法（0 < size ≤ 50MB）"));
        }
        String ext = extractExt(fileName);
        String key = buildKey(ext, fileName, null);
        OssService.PresignResult presign = ossService.presignPut(key, contentType);
        // 登记签发的 key：finish 只认登记过的，防止拿任意 key 换签名下载 URL
        presignKeyRegistry.register(key, ossProperties.getPresignExpireSeconds() * 1000L + 60_000L);
        Map<String, Object> body = new HashMap<>();
        body.put("uploadUrl", presign.getUploadUrl());
        body.put("key", presign.getKey());
        body.put("publicUrl", presign.getPublicUrl());
        body.put("expiresIn", presign.getExpiresIn());
        return ResponseEntity.ok(body);
    }

    // ---------- 2. 完成通知 + ingestion ----------

    @PostMapping("/finish")
    public ResponseEntity<Map<String, Object>> finish(@RequestBody FinishRequest req) {
        if (req == null || req.key == null || req.fileName == null) {
            return ResponseEntity.badRequest().body(error("缺少 key 或 fileName"));
        }
        // key 必须是本服务 presign 签发的（保留到 presign 过期 + 缓冲，finish 失败重试不受影响）
        if (!presignKeyRegistry.isIssued(req.key)) {
            return ResponseEntity.badRequest().body(error("key 无效或已过期，请重新上传获取"));
        }
        String originalName = req.fileName;
        String ext = extractExt(originalName);
        Map<String, Object> result = new HashMap<>();
        result.put("fileName", originalName);
        result.put("savedAs", req.key);
        result.put("size", req.size);
        result.put("mimeType", req.mimeType);
        // 兼容旧字段：前端 useChat.js 只读 url / indexed / chunks / summary / frames
        result.put("url", ossService.resolvePublicUrl(req.key));

        try {
            if (INDEXABLE.contains(ext)) {
                ingestDocument(req.key, originalName, result);
            } else if (IMAGE_EXTS.contains(ext)) {
                ingestImage(req.key, originalName, req.mimeType, result);
            } else if (VIDEO_EXTS.contains(ext)) {
                ingestVideo(req.key, originalName, result);
            } else {
                result.put("indexed", false);
                result.put("reason", "非文本/图片/视频类型，不入 RAG 库");
            }
            log.info("上传完成: {} key={} ext={} indexed={}",
                    originalName, req.key, ext, result.get("indexed"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("上传 finish 失败: key={} file={}", req.key, originalName, e);
            return ResponseEntity.internalServerError().body(error("处理失败: " + e.getMessage()));
        }
    }

    // ---------- ingestion 分支 ----------

    private void ingestDocument(String key, String originalName, Map<String, Object> result) {
        String text = null;
        try (InputStream in = ossService.openStream(key)) {
            text = ragIngestService.extractText(in, originalName);
        } catch (Exception e) {
            log.warn("文档 extractText 失败: key={} err={}", key, e.getMessage());
        }
        if (text != null && !text.isBlank()) {
            try (InputStream in = ossService.openStream(key)) {
                int chunks = ragIngestService.ingestFile(in, originalName);
                result.put("indexed", true);
                result.put("chunks", chunks);
                String summary = docSummaryService.summarize(text);
                if (summary != null) result.put("summary", summary);
            } catch (Exception e) {
                log.warn("文档 ingestFile 失败: key={} err={}", key, e.getMessage());
                result.put("indexed", false);
                result.put("indexError", e.getMessage());
            }
        } else {
            result.put("indexed", false);
            result.put("indexError", "文档无可抽取文本");
        }
    }

    private void ingestImage(String key, String originalName, String mimeType, Map<String, Object> result) {
        try {
            byte[] bytes = ossService.downloadBytes(key);
            int chunks = ragIngestService.ingestImage(bytes, mimeType, originalName, key);
            result.put("indexed", true);
            result.put("chunks", chunks);
            result.put("type", "image");
        } catch (Exception e) {
            log.warn("图片入库失败: key={} err={}", key, e.getMessage());
            result.put("indexed", false);
            result.put("indexError", e.getMessage());
        }
    }

    private void ingestVideo(String key, String originalName, Map<String, Object> result) {
        String ext = extractExt(originalName);
        Path tmp = null;
        Path frameDir = null;
        try {
            tmp = ossService.downloadToTemp(key, ext.replace(".", ""));
            if (!videoFrameExtractor.isAvailable()) {
                result.put("indexed", false);
                result.put("frames", List.of());
                result.put("frameWarning", "系统未安装 ffmpeg，无法抽帧；请先安装 ffmpeg");
                return;
            }
            // 帧目录名 = 视频 key 自身文件名主干（16 位 hex uuid）：
            // RagAdminService 删除时按 public/{stem}_frames/ 反查，随机后缀会对不上、帧变孤儿
            String base = key.substring(key.lastIndexOf('/') + 1);
            int dotIdx = base.lastIndexOf('.');
            String safeStem = dotIdx > 0 ? base.substring(0, dotIdx) : base;
            frameDir = Paths.get(System.getProperty("java.io.tmpdir"), "ai-oss", safeStem + "_frames");
            List<Path> frames = videoFrameExtractor.extract(tmp, frameDir);
            List<String> frameUrls = new ArrayList<>();
            for (Path f : frames) {
                String frameKey = ossProperties.getPublicPrefix() + "/" + safeStem + "_frames/" + f.getFileName();
                ossService.putFromFile(frameKey, f.toFile());
                frameUrls.add(ossService.resolvePublicUrl(frameKey));
            }
            result.put("frames", frameUrls);
            result.put("frameCount", frames.size());
            result.put("type", "video");
        } catch (Exception e) {
            log.warn("视频抽帧失败: key={} err={}", key, e.getMessage());
            result.put("frames", List.of());
            result.put("frameError", e.getMessage());
        } finally {
            deleteQuietly(tmp);
            deleteRecursively(frameDir);
        }
    }

    // ---------- 工具 ----------

    private Map<String, Object> error(String msg) { return Map.of("error", msg); }

    private static String extractExt(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.contains(".") ? lower.substring(lower.lastIndexOf('.')) : "";
    }

    /** 按扩展名决定 key 前缀：图片 → public/，其它 → uploads/ */
    private String buildKey(String ext, String fileName, String fixedStem) {
        String prefix = IMAGE_EXTS.contains(ext) ? ossProperties.getPublicPrefix() : ossProperties.getKeyPrefix();
        String ym = LocalDate.now().format(YM);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String nameExt = ext.isEmpty() ? "" : ext;
        return prefix + "/" + ym + "/" + uuid + nameExt;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignore) {}
    }

    private static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (var paths = Files.walk(p)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                 .forEach(child -> { try { Files.deleteIfExists(child); } catch (Exception ignore) {} });
        } catch (Exception ignore) {}
    }

    // ---------- DTO ----------

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinishRequest {
        private String key;
        private String fileName;
        private Long size;
        private String mimeType;
    }
}