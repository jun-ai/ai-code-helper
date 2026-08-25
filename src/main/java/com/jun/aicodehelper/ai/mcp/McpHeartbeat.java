package com.jun.aicodehelper.ai.mcp;

import com.jun.aicodehelper.ai.metrics.AppMetrics;
import dev.langchain4j.mcp.client.McpClient;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MCP 长连接心跳：30 秒一次 ping（PONG），阻止 bigmodel.cn 60 秒空闲断连误判；
 * 失败计入 mcp.heartbeat{result=failure}，让运维能在 Grafana 看到趋势。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpHeartbeat {

    private final McpClient mcpClient;
    private final AppMetrics metrics;

    @Scheduled(fixedRate = 30_000, initialDelay = 5_000)
    public void ping() {
        Timer.Sample sample = Timer.start();
        try {
            mcpClient.checkHealth();
            metrics.getMcpHeartbeatSuccess().increment();
        } catch (Exception e) {
            metrics.getMcpHeartbeatFailure().increment();
            log.debug("MCP 心跳失败: {}", e.getMessage());
        } finally {
            sample.stop(metrics.getMcpHeartbeatLatency());
        }
    }

    /**
     * 单元测试静态引用，构造时无需真健康检查
     */
    @SuppressWarnings("unused")
    private static long elapsedMicros(long startNanos) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
    }
}