package com.jun.aicodehelper.ai;

import com.jun.aicodehelper.ai.memory.MysqlChatMemoryStore;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiCodeHelperServiceFactory {

    @Resource
    private ChatModel zhipuChatModel;

    @Resource
    private StreamingChatModel zhipuStreamingChatModel;

    @Resource
    private ContentRetriever contentRetriever;

    @Resource
    private McpToolProvider mcpToolProvider;

    @Resource
    private MysqlChatMemoryStore mysqlChatMemoryStore;

    @Resource
    private com.jun.aicodehelper.ai.metrics.AppMetrics metrics;

    @Bean
    public AiCodeHelperService aiCodeHelperService() {
        // 检索用用户原句（lambda identity）：默认压缩改写器会先用 LLM 重写查询，历史污染且质量不可控
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(query -> List.of(query))
                .contentRetriever(contentRetriever)
                .build();
        AiCodeHelperService aiCodeHelperService = AiServices.builder(AiCodeHelperService.class)
                .chatModel(zhipuChatModel)
                .streamingChatModel(zhipuStreamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .chatMemoryStore(mysqlChatMemoryStore) // 记忆落 MySQL，重启不丢
                        .build())
                .retrievalAugmentor(retrievalAugmentor) // RAG 检索增强生成
                .toolProvider(mcpToolProvider) // MCP 工具调用
                .build();
        return aiCodeHelperService;
    }
}
