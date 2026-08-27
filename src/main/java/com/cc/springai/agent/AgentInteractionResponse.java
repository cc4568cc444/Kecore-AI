package com.cc.springai.agent;

public record AgentInteractionResponse(String runId,
                                       String value,
                                       String approvalMode,
                                       String modelId,
                                       String reasoningEffort,
                                       String thinkingType,
                                       String extraBody) {
}
