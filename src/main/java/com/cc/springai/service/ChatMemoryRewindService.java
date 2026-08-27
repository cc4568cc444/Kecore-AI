package com.cc.springai.service;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatMemoryRewindService {

    private final ChatMemory chatMemory;

    public ChatMemoryRewindService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public void rewindLatestUserTurn(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<Message> history = new ArrayList<>(chatMemory.get(conversationId));
        int userIndex = lastUserMessageIndex(history);
        if (userIndex < 0) {
            return;
        }
        chatMemory.clear(conversationId);
        for (int i = 0; i < userIndex; i++) {
            chatMemory.add(conversationId, history.get(i));
        }
    }

    private int lastUserMessageIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getMessageType() == MessageType.USER) {
                return i;
            }
        }
        return -1;
    }
}
