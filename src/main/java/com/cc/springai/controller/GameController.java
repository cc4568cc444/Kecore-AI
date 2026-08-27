package com.cc.springai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.service.SimpleChatService;
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
@RequestMapping("/game")
public class GameController {
    private final SimpleChatService simpleChatService;
    private final ObjectMapper objectMapper;

    public GameController(SimpleChatService simpleChatService, ObjectMapper objectMapper) {
        this.simpleChatService = simpleChatService;
        this.objectMapper = objectMapper;
    }
    
    @GetMapping(value = "/chat", produces = "text/plain; charset=utf-8")
    public Flux<String> chat(@RequestParam String prompt,
                             @RequestParam(defaultValue = "default") String conv_id,
                             @RequestParam(required = false) String modelId) {
        return simpleChatService.game(prompt, conv_id, modelId);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String prompt,
                                 @RequestParam(defaultValue = "default") String conv_id,
                                 @RequestParam(required = false) String modelId,
                                 @RequestParam(required = false) String reasoningEffort,
                                 @RequestParam(required = false) String thinkingType,
                                 @RequestParam(required = false) String extraBody) {
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> complete(emitter, open));
        emitter.onError(error -> open.set(false));

        new Thread(() -> {
            try {
                simpleChatService.gameStream(prompt, conv_id, modelId,
                                new ModelRuntimeOptions(reasoningEffort, thinkingType, extraBody))
                        .doOnNext(event -> sendEvent(emitter, open, event.type(), Map.of("content", event.content())))
                        .blockLast();
                sendEvent(emitter, open, "complete", Map.of());
                complete(emitter, open);
            } catch (Exception ex) {
                sendEvent(emitter, open, "error", Map.of("message", ex.getMessage()));
                complete(emitter, open);
            }
        }).start();
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
