package com.cc.springai.agent;

public record AgentContextRequest(String conversationId, String workingDirectory, Long lightCompactedBefore,
                                  String modelId) {
}
