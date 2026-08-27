package com.cc.springai.agent;

import java.util.List;

public record AgentReply(String status,
                         String content,
                         String runId,
                         List<ToolRequest> tools,
                         List<String> activities,
                         List<ExecutedToolBatch> executions,
                         InteractionRequest interaction,
                         Long latencyMs,
                         TokenUsage tokenUsage) {

    public static AgentReply completed(String content, List<String> activities, List<ExecutedToolBatch> executions) {
        return completed(content, activities, executions, null);
    }

    public static AgentReply completed(String content, List<String> activities, List<ExecutedToolBatch> executions,
                                       TokenUsage tokenUsage) {
        return new AgentReply("completed", content, null, List.of(), activities, executions, null, null, tokenUsage);
    }

    public static AgentReply approvalRequired(String runId,
                                              List<ToolRequest> tools,
                                              List<String> activities,
                                              List<ExecutedToolBatch> executions) {
        return new AgentReply("approval_required", "", runId, tools, activities, executions, null, null, null);
    }

    public static AgentReply interactionRequired(String runId,
                                                 List<ToolRequest> tools,
                                                 InteractionRequest interaction,
                                                 List<String> activities,
                                                 List<ExecutedToolBatch> executions) {
        return new AgentReply("interaction_required", "", runId, tools, activities, executions, interaction, null, null);
    }

    public static AgentReply loopLimitRequired(String runId,
                                               InteractionRequest interaction,
                                               List<String> activities,
                                               List<ExecutedToolBatch> executions) {
        return new AgentReply("loop_limit_required", "", runId, List.of(), activities, executions, interaction, null, null);
    }

    public AgentReply withLatency(long latencyMs) {
        return new AgentReply(status, content, runId, tools, activities, executions, interaction, latencyMs, tokenUsage);
    }

    public AgentReply withTokenUsage(TokenUsage tokenUsage) {
        return new AgentReply(status, content, runId, tools, activities, executions, interaction, latencyMs, tokenUsage);
    }
}
