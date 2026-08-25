package com.jun.aicodehelper.security;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 并发守卫：单 IP 同时活跃的 SSE 流数封顶。
 * 防止单客户端开 N 路长连接耗光 WebFlux 线程池。
 */
@Slf4j
@Component
public class SseConcurrencyGuard {

    private final ConcurrentHashMap<String, AtomicInteger> activeByIp = new ConcurrentHashMap<>();

    @Value("${api.sse.max-concurrent-per-ip:5}")
    private int maxConcurrentPerIp;

    private final MeterRegistry meterRegistry;

    public SseConcurrencyGuard(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerMetrics() {
        // 暴露全局活跃 SSE 连接数给 Prometheus
        Gauge.builder("sse.active.connections", this, SseConcurrencyGuard::totalActive)
                .description("当前活跃 SSE 连接总数（按 IP 求和）")
                .register(meterRegistry);
    }

    private double totalActive() {
        int sum = 0;
        for (AtomicInteger c : activeByIp.values()) {
            sum += c.get();
        }
        return sum;
    }

    /**
     * 尝试占位。true = 通过；false = 已达上限。
     */
    public boolean tryAcquire(String ip) {
        AtomicInteger counter = activeByIp.computeIfAbsent(ip, k -> new AtomicInteger());
        int current = counter.incrementAndGet();
        if (current > maxConcurrentPerIp) {
            counter.decrementAndGet();
            log.debug("SSE 并发限: ip={} active={} limit={}", ip, current - 1, maxConcurrentPerIp);
            return false;
        }
        return true;
    }

    /**
     * 流结束时必须调用，对称释放。
     */
    public void release(String ip) {
        AtomicInteger counter = activeByIp.get(ip);
        if (counter == null) {
            return;
        }
        int remaining = counter.decrementAndGet();
        if (remaining <= 0) {
            activeByIp.remove(ip, counter);
        }
    }
}
