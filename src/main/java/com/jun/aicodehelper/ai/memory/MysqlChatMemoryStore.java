package com.jun.aicodehelper.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆落 MySQL：每个 memoryId 一行，消息列表序列化成 JSON，重启不丢。
 * 写入时按 maxMessages 滑窗封顶，防止长会话把上下文窗口撑爆。
 */
@Repository
public class MysqlChatMemoryStore implements ChatMemoryStore {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${ai.chat-memory.max-messages:20}")
    private int maxMessages;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<String> json = jdbcTemplate.queryForList(
                "SELECT messages FROM chat_memory WHERE memory_id = ?", String.class, String.valueOf(memoryId));
        return json.isEmpty() ? new ArrayList<>() : ChatMessageDeserializer.messagesFromJson(json.get(0));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 滑窗：超出上限时从头部截掉最早的消息，保留最近 maxMessages 条
        List<ChatMessage> trimmed = messages.size() <= maxMessages
                ? messages
                : messages.subList(messages.size() - maxMessages, messages.size());
        String json = ChatMessageSerializer.messagesToJson(trimmed);
        jdbcTemplate.update("""
                INSERT INTO chat_memory (memory_id, messages) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE messages = VALUES(messages)
                """, String.valueOf(memoryId), json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE memory_id = ?", String.valueOf(memoryId));
    }
}
