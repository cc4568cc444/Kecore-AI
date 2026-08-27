package com.cc.springai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.service.RagService;
import com.cc.springai.service.SessionPersistenceService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final String DEFAULT_AGENT_WORKING_DIRECTORY = "D:\\AgentWorkshop";

    private final SessionPersistenceService sessionPersistenceService;
    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final ChatMemory chatMemory;

    public SessionController(SessionPersistenceService sessionPersistenceService,
                             RagService ragService,
                             ObjectMapper objectMapper,
                             ChatMemory chatMemory) {
        this.sessionPersistenceService = sessionPersistenceService;
        this.ragService = ragService;
        this.objectMapper = objectMapper;
        this.chatMemory = chatMemory;
    }

    @GetMapping
    public List<JsonNode> listSessions() throws Exception {
        List<JsonNode> sessions = new ArrayList<>();
        for (String payload : sessionPersistenceService.listPayloads()) {
            sessions.add(objectMapper.readTree(payload));
        }
        return sessions;
    }

    @GetMapping("/default-working-directory")
    public Map<String, String> defaultWorkingDirectory() {
        return Map.of("workingDirectory", DEFAULT_AGENT_WORKING_DIRECTORY);
    }

    @PutMapping
    public void saveSessions(@RequestBody List<JsonNode> sessions) throws Exception {
        for (JsonNode session : sessions) {
            sessionPersistenceService.save(session, objectMapper.writeValueAsString(session));
        }
    }

    @DeleteMapping("/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        sessionPersistenceService.delete(sessionId);
        chatMemory.clear(sessionId);
        ragService.deleteConversation(sessionId);
    }
}
