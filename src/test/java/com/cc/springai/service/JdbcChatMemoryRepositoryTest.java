package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdbcChatMemoryRepositoryTest {

    @Test
    void preservesToolCallsAndToolResponsesAcrossReload() {
        JdbcChatMemoryRepository repository = repository();
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .properties(Map.of("finishReason", "tool_calls"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "read_memory",
                        "{\"key\":\"Memory.md\"}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .metadata(Map.of("recordedAt", 123L))
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "read_memory",
                        "memory content")))
                .build();

        String assistantPayload = ReflectionTestUtils.invokeMethod(repository, "toPayload", assistant);
        String toolResponsePayload = ReflectionTestUtils.invokeMethod(repository, "toPayload", toolResponse);
        List<Message> reloaded = List.of(
                ReflectionTestUtils.invokeMethod(repository, "toMessage", "ASSISTANT", "", assistantPayload),
                ReflectionTestUtils.invokeMethod(repository, "toMessage", "TOOL", "", toolResponsePayload)
        );
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0)).isInstanceOf(AssistantMessage.class);
        AssistantMessage reloadedAssistant = (AssistantMessage) reloaded.get(0);
        assertThat(reloadedAssistant.getText()).isEmpty();
        assertThat(reloadedAssistant.getToolCalls()).containsExactly(new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "read_memory",
                "{\"key\":\"Memory.md\"}"));
        assertThat(reloadedAssistant.getMetadata()).containsEntry("finishReason", "tool_calls");

        assertThat(reloaded.get(1)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage reloadedToolResponse = (ToolResponseMessage) reloaded.get(1);
        assertThat(reloadedToolResponse.getResponses()).containsExactly(new ToolResponseMessage.ToolResponse(
                "call-1",
                "read_memory",
                "memory content"));
        assertThat(reloadedToolResponse.getMetadata()).containsEntry("recordedAt", 123);
    }

    @Test
    void readsLegacyToolRowsAsToolResponseMessages() {
        JdbcChatMemoryRepository repository = repository();

        Message message = ReflectionTestUtils.invokeMethod(repository, "toMessage", "TOOL", "old output", null);

        assertThat(message).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponse = (ToolResponseMessage) message;
        assertThat(toolResponse.getResponses().get(0).responseData()).isEqualTo("old output");
    }

    private JdbcChatMemoryRepository repository() {
        return new JdbcChatMemoryRepository(mock(JdbcTemplate.class), new ObjectMapper());
    }
}
