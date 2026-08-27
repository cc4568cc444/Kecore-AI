package com.cc.springai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompactionServiceTest {

    @Test
    void compactsOldMessagesAndKeepsRecentMessages() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- compacted history");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", numberedMessages(270));

        boolean compacted = service.compactIfNeeded("c1");

        assertThat(compacted).isTrue();
        assertThat(chatModel.calls).isEqualTo(1);
        assertThat(chatModel.lastPrompt.getContents()).contains("user 0");
        assertThat(chatModel.lastPrompt.getContents()).contains("assistant 253");
        assertThat(chatModel.lastPrompt.getContents()).doesNotContain("user 254");

        List<Message> stored = chatMemory.get("c1");
        assertThat(stored).hasSize(17);
        assertThat(stored.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(stored.get(0).getText())
                .startsWith(ConversationCompactionService.SUMMARY_MARKER)
                .contains("compacted history");
        assertThat(stored.get(1).getText()).isEqualTo("user 254");
        assertThat(stored.get(16).getText()).isEqualTo("assistant 269");
    }

    @Test
    void doesNotCompactSmallHistory() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", numberedMessages(20));

        boolean compacted = service.compactIfNeeded("c1");

        assertThat(compacted).isFalse();
        assertThat(chatModel.calls).isZero();
        assertThat(chatMemory.get("c1")).hasSize(20);
    }

    @Test
    void manualCompactionCanCompactBeforeThreshold() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- manually compacted");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", numberedMessages(20));

        boolean compacted = service.compactNow("c1");

        assertThat(compacted).isTrue();
        assertThat(chatModel.calls).isEqualTo(1);
        assertThat(chatMemory.get("c1")).hasSize(11);
        assertThat(chatMemory.get("c1").get(0).getText()).contains("manually compacted");
    }

    @Test
    void manualCompactionUsesProvidedModelWhenPresent() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel defaultModel = new RecordingChatModel("Goal:\n- default model");
        RecordingChatModel selectedModel = new RecordingChatModel("Goal:\n- selected model");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, defaultModel);

        chatMemory.add("c1", numberedMessages(20));

        boolean compacted = service.compactNow("c1", selectedModel);

        assertThat(compacted).isTrue();
        assertThat(defaultModel.calls).isZero();
        assertThat(selectedModel.calls).isEqualTo(1);
        assertThat(chatMemory.get("c1").get(0).getText()).contains("selected model");
    }

    @Test
    void compactionPromptIncludesToolCallsAndResponsesWhenTextIsEmpty() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- summarized tools");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", List.of(
                new UserMessage("读一下桌面的十月计划"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "readFile",
                                "{\"path\":\"C:\\\\Users\\\\me\\\\Desktop\\\\十月计划.md\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1",
                                "readFile",
                                "十月计划：完成报告，准备预算。")))
                        .build(),
                new AssistantMessage("我读到了十月计划。"),
                new UserMessage("继续整理"),
                new AssistantMessage("好的")
        ));

        boolean compacted = service.compactNow("c1");

        assertThat(compacted).isTrue();
        assertThat(chatModel.lastPrompt.getContents())
                .contains("Tool calls")
                .contains("readFile")
                .contains("十月计划.md")
                .contains("Tool responses")
                .contains("完成报告")
                .contains("准备预算");
    }

    @Test
    void compactionDoesNotSplitToolCallFromResponseAtBoundary() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- boundary summarized");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", List.of(
                new UserMessage("读一下桌面的十月计划"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-search", "function", "searchFiles", "{\"query\":\"十月计划\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-search", "searchFiles", "找到 10月计划.txt")))
                        .build(),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-read", "function", "readFile", "{\"path\":\"C:\\\\Users\\\\me\\\\Desktop\\\\10月计划.txt\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-read", "readFile", "10月计划正文：完成报告，准备预算。")))
                        .build(),
                new AssistantMessage("我读到了十月计划。"),
                new UserMessage("继续"),
                new AssistantMessage("好的")
        ));

        boolean compacted = service.compactNow("c1");

        assertThat(compacted).isTrue();
        assertThat(chatModel.lastPrompt.getContents())
                .contains("call-read")
                .contains("readFile")
                .contains("10月计划正文")
                .contains("完成报告");
    }

    @Test
    void estimatesUsageAgainstTokenBudget() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        ConversationCompactionService.ContextUsage usage = service.estimateUsage(List.of(
                new UserMessage("12345"),
                new AssistantMessage("12345")
        ));

        assertThat(usage.usedTokens()).isGreaterThan(0);
        assertThat(usage.maxTokens()).isEqualTo(128_000L);
        assertThat(usage.usedChars()).isEqualTo(10);
        assertThat(usage.messageCount()).isEqualTo(2);
        assertThat(usage.percent()).isGreaterThan(0.0);
    }

    @Test
    void estimatesAutoCompactionProgress() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        ConversationCompactionService.AutoCompactionProgress progress =
                service.estimateAutoCompactionProgress(numberedMessages(30));

        assertThat(progress.percent()).isGreaterThan(0.0);
        assertThat(progress.tokenPercent()).isGreaterThan(0.0);
        assertThat(progress.messagePercent()).isGreaterThan(0.0);
        assertThat(progress.messageCount()).isEqualTo(30);
        assertThat(progress.messageThreshold()).isEqualTo(256);
        assertThat(progress.messagesUntilAutoCompact()).isEqualTo(227);
        assertThat(progress.wouldCompact()).isFalse();
    }

    @Test
    void autoCompactionProgressReportsReadyAfterThreshold() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        ConversationCompactionService.AutoCompactionProgress progress =
                service.estimateAutoCompactionProgress(numberedMessages(257));

        assertThat(progress.tokenPercent()).isGreaterThan(0.0);
        assertThat(progress.percent()).isGreaterThan(0.0);
        assertThat(progress.messagesUntilAutoCompact()).isZero();
        assertThat(progress.wouldCompact()).isTrue();
    }

    @Test
    void replacesExistingSummaryInsteadOfStackingSummaries() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- refreshed summary");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", List.of(new SystemMessage(
                ConversationCompactionService.SUMMARY_MARKER + "\nGoal:\n- previous summary")));
        chatMemory.add("c1", numberedMessages(270));

        boolean compacted = service.compactIfNeeded("c1");

        assertThat(compacted).isTrue();
        assertThat(chatModel.lastPrompt.getContents()).contains("previous summary");
        List<Message> stored = chatMemory.get("c1");
        assertThat(stored.stream().filter(message -> message instanceof SystemMessage)).hasSize(1);
        assertThat(stored.get(0).getText()).contains("refreshed summary");
    }

    @Test
    void compactionUsageCanBeEstimatedFromCompactedHistory() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("Goal:\n- compacted history");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", numberedMessages(270));
        ConversationCompactionService.ContextUsage before = service.estimateUsage(chatMemory.get("c1"));

        boolean compacted = service.compactNow("c1");
        ConversationCompactionService.ContextUsage after = service.estimateUsage(chatMemory.get("c1"));

        assertThat(compacted).isTrue();
        assertThat(chatMemory.get("c1")).hasSize(17);
        assertThat(after.messageCount()).isEqualTo(17);
        assertThat(after.usedTokens()).isLessThan(before.usedTokens());
    }

    @Test
    void fallsBackToLocalSummaryWhenModelFails() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        chatModel.fail = true;
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", numberedMessages(270));

        boolean compacted = service.compactIfNeeded("c1");

        assertThat(compacted).isTrue();
        assertThat(chatMemory.get("c1").get(0).getText())
                .startsWith(ConversationCompactionService.SUMMARY_MARKER)
                .contains("local fallback summary")
                .contains("assistant 253");
    }

    @Test
    void estimatesLightCompactedToolResponsesWithoutChangingChatMemory() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);
        String longOutput = "large file output ".repeat(40);

        chatMemory.add("c1", List.of(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "readFile", longOutput)))
                .build()));

        ConversationCompactionService.LightCompactionResult result =
                service.estimateLightCompactToolResponses("c1", System.currentTimeMillis());

        assertThat(result.compactedToolResponses()).isEqualTo(1);
        assertThat(result.compactedChars()).isEqualTo(longOutput.length());
        ToolResponseMessage stored = (ToolResponseMessage) chatMemory.get("c1").get(0);
        assertThat(stored.getResponses().get(0).responseData()).isEqualTo(longOutput);
    }

    @Test
    void doesNotCountShortOutputWhenPlaceholderWouldBeLonger() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", List.of(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "getDateTime", "2026-06-30T17:00:00")))
                .build()));

        ConversationCompactionService.LightCompactionResult result =
                service.estimateLightCompactToolResponses("c1", System.currentTimeMillis());

        assertThat(result.compactedToolResponses()).isZero();
        assertThat(result.compactedChars()).isZero();
    }

    @Test
    void doesNotCountWhitelistedReadMemoryOutputAsLightCompactionTarget() {
        RecordingChatMemory chatMemory = new RecordingChatMemory();
        RecordingChatModel chatModel = new RecordingChatModel("unused");
        ConversationCompactionService service = new ConversationCompactionService(chatMemory, chatModel);

        chatMemory.add("c1", List.of(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "read_memory", "memory body")))
                .build()));

        ConversationCompactionService.LightCompactionResult result =
                service.estimateLightCompactToolResponses("c1", System.currentTimeMillis());

        assertThat(result.compactedToolResponses()).isZero();
        assertThat(result.compactedChars()).isZero();
    }

    private List<Message> numberedMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(index % 2 == 0
                    ? new UserMessage("user " + index)
                    : new AssistantMessage("assistant " + index));
        }
        return messages;
    }

    private static final class RecordingChatMemory implements ChatMemory {

        private final List<Message> messages = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            this.messages.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(messages);
        }

        @Override
        public void clear(String conversationId) {
            messages.clear();
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private final String response;
        private int calls;
        private boolean fail;
        private Prompt lastPrompt;

        private RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastPrompt = prompt;
            if (fail) {
                throw new IllegalStateException("model unavailable");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
