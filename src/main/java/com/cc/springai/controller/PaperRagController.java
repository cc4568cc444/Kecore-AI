package com.cc.springai.controller;

import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.service.ChatMemoryRewindService;
import com.cc.springai.service.PaperRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/paper")
public class PaperRagController {

    private final PaperRagService paperRagService;
    private final ChatMemoryRewindService chatMemoryRewindService;
    private final ObjectMapper objectMapper;

    public PaperRagController(PaperRagService paperRagService,
                              ChatMemoryRewindService chatMemoryRewindService,
                              ObjectMapper objectMapper) {
        this.paperRagService = paperRagService;
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
        return paperRagService.chat(prompt, conversationId, modelId,
                PaperRagService.RetrievalMode.fromValue(retrievalStrategy));
    }

    @PostMapping(value = "/evaluation/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaperRagService.EvaluationResult evaluate(@RequestBody EvaluationRequest request) {
        String contextMode = request.contextMode() == null ? "parent-child" : request.contextMode();
        return paperRagService.evaluate(new PaperRagService.EvaluationQuery(
                request.questionId(),
                request.paperId(),
                request.question(),
                PaperRagService.EvaluationRetrievalMode.fromValue(request.retrievalMode()),
                PaperRagService.RetrievalMode.fromValue("child".equalsIgnoreCase(contextMode) ? "default" : contextMode),
                request.topK() == null ? 8 : request.topK(),
                request.modelId(),
                request.judgeModelId(),
                request.goldAnswers(),
                request.generateAnswer() == null || request.generateAnswer(),
                Boolean.TRUE.equals(request.judgeFaithfulness())));
    }

    @GetMapping(value = "/evaluation/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaperRagService.EvaluationConfig evaluationConfig() {
        return paperRagService.evaluationConfig();
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String prompt,
                                 @RequestParam(defaultValue = "default") String conv_id,
                                 @RequestParam(required = false) String modelId,
                                 @RequestParam(required = false) String reasoningEffort,
                                 @RequestParam(required = false) String thinkingType,
                                 @RequestParam(required = false) String extraBody,
                                 @RequestParam(required = false) String retrievalStrategy,
                                 @RequestParam(defaultValue = "false") boolean regenerate,
                                 HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        long startedAt = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> complete(emitter, open));
        emitter.onError(error -> open.set(false));

        new Thread(() -> {
            long planningMs = 0L;
            long retrievalMs = 0L;
            long modelMs = 0L;
            try {
                String conversationId = normalizeConversationId(conv_id);
                if (regenerate) {
                    chatMemoryRewindService.rewindLatestUserTurn(conversationId);
                }
                PaperRagService.PaperAnswerStream answer = paperRagService.prepareChat(
                        prompt, conversationId, modelId,
                        new ModelRuntimeOptions(reasoningEffort, thinkingType, extraBody),
                        PaperRagService.RetrievalMode.fromValue(retrievalStrategy));
                planningMs = answer.translationMs();
                retrievalMs = answer.retrievalMs();
                if (!send(emitter, open, "metrics", Map.of(
                        "translationMs", planningMs,
                        "retrievalMs", retrievalMs,
                        "retrievalQuery", answer.retrievalQuery()))) {
                    return;
                }
                long modelStartedAt = System.currentTimeMillis();
                AtomicBoolean answerTextSent = new AtomicBoolean(false);
                answer.content().doOnNext(event -> {
                    String content = event.content() == null ? "" : event.content();
                    if ("token".equals(event.type()) && !content.isBlank()) {
                        answerTextSent.set(true);
                    }
                    send(emitter, open, event.type(), Map.of("content", content));
                }).blockLast();
                modelMs = answer.modelRequested() ? elapsedMs(modelStartedAt) : 0L;
                if (answer.modelRequested() && !answerTextSent.get()) {
                    throw new IllegalStateException("模型请求已结束，但没有返回可显示的答案文本。请检查上游模型接口或更换模型后重试。");
                }
                send(emitter, open, "complete", Map.of(
                        "latencyMs", elapsedMs(startedAt),
                        "translationMs", planningMs,
                        "retrievalMs", retrievalMs,
                        "modelResponseMs", modelMs,
                        "retrievalQuery", answer.retrievalQuery()));
                complete(emitter, open);
            } catch (Exception ex) {
                send(emitter, open, "error", Map.of(
                        "message", ex.getMessage() == null ? "论文问答请求失败" : ex.getMessage(),
                        "latencyMs", elapsedMs(startedAt),
                        "translationMs", planningMs,
                        "retrievalMs", retrievalMs,
                        "modelResponseMs", modelMs,
                        "retrievalQuery", ""));
                complete(emitter, open);
            }
        }, "paper-rag-stream").start();
        return emitter;
    }

    private boolean send(SseEmitter emitter, AtomicBoolean open, String event, Object data) {
        if (!open.get()) return false;
        try {
            synchronized (emitter) {
                if (!open.get()) return false;
                emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
            }
            return true;
        } catch (Exception ignored) {
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

    private String normalizeConversationId(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    public record EvaluationRequest(String questionId,
                                    String paperId,
                                    String question,
                                    String retrievalMode,
                                    String contextMode,
                                    Integer topK,
                                    String modelId,
                                    String judgeModelId,
                                    java.util.List<String> goldAnswers,
                                    Boolean generateAnswer,
                                    Boolean judgeFaithfulness) {
    }
}
