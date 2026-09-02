package com.jun.aicodehelper.oss;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * presign 签发登记：/upload/finish 只接受本服务签发过的 key，
 * 防止调用方拿任意 key 换取私有对象的签名下载 URL。
 * 记录保留到 presign 过期 + 1 分钟缓冲，过期定期清理防膨胀。
 */
@Component
public class PresignKeyRegistry {

    private final ConcurrentHashMap<String, Long> issued = new ConcurrentHashMap<>();

    public void register(String key, long ttlMillis) {
        issued.put(key, System.currentTimeMillis() + ttlMillis);
    }

    public boolean isIssued(String key) {
        Long expireAt = issued.get(key);
        return expireAt != null && expireAt >= System.currentTimeMillis();
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 300_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        issued.entrySet().removeIf(e -> e.getValue() < now);
    }
}
