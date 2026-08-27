package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ConversationCompactionService {

    static final String SUMMARY_MARKER = "[AUTO_COMPACTED_CONVERSATION_SUMMARY]";
    public static final String LIGHT_TOOL_OUTPUT_MARKER = "[LIGHT_COMPACTED_TOOL_OUTPUT]";
    public static final Set<String> LIGHT_COMPACTION_TOOL_WHITELIST = Set.of("read_memory");
    private static final String TOOL_RESPONSE_RECORDED_AT_METADATA = "recordedAt";

    private static final int MESSAGE_COUNT_THRESHOLD = 256;
    private static final int TEXT_LENGTH_THRESHOLD = 60_000;
    private static final int RECENT_MESSAGE_COUNT = 16;
    private static final int MAX_SOURCE_CHARS = 80_000;
    private static final int MAX_SUMMARY_CHARS = 8_000;
    public static final long MAX_CONTEXT_TOKENS = 128_000L;
    private static final double FALLBACK_CHARS_PER_TOKEN = 4.0d;

    private final ChatMemory chatMemory;
    private final ChatModel chatModel;
    private final TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();
    private final EncodingRegistry encodingRegistry = Encodings.newLazyEncodingRegistry();
    private final ObjectMapper tokenObjectMapper = new ObjectMapper();

    public ConversationCompactionService(ChatMemory chatMemory, ChatModel chatModel) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
    }

    public boolean compactIfNeeded(String conversationId) {
        return compactIfNeeded(conversationId, null);
    }

    public boolean compactIfNeeded(String conversationId, ChatModel compactionModel) {
        return compact(conversationId, false, compactionModel);
    }

    public boolean compactNow(String conversationId) {
        return compactNow(conversationId, null);
    }

    public boolean compactNow(String conversationId, ChatModel compactionModel) {
        return compact(conversationId, true, compactionModel);
    }

    public LightCompactionResult estimateLightCompactToolResponses(String conversationId, Long lightCompactedBefore) {
        List<Message> history = new ArrayList<>(chatMemory.get(conversationId));
        if (history.isEmpty() || lightCompactedBefore == null || lightCompactedBefore <= 0) {
            return new LightCompactionResult(0, 0);
        }

        int compactedResponses = 0;
        long compactedChars = 0;
        for (Message message : history) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            if (!shouldLightCompactToolResponse(toolResponseMessage, lightCompactedBefore)) {
                continue;
            }

            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                String data = response.responseData() == null ? "" : response.responseData();
                if (shouldSkipLightCompaction(response.name(), data)) {
                    continue;
                }
                compactedResponses++;
                compactedChars += data.length();
            }
        }
        return new LightCompactionResult(compactedResponses, compactedChars);
    }

    private boolean shouldLightCompactToolResponse(ToolResponseMessage message, long lightCompactedBefore) {
        Object recordedAtValue = message.getMetadata() == null
                ? null
                : message.getMetadata().get(TOOL_RESPONSE_RECORDED_AT_METADATA);
        long recordedAt = recordedAtValue instanceof Number number ? number.longValue() : 0L;
        return recordedAt <= 0L || recordedAt <= lightCompactedBefore;
    }

    public static boolean shouldSkipLightCompaction(String toolName, String responseData) {
        if (toolName != null && LIGHT_COMPACTION_TOOL_WHITELIST.contains(toolName)) {
            return true;
        }
        String data = responseData == null ? "" : responseData;
        if (data.startsWith(LIGHT_TOOL_OUTPUT_MARKER)) {
            return true;
        }
        return lightToolOutputPlaceholder(toolName, data).length() >= data.length();
    }

    public static String lightToolOutputPlaceholder(String toolName, String output) {
        String name = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        int outputChars = output == null ? 0 : output.length();
        return "%s Tool output is hidden from model context by light compaction. Tool: %s. Original output length: %d chars. Ask the user to re-run or inspect the visible UI history if exact output is needed."
                .formatted(LIGHT_TOOL_OUTPUT_MARKER, name, outputChars);
    }

    public ContextUsage estimateUsage(List<Message> messages) {
        return estimatePromptUsage(messages, List.of(), null);
    }

    public ContextUsage estimatePromptUsage(List<Message> messages, List<ToolCallback> toolCallbacks,
                                            String modelName) {
        long usedTokens = estimateTokens(messages);
        long promptTokens = estimatePromptTokens(messages, toolCallbacks, modelName);
        if (promptTokens > 0) {
            usedTokens = promptTokens;
        }
        long usedChars = totalTextLength(messages);
        double percent = usedTokens * 100.0 / MAX_CONTEXT_TOKENS;
        return new ContextUsage(
                usedTokens,
                MAX_CONTEXT_TOKENS,
                Math.min(100.0, percent),
                usedChars,
                messages == null ? 0 : messages.size(),
                toolCallbacks == null ? 0 : toolCallbacks.size(),
                modelName == null || modelName.isBlank() ? "" : modelName,
                promptTokens > 0 ? "jtokkit" : "spring-ai-estimator"
        );
    }

    public AutoCompactionProgress estimateAutoCompactionProgress(List<Message> messages) {
        int messageCount = conversationMessageCount(messages);
        long textChars = totalTextLength(messages);
        long messageTokens = estimateMessageTokens(messages);
        long textTokens = estimateTextTokens(textChars);
        long actualTokens = estimateTokens(messages);
        double messagePercent = percentage(messageTokens, MAX_CONTEXT_TOKENS);
        double textPercent = percentage(textTokens, MAX_CONTEXT_TOKENS);
        double tokenPercent = percentage(actualTokens, MAX_CONTEXT_TOKENS);
        return new AutoCompactionProgress(
                Math.max(tokenPercent, Math.max(messagePercent, textPercent)),
                tokenPercent,
                messagePercent,
                textPercent,
                messageCount,
                MESSAGE_COUNT_THRESHOLD,
                Math.max(0, MESSAGE_COUNT_THRESHOLD + 1 - messageCount),
                actualTokens,
                MAX_CONTEXT_TOKENS,
                Math.max(0, MAX_CONTEXT_TOKENS + 1L - actualTokens),
                shouldCompact(messages)
        );
    }

    private boolean compact(String conversationId, boolean force, ChatModel compactionModel) {
        List<Message> history = new ArrayList<>(chatMemory.get(conversationId));
        if (!force && !shouldCompact(history)) {
            return false;
        }

        List<Message> summaryMessages = history.stream()
                .filter(this::isSummaryMessage)
                .toList();
        List<Message> conversationMessages = history.stream()
                .filter(message -> !isSummaryMessage(message))
                .toList();
        if (conversationMessages.size() < (force ? 4 : 2)) {
            return false;
        }

        int recentCount = recentMessageCount(conversationMessages.size(), force);
        int compactUntil = Math.max(1, conversationMessages.size() - recentCount);
        if (compactUntil >= conversationMessages.size()) {
            compactUntil = conversationMessages.size() - 1;
        }
        compactUntil = includePendingToolResponses(conversationMessages, compactUntil);

        List<Message> messagesToCompact = conversationMessages.subList(0, compactUntil);
        List<Message> recentMessages = conversationMessages.subList(compactUntil, conversationMessages.size());
        String previousSummary = mergeSummaries(summaryMessages);
        String summary = summarize(previousSummary, messagesToCompact, compactionModel);

        List<Message> compacted = new ArrayList<>();
        compacted.add(new SystemMessage(SUMMARY_MARKER + "\n" + summary));
        compacted.addAll(recentMessages);

        chatMemory.clear(conversationId);
        chatMemory.add(conversationId, compacted);
        return true;
    }

    boolean shouldCompact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int conversationMessageCount = conversationMessageCount(messages);
        long actualTokens = estimateTokens(messages);
        long messageTokens = estimateMessageTokens(messages);
        long textTokens = estimateTextTokens(totalTextLength(messages));
        return conversationMessageCount > MESSAGE_COUNT_THRESHOLD
                || actualTokens > MAX_CONTEXT_TOKENS
                || messageTokens > MAX_CONTEXT_TOKENS
                || textTokens > MAX_CONTEXT_TOKENS;
    }

    private int recentMessageCount(int conversationMessageCount, boolean force) {
        if (force && conversationMessageCount <= MESSAGE_COUNT_THRESHOLD) {
            return Math.max(2, conversationMessageCount / 2);
        }
        if (conversationMessageCount > RECENT_MESSAGE_COUNT) {
            return RECENT_MESSAGE_COUNT;
        }
        return Math.max(1, conversationMessageCount / 2);
    }

    private int includePendingToolResponses(List<Message> messages, int compactUntil) {
        int adjusted = compactUntil;
        while (splitsToolExchange(messages, adjusted)) {
            adjusted++;
        }
        return Math.min(adjusted, messages.size());
    }

    private boolean splitsToolExchange(List<Message> messages, int compactUntil) {
        if (messages == null || compactUntil <= 0 || compactUntil >= messages.size()) {
            return false;
        }
        Message lastCompacted = messages.get(compactUntil - 1);
        Message firstRecent = messages.get(compactUntil);
        return lastCompacted instanceof AssistantMessage assistantMessage
                && assistantMessage.getToolCalls() != null
                && !assistantMessage.getToolCalls().isEmpty()
                && firstRecent instanceof ToolResponseMessage;
    }

    private String summarize(String previousSummary, List<Message> messagesToCompact, ChatModel compactionModel) {
        String source = truncateMiddle(formatMessages(messagesToCompact), MAX_SOURCE_CHARS);
        try {
            ChatModel summaryModel = compactionModel == null ? chatModel : compactionModel;
            ChatResponse response = summaryModel.call(new Prompt(List.of(
                    new SystemMessage("""
                            You compact chat history for a coding agent.
                            Return only a concise durable summary. Preserve goals, confirmed constraints,
                            key facts, files, commands, tool results, open TODOs, user preferences, and failed paths.
                            Do not answer the user and do not invent details.
                            """),
                    new UserMessage("""
                            Previous summary:
                            %s

                            Messages to compact:
                            %s

                            Use this exact shape:
                            Goal:
                            Confirmed constraints:
                            Key facts:
                            Files and directories:
                            Actions already taken:
                            Tool results:
                            Open TODOs:
                            User preferences:
                            Failed paths to avoid:
                            """.formatted(blankToNone(previousSummary), source))
            )));
            String summary = response.getResult().getOutput().getText();
            if (summary != null && !summary.isBlank()) {
                return normalizeSummary(summary);
            }
        } catch (Exception ignored) {
        }
        return fallbackSummary(previousSummary, messagesToCompact);
    }

    private String fallbackSummary(String previousSummary, List<Message> messagesToCompact) {
        StringBuilder summary = new StringBuilder();
        summary.append("Goal:\n");
        summary.append("- Earlier context was compacted with a local fallback summary.\n");
        summary.append("Confirmed constraints:\n");
        summary.append("- Preserve the recent raw messages that follow this summary.\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            summary.append("Key facts:\n");
            summary.append(truncateEnd(stripMarker(previousSummary), 2_000)).append('\n');
        }
        summary.append("Actions already taken:\n");
        int start = Math.max(0, messagesToCompact.size() - 12);
        for (Message message : messagesToCompact.subList(start, messagesToCompact.size())) {
            summary.append("- ")
                    .append(roleOf(message))
                    .append(": ")
                    .append(truncateEnd(oneLine(formatMessageBody(message)), 500))
                    .append('\n');
        }
        summary.append("Open TODOs:\n");
        summary.append("- Review the preserved recent messages for the active task state.\n");
        return normalizeSummary(summary.toString());
    }

    private String mergeSummaries(List<Message> summaryMessages) {
        if (summaryMessages.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (Message message : summaryMessages) {
            String text = stripMarker(message.getText());
            if (!text.isBlank()) {
                if (!merged.isEmpty()) {
                    merged.append("\n\n");
                }
                merged.append(text.strip());
            }
        }
        return truncateEnd(merged.toString(), MAX_SUMMARY_CHARS);
    }

    private boolean isSummaryMessage(Message message) {
        return message instanceof SystemMessage
                && message.getText() != null
                && message.getText().startsWith(SUMMARY_MARKER);
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder formatted = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            formatted.append("### Message ")
                    .append(index + 1)
                    .append(" (")
                    .append(roleOf(message))
                    .append(")\n")
                    .append(formatMessageBody(message))
                    .append("\n\n");
        }
        return formatted.toString().strip();
    }

    private String formatMessageBody(Message message) {
        if (message == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        String text = message.getText();
        if (text != null && !text.isBlank()) {
            body.append(text.strip());
        }
        if (message instanceof AssistantMessage assistantMessage
                && assistantMessage.getToolCalls() != null
                && !assistantMessage.getToolCalls().isEmpty()) {
            appendSectionHeader(body, "Tool calls");
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                body.append("- id: ").append(blankToNone(toolCall.id())).append('\n')
                        .append("  type: ").append(blankToNone(toolCall.type())).append('\n')
                        .append("  name: ").append(blankToNone(toolCall.name())).append('\n')
                        .append("  arguments: ").append(blankToNone(toolCall.arguments())).append('\n');
            }
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && toolResponseMessage.getResponses() != null
                && !toolResponseMessage.getResponses().isEmpty()) {
            appendSectionHeader(body, "Tool responses");
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                body.append("- id: ").append(blankToNone(response.id())).append('\n')
                        .append("  name: ").append(blankToNone(response.name())).append('\n')
                        .append("  data: ").append(blankToNone(response.responseData())).append('\n');
            }
        }
        return body.toString().strip();
    }

    private void appendSectionHeader(StringBuilder body, String header) {
        if (!body.isEmpty()) {
            body.append("\n\n");
        }
        body.append(header).append(":\n");
    }

    private String roleOf(Message message) {
        MessageType type = message.getMessageType();
        if (type == null) {
            return "unknown";
        }
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    private int totalTextLength(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream()
                .map(Message::getText)
                .filter(text -> text != null)
                .mapToInt(String::length)
                .sum();
    }

    private int conversationMessageCount(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        return (int) messages.stream()
                .filter(message -> !isSummaryMessage(message))
                .count();
    }

    private long estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Message message : messages) {
            total += estimateMessageTokens(message);
        }
        return total;
    }

    private long estimateMessageTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        return messages.stream()
                .mapToLong(this::estimateMessageTokens)
                .sum();
    }

    private long estimateMessageTokens(Message message) {
        if (message == null) {
            return 0L;
        }
        try {
            long total = tokenCountEstimator.estimate(message.getText());
            if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMessage
                    && assistantMessage.getToolCalls() != null) {
                for (org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    total += tokenCountEstimator.estimate(toolCall.id());
                    total += tokenCountEstimator.estimate(toolCall.type());
                    total += tokenCountEstimator.estimate(toolCall.name());
                    total += tokenCountEstimator.estimate(toolCall.arguments());
                }
            }
            if (message instanceof ToolResponseMessage toolResponseMessage
                    && toolResponseMessage.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    total += tokenCountEstimator.estimate(response.id());
                    total += tokenCountEstimator.estimate(response.name());
                    total += tokenCountEstimator.estimate(response.responseData());
                }
            }
            return Math.max(0, total);
        } catch (Exception ignored) {
            String text = message.getText();
            if (text == null || text.isBlank()) {
                return 0L;
            }
            return Math.max(1L, Math.round(text.length() / FALLBACK_CHARS_PER_TOKEN));
        }
    }

    private long estimateTextTokens(long chars) {
        if (chars <= 0) {
            return 0L;
        }
        return Math.max(1L, Math.round(chars / FALLBACK_CHARS_PER_TOKEN));
    }

    private long estimatePromptTokens(List<Message> messages, List<ToolCallback> toolCallbacks, String modelName) {
        if ((messages == null || messages.isEmpty()) && (toolCallbacks == null || toolCallbacks.isEmpty())) {
            return 0L;
        }
        try {
            return countTokens(serializePromptForTokenCount(messages, toolCallbacks, modelName), modelName);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long countTokens(String value, String modelName) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        Encoding encoding = encodingFor(modelName);
        return Math.max(1L, encoding.countTokens(value));
    }

    private Encoding encodingFor(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            try {
                return encodingRegistry.getEncodingForModel(modelName).orElseGet(this::defaultEncoding);
            } catch (Exception ignored) {
            }
        }
        return defaultEncoding();
    }

    private Encoding defaultEncoding() {
        try {
            return encodingRegistry.getEncoding(EncodingType.O200K_BASE);
        } catch (Exception ignored) {
            return encodingRegistry.getEncoding(EncodingType.CL100K_BASE);
        }
    }

    private String serializePromptForTokenCount(List<Message> messages, List<ToolCallback> toolCallbacks,
                                                String modelName) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        if (modelName != null && !modelName.isBlank()) {
            request.put("model", modelName);
        }
        request.put("messages", (messages == null ? List.<Message>of() : messages).stream()
                .map(this::messagePayload)
                .toList());
        List<Map<String, Object>> tools = toolPayloads(toolCallbacks);
        if (!tools.isEmpty()) {
            request.put("tools", tools);
        }
        return tokenObjectMapper.writeValueAsString(request);
    }

    private Map<String, Object> messagePayload(Message message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", roleOf(message));
        payload.put("content", message == null || message.getText() == null ? "" : message.getText());
        if (message instanceof AssistantMessage assistantMessage
                && assistantMessage.getToolCalls() != null
                && !assistantMessage.getToolCalls().isEmpty()) {
            payload.put("tool_calls", assistantMessage.getToolCalls().stream()
                    .map(this::toolCallPayload)
                    .toList());
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && toolResponseMessage.getResponses() != null
                && !toolResponseMessage.getResponses().isEmpty()) {
            payload.put("tool_responses", toolResponseMessage.getResponses().stream()
                    .map(this::toolResponsePayload)
                    .toList());
        }
        if (message instanceof UserMessage userMessage
                && userMessage.getMedia() != null
                && !userMessage.getMedia().isEmpty()) {
            payload.put("media", userMessage.getMedia().stream()
                    .map(String::valueOf)
                    .toList());
        }
        return payload;
    }

    private Map<String, Object> toolCallPayload(AssistantMessage.ToolCall toolCall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", toolCall.id());
        payload.put("type", toolCall.type());
        payload.put("function", Map.of(
                "name", toolCall.name() == null ? "" : toolCall.name(),
                "arguments", toolCall.arguments() == null ? "" : toolCall.arguments()
        ));
        return payload;
    }

    private Map<String, Object> toolResponsePayload(ToolResponseMessage.ToolResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_call_id", response.id());
        payload.put("name", response.name());
        payload.put("content", response.responseData() == null ? "" : response.responseData());
        return payload;
    }

    private List<Map<String, Object>> toolPayloads(List<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return List.of();
        }
        return toolCallbacks.stream()
                .map(ToolCallback::getToolDefinition)
                .filter(definition -> definition != null && definition.name() != null && !definition.name().isBlank())
                .map(this::toolPayload)
                .toList();
    }

    private Map<String, Object> toolPayload(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.name());
        function.put("description", definition.description() == null ? "" : definition.description());
        function.put("parameters", schemaPayload(definition.inputSchema()));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Object schemaPayload(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Map.of();
        }
        try {
            return tokenObjectMapper.readValue(inputSchema, Object.class);
        } catch (Exception ignored) {
            return inputSchema;
        }
    }

    private double percentage(long value, long threshold) {
        if (threshold <= 0) {
            return 100.0;
        }
        return Math.min(100.0, value * 100.0 / threshold);
    }

    public record ContextUsage(long usedTokens, long maxTokens, double percent, long usedChars, int messageCount,
                               int toolCount, String tokenizerModel, String tokenSource) {
    }

    public record AutoCompactionProgress(double percent, double tokenPercent, double messagePercent, double textPercent,
                                         int messageCount, int messageThreshold, int messagesUntilAutoCompact,
                                         long actualTokens, long tokenThreshold, long tokensUntilAutoCompact,
                                         boolean wouldCompact) {
    }

    public record LightCompactionResult(int compactedToolResponses, long compactedChars) {
    }

    private String normalizeSummary(String summary) {
        String normalized = stripMarker(summary).strip();
        if (normalized.isBlank()) {
            normalized = "Goal:\n- Earlier conversation context was compacted.";
        }
        return truncateEnd(normalized, MAX_SUMMARY_CHARS);
    }

    private String stripMarker(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(SUMMARY_MARKER, "").strip();
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    private String truncateMiddle(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        int head = (int) (maxChars * 0.6);
        int tail = maxChars - head;
        return value.substring(0, head)
                + "\n\n[...omitted during compaction...]\n\n"
                + value.substring(value.length() - tail);
    }

    private String truncateEnd(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 40)).strip()
                + "\n[summary truncated]";
    }
}
