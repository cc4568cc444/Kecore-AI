package com.cc.springai.agent;

public record AgentApprovalModeRequest(String conversationId, String approvalMode, Boolean sandboxEnabled,
                                       String workingDirectory) {
}
