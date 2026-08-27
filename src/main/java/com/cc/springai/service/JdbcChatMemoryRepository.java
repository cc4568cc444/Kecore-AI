package com.cc.springai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_memory_messages (
                    conversation_id VARCHAR(128) NOT NULL,
                    message_index INTEGER NOT NULL,
                    message_type VARCHAR(32) NOT NULL,
                    content TEXT NOT NULL,
                    PRIMARY KEY (conversation_id, message_index)
                )
                """);
        addPayloadColumnIfMissing();
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.query(
                "SELECT DISTINCT conversation_id FROM chat_memory_messages ORDER BY conversation_id",
                (rs, rowNum) -> rs.getString("conversation_id")
        );
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query("""
                        SELECT message_type, content, payload
                        FROM chat_memory_messages
                        WHERE conversation_id = ?
                        ORDER BY message_index ASC
                        """,
                (rs, rowNum) -> toMessage(
                        rs.getString("message_type"),
                        rs.getString("content"),
                        rs.getString("payload")),
                conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        deleteByConversationId(conversationId);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            jdbcTemplate.update("""
                    INSERT INTO chat_memory_messages (conversation_id, message_index, message_type, content, payload)
                    VALUES (?, ?, ?, ?, ?)
                    """, conversationId, i, message.getMessageType().name(), safeText(message), toPayload(message));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_memory_messages WHERE conversation_id = ?", conversationId);
    }

    private void addPayloadColumnIfMissing() {
        try {
            jdbcTemplate.execute("ALTER TABLE chat_memory_messages ADD COLUMN payload TEXT");
        } catch (Exception ignored) {
            // Existing databases already have the column.
        }
    }

    private String safeText(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    private String toPayload(Message message) {
        try {
            MessagePayload payload = switch (message.getMessageType()) {
                case ASSISTANT -> assistantPayload((AssistantMessage) message);
                case TOOL -> toolPayload((ToolResponseMessage) message);
                case SYSTEM, USER -> new MessagePayload(
                        message.getMessageType().name(),
                        safeText(message),
                        message.getMetadata(),
                        List.of(),
                        List.of()
                );
            };
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat memory message", e);
        }
    }

    private MessagePayload assistantPayload(AssistantMessage message) {
        return new MessagePayload(
                message.getMessageType().name(),
                safeText(message),
                message.getMetadata(),
                message.getToolCalls() == null ? List.of() : message.getToolCalls(),
                List.of()
        );
    }

    private MessagePayload toolPayload(ToolResponseMessage message) {
        return new MessagePayload(
                message.getMessageType().name(),
                safeText(message),
                message.getMetadata(),
                List.of(),
                message.getResponses() == null ? List.of() : message.getResponses()
        );
    }

    private Message toMessage(String messageType, String content, String payload) {
        if (payload != null && !payload.isBlank()) {
            try {
                return toMessage(objectMapper.readValue(payload, MessagePayload.class), content);
            } catch (Exception ignored) {
                // Fall through to legacy text-only rows if payload is malformed.
            }
        }
        return legacyMessage(messageType, content);
    }

    private Message toMessage(MessagePayload payload, String legacyContent) {
        String content = payload.content() == null ? legacyContent : payload.content();
        String typeValue = payload.type() == null ? "" : payload.type();
        MessageType type = MessageType.valueOf(typeValue);
        Map<String, Object> metadata = payload.metadata() == null ? Map.of() : payload.metadata();
        return switch (type) {
            case USER -> UserMessage.builder()
                    .text(content)
                    .metadata(metadata)
                    .build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(content)
                    .properties(metadata)
                    .toolCalls(payload.toolCalls() == null ? List.of() : payload.toolCalls())
                    .build();
            case SYSTEM -> SystemMessage.builder()
                    .text(content)
                    .metadata(metadata)
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(payload.toolResponses() == null ? List.of() : payload.toolResponses())
                    .metadata(metadata)
                    .build();
        };
    }

    private Message legacyMessage(String messageType, String content) {
        MessageType type = MessageType.valueOf(messageType);
        return switch (type) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("", "", content)))
                    .build();
        };
    }

    private record MessagePayload(String type,
                                  String content,
                                  Map<String, Object> metadata,
                                  List<AssistantMessage.ToolCall> toolCalls,
                                  List<ToolResponseMessage.ToolResponse> toolResponses) {
    }
}
