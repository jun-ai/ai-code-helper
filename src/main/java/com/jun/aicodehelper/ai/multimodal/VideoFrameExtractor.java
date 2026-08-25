package com.jun.aicodehelper.ai.multimodal;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频帧抽取：调外部 ffmpeg 进程抽 I 帧到 JPEG。
 * 不引入 javacv/opencv，避免 JAR 膨胀和平台依赖。
 * 部署要求：系统 PATH 含 ffmpeg；缺失时 fail-fast 走降级。
 */
@Slf4j
@Component
public class VideoFrameExtractor {

    @Value("${app.video.max-frames:12}")
    private int maxFrames;

    @Value("${app.video.frame-width:640}")
    private int frameWidth;

    @Value("${app.video.timeout-seconds:30}")
    private int timeoutSeconds;

    private volatile boolean ffmpegAvailable = false;

    @PostConstruct
    public void init() {
        // 启动期校验 ffmpeg，未装则所有视频问答降级提示
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                ffmpegAvailable = true;
                log.info("ffmpeg 可用，最大抽帧={} 宽度={}px", maxFrames, frameWidth);
            } else {
                log.warn("ffmpeg -version 退出码={}，视频问答将降级提示", p.exitValue());
            }
        } catch (Exception e) {
            log.warn("系统未检测到 ffmpeg，视频问答将降级提示: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return ffmpegAvailable;
    }

    /**
     * 抽帧到 outDir/frame_001.jpg ... 返回所有抽出的文件路径。
     * 失败抛 RuntimeException，调用方降级提示。
     */
    public List<Path> extract(Path videoFile, Path outDir) throws IOException, InterruptedException {
        if (!ffmpegAvailable) {
            throw new IllegalStateException("系统未安装 ffmpeg，无法抽帧。请先安装 ffmpeg（apt install ffmpeg 或 winget install Gyan.FFmpeg）");
        }
        Files.createDirectories(outDir);
        // 过滤前先清空目标目录残留
        if (Files.exists(outDir)) {
            try (var stream = Files.list(outDir)) {
                stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        Path pattern = outDir.resolve("frame_%03d.jpg");
        // I 帧 + 缩放到指定宽度 + 限制总帧数
        List<String> cmd = List.of(
                "ffmpeg",
                "-y",
                "-i", videoFile.toAbsolutePath().toString(),
                "-vf", String.format("select='eq(pict_type,I)',scale=%d:-1", frameWidth),
                "-fps_mode", "vfr",
                "-frames:v", String.valueOf(maxFrames),
                "-q:v", "4",
                pattern.toString()
        );
        log.info("抽帧命令: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder err = new StringBuilder();
        try (InputStream is = p.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                err.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("ffmpeg 抽帧超时 " + timeoutSeconds + "s");
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("ffmpeg 抽帧失败 exit=" + p.exitValue() + ": " + err);
        }
        List<Path> frames = new ArrayList<>();
        try (var stream = Files.list(outDir)) {
            stream.sorted(Comparator.comparing(Path::toString))
                    .filter(Files::isRegularFile)
                    .forEach(frames::add);
        }
        if (frames.isEmpty()) {
            throw new RuntimeException("ffmpeg 抽帧成功但目录为空");
        }
        log.info("抽帧完成: {} 张 → {}", frames.size(), outDir);
        return frames;
    }
}