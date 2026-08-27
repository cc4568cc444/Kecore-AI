package com.cc.springai.agent;

public record AgentContextUsage(long usedTokens,
                                long maxTokens,
                                double percent,
                                long usedChars,
                                int messageCount,
                                boolean compacted,
                                long estimatedTokens,
                                Integer actualPromptTokens,
                                int toolCount,
                                String tokenSource,
                                String tokenizerModel,
                                AgentAutoCompactionUsage autoCompaction) {
}
