package com.cc.springai.agent;

public record AgentAutoCompactionUsage(double percent,
                                       double tokenPercent,
                                       double messagePercent,
                                       double textPercent,
                                       int messageCount,
                                       int messageThreshold,
                                       int messagesUntilAutoCompact,
                                       long actualTokens,
                                       long tokenThreshold,
                                       long tokensUntilAutoCompact,
                                       boolean wouldCompact) {
}
