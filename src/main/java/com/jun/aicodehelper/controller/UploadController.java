package com.jun.aicodehelper.controller;

import com.jun.aicodehelper.ai.multimodal.DocSummaryService;
import com.jun.aicodehelper.ai.multimodal.VideoFrameExtractor;
import com.jun.aicodehelper.ai.rag.RagIngestService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传：PDF / Word / TXT / MD / 图片 / 视频。
 * 文档类自动入 RAG 库 + 返回 summary；图片 caption 入库；视频上传时同步抽帧返回帧路径。
 */
@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final long MAX_FILE_SIZE = 50 * 1024L * 1024L; // 50MB

    // 可入 RAG 的文本类扩展名
    private static final Set<String> INDEXABLE = Set.of(".pdf", ".docx", ".txt", ".md");

    // 图片类：调视觉模型生成 caption 再入 RAG
    private static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif");

    // 视频类：上传后抽帧返回，多图视觉问答由 chat 接口拼装
    private static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".mkv", ".avi", ".webm", ".flv");

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Resource
    private RagIngestService ragIngestService;

    @Resource
    private VideoFrameExtractor videoFrameExtractor;

    @Resource
    private DocSummaryService docSummaryService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(error("文件为空"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(error("文件过大，最大 50MB"));
        }
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String lower = originalName.toLowerCase();
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.')) : "";

        try {
            // 落盘到独立 uploads 目录，避免污染 RAG 原始 docs/
            Path dir = Paths.get(uploadDir).toAbsolutePath();
            Files.createDirectories(dir);
            String savedName = UUID.randomUUID() + ext;
            Path target = dir.resolve(savedName);
            byte[] bytes;
            try (var in = file.getInputStream()) {
                bytes = in.readAllBytes();
            }
            Files.write(target, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            Map<String, Object> result = new HashMap<>();
            result.put("url", "/api/uploads/" + savedName);
            result.put("fileName", originalName);
            result.put("savedAs", savedName);
            result.put("size", file.getSize());
            result.put("mimeType", file.getContentType());

            // 文本类自动入 RAG
            if (INDEXABLE.contains(ext)) {
                String extractedText = null;
                try {
                    extractedText = ragIngestService.extractText(target, originalName);
                    int chunks = ragIngestService.ingestFile(target, originalName);
                    result.put("indexed", true);
                    result.put("chunks", chunks);
                } catch (Exception e) {
                    log.warn("文件入库失败但已落盘: {} err={}", originalName, e.getMessage());
                    result.put("indexed", false);
                    result.put("indexError", e.getMessage());
                }
                // summary：从已抽到的文本做 ≤200 字摘要，前端用作 chip
                if (extractedText != null && !extractedText.isBlank()) {
                    String summary = docSummaryService.summarize(extractedText);
                    if (summary != null) result.put("summary", summary);
                }
            } else if (IMAGE_EXTS.contains(ext)) {
                try {
                    int chunks = ragIngestService.ingestImage(bytes, file.getContentType(), originalName, savedName);
                    result.put("indexed", true);
                    result.put("chunks", chunks);
                    result.put("type", "image");
                } catch (Exception e) {
                    log.warn("图片入库失败但已落盘: {} err={}", originalName, e.getMessage());
                    result.put("indexed", false);
                    result.put("indexError", e.getMessage());
                }
            } else if (VIDEO_EXTS.contains(ext)) {
                if (!videoFrameExtractor.isAvailable()) {
                    result.put("indexed", false);
                    result.put("frames", List.of());
                    result.put("frameWarning", "系统未安装 ffmpeg，无法抽帧；请先安装 ffmpeg");
                } else {
                    try {
                        String stem = savedName.substring(0, savedName.lastIndexOf('.'));
                        Path frameDir = dir.resolve(stem + "_frames");
                        List<Path> frames = videoFrameExtractor.extract(target, frameDir);
                        List<String> frameUrls = new ArrayList<>();
                        for (Path f : frames) {
                            frameUrls.add("/api/uploads/" + stem + "_frames/" + f.getFileName().toString());
                        }
                        result.put("frames", frameUrls);
                        result.put("frameCount", frames.size());
                        result.put("type", "video");
                    } catch (Exception e) {
                        log.warn("视频抽帧失败但已落盘: {} err={}", originalName, e.getMessage());
                        result.put("frames", List.of());
                        result.put("frameError", e.getMessage());
                    }
                }
            } else {
                result.put("indexed", false);
                result.put("reason", "非文本/图片/视频类型，不入 RAG 库");
            }

            log.info("上传成功: {} size={} ext={} indexed={}",
                    originalName, file.getSize(), ext, result.get("indexed"));
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("上传失败: {}", originalName, e);
            return ResponseEntity.internalServerError().body(error("保存失败: " + e.getMessage()));
        }
    }

    private Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
