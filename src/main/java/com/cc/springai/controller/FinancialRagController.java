package com.cc.springai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.service.ChatMemoryRewindService;
import com.cc.springai.service.FinancialRagService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/finance")
public class FinancialRagController {

    private final FinancialRagService financialRagService;
    private final ChatMemoryRewindService chatMemoryRewindService;
    private final ObjectMapper objectMapper;

    public FinancialRagController(FinancialRagService financialRagService,
                                  ChatMemoryRewindService chatMemoryRewindService,
                                  ObjectMapper objectMapper) {
        this.financialRagService = financialRagService;
        this.chatMemoryRewindService = chatMemoryRewindService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/chat", produces = "text/plain; charset=utf-8")
    public Flux<String> chat(@RequestParam String prompt,
                             @RequestParam(defaultValue = "default") String conv_id,
                             @RequestParam(required = false) String modelId,
                             @RequestParam(required = false) String retrievalStrategy,
                             @RequestParam(defaultValue = "false") boolean regenerate) {
        String conversationId = normalizeConversationId(conv_id);
        if (regenerate) {
            chatMemoryRewindService.rewindLatestUserTurn(conversationId);
        }
        return financialRagService.chat(prompt, conversationId, modelId,
                FinancialRagService.FinancialRetrievalMode.fromValue(retrievalStrategy));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String prompt,
                                 @RequestParam(defaultValue = "default") String conv_id,
                                 @RequestParam(required = false) String modelId,
                                 @RequestParam(required = false) String reasoningEffort,
                                 @RequestParam(required = false) String thinkingType,
                                 @RequestParam(required = false) String extraBody,
                                 @RequestParam(required = false) String retrievalStrategy,
                                 @RequestParam(defaultValue = "false") boolean regenerate) {
        long startedAtMs = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> complete(emitter, open));
        emitter.onError(error -> open.set(false));

        new Thread(() -> {
            long translationMs = 0L;
            long retrievalMs = 0L;
            long modelResponseMs = 0L;
            try {
                String conversationId = normalizeConversationId(conv_id);
                if (regenerate) {
                    chatMemoryRewindService.rewindLatestUserTurn(conversationId);
                }
                FinancialRagService.FinancialAnswerStream answer = financialRagService.prepareChat(
                        prompt, conversationId, modelId,
                        new ModelRuntimeOptions(reasoningEffort, thinkingType, extraBody),
                        FinancialRagService.FinancialRetrievalMode.fromValue(retrievalStrategy));
                translationMs = answer.translationMs();
                retrievalMs = answer.retrievalMs();
                if (!sendEvent(emitter, open, "metrics", Map.of(
                        "translationMs", translationMs,
                        "retrievalMs", retrievalMs,
                        "retrievalQuery", answer.retrievalQuery()))) {
                    return;
                }

                long modelStartedAt = System.currentTimeMillis();
                answer.content()
                        .doOnNext(event -> sendEvent(emitter, open, event.type(), Map.of("content", event.content())))
                        .blockLast();
                modelResponseMs = answer.modelRequested() ? elapsedMs(modelStartedAt) : 0L;
                sendEvent(emitter, open, "complete", Map.of(
                        "latencyMs", elapsedMs(startedAtMs),
                        "translationMs", translationMs,
                        "retrievalMs", retrievalMs,
                        "modelResponseMs", modelResponseMs,
                        "retrievalQuery", answer.retrievalQuery()));
                complete(emitter, open);
            } catch (Exception ex) {
                sendEvent(emitter, open, "error", Map.of(
                        "message", ex.getMessage(),
                        "latencyMs", elapsedMs(startedAtMs),
                        "translationMs", translationMs,
                        "retrievalMs", retrievalMs,
                        "modelResponseMs", modelResponseMs,
                        "retrievalQuery", ""));
                complete(emitter, open);
            }
        }).start();

        return emitter;
    }

    @GetMapping(value = "/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public FinancialRagService.FinancialAnalysisResult analyze(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "default") String conv_id,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String retrievalStrategy) {
        return financialRagService.analyze(prompt, normalizeConversationId(conv_id), modelId,
                FinancialRagService.FinancialRetrievalMode.fromValue(retrievalStrategy));
    }

    private boolean sendEvent(SseEmitter emitter, AtomicBoolean open, String eventName, Object data) {
        if (!open.get()) {
            return false;
        }
        try {
            synchronized (emitter) {
                if (!open.get()) {
                    return false;
                }
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(objectMapper.writeValueAsString(data)));
            }
            return true;
        } catch (Exception e) {
            open.set(false);
            return false;
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean open) {
        if (open.compareAndSet(true, false)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    private long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private String normalizeConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? "default" : conversationId;
    }
}
