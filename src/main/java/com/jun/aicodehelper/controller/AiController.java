package com.jun.aicodehelper.controller;

import com.jun.aicodehelper.ai.AiCodeHelperService;
import com.jun.aicodehelper.ai.VisionChatService;
import com.jun.aicodehelper.ai.metrics.AppMetrics;
import com.jun.aicodehelper.ai.multimodal.VideoFrameExtractor;
import com.jun.aicodehelper.security.SseConcurrencyGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Set<String> IMAGE_MIME = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Resource
    private VisionChatService visionChatService;

    @Resource
    private VideoFrameExtractor videoFrameExtractor;

    @Resource
    private AppMetrics metrics;

    @Resource
    private SseConcurrencyGuard sseConcurrencyGuard;

    /**
     * 纯文本流式对话：保留原 GET 接口供前端默认调用
     */
    @GetMapping("/chat")
    public Flux<ServerSentEvent<String>> chat(int memoryId, String message, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        AtomicBoolean done = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> data = aiCodeHelperService.chatStream(memoryId, message)
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                .doFinally(signal -> {
                    done.set(true);
                    metrics.getChatRequests().increment();
                    sseConcurrencyGuard.release(ip);
                })
                .onErrorResume(e -> emitError(e));
        Flux<ServerSentEvent<String>> heartbeat = heartbeat(done);
        return data.mergeWith(heartbeat);
    }

    /**
     * 多模态：上传文件时走 POST。
     * 图片 → vision 模型；视频 → ffmpeg 抽帧 + 多图视觉问答；其他 → 文本附件。
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<ServerSentEvent<String>> chatWithFile(@RequestParam int memoryId,
                                                       @RequestParam(required = false) String message,
                                                       @RequestPart(value = "file", required = false) MultipartFile file,
                                                       HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        AtomicBoolean done = new AtomicBoolean(false);

        Flux<String> source;
        try {
            if (file != null && !file.isEmpty()) {
                String mime = file.getContentType();
                if (mime != null && IMAGE_MIME.contains(mime.toLowerCase())) {
                    source = visionChatService.stream(message, file.getBytes(), mime);
                } else if (mime != null && mime.toLowerCase().startsWith("video/")) {
                    source = handleVideoChat(message, file);
                } else {
                    String filename = file.getOriginalFilename();
                    String headerNote = String.format("【附件：%s（%s，%.1f KB）已收到】\n",
                            filename, mime, file.getSize() / 1024.0);
                    String userMsg = (message == null || message.isBlank())
                            ? "请基于上述附件，告诉用户你看到的内容（如果是文本类）"
                            : message;
                    source = aiCodeHelperService.chatStream(memoryId, headerNote + userMsg);
                }
            } else {
                source = aiCodeHelperService.chatStream(memoryId, message == null ? "" : message);
            }
        } catch (Exception e) {
            return Flux.just(errorEvent(e));
        }

        Flux<ServerSentEvent<String>> data = source
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())
                .doFinally(signal -> {
                    done.set(true);
                    metrics.getChatRequests().increment();
                    sseConcurrencyGuard.release(ip);
                })
                .onErrorResume(e -> emitError(e));
        Flux<ServerSentEvent<String>> heartbeat = heartbeat(done);
        return data.mergeWith(heartbeat);
    }

    /**
     * 视频问答：把视频文件落盘 → ffmpeg 抽帧到临时目录 → 读帧 bytes → 调多图 stream。
     * 临时目录每次 chat 独立，结束后清理。
     */
    private Flux<String> handleVideoChat(String message, MultipartFile file) throws IOException, InterruptedException {
        if (!videoFrameExtractor.isAvailable()) {
            throw new IllegalStateException("系统未安装 ffmpeg，无法处理视频问答。请联系管理员安装 ffmpeg。");
        }
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "ai-chat");
        Files.createDirectories(tmpDir);
        String tmpName = UUID.randomUUID() + ".mp4";
        Path tmp = tmpDir.resolve(tmpName);
        Files.write(tmp, file.getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Path frameDir = tmpDir.resolve(tmpName + "_frames");
        List<byte[]> frameBytes = new ArrayList<>();
        try {
            List<Path> frames = videoFrameExtractor.extract(tmp, frameDir);
            for (Path f : frames) {
                frameBytes.add(Files.readAllBytes(f));
            }
        } finally {
            // 抽完即清，避免临时目录堆积
            try {
                if (Files.exists(frameDir)) {
                    try (var stream = Files.list(frameDir)) {
                        stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                    }
                    Files.deleteIfExists(frameDir);
                }
            } catch (Exception ignored) {}
            Files.deleteIfExists(tmp);
        }
        if (frameBytes.isEmpty()) {
            throw new RuntimeException("视频抽帧成功但未读到任何帧");
        }
        return visionChatService.stream(message, frameBytes, "image/jpeg");
    }

    private Flux<ServerSentEvent<String>> heartbeat(AtomicBoolean done) {
        return Flux.interval(Duration.ofSeconds(1))
                .takeWhile(i -> !done.get())
                .filter(i -> i % HEARTBEAT_INTERVAL.toSeconds() == 0)
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
    }

    private Flux<ServerSentEvent<String>> emitError(Throwable e) {
        metrics.getChatErrors().increment();
        return Flux.just(errorEvent(e));
    }

    private ServerSentEvent<String> errorEvent(Throwable e) {
        return ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"message\":\"" + (e.getMessage() == null ? "unknown" : e.getMessage().replace("\"", "'")) + "\"}")
                .build();
    }
}
