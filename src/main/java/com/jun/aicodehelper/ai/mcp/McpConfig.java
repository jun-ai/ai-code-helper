package com.jun.aicodehelper.ai.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class McpConfig {

    @Value("${bigmodel.api-key}")
    private String apiKey;

    @Bean
    public McpClient mcpClient() {
        // SSE 单次读超时拉长到 2 分钟；bigmodel.cn 大约 60 秒空闲会主动断，5 秒默认读超时误判失败
        // web_search_prime = 编程套餐专属端点（走套餐额度）；标准 web_search 是按量计费会 429
        McpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl("https://open.bigmodel.cn/api/mcp/web_search_prime/sse?Authorization=" + apiKey)
                .timeout(Duration.ofMinutes(2))
                .logRequests(false)
                .logResponses(false)
                .build();
        return new DefaultMcpClient.Builder()
                .key("junMcpClient")
                .clientName("ai-code-helper")
                .clientVersion("1.0.0")
                .transport(transport)
                .reconnectInterval(Duration.ofSeconds(2))
                .initializationTimeout(Duration.ofSeconds(10))
                .toolExecutionTimeout(Duration.ofSeconds(15))
                .toolExecutionTimeoutErrorMessage("联网搜索工具暂时不可用，请基于已有知识作答或稍后重试")
                .build();
    }

    @Bean
    public ToolProvider mcpToolProvider(McpClient mcpClient) {
        // 加固版：1301 风控/异常自动重试 3 次，仍失败返回友好提示
        return new ResilientMcpToolProvider(mcpClient);
    }
}
