package com.cc.springai.agent;

import java.util.List;

public record AgentContextPreview(String conversationId,
                                  String workingDirectory,
                                  long usedTokens,
                                  long usedChars,
                                  int messageCount,
                                  List<ContextMessage> messages,
                                  List<ToolDefinitionPreview> tools) {

    public record ContextMessage(int index,
                                 String role,
                                 String text,
                                 int chars,
                                 List<ToolCallPreview> toolCalls,
                                 List<ToolResponsePreview> toolResponses) {
    }

    public record ToolCallPreview(String id, String type, String name, String arguments) {
    }

    public record ToolResponsePreview(String id, String name, String responseData) {
    }

    public record ToolDefinitionPreview(String name, String description, String inputSchema) {
    }
}
