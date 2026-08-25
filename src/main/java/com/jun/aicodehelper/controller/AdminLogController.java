package com.jun.aicodehelper.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 后台日志接口：读取最近 N 行（默认 200），可选按关键词过滤（默认 ERROR）。
 * 覆盖 /api/admin/**，由 AdminAuthFilter 守卫。
 */
@Slf4j
@RestController
@RequestMapping("/admin/logs")
public class AdminLogController {

    /** 日志输出目录：与 application.yml 的 logging.file.name 配套；缺省回落 stdout */
    private static final Path[] LOG_CANDIDATES = {
            Paths.get("logs"),
            Paths.get(System.getProperty("user.home"), "logs"),
            Paths.get(System.getProperty("java.io.tmpdir"), "logs")
    };

    @GetMapping
    public Map<String, Object> recent(
            @RequestParam(value = "lines", defaultValue = "200") int linesParam,
            @RequestParam(value = "filter", required = false) String filter) {
        final int lines = (linesParam <= 0 || linesParam > 5000) ? 200 : linesParam;
        List<String> collected = new ArrayList<>();
        for (Path dir : LOG_CANDIDATES) {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                     .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                     .findFirst()
                     .ifPresent(latest -> tailFile(latest, lines, filter, collected));
            } catch (IOException ignore) {}
            if (!collected.isEmpty()) break;
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("lines", collected);
        resp.put("total", collected.size());
        resp.put("filter", filter == null ? "" : filter);
        return resp;
    }

    /** 取文件末尾 N 行，可选按关键词过滤；结果倒序（最新在前） */
    private void tailFile(Path file, int lines, String filter, List<String> out) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int size = all.size();
            int from = Math.max(0, size - lines);
            List<String> slice = all.subList(from, size);
            if (filter != null && !filter.isBlank()) {
                String f = filter.toLowerCase();
                List<String> filtered = new ArrayList<>();
                for (int i = slice.size() - 1; i >= 0; i--) {
                    String s = slice.get(i);
                    if (s.toLowerCase().contains(f)) filtered.add(s);
                }
                out.addAll(filtered);
            } else {
                Collections.reverse(slice);
                out.addAll(slice);
            }
        } catch (IOException e) {
            log.warn("读日志失败: file={} err={}", file, e.getMessage());
        }
    }
}