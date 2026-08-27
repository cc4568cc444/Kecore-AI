package com.cc.springai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.AgentApprovalModeRequest;
import com.cc.springai.agent.AgentApprovalRequest;
import com.cc.springai.agent.AgentAutoCompactionUsage;
import com.cc.springai.agent.AgentChatRequest;
import com.cc.springai.agent.AgentContextRequest;
import com.cc.springai.agent.AgentContextPreview;
import com.cc.springai.agent.AgentContextUsage;
import com.cc.springai.agent.AgentInteractionResponse;
import com.cc.springai.agent.AgentLightCompactionResult;
import com.cc.springai.agent.AgentReply;
import com.cc.springai.agent.AgentSandboxContext;
import com.cc.springai.agent.AgentToolOptionsFactory;
import com.cc.springai.agent.AgentToolPolicy;
import com.cc.springai.agent.ExecutedTool;
import com.cc.springai.agent.ExecutedToolBatch;
import com.cc.springai.agent.InteractionRequest;
import com.cc.springai.agent.ModelRuntimeOptions;
import com.cc.springai.agent.TokenUsage;
import com.cc.springai.agent.ToolRequest;
import com.cc.springai.constants.SystemConstants;
import com.cc.springai.service.ChatMemoryRewindService;
import com.cc.springai.service.ConversationCompactionService;
import com.cc.springai.service.MemoryService;
import com.cc.springai.service.ModelConfigService;
import com.cc.springai.service.RagService;
import com.cc.springai.service.TaskAgentService;
import com.cc.springai.skill.AgentSkillService;
import com.cc.springai.skill.RenderedSkill;
import com.cc.springai.tools.TaskTools;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.ai.content.Media;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.cc.springai.agent.AgentToolPolicy.RAG_SEARCH_TOOL;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final int INITIAL_TOOL_ROUND_LIMIT = 64;
    private static final int HARD_TOOL_ROUND_LIMIT = 256;
    private static final int MAX_USER_INTERACTION_ROUNDS = 3;
    private static final long STREAM_TIMEOUT_MILLIS = 0L;
    private static final long STREAM_HEARTBEAT_INTERVAL_MILLIS = 15000L;
    private static final Duration MODEL_STREAM_TIMEOUT = Duration.ofMinutes(15);
    private static final int MAX_TOOL_ARGUMENT_EVENT_CHARS = 8_000;
    private static final int MAX_TOOL_OUTPUT_EVENT_CHARS = 20_000;
    private static final int MAX_TOOL_RESPONSE_CONTEXT_CHARS = 8_000;
    private static final String TOOL_RESPONSE_RECORDED_AT_METADATA = "recordedAt";

    @Autowired
    private OpenAiChatModel model;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private TaskTools taskTools;

    @Autowired
    private TaskAgentService taskAgentService;

    @Autowired
    private RagService ragService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private ConversationCompactionService conversationCompactionService;

    @Autowired
    private ChatMemoryRewindService chatMemoryRewindService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentSkillService skillService;

    @Autowired
    private AgentToolPolicy agentToolPolicy;

    @Autowired
    private AgentToolOptionsFactory agentToolOptionsFactory;

    @Autowired
    private ModelConfigService modelConfigService;

    private final Map<String, PendingRun> pendingRuns = new ConcurrentHashMap<>();
    private final Map<String, ApprovalMode> approvalModes = new ConcurrentHashMap<>();
    private final Map<String, AgentSandboxContext> sandboxContexts = new ConcurrentHashMap<>();
    private final Map<String, ActualPromptUsage> actualPromptUsages = new ConcurrentHashMap<>();
    private final ThreadLocal<String> activeModelId = new ThreadLocal<>();
    private final ThreadLocal<ModelRuntimeOptions> activeRuntimeOptions = new ThreadLocal<>();
    private final ThreadLocal<String> activeConversationId = new ThreadLocal<>();
    private final ThreadLocal<Long> activeLightCompactedBefore = new ThreadLocal<>();

    @GetMapping("/context")
    public AgentContextUsage contextUsage(@RequestParam String conversationId,
                                          @RequestParam String workingDirectory,
                                          @RequestParam(required = false) Long lightCompactedBefore,
                                          @RequestParam(required = false) String modelId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String directory = requireWorkingDirectory(workingDirectory);
        return contextUsageResponse(normalizedConversationId, directory, lightCompactedBefore, modelId, false);
    }

    @GetMapping("/context/preview")
    public AgentContextPreview contextPreview(@RequestParam String conversationId,
                                              @RequestParam String workingDirectory,
                                              @RequestParam(required = false) Long lightCompactedBefore,
                                              @RequestParam(required = false) String approvalMode,
                                              @RequestParam(required = false) Boolean sandboxEnabled,
                                              @RequestParam(required = false) String modelId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String directory = requireWorkingDirectory(workingDirectory);
        ApprovalMode mode = ApprovalMode.from(approvalMode);
        AgentSandboxContext sandboxContext = sandboxContext(directory, sandboxEnabled, mode);
        List<Message> messages = buildCurrentContextMessages(normalizedConversationId, directory, lightCompactedBefore);
        OpenAiChatOptions options = agentOptions(directory, normalizedConversationId, messages, sandboxContext);
        String modelName = resolvedModelName(modelId);
        ConversationCompactionService.ContextUsage usage = conversationCompactionService.estimatePromptUsage(
                messages,
                options.getToolCallbacks() == null ? List.of() : options.getToolCallbacks(),
                modelName);
        return new AgentContextPreview(
                normalizedConversationId,
                directory,
                usage.usedTokens(),
                messages.stream().map(Message::getText).filter(Objects::nonNull).mapToLong(String::length).sum(),
                messages.size(),
                IntStream.range(0, messages.size())
                        .mapToObj(index -> contextMessage(index, messages.get(index)))
                        .toList(),
                toolPreviews(directory, normalizedConversationId, messages, sandboxContext)
        );
    }

    @PostMapping("/context/compact")
    public AgentContextUsage compactContext(@RequestBody AgentContextRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        String workingDirectory = requireWorkingDirectory(request.workingDirectory());
        boolean compacted = conversationCompactionService.compactNow(conversationId,
                selectedChatModel(request.modelId()));
        actualPromptUsages.remove(conversationId);
        return contextUsageResponse(conversationId, workingDirectory, request.lightCompactedBefore(),
                request.modelId(), compacted);
    }

    @PostMapping("/context/light-compact")
    public AgentLightCompactionResult lightCompactContext(@RequestBody AgentContextRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        actualPromptUsages.remove(conversationId);
        ConversationCompactionService.LightCompactionResult result =
                conversationCompactionService.estimateLightCompactToolResponses(conversationId, request.lightCompactedBefore());
        return new AgentLightCompactionResult(result.compactedToolResponses(), result.compactedChars());
    }

    @GetMapping("/tasks/progress")
    public List<TaskAgentService.TaskProgressSnapshot> taskProgress(@RequestParam String conversationId) {
        return taskAgentService.progressSnapshots(normalizeConversationId(conversationId));
    }

    @GetMapping("/tasks/groups")
    public List<TaskAgentService.TaskGroupSnapshot> taskGroups(@RequestParam String conversationId) {
        return taskAgentService.taskGroups(normalizeConversationId(conversationId));
    }

    @PostMapping("/approval-mode")
    public Map<String, String> updateApprovalMode(@RequestBody AgentApprovalModeRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        ApprovalMode approvalMode = ApprovalMode.from(request.approvalMode());
        approvalModes.put(conversationId, approvalMode);
        if (request.workingDirectory() != null && !request.workingDirectory().isBlank()) {
            sandboxContexts.put(conversationId,
                    sandboxContext(request.workingDirectory(), request.sandboxEnabled(), approvalMode));
        }
        return Map.of(
                "approvalMode", approvalMode.value(),
                "sandboxEnabled", String.valueOf(!ApprovalMode.AUTO.equals(approvalMode)
                        && sandboxContexts.getOrDefault(conversationId,
                        sandboxContext(request.workingDirectory(), request.sandboxEnabled(), approvalMode)).enabled())
        );
    }

    @PostMapping("/chat")
    public AgentReply chat(@ModelAttribute AgentChatRequest request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        long startedAtMs = System.currentTimeMillis();
        String conversationId = normalizeConversationId(request.conversationId());
        String workingDirectory = requireWorkingDirectory(request.workingDirectory());
        activeConversationId.set(conversationId);
        activeLightCompactedBefore.set(request.lightCompactedBefore());
        ApprovalMode approvalMode = ApprovalMode.from(request.approvalMode());
        AgentSandboxContext sandboxContext = sandboxContext(workingDirectory, request.sandboxEnabled(), approvalMode);
        approvalModes.put(conversationId, approvalMode);
        sandboxContexts.put(conversationId, sandboxContext);
        UserMessage userMessage = buildUserMessage(request, conversationId, workingDirectory);
        rewindLatestTurnIfRegenerating(request, conversationId);
        conversationCompactionService.compactIfNeeded(conversationId, selectedChatModel(request.modelId()));
        List<Message> messages = buildInitialMessages(conversationId, workingDirectory, userMessage,
                request.lightCompactedBefore());
        chatMemory.add(conversationId, userMessage);

        Prompt prompt = new Prompt(messages, agentOptions(workingDirectory, conversationId, messages, sandboxContext));
        return continueAgentLoop(prompt, callModel(prompt), conversationId, workingDirectory, 0, 0,
                INITIAL_TOOL_ROUND_LIMIT, approvalMode, sandboxContext,
                new ArrayList<>(List.of("已分析请求，正在确定是否需要调用工具。")), new ArrayList<>())
                .withLatency(elapsedMs(startedAtMs));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@ModelAttribute AgentChatRequest request) {
        StreamContext stream = newStreamContext(System.currentTimeMillis());

        new Thread(() -> {
            try {
                activeModelId.set(request.modelId());
                activeRuntimeOptions.set(runtimeOptions(request));
                String conversationId = normalizeConversationId(request.conversationId());
                String workingDirectory = requireWorkingDirectory(request.workingDirectory());
                activeConversationId.set(conversationId);
                activeLightCompactedBefore.set(request.lightCompactedBefore());
                ApprovalMode approvalMode = ApprovalMode.from(request.approvalMode());
                AgentSandboxContext sandboxContext = sandboxContext(workingDirectory, request.sandboxEnabled(), approvalMode);
                approvalModes.put(conversationId, approvalMode);
                sandboxContexts.put(conversationId, sandboxContext);

                UserMessage userMessage = buildUserMessage(request, conversationId, workingDirectory);

                rewindLatestTurnIfRegenerating(request, conversationId);
                conversationCompactionService.compactIfNeeded(conversationId, selectedChatModel(request.modelId()));
                List<Message> messages = buildInitialMessages(conversationId, workingDirectory, userMessage,
                        request.lightCompactedBefore());
                chatMemory.add(conversationId, userMessage);

                Prompt prompt = new Prompt(messages, agentOptions(workingDirectory, conversationId, messages, sandboxContext));

                processAgentLoopStream(stream, prompt, conversationId, workingDirectory, 0, 0,
                        INITIAL_TOOL_ROUND_LIMIT, approvalMode, sandboxContext,
                        new ArrayList<>(List.of("已分析请求，正在确定是否需要调用工具。")), new ArrayList<>());

            } catch (Exception e) {
                sendEvent(stream, "error", Map.of("message", e.getMessage()));
                completeStream(stream);
            } finally {
                activeRuntimeOptions.remove();
                activeModelId.remove();
                activeConversationId.remove();
                activeLightCompactedBefore.remove();
            }
        }).start();

        return stream.emitter();
    }

    private UserMessage buildUserMessage(AgentChatRequest request, String conversationId, String workingDirectory) {
        if (request.files() == null || request.files().isEmpty()) {
            return UserMessage.builder()
                    .text(request.prompt())
                    .build();
        } else {
            List<Media> medias = new ArrayList<>();
            for (MultipartFile file : request.files()) {
                try {
                    String contentType = file.getContentType();
                    if (contentType == null) contentType = "application/octet-stream";
                    medias.add(new Media(MimeType.valueOf(contentType), file.getResource()));
                } catch (Exception ignored) {}
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("conversationId", conversationId);
            metadata.put("workingDirectory", workingDirectory);
            metadata.put("fileCount", medias.size());

            String fileList = String.join("\n", request.files().stream().map(this::displayFileName).toList());
            return UserMessage.builder()
                    .text("""
                ## 附件文件列表
                %s

                ## 用户请求
                请仔细查看附件中的图片内容，并回答用户的问题：%s
                """.formatted(fileList, request.prompt()))
                    .media(medias)
                    .metadata(metadata)
                    .build();
        }
    }

    private String displayFileName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.isBlank()) {
            return originalFilename;
        }
        return file.getName();
    }

    private void rewindLatestTurnIfRegenerating(AgentChatRequest request, String conversationId) {
        if (Boolean.TRUE.equals(request.regenerate())) {
            chatMemoryRewindService.rewindLatestUserTurn(conversationId);
        }
    }

    private List<Message> buildInitialMessages(String conversationId, String workingDirectory, UserMessage userMessage,
                                               Long lightCompactedBefore) {
        List<Message> messages = buildCurrentContextMessages(conversationId, workingDirectory, lightCompactedBefore);
        messages.addAll(buildDirectSkillMessages(workingDirectory, userMessage.getText()));
        messages.add(userMessage);
        return messages;
    }

    private List<Message> buildCurrentContextMessages(String conversationId, String workingDirectory) {
        return buildCurrentContextMessages(conversationId, workingDirectory, null);
    }

    private List<Message> buildCurrentContextMessages(String conversationId, String workingDirectory,
                                                      Long lightCompactedBefore) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SystemConstants.AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage("""
                ## 当前运行上下文
                默认工作目录：%s
                """.formatted(workingDirectory)));
        String memory = memoryService.loadPromptMemory(workingDirectory);
        if (!memory.isBlank()) {
            messages.add(new SystemMessage(memory));
        }
        String knowledgeContext = buildKnowledgeContext(conversationId);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }
        if (taskTools != null) {
            messages.addAll(taskTools.drainCompletedTaskMessages(conversationId));
        }
        String skillCatalog = skillService == null ? "" : skillService.buildCatalogMessage(workingDirectory);
        if (!skillCatalog.isBlank()) {
            messages.add(new SystemMessage(skillCatalog));
        }
        messages.addAll(lightCompactMessagesForModel(chatMemory.get(conversationId), lightCompactedBefore));
        return messages;
    }

    private List<Message> buildDirectSkillMessages(String workingDirectory, String userText) {
        if (skillService == null) {
            return List.of();
        }
        String prompt = extractUserPrompt(userText);
        return skillService.renderDirectInvocation(workingDirectory, prompt)
                .map(this::directSkillMessage)
                .map(List::<Message>of)
                .orElse(List.of());
    }

    private SystemMessage directSkillMessage(RenderedSkill renderedSkill) {
        return new SystemMessage("""
                ## 已调用 Skill
                用户通过 /%s 显式调用了以下 skill。你必须按照该 skill 的完整说明处理本轮请求；如果说明中引用了支持文件，可调用 readSkillResource 读取。

                %s
                """.formatted(renderedSkill.commandName(), renderedSkill.content()));
    }

    private List<Message> lightCompactMessagesForModel(List<Message> messages, Long lightCompactedBefore) {
        if (messages == null || messages.isEmpty() || lightCompactedBefore == null || lightCompactedBefore <= 0) {
            return messages == null ? List.of() : messages;
        }
        return messages.stream()
                .map(message -> lightCompactMessageForModel(message, lightCompactedBefore))
                .toList();
    }

    private Message lightCompactMessageForModel(Message message, long lightCompactedBefore) {
        if (!(message instanceof ToolResponseMessage toolResponseMessage)
                || !shouldLightCompactToolResponse(toolResponseMessage, lightCompactedBefore)) {
            return message;
        }
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses().stream()
                .map(response -> ConversationCompactionService.shouldSkipLightCompaction(
                                response.name(),
                                response.responseData())
                        ? response
                        : new ToolResponseMessage.ToolResponse(
                                response.id(),
                                response.name(),
                                ConversationCompactionService.lightToolOutputPlaceholder(
                                        response.name(),
                                        response.responseData())))
                .toList();
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(toolResponseMessage.getMetadata())
                .build();
    }

    private boolean shouldLightCompactToolResponse(ToolResponseMessage message, long lightCompactedBefore) {
        Object recordedAtValue = message.getMetadata() == null
                ? null
                : message.getMetadata().get(TOOL_RESPONSE_RECORDED_AT_METADATA);
        long recordedAt = recordedAtValue instanceof Number number ? number.longValue() : 0L;
        return recordedAt <= 0L || recordedAt <= lightCompactedBefore;
    }

    private String extractUserPrompt(String userText) {
        if (userText == null || userText.isBlank()) {
            return "";
        }
        String marker = "## 鐢ㄦ埛璇锋眰";
        int markerIndex = userText.lastIndexOf(marker);
        if (markerIndex < 0) {
            marker = "## 用户请求";
            markerIndex = userText.lastIndexOf(marker);
        }
        if (markerIndex < 0) {
            return userText.strip();
        }
        return userText.substring(markerIndex + marker.length()).strip();
    }

    private String buildKnowledgeContext(String conversationId) {
        List<RagService.KnowledgeDocumentSummary> documents = knowledgeDocuments(conversationId);
        if (documents.isEmpty()) {
            return """
                    ## 当前会话知识库
                    当前会话没有上传知识库文件；本轮不可调用 ragSearch，也不要声称已检索知识库。
                    """;
        }
        String fileList = documents.stream()
                .map(document -> "- %s（%d 个片段）".formatted(document.fileName(), document.chunkCount()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return """
                ## 当前会话知识库
                当前会话已上传以下知识库文件。用户问题可能涉及这些文件内容时，应先调用 ragSearch 检索，再基于检索结果回答。
                %s
                """.formatted(fileList);
    }

    private AgentContextUsage contextUsageResponse(String conversationId, String workingDirectory,
                                                   Long lightCompactedBefore, String modelId, boolean compacted) {
        List<Message> messages = buildCurrentContextMessages(conversationId, workingDirectory, lightCompactedBefore);
        OpenAiChatOptions options = agentOptions(workingDirectory, conversationId, messages);
        String modelName = resolvedModelName(modelId);
        ConversationCompactionService.ContextUsage estimate = conversationCompactionService.estimatePromptUsage(
                messages,
                options.getToolCallbacks() == null ? List.of() : options.getToolCallbacks(),
                modelName);
        ActualPromptUsage actualPromptUsage = matchingActualPromptUsage(conversationId, lightCompactedBefore, modelName);
        long usedTokens = actualPromptUsage == null ? estimate.usedTokens() : actualPromptUsage.promptTokens();
        double percent = usedTokens * 100.0 / estimate.maxTokens();
        ConversationCompactionService.AutoCompactionProgress autoCompaction =
                conversationCompactionService.estimateAutoCompactionProgress(chatMemory.get(conversationId));
        double contextTokenPercent = Math.min(100.0, usedTokens * 100.0 / estimate.maxTokens());
        double autoPercent = Math.max(contextTokenPercent,
                Math.max(autoCompaction.messagePercent(), autoCompaction.textPercent()));
        boolean wouldCompact = autoCompaction.wouldCompact() || usedTokens > estimate.maxTokens();
        return new AgentContextUsage(
                usedTokens,
                estimate.maxTokens(),
                Math.min(100.0, percent),
                estimate.usedChars(),
                estimate.messageCount(),
                compacted,
                estimate.usedTokens(),
                actualPromptUsage == null ? null : Math.toIntExact(Math.min(Integer.MAX_VALUE, actualPromptUsage.promptTokens())),
                estimate.toolCount(),
                actualPromptUsage == null ? estimate.tokenSource() : "model-usage",
                modelName,
                new AgentAutoCompactionUsage(
                        autoPercent,
                        contextTokenPercent,
                        autoCompaction.messagePercent(),
                        autoCompaction.textPercent(),
                        autoCompaction.messageCount(),
                        autoCompaction.messageThreshold(),
                        autoCompaction.messagesUntilAutoCompact(),
                        usedTokens,
                        autoCompaction.tokenThreshold(),
                        Math.max(0, autoCompaction.tokenThreshold() + 1L - usedTokens),
                        wouldCompact
                )
        );
    }

    private AgentContextPreview.ContextMessage contextMessage(int index, Message message) {
        List<AgentContextPreview.ToolCallPreview> toolCalls = toolCallPreviews(message);
        List<AgentContextPreview.ToolResponsePreview> toolResponses = toolResponsePreviews(message);
        String text = message.getText() == null ? "" : message.getText();
        if (text.isBlank() && !toolCalls.isEmpty()) {
            text = "Tool calls: " + toolCalls.stream()
                    .map(AgentContextPreview.ToolCallPreview::name)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        } else if (text.isBlank() && !toolResponses.isEmpty()) {
            text = "Tool responses: " + toolResponses.stream()
                    .map(AgentContextPreview.ToolResponsePreview::name)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
        String role = message.getMessageType() == null ? "unknown" : message.getMessageType().getValue();
        return new AgentContextPreview.ContextMessage(index + 1, role, text, text.length(), toolCalls, toolResponses);
    }

    private List<AgentContextPreview.ToolCallPreview> toolCallPreviews(Message message) {
        if (!(message instanceof AssistantMessage assistantMessage)
                || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            return List.of();
        }
        return assistantMessage.getToolCalls().stream()
                .map(call -> new AgentContextPreview.ToolCallPreview(
                        call.id(),
                        call.type(),
                        call.name(),
                        call.arguments()))
                .toList();
    }

    private List<AgentContextPreview.ToolResponsePreview> toolResponsePreviews(Message message) {
        if (!(message instanceof ToolResponseMessage toolResponseMessage)
                || toolResponseMessage.getResponses() == null
                || toolResponseMessage.getResponses().isEmpty()) {
            return List.of();
        }
        return toolResponseMessage.getResponses().stream()
                .map(response -> new AgentContextPreview.ToolResponsePreview(
                        response.id(),
                        response.name(),
                        response.responseData()))
                .toList();
    }

    private List<AgentContextPreview.ToolDefinitionPreview> toolPreviews(String workingDirectory,
                                                                         String conversationId,
                                                                         List<Message> parentMessages,
                                                                         AgentSandboxContext sandboxContext) {
        OpenAiChatOptions options = agentOptions(workingDirectory, conversationId, parentMessages, sandboxContext);
        List<ToolCallback> callbacks = options.getToolCallbacks() == null
                ? List.of()
                : options.getToolCallbacks();
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition())
                .filter(Objects::nonNull)
                .map(definition -> new AgentContextPreview.ToolDefinitionPreview(
                        definition.name(),
                        definition.description(),
                        definition.inputSchema()))
                .toList();
    }

    private StreamContext newStreamContext(long startedAtMs) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        StreamContext stream = new StreamContext(emitter, new AtomicBoolean(true), startedAtMs, new TokenUsageAccumulator());
        emitter.onCompletion(() -> {
            stream.open().set(false);
            log.debug("Agent SSE completed after {} ms", elapsedMs(startedAtMs));
        });
        emitter.onTimeout(() -> {
            log.warn("Agent SSE timed out after {} ms", elapsedMs(startedAtMs));
            completeStream(stream);
        });
        emitter.onError(error -> {
            stream.open().set(false);
            log.warn("Agent SSE error after {} ms: {}", elapsedMs(startedAtMs),
                    error == null ? "unknown" : error.toString());
        });
        startHeartbeat(stream);
        return stream;
    }

    private void startHeartbeat(StreamContext stream) {
        Thread heartbeat = new Thread(() -> {
            while (stream.open().get()) {
                try {
                    Thread.sleep(STREAM_HEARTBEAT_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                sendEvent(stream, "heartbeat", Map.of("time", System.currentTimeMillis()));
            }
        }, "agent-sse-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private boolean sendEvent(StreamContext stream, String eventName, Object data) {
        if (!stream.open().get()) {
            return false;
        }
        try {
            synchronized (stream.emitter()) {
                if (!stream.open().get()) {
                    return false;
                }
                stream.emitter().send(SseEmitter.event()
                        .name(eventName)
                        .data(objectMapper.writeValueAsString(timingPayload(stream, eventName, data))));
            }
            return true;
        } catch (Exception e) {
            stream.open().set(false);
            log.warn("Failed to send Agent SSE event '{}' after {} ms: {}", eventName,
                    elapsedMs(stream.startedAtMs()), e.toString());
            return false;
        }
    }

    private boolean sendActivity(StreamContext stream, List<String> activities, List<ExecutedToolBatch> executions) {
        return sendEvent(stream, "activity", Map.of(
                "activities", List.copyOf(activities),
                "executions", List.copyOf(executions)));
    }

    private boolean sendLoopProgress(StreamContext stream, int round, int toolRoundLimit, String status) {
        int safeLimit = Math.max(1, toolRoundLimit);
        int completedRounds = Math.max(0, Math.min(round, safeLimit));
        int currentRound = Math.min(completedRounds + 1, safeLimit);
        int percent = Math.min(100, Math.round((completedRounds * 100.0f) / safeLimit));
        return sendEvent(stream, "loop_progress", Map.of(
                "currentRound", currentRound,
                "completedRounds", completedRounds,
                "toolRoundLimit", safeLimit,
                "hardToolRoundLimit", HARD_TOOL_ROUND_LIMIT,
                "percent", percent,
                "status", status
        ));
    }

    private Object timingPayload(StreamContext stream, String eventName, Object data) {
        if (data instanceof AgentReply reply && reply.latencyMs() == null) {
            AgentReply timed = reply.withLatency(elapsedMs(stream.startedAtMs()));
            TokenUsage tokenUsage = stream.tokenUsage().snapshot();
            return timed.tokenUsage() == null && tokenUsage != null
                    ? timed.withTokenUsage(tokenUsage)
                    : timed;
        }
        if (data instanceof AgentReply reply && reply.tokenUsage() == null) {
            TokenUsage tokenUsage = stream.tokenUsage().snapshot();
            return tokenUsage == null ? data : reply.withTokenUsage(tokenUsage);
        }
        if (!"error".equals(eventName) || !(data instanceof Map<?, ?> map)) {
            return data;
        }
        if (map.containsKey("latencyMs")) {
            return data;
        }
        Map<Object, Object> withTiming = new LinkedHashMap<>(map);
        withTiming.put("latencyMs", elapsedMs(stream.startedAtMs()));
        TokenUsage tokenUsage = stream.tokenUsage().snapshot();
        if (tokenUsage != null) {
            withTiming.put("tokenUsage", tokenUsage);
        }
        return withTiming;
    }

    private long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private void completeStream(StreamContext stream) {
        if (stream.open().compareAndSet(true, false)) {
            try {
                stream.emitter().complete();
            } catch (Exception ignored) {
            }
        }
    }

    private void processAgentLoopStream(StreamContext stream, Prompt prompt,
                                         String conversationId, String workingDirectory, int round, int interactionCount,
                                         int toolRoundLimit, ApprovalMode approvalMode,
                                         AgentSandboxContext sandboxContext,
                                         List<String> activities, List<ExecutedToolBatch> executions) throws Exception {
        activeConversationId.set(conversationId);
        ChatResponse response = streamChatResponse(stream, prompt);
        if (!stream.open().get()) {
            return;
        }
        processAgentLoopAfterStream(stream, prompt, response, conversationId, workingDirectory, round, interactionCount,
                toolRoundLimit, approvalMode, sandboxContext, activities, executions);
    }

    private void processAgentLoopAfterStream(StreamContext stream, Prompt prompt, ChatResponse response,
                                              String conversationId, String workingDirectory, int round, int interactionCount,
                                              int toolRoundLimit, ApprovalMode approvalMode,
                                              AgentSandboxContext sandboxContext,
                                              List<String> activities, List<ExecutedToolBatch> executions) throws Exception {
        while (true) {
            ApprovalMode effectiveApprovalMode = currentApprovalMode(conversationId, approvalMode);
            if (!response.hasToolCalls()) {
                AgentReply reply = completeStreamedAnswer(conversationId, activities, executions, response);
                sendEvent(stream, "complete", reply);
                completeStream(stream);
                return;
            }

            if (round >= toolRoundLimit) {
                sendLoopProgress(stream, round, toolRoundLimit, "limit_reached");
                AgentReply reply = handleToolRoundLimit(prompt, response, conversationId, workingDirectory, round,
                        interactionCount, toolRoundLimit, effectiveApprovalMode, sandboxContext, activities, executions);
                String eventName = "loop_limit_required".equals(reply.status()) ? "interaction_required" : "complete";
                sendEvent(stream, eventName, reply);
                completeStream(stream);
                return;
            }

            List<ToolRequest> tools = toolRequests(response);
            if (requiresUserInteraction(tools)) {
                sendLoopProgress(stream, round, toolRoundLimit, "waiting_input");
                if (interactionCount >= MAX_USER_INTERACTION_ROUNDS) {
                    String content = "需要用户补充的信息过多，任务已暂停。请在下一条消息中直接补充关键约束后继续。";
                    chatMemory.add(conversationId, new AssistantMessage(content));
                    activities.add("用户交互次数达到上限。");
                    AgentReply reply = AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions));
                    sendEvent(stream, "complete", reply);
                    completeStream(stream);
                    return;
                }
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount + 1, toolRoundLimit, stream.startedAtMs(), effectiveApprovalMode,
                        sandboxContext, PendingRunKind.USER_INTERACTION));
                activities.add("模型请求用户补充输入，等待前端交互。");

                AgentReply reply = AgentReply.interactionRequired(runId, tools, buildInteractionRequest(runId, tools),
                        List.copyOf(activities), List.copyOf(executions));
                sendEvent(stream, "interaction_required", reply);
                completeStream(stream);
                return;
            }
            if (requiresApproval(tools, effectiveApprovalMode, sandboxContext)) {
                sendLoopProgress(stream, round, toolRoundLimit, "waiting_approval");
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount, toolRoundLimit, stream.startedAtMs(), effectiveApprovalMode,
                        sandboxContext, PendingRunKind.APPROVAL));
                activities.add("模型请求执行有风险的工具，等待用户确认。");

                AgentReply reply = AgentReply.approvalRequired(runId, tools, List.copyOf(activities), List.copyOf(executions));
                sendEvent(stream, "approval_required", reply);
                completeStream(stream);
                return;
            }

            if (exceedsConsecutiveRagSearchLimit(tools, executions)) {
                AgentReply reply = streamFinalAnswerFromExistingResults(stream, prompt, conversationId, activities, executions);
                sendEvent(stream, "complete", reply);
                completeStream(stream);
                return;
            }

            activities.add("已自动执行只读工具：" + tools.stream()
                    .map(ToolRequest::name).distinct()
                    .reduce((left, right) -> left + "、" + right).orElse(""));

            if (!sendLoopProgress(stream, round, toolRoundLimit, "tool_calling")) {
                return;
            }
            if (!sendActivity(stream, activities, executions)) {
                return;
            }
            ToolExecutionResult result = executeToolCallsWithTaskProgress(stream, prompt, response);
            if (!stream.open().get()) {
                return;
            }
            persistToolExecution(conversationId, response, result);
            executions.add(executedBatch(tools, result));
            if (!sendLoopProgress(stream, round + 1, toolRoundLimit, "tool_completed")) {
                return;
            }
            if (!sendActivity(stream, activities, executions)) {
                return;
            }
            prompt = promptWithTaskNotifications(result.conversationHistory(), conversationId, workingDirectory,
                    sandboxContext, activities);
            response = streamChatResponse(stream, prompt);
            if (!stream.open().get()) {
                return;
            }
            round++;
        }
    }

    @PostMapping("/approve")
    public AgentReply approve(@RequestBody AgentApprovalRequest request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        PendingRun pending = takePendingRun(request.runId());
        activeConversationId.set(pending.conversationId());
        activeLightCompactedBefore.remove();
        ApprovalMode approvalMode = approvalModeFromRequest(request.approvalMode(), pending.approvalMode());
        approvalModes.put(pending.conversationId(), approvalMode);
        if (pending.kind() != PendingRunKind.APPROVAL) {
            throw new IllegalArgumentException("当前待处理项不是工具确认请求。");
        }
        List<ToolRequest> tools = toolRequests(pending.response());
        ToolExecutionResult result = toolCallingManager.executeToolCalls(pending.prompt(), pending.response());
        persistToolExecution(pending.conversationId(), pending.response(), result);
        List<String> activities = new ArrayList<>(List.of("用户已确认，工具已执行，正在整理结果。"));
        Prompt nextPrompt = promptWithTaskNotifications(result.conversationHistory(),
                pending.conversationId(), pending.workingDirectory(), pending.sandboxContext(), activities);
        return continueAgentLoop(nextPrompt, callModel(nextPrompt), pending.conversationId(), pending.workingDirectory(),
                pending.round() + 1, pending.interactionCount(), pending.toolRoundLimit(), approvalMode,
                pending.sandboxContext(),
                activities,
                new ArrayList<>(List.of(executedBatch(tools, result))))
                .withLatency(elapsedMs(pending.startedAtMs()));
    }

    @PostMapping(value = "/approve/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approveStream(@RequestBody AgentApprovalRequest request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        PendingRun pending = takePendingRun(request.runId());
        String requestModelId = request.modelId();
        String pendingConversationId = pending.conversationId();
        ApprovalMode approvalMode = approvalModeFromRequest(request.approvalMode(), pending.approvalMode());
        approvalModes.put(pending.conversationId(), approvalMode);
        StreamContext stream = newStreamContext(pending.startedAtMs());

        new Thread(() -> {
            try {
                activeModelId.set(requestModelId);
                activeRuntimeOptions.set(runtimeOptions(request));
                activeConversationId.set(pendingConversationId);
                activeLightCompactedBefore.remove();
                if (pending.kind() != PendingRunKind.APPROVAL) {
                    throw new IllegalArgumentException("当前待处理项不是工具确认请求。");
                }

                List<ToolRequest> tools = toolRequests(pending.response());
                if (!sendLoopProgress(stream, pending.round(), pending.toolRoundLimit(), "tool_calling")) {
                    return;
                }
                ToolExecutionResult result = executeToolCallsWithTaskProgress(stream, pending.prompt(), pending.response());
                if (!stream.open().get()) {
                    return;
                }
                persistToolExecution(pending.conversationId(), pending.response(), result);

                List<String> activities = new ArrayList<>(List.of("用户已确认，工具已执行，正在整理结果。"));
                List<ExecutedToolBatch> executions = new ArrayList<>(List.of(executedBatch(tools, result)));
                Prompt nextPrompt = promptWithTaskNotifications(result.conversationHistory(),
                        pending.conversationId(), pending.workingDirectory(), pending.sandboxContext(), activities);
                if (!sendLoopProgress(stream, pending.round() + 1, pending.toolRoundLimit(), "tool_completed")) {
                    return;
                }
                if (!sendActivity(stream, activities, executions)) {
                    return;
                }

                processAgentLoopStream(stream, nextPrompt, pending.conversationId(), pending.workingDirectory(),
                        pending.round() + 1, pending.interactionCount(), pending.toolRoundLimit(), approvalMode,
                        pending.sandboxContext(),
                        activities, executions);
            } catch (Exception e) {
                sendEvent(stream, "error", Map.of("message", e.getMessage()));
                completeStream(stream);
            } finally {
                activeRuntimeOptions.remove();
                activeModelId.remove();
                activeConversationId.remove();
                activeLightCompactedBefore.remove();
            }
        }).start();

        return stream.emitter();
    }

    @PostMapping("/reject")
    public AgentReply reject(@RequestBody AgentApprovalRequest request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        PendingRun pending = takePendingRun(request.runId());
        activeConversationId.set(pending.conversationId());
        activeLightCompactedBefore.remove();
        ApprovalMode approvalMode = approvalModeFromRequest(request.approvalMode(), pending.approvalMode());
        approvalModes.put(pending.conversationId(), approvalMode);
        if (pending.kind() == PendingRunKind.APPROVAL && (request.runId() != null || request.runId() == null)) {
            List<ToolRequest> tools = toolRequests(pending.response());
            String output = rejectedToolOutput(tools);
            List<String> activities = new ArrayList<>(List.of(
                    "用户拒绝执行工具：" + toolNames(tools) + "，已将拒绝结果反馈给模型继续决策。"
            ));
            List<Message> nextMessages = persistAndReturnToolConversation(pending.conversationId(),
                    conversationWithRejectedToolResponse(pending.prompt(), pending.response(), output));
            Prompt nextPrompt = promptWithTaskNotifications(
                    nextMessages,
                    pending.conversationId(), pending.workingDirectory(), pending.sandboxContext(), activities);
            ExecutedToolBatch batch = rejectedToolBatch(tools, output);
            return continueAgentLoop(nextPrompt, callModel(nextPrompt), pending.conversationId(), pending.workingDirectory(),
                    pending.round() + 1, pending.interactionCount(), pending.toolRoundLimit(), approvalMode,
                    pending.sandboxContext(),
                    activities,
                    new ArrayList<>(List.of(batch)))
                    .withLatency(elapsedMs(pending.startedAtMs()));
        }
        if (pending.kind() != PendingRunKind.APPROVAL) {
            throw new IllegalArgumentException("当前待处理项不是工具确认请求。");
        }
        String content = "已取消工具调用，本次未执行请求的操作。";
        chatMemory.add(pending.conversationId(), new AssistantMessage(content));
        return AgentReply.completed(content, List.of("用户拒绝执行工具，本轮已结束。"), List.of());
    }

    @PostMapping("/interact")
    public AgentReply interact(@RequestBody AgentInteractionResponse request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        PendingRun pending = takePendingRun(request.runId());
        activeConversationId.set(pending.conversationId());
        activeLightCompactedBefore.remove();
        ApprovalMode approvalMode = approvalModeFromRequest(request.approvalMode(), pending.approvalMode());
        approvalModes.put(pending.conversationId(), approvalMode);
        if (pending.kind() == PendingRunKind.LOOP_LIMIT) {
            return handleLoopLimitInteraction(pending, request.value(), approvalMode);
        }
        if (pending.kind() != PendingRunKind.USER_INTERACTION) {
            throw new IllegalArgumentException("当前待处理项不是用户交互请求。");
        }
        List<ToolRequest> tools = toolRequests(pending.response());
        if (!requiresUserInteraction(tools)) {
            throw new IllegalArgumentException("当前待处理项不是用户交互请求。");
        }

        String output = userInteractionOutput(tools, request.value());
        List<String> activities = new ArrayList<>(List.of("用户已通过界面补充信息，正在继续处理。"));
        List<Message> nextMessages = persistAndReturnToolConversation(pending.conversationId(),
                conversationWithManualToolResponse(pending.prompt(), pending.response(), output));
        Prompt nextPrompt = promptWithTaskNotifications(
                nextMessages,
                pending.conversationId(), pending.workingDirectory(), pending.sandboxContext(), activities);
        ExecutedToolBatch batch = executedUserInteractionBatch(tools, output);
        return continueAgentLoop(nextPrompt, callModel(nextPrompt), pending.conversationId(), pending.workingDirectory(),
                pending.round() + 1, pending.interactionCount(), pending.toolRoundLimit(), approvalMode,
                pending.sandboxContext(),
                activities,
                new ArrayList<>(List.of(batch)))
                .withLatency(elapsedMs(pending.startedAtMs()));
    }

    @PostMapping(value = "/interact/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter interactStream(@RequestBody AgentInteractionResponse request) {
        activeModelId.set(request.modelId());
        activeRuntimeOptions.set(runtimeOptions(request));
        PendingRun pending = takePendingRun(request.runId());
        String requestModelId = request.modelId();
        String pendingConversationId = pending.conversationId();
        ApprovalMode approvalMode = approvalModeFromRequest(request.approvalMode(), pending.approvalMode());
        approvalModes.put(pending.conversationId(), approvalMode);
        StreamContext stream = newStreamContext(pending.startedAtMs());

        new Thread(() -> {
            try {
                activeModelId.set(requestModelId);
                activeRuntimeOptions.set(runtimeOptions(request));
                activeConversationId.set(pendingConversationId);
                activeLightCompactedBefore.remove();
                if (pending.kind() == PendingRunKind.LOOP_LIMIT) {
                    handleLoopLimitInteractionStream(stream, pending, request.value(), approvalMode);
                    return;
                }
                if (pending.kind() != PendingRunKind.USER_INTERACTION) {
                    throw new IllegalArgumentException("当前待处理项不是用户交互请求。");
                }

                List<ToolRequest> tools = toolRequests(pending.response());
                if (!requiresUserInteraction(tools)) {
                    throw new IllegalArgumentException("当前待处理项不是用户交互请求。");
                }

                String output = userInteractionOutput(tools, request.value());
                List<String> activities = new ArrayList<>(List.of("用户已通过界面补充信息，正在继续处理。"));
                List<ExecutedToolBatch> executions = new ArrayList<>(List.of(executedUserInteractionBatch(tools, output)));
                List<Message> nextMessages = persistAndReturnToolConversation(pending.conversationId(),
                        conversationWithManualToolResponse(pending.prompt(), pending.response(), output));
                Prompt nextPrompt = promptWithTaskNotifications(
                        nextMessages,
                        pending.conversationId(), pending.workingDirectory(), pending.sandboxContext(), activities);
                if (!sendLoopProgress(stream, pending.round() + 1, pending.toolRoundLimit(), "tool_completed")) {
                    return;
                }
                if (!sendActivity(stream, activities, executions)) {
                    return;
                }

                processAgentLoopStream(stream, nextPrompt, pending.conversationId(), pending.workingDirectory(),
                        pending.round() + 1, pending.interactionCount(), pending.toolRoundLimit(), approvalMode,
                        pending.sandboxContext(),
                        activities, executions);
            } catch (Exception e) {
                sendEvent(stream, "error", Map.of("message", e.getMessage()));
                completeStream(stream);
            } finally {
                activeRuntimeOptions.remove();
                activeModelId.remove();
                activeConversationId.remove();
                activeLightCompactedBefore.remove();
            }
        }).start();

        return stream.emitter();
    }

    private void handleLoopLimitInteractionStream(StreamContext stream, PendingRun pending, String value,
                                                  ApprovalMode approvalMode) throws Exception {
        String answer = value == null ? "" : value.strip();
        if (!"继续执行".equals(answer)) {
            String content = "已停止继续执行。本轮任务停在工具调用上限 %d 次处。".formatted(pending.toolRoundLimit());
            chatMemory.add(pending.conversationId(), new AssistantMessage(content));
            AgentReply reply = AgentReply.completed(content, List.of("用户选择停止继续执行。"), List.of());
            sendEvent(stream, "complete", reply);
            completeStream(stream);
            return;
        }

        int nextLimit = nextToolRoundLimit(pending.toolRoundLimit());
        List<String> activities = new ArrayList<>(List.of("用户确认继续执行，工具调用上限已从 %d 次提升到 %d 次。"
                .formatted(pending.toolRoundLimit(), nextLimit)));
        if (!sendActivity(stream, activities, List.of())) {
            return;
        }
        continueAgentLoopStream(stream, pending.prompt(), pending.response(), pending.conversationId(),
                pending.workingDirectory(), pending.round(), pending.interactionCount(), nextLimit,
                approvalMode, pending.sandboxContext(), activities, new ArrayList<>());
    }

    private void continueAgentLoopStream(StreamContext stream, Prompt prompt, ChatResponse response,
                                         String conversationId, String workingDirectory, int round, int interactionCount,
                                         int toolRoundLimit, ApprovalMode approvalMode, List<String> activities,
                                         List<ExecutedToolBatch> executions) throws Exception {
        continueAgentLoopStream(stream, prompt, response, conversationId, workingDirectory, round, interactionCount,
                toolRoundLimit, approvalMode, currentSandboxContext(conversationId, workingDirectory, approvalMode),
                activities, executions);
    }

    private void continueAgentLoopStream(StreamContext stream, Prompt prompt, ChatResponse response,
                                         String conversationId, String workingDirectory, int round, int interactionCount,
                                         int toolRoundLimit, ApprovalMode approvalMode,
                                         AgentSandboxContext sandboxContext, List<String> activities,
                                         List<ExecutedToolBatch> executions) throws Exception {
        activeConversationId.set(conversationId);
        while (true) {
            ApprovalMode effectiveApprovalMode = currentApprovalMode(conversationId, approvalMode);
            if (!response.hasToolCalls()) {
                AgentReply reply = completeStreamedAnswer(conversationId, activities, executions, response);
                sendEvent(stream, "complete", reply);
                completeStream(stream);
                return;
            }
            if (round >= toolRoundLimit) {
                sendLoopProgress(stream, round, toolRoundLimit, "limit_reached");
                AgentReply reply = handleToolRoundLimit(prompt, response, conversationId, workingDirectory, round,
                        interactionCount, toolRoundLimit, effectiveApprovalMode, sandboxContext, activities, executions);
                String eventName = "loop_limit_required".equals(reply.status()) ? "interaction_required" : "complete";
                sendEvent(stream, eventName, reply);
                completeStream(stream);
                return;
            }

            List<ToolRequest> tools = toolRequests(response);
            if (requiresUserInteraction(tools)) {
                sendLoopProgress(stream, round, toolRoundLimit, "waiting_input");
                if (interactionCount >= MAX_USER_INTERACTION_ROUNDS) {
                    String content = "需要用户补充的信息过多，任务已暂停。请在下一条消息中直接补充关键约束后继续。";
                    chatMemory.add(conversationId, new AssistantMessage(content));
                    activities.add("用户交互次数达到上限。");
                    AgentReply reply = AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions));
                    sendEvent(stream, "complete", reply);
                    completeStream(stream);
                    return;
                }
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount + 1, toolRoundLimit, effectiveApprovalMode, sandboxContext,
                        PendingRunKind.USER_INTERACTION));
                activities.add("模型请求用户补充输入，等待前端交互。");
                AgentReply reply = AgentReply.interactionRequired(runId, tools, buildInteractionRequest(runId, tools),
                        List.copyOf(activities), List.copyOf(executions));
                sendEvent(stream, "interaction_required", reply);
                completeStream(stream);
                return;
            }
            if (requiresApproval(tools, effectiveApprovalMode, sandboxContext)) {
                sendLoopProgress(stream, round, toolRoundLimit, "waiting_approval");
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount, toolRoundLimit, effectiveApprovalMode, sandboxContext,
                        PendingRunKind.APPROVAL));
                activities.add("模型请求执行有风险的工具，等待用户确认。");
                AgentReply reply = AgentReply.approvalRequired(runId, tools, List.copyOf(activities), List.copyOf(executions));
                sendEvent(stream, "approval_required", reply);
                completeStream(stream);
                return;
            }

            if (exceedsConsecutiveRagSearchLimit(tools, executions)) {
                AgentReply reply = streamFinalAnswerFromExistingResults(stream, prompt, conversationId, activities, executions);
                sendEvent(stream, "complete", reply);
                completeStream(stream);
                return;
            }

            activities.add("已自动执行只读工具：" + tools.stream()
                    .map(ToolRequest::name)
                    .distinct()
                    .reduce((left, right) -> left + "、" + right)
                    .orElse(""));
            if (!sendLoopProgress(stream, round, toolRoundLimit, "tool_calling")) {
                return;
            }
            if (!sendActivity(stream, activities, executions)) {
                return;
            }

            ToolExecutionResult result = executeToolCallsWithTaskProgress(stream, prompt, response);
            if (!stream.open().get()) {
                return;
            }
            persistToolExecution(conversationId, response, result);
            executions.add(executedBatch(tools, result));
            if (!sendLoopProgress(stream, round + 1, toolRoundLimit, "tool_completed")) {
                return;
            }
            if (!sendActivity(stream, activities, executions)) {
                return;
            }
            prompt = promptWithTaskNotifications(result.conversationHistory(), conversationId, workingDirectory,
                    sandboxContext, activities);
            response = streamChatResponse(stream, prompt);
            if (!stream.open().get()) {
                return;
            }
            round++;
        }
    }

    private AgentReply continueAgentLoop(Prompt prompt, ChatResponse response, String conversationId,
                                         String workingDirectory, int round, int interactionCount, int toolRoundLimit,
                                         ApprovalMode approvalMode, List<String> activities,
                                         List<ExecutedToolBatch> executions) {
        return continueAgentLoop(prompt, response, conversationId, workingDirectory, round, interactionCount,
                toolRoundLimit, approvalMode, currentSandboxContext(conversationId, workingDirectory, approvalMode),
                activities, executions);
    }

    private AgentReply continueAgentLoop(Prompt prompt, ChatResponse response, String conversationId,
                                         String workingDirectory, int round, int interactionCount, int toolRoundLimit,
                                         ApprovalMode approvalMode, AgentSandboxContext sandboxContext,
                                         List<String> activities, List<ExecutedToolBatch> executions) {
        activeConversationId.set(conversationId);
        while (true) {
            ApprovalMode effectiveApprovalMode = currentApprovalMode(conversationId, approvalMode);
            if (!response.hasToolCalls()) {
                String content = response.getResult().getOutput().getText();
                chatMemory.add(conversationId, new AssistantMessage(content));
                activities.add("模型已生成最终回复。");
                return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions),
                        tokenUsage(response));
            }
            if (round >= toolRoundLimit) {
                return handleToolRoundLimit(prompt, response, conversationId, workingDirectory, round, interactionCount,
                        toolRoundLimit, effectiveApprovalMode, sandboxContext, activities, executions);
            }

            List<ToolRequest> tools = toolRequests(response);
            if (requiresUserInteraction(tools)) {
                if (interactionCount >= MAX_USER_INTERACTION_ROUNDS) {
                    String content = "需要用户补充的信息过多，任务已暂停。请在下一条消息中直接补充关键约束后继续。";
                    chatMemory.add(conversationId, new AssistantMessage(content));
                    activities.add("用户交互次数达到上限。");
                    return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions));
                }
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount + 1, toolRoundLimit, effectiveApprovalMode, sandboxContext,
                        PendingRunKind.USER_INTERACTION));
                activities.add("模型请求用户补充输入，等待前端交互。");
                return AgentReply.interactionRequired(runId, tools, buildInteractionRequest(runId, tools),
                        List.copyOf(activities), List.copyOf(executions));
            }
            if (requiresApproval(tools, effectiveApprovalMode, sandboxContext)) {
                String runId = UUID.randomUUID().toString();
                pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                        round, interactionCount, toolRoundLimit, effectiveApprovalMode, sandboxContext,
                        PendingRunKind.APPROVAL));
                activities.add("模型请求执行有风险的工具，等待用户确认。");
                return AgentReply.approvalRequired(runId, tools, List.copyOf(activities), List.copyOf(executions));
            }

            if (exceedsConsecutiveRagSearchLimit(tools, executions)) {
                return finalAnswerFromExistingResults(prompt, conversationId, activities, executions);
            }

            activities.add("已自动执行只读工具：" + tools.stream()
                    .map(ToolRequest::name)
                    .distinct()
                    .reduce((left, right) -> left + "、" + right)
                    .orElse(""));
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            persistToolExecution(conversationId, response, result);
            executions.add(executedBatch(tools, result));
            prompt = promptWithTaskNotifications(result.conversationHistory(), conversationId, workingDirectory,
                    sandboxContext, activities);
            response = callModel(prompt);
            round++;
        }
    }

    private AgentReply handleToolRoundLimit(Prompt prompt, ChatResponse response, String conversationId,
                                            String workingDirectory, int round, int interactionCount,
                                            int toolRoundLimit, ApprovalMode approvalMode, List<String> activities,
                                            List<ExecutedToolBatch> executions) {
        return handleToolRoundLimit(prompt, response, conversationId, workingDirectory, round, interactionCount,
                toolRoundLimit, approvalMode, currentSandboxContext(conversationId, workingDirectory, approvalMode),
                activities, executions);
    }

    private AgentReply handleToolRoundLimit(Prompt prompt, ChatResponse response, String conversationId,
                                            String workingDirectory, int round, int interactionCount,
                                            int toolRoundLimit, ApprovalMode approvalMode,
                                            AgentSandboxContext sandboxContext, List<String> activities,
                                            List<ExecutedToolBatch> executions) {
        if (toolRoundLimit >= HARD_TOOL_ROUND_LIMIT) {
            String content = "工具调用轮数已达到最高上限 %d 次，任务已暂停。请缩小任务范围后重试。"
                    .formatted(HARD_TOOL_ROUND_LIMIT);
            chatMemory.add(conversationId, new AssistantMessage(content));
            activities.add("工具调用已达到最高上限 %d 次。".formatted(HARD_TOOL_ROUND_LIMIT));
            return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions));
        }

        int nextLimit = nextToolRoundLimit(toolRoundLimit);
        String runId = UUID.randomUUID().toString();
        pendingRuns.put(runId, new PendingRun(prompt, response, conversationId, workingDirectory,
                round, interactionCount, toolRoundLimit, approvalMode, sandboxContext, PendingRunKind.LOOP_LIMIT));
        activities.add("工具调用已达到当前上限 %d 次，等待用户确认是否继续；继续后上限将提升到 %d 次。"
                .formatted(toolRoundLimit, nextLimit));
        return AgentReply.loopLimitRequired(runId, buildLoopLimitInteraction(runId, round, toolRoundLimit, nextLimit),
                List.copyOf(activities), List.copyOf(executions));
    }

    private AgentReply handleLoopLimitInteraction(PendingRun pending, String value, ApprovalMode approvalMode) {
        String answer = value == null ? "" : value.strip();
        if (!"继续执行".equals(answer)) {
            String content = "已停止继续执行。本轮任务停在工具调用上限 %d 次处。".formatted(pending.toolRoundLimit());
            chatMemory.add(pending.conversationId(), new AssistantMessage(content));
            return AgentReply.completed(content, List.of("用户选择停止继续执行。"), List.of());
        }

        int nextLimit = nextToolRoundLimit(pending.toolRoundLimit());
        return continueAgentLoop(pending.prompt(), pending.response(), pending.conversationId(),
                pending.workingDirectory(), pending.round(), pending.interactionCount(), nextLimit,
                approvalMode,
                new ArrayList<>(List.of("用户确认继续执行，工具调用上限已从 %d 次提升到 %d 次。"
                        .formatted(pending.toolRoundLimit(), nextLimit))),
                new ArrayList<>());
    }

    private InteractionRequest buildLoopLimitInteraction(String runId, int round, int currentLimit, int nextLimit) {
        return new InteractionRequest(
                runId,
                "choice",
                "已达到当前工具调用上限，是否继续执行？",
                List.of("继续执行", "停止"),
                "",
                "当前已执行 %d 轮工具调用；继续后本轮上限将从 %d 提升到 %d，最高不超过 %d。"
                        .formatted(round, currentLimit, nextLimit, HARD_TOOL_ROUND_LIMIT),
                false
        );
    }

    int nextToolRoundLimit(int currentLimit) {
        int increment = Math.max(2, (int) Math.ceil(currentLimit * 0.5));
        return Math.min(currentLimit + increment, HARD_TOOL_ROUND_LIMIT);
    }

    private List<ToolRequest> toolRequests(ChatResponse response) {
        return response.getResult().getOutput().getToolCalls().stream()
                .map(call -> new ToolRequest(call.name(), call.arguments()))
                .toList();
    }

    private boolean requiresUserInteraction(List<ToolRequest> tools) {
        return agentToolPolicy.requiresUserInteraction(tools);
    }

    private InteractionRequest buildInteractionRequest(String runId, List<ToolRequest> tools) {
        ToolRequest tool = tools.stream()
                .filter(item -> agentToolPolicy.isUserInteractionTool(item.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少用户交互工具。"));
        Map<String, Object> arguments = parseArguments(tool.arguments());
        String question = asText(arguments.get("question"), "请补充必要信息");
        String description = asText(arguments.get("description"), "");
        if (AgentToolPolicy.ASK_USER_TOOL.equals(tool.name())) {
            List<String> options = appendCustomOption(asStringList(arguments.get("options")));
            if (!options.isEmpty()) {
                return new InteractionRequest(
                        runId,
                        "choice",
                        question,
                        options,
                        asText(arguments.get("placeholder"), "选择“其他”后可手动输入"),
                        description,
                        true
                );
            }
        }
        return new InteractionRequest(
                runId,
                "text",
                question,
                List.of(),
                asText(arguments.get("placeholder"), ""),
                description,
                false
        );
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String asText(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .limit(5)
                    .toList();
        }
        if (value instanceof String text) {
            return Arrays.stream(text.split("[\\r\\n,，;；]+"))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .limit(5)
                    .toList();
        }
        return List.of();
    }

    private List<String> appendCustomOption(List<String> options) {
        if (options.isEmpty()) {
            return options;
        }
        List<String> result = new ArrayList<>(options);
        boolean hasCustomOption = result.stream().anyMatch("其他"::equals);
        if (!hasCustomOption) {
            result.add("其他");
        }
        return result;
    }

    private String userInteractionOutput(List<ToolRequest> tools, String value) {
        ToolRequest tool = tools.stream()
                .filter(item -> agentToolPolicy.isUserInteractionTool(item.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少用户交互工具。"));
        String answer = value == null || value.isBlank() ? "(用户未提供内容)" : value.strip();
        return "用户回答：" + answer;
    }

    private void persistToolExecution(String conversationId, ChatResponse response, ToolExecutionResult result) {
        if (response == null || response.getResult() == null || result == null) {
            return;
        }
        AssistantMessage assistantMessage = response.getResult().getOutput();
        ToolResponseMessage toolResponseMessage = lastToolResponseMessage(result.conversationHistory());
        persistToolExchange(conversationId, assistantMessage, toolResponseMessage);
    }

    private List<Message> persistAndReturnToolConversation(String conversationId, List<Message> messages) {
        persistToolConversationDelta(conversationId, messages);
        return messages;
    }

    private void persistToolConversationDelta(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int toolResponseIndex = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof ToolResponseMessage) {
                toolResponseIndex = index;
                break;
            }
        }
        if (toolResponseIndex < 0) {
            return;
        }
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) messages.get(toolResponseIndex);
        AssistantMessage assistantMessage = null;
        for (int index = toolResponseIndex - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage candidate
                    && candidate.getToolCalls() != null
                    && !candidate.getToolCalls().isEmpty()) {
                assistantMessage = candidate;
                break;
            }
        }
        persistToolExchange(conversationId, assistantMessage, toolResponseMessage);
    }

    private void persistToolExchange(String conversationId, AssistantMessage assistantMessage,
                                     ToolResponseMessage toolResponseMessage) {
        if (assistantMessage == null
                || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()
                || toolResponseMessage == null
                || toolResponseMessage.getResponses() == null
                || toolResponseMessage.getResponses().isEmpty()) {
            return;
        }
        chatMemory.add(conversationId, List.of(assistantMessage, toolResponseWithRecordedAt(toolResponseMessage)));
    }

    private ToolResponseMessage toolResponseWithRecordedAt(ToolResponseMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata() == null ? Map.of() : message.getMetadata());
        metadata.putIfAbsent(TOOL_RESPONSE_RECORDED_AT_METADATA, System.currentTimeMillis());
        return ToolResponseMessage.builder()
                .responses(message.getResponses())
                .metadata(metadata)
                .build();
    }

    private ToolResponseMessage lastToolResponseMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof ToolResponseMessage toolResponseMessage) {
                return toolResponseMessage;
            }
        }
        return null;
    }

    private List<Message> conversationWithManualToolResponse(Prompt prompt, ChatResponse response, String output) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        AssistantMessage assistantMessage = response.getResult().getOutput();
        messages.add(assistantMessage);
        List<ToolResponseMessage.ToolResponse> responses = assistantMessage.getToolCalls().stream()
                .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        agentToolPolicy.isUserInteractionTool(call.name()) ? output : "未执行：本轮优先处理用户交互。"))
                .toList();
        messages.add(ToolResponseMessage.builder().responses(responses).build());
        return messages;
    }

    private List<Message> conversationWithRejectedToolResponse(Prompt prompt, ChatResponse response, String output) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        AssistantMessage assistantMessage = response.getResult().getOutput();
        messages.add(assistantMessage);
        List<ToolResponseMessage.ToolResponse> responses = assistantMessage.getToolCalls().stream()
                .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), output))
                .toList();
        messages.add(ToolResponseMessage.builder().responses(responses).build());
        messages.add(new UserMessage("""
                用户拒绝了上面的工具调用。
                不要再次原样请求同一个被拒绝的工具调用；请基于当前限制重新决策：
                1. 如果可以用其他安全工具或只读方式完成，改用替代方案；
                2. 如果无法继续，说明被拒绝的操作是什么、影响是什么，以及用户可以如何调整请求。
                """));
        return messages;
    }

    private String rejectedToolOutput(List<ToolRequest> tools) {
        return """
                用户拒绝执行工具调用：%s。
                这些工具没有被执行，因此没有产生任何外部副作用或执行结果。
                请不要假设工具已经执行；请改用其他可行方案，或向用户说明当前限制。
                """.formatted(toolNames(tools));
    }

    private ExecutedToolBatch rejectedToolBatch(List<ToolRequest> tools, String output) {
        return new ExecutedToolBatch(tools.stream()
                .map(tool -> eventTool(tool.name(), tool.arguments(), output))
                .toList());
    }

    private String toolNames(List<ToolRequest> tools) {
        return tools.stream()
                .map(ToolRequest::name)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("未知工具");
    }

    private Prompt promptWithTaskNotifications(List<Message> messages, String conversationId,
                                               String workingDirectory, List<String> activities) {
        return promptWithTaskNotifications(messages, conversationId, workingDirectory,
                currentSandboxContext(conversationId, workingDirectory, currentApprovalMode(conversationId, ApprovalMode.DEFAULT)),
                activities);
    }

    private Prompt promptWithTaskNotifications(List<Message> messages, String conversationId,
                                               String workingDirectory, AgentSandboxContext sandboxContext,
                                               List<String> activities) {
        List<Message> nextMessages = new ArrayList<>(compactToolResponsesForModel(messages));
        List<SystemMessage> taskMessages = taskTools == null
                ? List.of()
                : taskTools.drainCompletedTaskMessages(conversationId);
        if (!taskMessages.isEmpty()) {
            nextMessages.addAll(taskMessages);
            activities.add("后台 task 子 agent 已完成，结果已注入上下文。");
        }
        return new Prompt(nextMessages, agentOptions(workingDirectory, conversationId, nextMessages, sandboxContext));
    }

    private List<Message> compactToolResponsesForModel(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(this::compactToolResponseForModel)
                .toList();
    }

    private Message compactToolResponseForModel(Message message) {
        if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
            return message;
        }
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses().stream()
                .map(response -> new ToolResponseMessage.ToolResponse(
                        response.id(),
                        response.name(),
                        truncateToolResponseForModel(response.responseData())))
                .toList();
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(toolResponseMessage.getMetadata())
                .build();
    }

    private String truncateToolResponseForModel(String value) {
        if (value == null || value.length() <= MAX_TOOL_RESPONSE_CONTEXT_CHARS) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, MAX_TOOL_RESPONSE_CONTEXT_CHARS - 96))
                + "\n...tool output truncated before the next model round; request a narrower follow-up if needed";
    }

    private ToolExecutionResult executeToolCallsWithTaskProgress(StreamContext stream, Prompt prompt,
                                                                 ChatResponse response) {
        if (taskAgentService == null) {
            return toolCallingManager.executeToolCalls(prompt, response);
        }
        return taskAgentService.withProgressSink(
                event -> sendEvent(stream, "task_progress", event),
                () -> toolCallingManager.executeToolCalls(prompt, response));
    }

    private ExecutedToolBatch executedUserInteractionBatch(List<ToolRequest> tools, String output) {
        return new ExecutedToolBatch(tools.stream()
                .map(tool -> eventTool(tool.name(), tool.arguments(),
                        agentToolPolicy.isUserInteractionTool(tool.name()) ? output : "未执行"))
                .toList());
    }

    private ExecutedToolBatch executedBatch(List<ToolRequest> tools, ToolExecutionResult result) {
        List<ToolResponseMessage.ToolResponse> responses = result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((left, right) -> right)
                .map(ToolResponseMessage::getResponses)
                .orElse(List.of());
        List<ExecutedTool> executedTools = IntStream.range(0, tools.size())
                .mapToObj(index -> {
                    ToolRequest tool = tools.get(index);
                    String output = index < responses.size() ? responses.get(index).responseData() : "";
                    return eventTool(tool.name(), tool.arguments(), output);
                })
                .toList();
        return new ExecutedToolBatch(executedTools);
    }

    private ExecutedTool eventTool(String name, String arguments, String output) {
        return new ExecutedTool(
                name,
                truncateForEvent(arguments, MAX_TOOL_ARGUMENT_EVENT_CHARS),
                truncateForEvent(output, MAX_TOOL_OUTPUT_EVENT_CHARS)
        );
    }

    private String truncateForEvent(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 64))
                + "\n...truncated for UI event, full output remains in model context";
    }

    boolean requiresApproval(List<ToolRequest> tools) {
        return requiresApproval(tools, ApprovalMode.DEFAULT);
    }

    boolean requiresApproval(List<ToolRequest> tools, ApprovalMode approvalMode) {
        return requiresApproval(tools, approvalMode, AgentSandboxContext.of(false, ""));
    }

    boolean requiresApproval(List<ToolRequest> tools, ApprovalMode approvalMode, AgentSandboxContext sandboxContext) {
        return switch (approvalMode == null ? ApprovalMode.DEFAULT : approvalMode) {
            case AUTO -> false;
            case WORK_AUTO -> agentToolPolicy.requiresWorkspaceApproval(tools, sandboxContext);
            case DEFAULT -> agentToolPolicy.requiresApproval(tools);
        };
    }

    private ApprovalMode currentApprovalMode(String conversationId, ApprovalMode fallback) {
        return approvalModes.getOrDefault(normalizeConversationId(conversationId),
                fallback == null ? ApprovalMode.DEFAULT : fallback);
    }

    private ApprovalMode approvalModeFromRequest(String value, ApprovalMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? ApprovalMode.DEFAULT : fallback;
        }
        return ApprovalMode.from(value);
    }

    private AgentSandboxContext currentSandboxContext(String conversationId, String workingDirectory,
                                                      ApprovalMode approvalMode) {
        return sandboxContexts.getOrDefault(normalizeConversationId(conversationId),
                sandboxContext(workingDirectory, true, approvalMode));
    }

    private AgentSandboxContext sandboxContext(String workingDirectory, Boolean sandboxEnabled,
                                               ApprovalMode approvalMode) {
        boolean enabled = !ApprovalMode.AUTO.equals(approvalMode)
                && (sandboxEnabled == null || Boolean.TRUE.equals(sandboxEnabled));
        return AgentSandboxContext.of(enabled, workingDirectory);
    }

    boolean exceedsConsecutiveRagSearchLimit(List<ToolRequest> tools, List<ExecutedToolBatch> executions) {
        return agentToolPolicy.exceedsConsecutiveRagSearchLimit(tools, executions);
    }

    private AgentReply finalAnswerFromExistingResults(String conversationId, List<String> activities,
                                                       List<ExecutedToolBatch> executions, ChatResponse response) {
        String content = response.getResult().getOutput().getText();
        chatMemory.add(conversationId, new AssistantMessage(content));
        activities.add("ragSearch 已达到连续检索上限，已基于现有检索结果生成回复。");
        activities.add("模型已生成最终回复。");
        return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions), tokenUsage(response));
    }

    private AgentReply finalAnswerFromExistingResults(Prompt prompt, String conversationId,
                                                       List<String> activities, List<ExecutedToolBatch> executions) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        messages.add(new UserMessage("""
                ragSearch 已连续调用达到上限。不要再调用任何工具。
                请只基于上面已经返回的工具结果回答用户最初的问题。
                如果已有片段不足以得出完整答案，直接说明未找到完整信息，并给出已经找到的信息。
                """));

        ChatResponse response = callModel(new Prompt(messages, OpenAiChatOptions.builder().build()));
        return finalAnswerFromExistingResults(conversationId, activities, executions, response);
    }

    private AgentReply streamFinalAnswerFromExistingResults(StreamContext stream, Prompt prompt, String conversationId,
                                                            List<String> activities,
                                                            List<ExecutedToolBatch> executions) throws Exception {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        messages.add(new UserMessage("""
                ragSearch 已连续调用达到上限。不要再调用任何工具。
                请只基于上面已经返回的工具结果回答用户最初的问题。
                如果已有片段不足以得出完整答案，直接说明未找到完整信息，并给出已经找到的信息。
                """));

        activities.add("ragSearch 已达到连续检索上限，已基于现有检索结果生成回复。");
        return streamFinalAnswer(stream, new Prompt(messages, OpenAiChatOptions.builder().build()),
                conversationId, activities, executions, "");
    }

    private AgentReply streamFinalAnswer(StreamContext stream, Prompt prompt, String conversationId,
                                         List<String> activities, List<ExecutedToolBatch> executions,
                                         String fallbackContent) throws Exception {
        ChatResponse response = streamChatResponse(stream, prompt);
        String content = responseText(response);
        if (content.isBlank() && fallbackContent != null && !fallbackContent.isBlank()) {
            content = fallbackContent;
            sendEvent(stream, "token", Map.of("content", content));
        }
        chatMemory.add(conversationId, assistantMessage(content, reasoningText(response)));
        activities.add("模型已生成最终回复。");
        return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions), stream.tokenUsage().snapshot());
    }

    private AgentReply completeStreamedAnswer(String conversationId, List<String> activities,
                                              List<ExecutedToolBatch> executions, ChatResponse response) {
        String content = responseText(response);
        chatMemory.add(conversationId, assistantMessage(content, reasoningText(response)));
        activities.add("模型已生成最终回复。");
        return AgentReply.completed(content, List.copyOf(activities), List.copyOf(executions), tokenUsage(response));
    }

    private ChatResponse streamChatResponse(StreamContext stream, Prompt prompt) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<String, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        TokenUsageAccumulator callUsage = new TokenUsageAccumulator();
        long startedAtMs = System.currentTimeMillis();
        log.info("Starting streamed model request: messages={}, chars={}, toolResponses={}",
                prompt.getInstructions().size(), promptTextLength(prompt), promptToolResponseCount(prompt));
        try {
            modelConfigService.chatModel(activeModelId.get()).stream(prompt).doOnNext(chunk -> {
                try {
                    callUsage.replace(tokenUsage(chunk));
                    AssistantMessage output = chunk.getResult().getOutput();
                    String reasoningToken = reasoningText(output);
                    if (reasoningToken != null && !reasoningToken.isEmpty()) {
                        reasoning.append(reasoningToken);
                        sendEvent(stream, "reasoning", Map.of("content", reasoningToken));
                    }
                    String token = output.getText();
                    if (token != null && !token.isEmpty()) {
                        content.append(token);
                        sendEvent(stream, "token", Map.of("content", token));
                    }
                    mergeToolCalls(toolCalls, output.getToolCalls());
                } catch (Exception ignored) {
                }
            }).blockLast(MODEL_STREAM_TIMEOUT);
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            String detail = responseBody == null || responseBody.isBlank()
                    ? e.getMessage()
                    : responseBody;
            throw new IllegalStateException("模型请求被上游拒绝，HTTP " + e.getStatusCode().value() + ": " + detail, e);
        } catch (Exception e) {
            throw new IllegalStateException("模型流式响应超时或中断，请检查模型服务、网络连接和输出状态。", e);
        }
        log.info("Finished streamed model request after {} ms: contentChars={}, toolCalls={}",
                elapsedMs(startedAtMs), content.length(), toolCalls.size());
        TokenUsage tokenUsage = callUsage.snapshot();
        stream.tokenUsage().replace(tokenUsage);
        rememberActualPromptUsage(tokenUsage);
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content.toString())
                .properties(reasoning.isEmpty() ? Map.of() : Map.of("reasoningContent", reasoning.toString()))
                .toolCalls(toolCalls.values().stream()
                        .map(ToolCallAccumulator::toToolCall)
                        .toList())
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    private AssistantMessage assistantMessage(String content, String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return new AssistantMessage(content);
        }
        return AssistantMessage.builder()
                .content(content)
                .properties(Map.of("reasoningContent", reasoning))
                .build();
    }

    private String reasoningText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        return reasoningText(response.getResult().getOutput());
    }

    private String reasoningText(AssistantMessage output) {
        if (output == null || output.getMetadata() == null) {
            return "";
        }
        Object value = output.getMetadata().get("reasoningContent");
        if (value == null) {
            value = output.getMetadata().get("reasoning_content");
        }
        return value == null ? "" : String.valueOf(value);
    }

    private ChatResponse callModel(Prompt prompt) {
        long startedAtMs = System.currentTimeMillis();
        log.info("Starting model request: messages={}, chars={}, toolResponses={}",
                prompt.getInstructions().size(), promptTextLength(prompt), promptToolResponseCount(prompt));
        try {
            ChatResponse response = modelConfigService.chatModel(activeModelId.get()).call(prompt);
            log.info("Finished model request after {} ms", elapsedMs(startedAtMs));
            rememberActualPromptUsage(tokenUsage(response));
            return response;
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            String detail = responseBody == null || responseBody.isBlank()
                    ? e.getMessage()
                    : responseBody;
            throw new IllegalStateException("模型请求被上游拒绝，HTTP " + e.getStatusCode().value() + ": " + detail, e);
        }
    }

    private long promptTextLength(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .mapToLong(String::length)
                .sum();
    }

    private long promptToolResponseCount(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .mapToLong(message -> message.getResponses().size())
                .sum();
    }

    private TokenUsage tokenUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        return TokenUsage.from(usage);
    }

    private void rememberActualPromptUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null || tokenUsage.promptTokens() == null || tokenUsage.promptTokens() <= 0) {
            return;
        }
        String conversationId = activeConversationId.get();
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        actualPromptUsages.put(normalizeConversationId(conversationId), new ActualPromptUsage(
                tokenUsage.promptTokens(),
                resolvedModelName(activeModelId.get()),
                activeLightCompactedBefore.get(),
                System.currentTimeMillis()
        ));
    }

    private ActualPromptUsage matchingActualPromptUsage(String conversationId, Long lightCompactedBefore,
                                                        String modelName) {
        ActualPromptUsage usage = actualPromptUsages.get(normalizeConversationId(conversationId));
        if (usage == null) {
            return null;
        }
        long requestedLightCompactedBefore = lightCompactedBefore == null ? 0L : lightCompactedBefore;
        long actualLightCompactedBefore = usage.lightCompactedBefore() == null ? 0L : usage.lightCompactedBefore();
        if (requestedLightCompactedBefore != actualLightCompactedBefore) {
            return null;
        }
        if (modelName != null && !modelName.isBlank()
                && usage.modelName() != null && !usage.modelName().isBlank()
                && !modelName.equals(usage.modelName())) {
            return null;
        }
        return usage;
    }

    private void mergeToolCalls(Map<String, ToolCallAccumulator> accumulators,
                                List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            String positionalKey = "index:" + index;
            String key = toolCall.id() == null || toolCall.id().isBlank()
                    ? keyByPosition(accumulators, index, positionalKey)
                    : toolCall.id();
            ToolCallAccumulator accumulator = accumulators.get(key);
            if (accumulator == null && !key.equals(positionalKey)) {
                accumulator = accumulators.remove(positionalKey);
            }
            if (accumulator == null) {
                accumulator = new ToolCallAccumulator();
            }
            accumulator.merge(toolCall);
            accumulators.put(key, accumulator);
        }
    }

    private String keyByPosition(Map<String, ToolCallAccumulator> accumulators, int index, String fallback) {
        if (index < accumulators.size()) {
            return new ArrayList<>(accumulators.keySet()).get(index);
        }
        return fallback;
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private ModelRuntimeOptions runtimeOptions(AgentChatRequest request) {
        return new ModelRuntimeOptions(request.reasoningEffort(), request.thinkingType(), request.extraBody());
    }

    private ModelRuntimeOptions runtimeOptions(AgentApprovalRequest request) {
        return new ModelRuntimeOptions(request.reasoningEffort(), request.thinkingType(), request.extraBody());
    }

    private ModelRuntimeOptions runtimeOptions(AgentInteractionResponse request) {
        return new ModelRuntimeOptions(request.reasoningEffort(), request.thinkingType(), request.extraBody());
    }

    private OpenAiChatOptions activeModelOptions() {
        return modelConfigService == null
                ? OpenAiChatOptions.builder().build()
                : modelConfigService.chatOptions(activeModelId.get(), activeRuntimeOptions.get());
    }

    private String resolvedModelName(String modelId) {
        try {
            if (modelConfigService != null) {
                return modelConfigService.resolve(modelId).model();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private ChatModel selectedChatModel(String modelId) {
        try {
            return modelConfigService == null ? null : modelConfigService.chatModel(modelId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId) {
        return agentToolOptionsFactory.create(workingDirectory, conversationId, List.of(), activeModelId.get(),
                currentSandboxContext(conversationId, workingDirectory, currentApprovalMode(conversationId, ApprovalMode.DEFAULT)),
                activeModelOptions());
    }

    private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId, List<Message> parentMessages) {
        return agentOptions(workingDirectory, conversationId, parentMessages,
                currentSandboxContext(conversationId, workingDirectory, currentApprovalMode(conversationId, ApprovalMode.DEFAULT)));
    }

    private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId, List<Message> parentMessages,
                                           AgentSandboxContext sandboxContext) {
        return agentToolOptionsFactory.create(workingDirectory, conversationId, parentMessages, activeModelId.get(),
                sandboxContext, activeModelOptions());
    }

    private List<RagService.KnowledgeDocumentSummary> knowledgeDocuments(String conversationId) {
        try {
            return ragService.listDocuments(conversationId);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private PendingRun takePendingRun(String runId) {
        PendingRun pending = pendingRuns.remove(runId);
        if (pending == null) {
            throw new IllegalArgumentException("待确认的工具调用不存在或已处理。");
        }
        return pending;
    }

    private String normalizeConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? "default"
                : conversationId;
    }

    private String requireWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalArgumentException("请先选择默认工作目录。");
        }
        return workingDirectory;
    }

    private static final class ToolCallAccumulator {
        private String id = "";
        private String type = "";
        private String name = "";
        private String arguments = "";

        private void merge(AssistantMessage.ToolCall toolCall) {
            id = mergeSegment(id, toolCall.id());
            type = mergeSegment(type, toolCall.type());
            name = mergeSegment(name, toolCall.name());
            arguments = mergeSegment(arguments, toolCall.arguments());
        }

        private AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(id, type.isBlank() ? "function" : type, name, arguments);
        }

        private static String mergeSegment(String current, String incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return current == null ? "" : current;
            }
            if (current == null || current.isEmpty()) {
                return incoming;
            }
            if (incoming.equals(current) || current.startsWith(incoming)) {
                return current;
            }
            if (incoming.startsWith(current)) {
                return incoming;
            }
            return current + incoming;
        }
    }

    private static final class TokenUsageAccumulator {
        private TokenUsage value;

        private synchronized void replace(TokenUsage next) {
            if (next != null) {
                value = next;
            }
        }

        private synchronized TokenUsage snapshot() {
            return value;
        }
    }

    private record StreamContext(SseEmitter emitter, AtomicBoolean open, long startedAtMs,
                                 TokenUsageAccumulator tokenUsage) {
    }

    private record ActualPromptUsage(long promptTokens, String modelName, Long lightCompactedBefore,
                                     long recordedAtMs) {
    }

    private record PendingRun(Prompt prompt, ChatResponse response, String conversationId,
                              String workingDirectory, int round, int interactionCount,
                              int toolRoundLimit, long startedAtMs, ApprovalMode approvalMode,
                              AgentSandboxContext sandboxContext, PendingRunKind kind) {
        private PendingRun(Prompt prompt, ChatResponse response, String conversationId,
                           String workingDirectory, int round, int interactionCount,
                           int toolRoundLimit, ApprovalMode approvalMode, AgentSandboxContext sandboxContext,
                           PendingRunKind kind) {
            this(prompt, response, conversationId, workingDirectory, round, interactionCount,
                    toolRoundLimit, System.currentTimeMillis(), approvalMode, sandboxContext, kind);
        }
    }

    private enum PendingRunKind {
        APPROVAL,
        USER_INTERACTION,
        LOOP_LIMIT
    }

    enum ApprovalMode {
        DEFAULT,
        WORK_AUTO,
        AUTO;

        static ApprovalMode from(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "auto" -> AUTO;
                case "default" -> DEFAULT;
                default -> WORK_AUTO;
            };
        }

        String value() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
