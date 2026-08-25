package com.jun.aicodehelper.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台 Key 管理：查看/轮换 api.security.api-key 和 api.security.admin-key。
 * 覆盖 /api/admin/**，由 AdminAuthFilter 守卫。
 *
 * 实现取舍：写回 application-local.yml（gitignored，保存真实密钥）；
 * 不写 application.yml（默认值占位）。重启后生效。
 */
@Slf4j
@RestController
@RequestMapping("/admin/keys")
public class AdminKeyController {

    private static final Path LOCAL_YML = Paths.get("src/main/resources/application-local.yml");

    @Value("${api.security.api-key:}")
    private String apiKey;

    @Value("${api.security.admin-key:}")
    private String adminKey;

    @GetMapping
    public Map<String, Object> show() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("apiKeyMasked", mask(apiKey));
        resp.put("adminKeyMasked", mask(adminKey));
        resp.put("apiKeyConfigured", !apiKey.isEmpty());
        resp.put("adminKeyConfigured", !adminKey.isEmpty());
        return resp;
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotateAdmin() throws IOException {
        String newAdminKey = randomKey(24);
        writeAdminKey(newAdminKey);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("adminKey", newAdminKey);   // 一次性回显，重启前不会再次出现
        resp.put("message", "新 adminKey 已写入 application-local.yml，重启后端生效");
        log.warn("adminKey 已轮换（旧值已废弃）");
        return resp;
    }

    /** 写 application-local.yml 的 api.security.admin-key；其它 key 保持原样 */
    @SuppressWarnings("unchecked")
    private void writeAdminKey(String newAdminKey) throws IOException {
        Map<String, Object> root;
        if (Files.exists(LOCAL_YML)) {
            try (InputStream in = new FileInputStream(LOCAL_YML.toFile())) {
                Object loaded = new Yaml().load(in);
                root = (loaded instanceof Map) ? (Map<String, Object>) loaded : new LinkedHashMap<>();
            }
        } else {
            root = new LinkedHashMap<>();
            Files.createDirectories(LOCAL_YML.getParent());
        }
        Map<String, Object> spring = (Map<String, Object>) root.computeIfAbsent("spring", k -> new LinkedHashMap<>());
        Map<String, Object> api = (Map<String, Object>) root.computeIfAbsent("api", k -> new LinkedHashMap<>());
        Map<String, Object> security = (Map<String, Object>) api.computeIfAbsent("security", k -> new LinkedHashMap<>());
        security.put("admin-key", newAdminKey);

        // 避免写出 spring 节点下没用的 datasource/password 干扰（保留原样）
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        Yaml writer = new Yaml(options);
        try (Writer w = new FileWriter(LOCAL_YML.toFile())) {
            writer.dump(root, w);
        }
    }

    /** 仅展示前 4 + 后 4，中间 ***；空值返回 (未配置) */
    private String mask(String k) {
        if (k == null || k.isEmpty()) return "(未配置)";
        if (k.length() <= 8) return "***";
        return k.substring(0, 4) + "***" + k.substring(k.length() - 4);
    }

    private String randomKey(int byteLen) {
        byte[] buf = new byte[byteLen];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}