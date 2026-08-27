package com.cc.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.constants.SystemConstants;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

@Service
public class SimpleChatService {

    private final ChatMemory chatMemory;
    private final ModelConfigService modelConfigService;

    public SimpleChatService(ChatMemory chatMemory, ModelConfigService modelConfigService) {
        this.chatMemory = chatMemory;
        this.modelConfigService = modelConfigService;
    }

    public Flux<String> chat(String prompt, String conversationId, String modelId) {
        return stream(chatClient(modelId), prompt, conversationId, modelId, null).map(ModelStreamEvent::content);
    }

    public Flux<String> game(String prompt, String conversationId, String modelId) {
        return stream(gameChatClient(modelId), prompt, conversationId, modelId, null).map(ModelStreamEvent::content);
    }

    public Flux<ModelStreamEvent> chatStream(String prompt, String conversationId, String modelId,
                                             ModelRuntimeOptions runtimeOptions) {
        return stream(chatClient(modelId), prompt, conversationId, modelId, runtimeOptions);
    }

    public Flux<ModelStreamEvent> gameStream(String prompt, String conversationId, String modelId,
                                             ModelRuntimeOptions runtimeOptions) {
        return stream(gameChatClient(modelId), prompt, conversationId, modelId, runtimeOptions);
    }

    private Flux<ModelStreamEvent> stream(ChatClient client, String prompt, String conversationId, String modelId,
                                          ModelRuntimeOptions runtimeOptions) {
        return client.prompt()
                .user(prompt)
                .options(modelConfigService.chatOptions(modelId, runtimeOptions))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatResponse()
                .flatMapIterable(this::eventsFromResponse)
                .onErrorMap(WebClientResponseException.class, this::modelRequestException);
    }

    private java.util.List<ModelStreamEvent> eventsFromResponse(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return java.util.List.of();
        }
        Generation generation = response.getResult();
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return java.util.List.of();
        }
        java.util.List<ModelStreamEvent> events = new java.util.ArrayList<>();
        String reasoning = reasoningContent(output);
        if (reasoning != null && !reasoning.isEmpty()) {
            events.add(new ModelStreamEvent("reasoning", reasoning));
        }
        String content = output.getText();
        if (content != null && !content.isEmpty()) {
            events.add(new ModelStreamEvent("token", content));
        }
        return events;
    }

    private String reasoningContent(AssistantMessage output) {
        Object direct = output.getMetadata() == null ? null : output.getMetadata().get("reasoningContent");
        if (direct == null && output.getMetadata() != null) {
            direct = output.getMetadata().get("reasoning_content");
        }
        return direct == null ? "" : String.valueOf(direct);
    }

    private ChatClient chatClient(String modelId) {
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem("你是一个可靠、简洁的 AI 助手。优先直接回答问题，必要时给出可执行步骤。")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    private ChatClient gameChatClient(String modelId) {
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    private IllegalStateException modelRequestException(WebClientResponseException e) {
        String responseBody = e.getResponseBodyAsString();
        String detail = responseBody == null || responseBody.isBlank() ? e.getMessage() : responseBody;
        return new IllegalStateException("模型请求被上游拒绝，HTTP " + e.getStatusCode().value() + ": " + detail, e);
    }

    public record ModelStreamEvent(String type, String content) {
    }
}
