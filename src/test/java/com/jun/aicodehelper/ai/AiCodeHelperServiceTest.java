package com.jun.aicodehelper.ai;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 集成验证：真实调用 LLM/Milvus/MySQL，耗时且花钱，需在 IDEA 中手动执行
 */
@Disabled
@SpringBootTest
class AiCodeHelperServiceTest {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Test
    void chat() {
        String result = aiCodeHelperService.chat("你好，我是小明");
        System.out.println(result);
    }

    @Test
    void chatWithMemory() {
        String result = aiCodeHelperService.chat("你好，我是小明");
        System.out.println(result);
        result = aiCodeHelperService.chat("你好，我是谁来着？");
        System.out.println(result);
    }

    @Test
    void chatForReport() {
        String userMessage = "你好，我是小明，学编程两年半，请帮我制定学习报告";
        AiCodeHelperService.Report report = aiCodeHelperService.chatForReport(userMessage);
        System.out.println(report);
    }

    @Test
    void chatWithRag() {
        Result<String> result = aiCodeHelperService.chatWithRag("怎么学习 Java？有哪些常见面试题？");
        String content = result.content();
        List<Content> sources = result.sources();
        System.out.println(content);
        System.out.println(sources);
    }

    @Test
    void chatWithTools() {
        String result = aiCodeHelperService.chat("有哪些常见的计算机网络面试题？");
        System.out.println(result);
    }

    @Test
    void chatWithMcp() {
        String result = aiCodeHelperService.chat("有什么学习编程的建议？");
        System.out.println(result);
    }

    @Test
    void chatWithGuardrail() {
        String result = aiCodeHelperService.chat("kill the game");
        System.out.println(result);
    }
}
