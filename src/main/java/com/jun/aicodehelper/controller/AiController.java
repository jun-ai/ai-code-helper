package com.jun.aicodehelper.controller;

import com.jun.aicodehelper.ai.AiCodeHelperService;
import com.jun.aicodehelper.ai.VisionChatService;
import com.jun.aicodehelper.ai.metrics.AppMetrics;
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

import java.time.Duration;
import java.util.Set;
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
     * 多模态：上传文件时走 POST。图片 → vision 模型；其他类型 → 视为附件提示用户。
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<ServerSentEvent<String>> chatWithFile(@RequestParam int memoryId,
                                                       @RequestParam(required = false) String message,
                                                       @RequestPart(value = "file", required = false) MultipartFile file,
                                                       HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        AtomicBoolean done = new AtomicBoolean(false);

        Flux<String> source;
        String headerNote = null;
        try {
            if (file != null && !file.isEmpty()) {
                String mime = file.getContentType();
                if (mime != null && IMAGE_MIME.contains(mime.toLowerCase())) {
                    // 视觉问答
                    source = visionChatService.stream(message, file.getBytes(), mime);
                } else {
                    // 非图片附件：直接告诉用户文件已收到，询问意图
                    String filename = file.getOriginalFilename();
                    headerNote = String.format("【附件：%s（%s，%.1f KB）已收到】\n",
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
