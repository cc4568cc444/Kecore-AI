package com.cc.springai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.service.ChatMemoryRewindService;
import com.cc.springai.service.SimpleChatService;
import jakarta.servlet.http.HttpServletResponse;
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
@RequestMapping("/ai")
public class ChatController {
    private final SimpleChatService simpleChatService;
    private final ChatMemoryRewindService chatMemoryRewindService;
    private final ObjectMapper objectMapper;

    public ChatController(SimpleChatService simpleChatService,
                          ChatMemoryRewindService chatMemoryRewindService,
                          ObjectMapper objectMapper) {
        this.simpleChatService = simpleChatService;
        this.chatMemoryRewindService = chatMemoryRewindService;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/chat", produces = "text/plain; charset=utf-8")
    public Flux<String> chat(@RequestParam String prompt,
                             @RequestParam(defaultValue = "default") String conv_id,
                             @RequestParam(required = false) String modelId,
                             @RequestParam(defaultValue = "false") boolean regenerate) {
        if (regenerate) {
            chatMemoryRewindService.rewindLatestUserTurn(conv_id);
        }
        return simpleChatService.chat(prompt, conv_id, modelId);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String prompt,
                                 @RequestParam(defaultValue = "default") String conv_id,
                                 @RequestParam(required = false) String modelId,
                                 @RequestParam(required = false) String reasoningEffort,
                                 @RequestParam(required = false) String thinkingType,
                                 @RequestParam(required = false) String extraBody,
                                 @RequestParam(defaultValue = "false") boolean regenerate,
                                 HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        if (regenerate) {
            chatMemoryRewindService.rewindLatestUserTurn(conv_id);
        }
        return stream(simpleChatService.chatStream(prompt, conv_id, modelId,
                new ModelRuntimeOptions(reasoningEffort, thinkingType, extraBody)));
    }

    private SseEmitter stream(Flux<SimpleChatService.ModelStreamEvent> events) {
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> complete(emitter, open));
        emitter.onError(error -> open.set(false));

        new Thread(() -> {
            try {
                AtomicBoolean answerTextSent = new AtomicBoolean(false);
                events.doOnNext(event -> {
                            String content = event.content() == null ? "" : event.content();
                            if ("token".equals(event.type()) && !content.isBlank()) {
                                answerTextSent.set(true);
                            }
                            sendEvent(emitter, open, event.type(), Map.of("content", content));
                        })
                        .blockLast();
                if (!answerTextSent.get()) {
                    throw new IllegalStateException("模型请求已结束，但没有返回可显示的答案文本。请检查模型接口或更换模型后重试。");
                }
                sendEvent(emitter, open, "complete", Map.of());
                complete(emitter, open);
            } catch (Exception ex) {
                String message = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? "基本对话请求失败，请检查模型接口配置。"
                        : ex.getMessage();
                sendEvent(emitter, open, "error", Map.of("message", message));
                complete(emitter, open);
            }
        }, "basic-chat-stream").start();
        return emitter;
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
}
