package com.jun.aicodehelper.ai;

import com.jun.aicodehelper.ai.memory.MysqlChatMemoryStore;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
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
        // queryTransformer 透传原 query；history 注入留给 HybridContentRetriever：
        // 它会从 query.metadata() 反射读取 "history" 键（上游可在自定义 transformer 注入）。
        // 默认主路径下 history 为空，多轮指代由 ChatMemory 在 LLM prompt 中兜底。
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(q -> List.of(q))
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