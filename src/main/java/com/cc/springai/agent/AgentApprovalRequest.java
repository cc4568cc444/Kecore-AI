package com.cc.springai.agent;

public record AgentApprovalRequest(String runId,
                                   String approvalMode,
                                   String modelId,
                                   String reasoningEffort,
                                   String thinkingType,
                                   String extraBody) {
}
