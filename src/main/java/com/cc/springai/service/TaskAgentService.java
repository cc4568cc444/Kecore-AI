package com.cc.springai.service;

import com.cc.springai.agent.AgentSandboxContext;
import com.cc.springai.tools.BasicTools;
import com.cc.springai.tools.CalculationTools;
import com.cc.springai.tools.DateTimeTools;
import com.cc.springai.tools.RagTools;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class TaskAgentService {

    public static final String PARENT_MESSAGES_CONTEXT_KEY = "parentMessages";
    public static final String PARENT_RUN_ID_CONTEXT_KEY = "parentRunId";
    public static final String MODEL_ID_CONTEXT_KEY = "modelId";

    private static final int DEFAULT_MAX_ROUNDS = 10;
    private static final int MAX_PARENT_CONTEXT_CHARS = 40_000;
    private static final Set<TaskRole> BACKGROUND_ALLOWED_ROLES = Set.of(TaskRole.EXPLORER, TaskRole.REVIEWER);
    private static final Map<TaskRole, RolePolicy> ROLE_POLICIES = new EnumMap<>(TaskRole.class);
    private static final ThreadLocal<Consumer<TaskProgressEvent>> PROGRESS_SINK = new ThreadLocal<>();

    static {
        ROLE_POLICIES.put(TaskRole.EXPLORER, new RolePolicy(15, Set.of(
                "getDateTime", "readFile", "listFiles", "grep", "ragSearch", "calculateExpression", "calculateBigNumber")));
        ROLE_POLICIES.put(TaskRole.REVIEWER, new RolePolicy(20, Set.of(
                "getDateTime", "readFile", "listFiles", "grep", "ragSearch", "calculateExpression", "calculateBigNumber")));
        ROLE_POLICIES.put(TaskRole.PLANNER, new RolePolicy(10, Set.of(
                "getDateTime", "readFile", "listFiles", "grep", "calculateExpression", "calculateBigNumber")));
        ROLE_POLICIES.put(TaskRole.TESTER, new RolePolicy(25, Set.of(
                "getDateTime", "readFile", "listFiles", "grep", "executeShellCommand",
                "calculateExpression", "calculateBigNumber")));
        ROLE_POLICIES.put(TaskRole.IMPLEMENTER, new RolePolicy(50, Set.of(
                "getDateTime", "readFile", "listFiles", "grep", "executeShellCommand", "writeFile", "replaceText",
                "copyFile", "calculateExpression", "calculateBigNumber", "ragSearch")));
    }

    private final OpenAiChatModel model;
    private final ModelConfigService modelConfigService;
    private final ToolCallingManager toolCallingManager;
    private final DateTimeTools dateTimeTools;
    private final BasicTools basicTools;
    private final CalculationTools calculationTools;
    private final RagTools ragTools;
    private final RagService ragService;
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final Map<String, List<BackgroundTaskNotification>> completedBackgroundTasks = new ConcurrentHashMap<>();
    private final Map<String, TaskProgressSnapshot> taskProgressById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> taskIdsByConversation = new ConcurrentHashMap<>();
    private final Map<String, TaskGroupState> taskGroupsById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> taskGroupIdsByConversation = new ConcurrentHashMap<>();

    @Autowired
    public TaskAgentService(OpenAiChatModel model,
                            ModelConfigService modelConfigService,
                            ToolCallingManager toolCallingManager,
                            DateTimeTools dateTimeTools,
                            BasicTools basicTools,
                            CalculationTools calculationTools,
                            RagTools ragTools,
                            RagService ragService) {
        this.model = model;
        this.modelConfigService = modelConfigService;
        this.toolCallingManager = toolCallingManager;
        this.dateTimeTools = dateTimeTools;
        this.basicTools = basicTools;
        this.calculationTools = calculationTools;
        this.ragTools = ragTools;
        this.ragService = ragService;
    }

    public TaskAgentService(OpenAiChatModel model,
                            ToolCallingManager toolCallingManager,
                            DateTimeTools dateTimeTools,
                            BasicTools basicTools,
                            CalculationTools calculationTools,
                            RagTools ragTools,
                            RagService ragService) {
        this(model, null, toolCallingManager, dateTimeTools, basicTools, calculationTools, ragTools, ragService);
    }

    public String run(TaskRequest request) {
        TaskRole role = TaskRole.from(request.role());
        TaskMode mode = TaskMode.from(request.mode());
        String taskId = UUID.randomUUID().toString();
        Consumer<TaskProgressEvent> progressSink = PROGRESS_SINK.get();
        if (request.prompt() == null || request.prompt().isBlank()) {
            return "task 执行失败：prompt 不能为空。";
        }
        if (request.workingDirectory() == null || request.workingDirectory().isBlank()) {
            return "task 执行失败：缺少工作目录。";
        }
        if (request.background() && !BACKGROUND_ALLOWED_ROLES.contains(role)) {
            return "task 执行失败：background=true 只允许 explorer/reviewer 这类只读角色，避免并发写入造成状态污染。";
        }

        int maxRounds = effectiveMaxRounds(role, request.maxRounds());
        String taskGroupId = request.background()
                ? safeParentRunId(request.parentRunId(), request.conversationId())
                : "";
        TaskExecution execution = new TaskExecution(request, role, mode, maxRounds, taskId, taskGroupId, progressSink);
        emitProgress(execution, "started", 0, "子 agent 已启动", List.of(), "");
        if (!request.background()) {
            return runChildAgent(execution);
        }

        registerBackgroundTask(execution);
        backgroundExecutor.submit(() -> {
            String summary;
            boolean failed = false;
            try {
                summary = runChildAgent(execution);
            } catch (Exception e) {
                failed = true;
                summary = "后台 task 子 agent 执行失败：" + e.getMessage();
            }
            completedBackgroundTasks
                    .computeIfAbsent(request.conversationId(), ignored -> new CopyOnWriteArrayList<>())
                    .add(new BackgroundTaskNotification(taskId, execution.taskGroupId(), role.name().toLowerCase(), summary));
            completeBackgroundTask(execution, failed);
        });
        return """
                task 已在后台启动。
                taskId: %s
                taskGroupId: %s
                role: %s
                mode: %s
                maxRounds: %d
                父 agent 可以继续处理当前任务；同一轮派发的后台子 agent 会作为一个任务组等待全部完成后再注入后续上下文。
                """.formatted(taskId, taskGroupId, role.name().toLowerCase(), mode.name().toLowerCase(), maxRounds);
    }

    public List<SystemMessage> drainCompletedTaskMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<BackgroundTaskNotification> notifications = completedBackgroundTasks.get(conversationId);
        if (notifications == null || notifications.isEmpty()) {
            return List.of();
        }
        List<BackgroundTaskNotification> ready = notifications.stream()
                .filter(notification -> isNotificationReady(notification, conversationId))
                .toList();
        if (ready.isEmpty()) {
            return List.of();
        }
        notifications.removeAll(ready);
        if (notifications.isEmpty()) {
            completedBackgroundTasks.remove(conversationId, notifications);
        }
        ready.forEach(notification -> markGroupResumed(notification.taskGroupId()));
        return ready.stream()
                .map(notification -> new SystemMessage("""
                        ## 后台 task 子 agent 已完成
                        taskId: %s
                        role: %s
                        result:
                        %s
                        """.formatted(notification.taskId(), notification.role(), notification.summary())))
                .toList();
    }

    public List<TaskGroupSnapshot> taskGroups(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return taskGroupIdsByConversation.getOrDefault(conversationId, List.of()).stream()
                .map(taskGroupsById::get)
                .filter(Objects::nonNull)
                .map(TaskGroupState::snapshot)
                .toList();
    }

    public List<TaskProgressSnapshot> progressSnapshots(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return taskIdsByConversation.getOrDefault(conversationId, List.of()).stream()
                .map(taskProgressById::get)
                .filter(Objects::nonNull)
                .map(this::withGroupState)
                .toList();
    }

    public boolean isAutoApprovable(String role, boolean background) {
        TaskRole taskRole = TaskRole.from(role);
        if (background && !BACKGROUND_ALLOWED_ROLES.contains(taskRole)) {
            return true;
        }
        return !taskRole.canMutate();
    }

    public <T> T withProgressSink(Consumer<TaskProgressEvent> sink, Supplier<T> action) {
        Consumer<TaskProgressEvent> previous = PROGRESS_SINK.get();
        if (sink == null) {
            PROGRESS_SINK.remove();
        } else {
            PROGRESS_SINK.set(sink);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                PROGRESS_SINK.remove();
            } else {
                PROGRESS_SINK.set(previous);
            }
        }
    }

    int effectiveMaxRounds(TaskRole role, Integer requestedMaxRounds) {
        int requested = requestedMaxRounds == null ? DEFAULT_MAX_ROUNDS : Math.max(1, requestedMaxRounds);
        return Math.min(requested, ROLE_POLICIES.get(role).maxRounds());
    }

    private String runChildAgent(TaskExecution execution) {
        List<Message> messages = childInitialMessages(execution);
        Prompt prompt = new Prompt(messages, childOptions(execution));
        ChatResponse response = callModel(prompt, execution.request().modelId());
        List<ExecutedToolSummary> tools = new ArrayList<>();

        int round = 0;
        while (response.hasToolCalls()) {
            if (round >= execution.maxRounds()) {
                emitProgress(execution, "limit_reached", round,
                        "子 agent 达到最大工具轮数，已停止继续调用工具", List.of(), "");
                String partialSummary = summarizeToolEvidenceAtRoundLimit(execution, tools);
                emitProgress(execution, "completed", round,
                        "子 agent 已在轮数上限处完成部分结果总结", List.of(), partialSummary);
                return """
                        taskId: %s
                        role: %s
                        mode: %s
                        maxRounds: %d

                        子 agent 达到最大工具轮数 %d，已基于现有结果完成部分总结。
                        后续如需继续，应由父 agent 缩小子任务范围后重新派发。

                        子 agent 部分总结：
                        %s

                        已执行工具：
                        %s
                        """.formatted(
                        execution.taskId(),
                        execution.role().name().toLowerCase(),
                        execution.mode().name().toLowerCase(),
                        execution.maxRounds(),
                        execution.maxRounds(),
                        partialSummary,
                        formatToolSummaries(tools));
            }
            List<String> requestedTools = response.getResult().getOutput().getToolCalls().stream()
                    .map(call -> call.name())
                    .distinct()
                    .toList();
            emitProgress(execution, "tool_calling", round + 1,
                    "第 %d 轮正在调用工具：%s".formatted(round + 1, String.join("、", requestedTools)),
                    requestedTools, "");
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            List<ExecutedToolSummary> roundTools = executedToolSummaries(result);
            tools.addAll(roundTools);
            emitProgress(execution, "tool_completed", round + 1,
                    "第 %d 轮工具执行完成".formatted(round + 1),
                    roundTools.stream().map(ExecutedToolSummary::name).toList(),
                    formatToolSummaries(roundTools));
            messages = result.conversationHistory();
            prompt = new Prompt(messages, childOptions(execution));
            response = callModel(prompt, execution.request().modelId());
            round++;
        }

        String answer = response.getResult().getOutput().getText();
        emitProgress(execution, "completed", round, "子 agent 已完成", List.of(), answer == null ? "" : answer);
        return """
                taskId: %s
                role: %s
                mode: %s
                maxRounds: %d

                子 agent 总结：
                %s

                已执行工具：
                %s
        """.formatted(
                execution.taskId(),
                execution.role().name().toLowerCase(),
                execution.mode().name().toLowerCase(),
                execution.maxRounds(),
                answer == null ? "" : answer,
                formatToolSummaries(tools));
    }

    private ChatResponse callModel(Prompt prompt, String modelId) {
        try {
            OpenAiChatModel activeModel = modelConfigService == null ? model : modelConfigService.chatModel(modelId);
            return activeModel.call(prompt);
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            String detail = responseBody == null || responseBody.isBlank()
                    ? e.getMessage()
                    : responseBody;
            throw new IllegalStateException("模型请求被上游拒绝，HTTP " + e.getStatusCode().value() + ": " + detail, e);
        }
    }

    private String summarizeToolEvidenceAtRoundLimit(TaskExecution execution, List<ExecutedToolSummary> tools) {
        String latestEvidence = tools.isEmpty()
                ? "(尚未执行任何工具)"
                : tools.stream()
                .skip(Math.max(0, tools.size() - 8))
                .map(tool -> "- %s: %s".formatted(tool.name(), tool.outputPreview()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(无)");
        return """
                已达到该角色最大工具轮数 %d，子 agent 已停止继续调用工具。
                已完成：执行了 %d 次工具调用，最近结果如下：
                %s

                未完成：没有机会继续扩展检索或做最终模型整理。
                建议：父 agent 应基于上述已返回证据继续回答；如信息不足，请派发更小、更明确的子任务。
                """.formatted(execution.maxRounds(), tools.size(), latestEvidence);
    }

    private List<Message> childInitialMessages(TaskExecution execution) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(childSystemPrompt(execution)));
        if (execution.mode() == TaskMode.FORK) {
            String parentContext = formatParentContext(execution.request().parentMessages());
            if (!parentContext.isBlank()) {
                messages.add(new SystemMessage("""
                        ## 父 agent 继承上下文
                        以下是父 agent 当前可见上下文的压缩文本。只把它当作背景信息，最终只返回子任务结论。

                        %s
                        """.formatted(parentContext)));
            }
        }
        messages.add(new UserMessage("""
                ## 子任务
                %s
                """.formatted(execution.request().prompt().strip())));
        return messages;
    }

    private String childSystemPrompt(TaskExecution execution) {
        String roleGuide = switch (execution.role()) {
            case EXPLORER -> "你是 explorer 子 agent。目标是快速收集事实、读取必要文件、整理证据和来源，不做代码修改。";
            case REVIEWER -> "你是 reviewer 子 agent。目标是发现缺陷、风险、回归和缺失测试；输出按严重程度排序的审查发现，不做代码修改。";
            case PLANNER -> "你是 planner 子 agent。目标是拆解问题、提出可执行计划和关键取舍，避免执行实现工作。";
            case TESTER -> "你是 tester 子 agent。目标是运行或设计验证步骤，报告命令、结果和失败原因，不主动修改文件。";
            case IMPLEMENTER -> "你是 implementer 子 agent。目标是按子任务范围修改文件并验证结果，避免无关重构。";
        };
        return """
                %s

                默认工作目录：%s
                上下文模式：%s
                最大工具轮数：%d

                只使用系统提供给你的工具；你没有 task 工具，不能再派生子 agent。
                完成后只向父 agent 返回简明总结，包括关键结论、修改位置或验证结果。
                """.formatted(
                roleGuide,
                execution.request().workingDirectory(),
                execution.mode().name().toLowerCase(),
                execution.maxRounds());
    }

    private String formatParentContext(List<Message> parentMessages) {
        if (parentMessages == null || parentMessages.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        for (Message message : parentMessages) {
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            context.append("[").append(message.getMessageType().getValue()).append("]\n")
                    .append(text.strip()).append("\n\n");
            if (context.length() >= MAX_PARENT_CONTEXT_CHARS) {
                return context.substring(0, MAX_PARENT_CONTEXT_CHARS) + "\n...父上下文已截断";
            }
        }
        return context.toString().strip();
    }

    private OpenAiChatOptions childOptions(TaskExecution execution) {
        return OpenAiChatOptions.builder()
                .toolCallbacks(childToolCallbacks(execution.role(), execution.request().conversationId()))
                .toolContext(Map.of(
                        BasicTools.WORKING_DIRECTORY_CONTEXT_KEY, execution.request().workingDirectory(),
                        AgentSandboxContext.TOOL_CONTEXT_KEY, execution.request().sandboxContext(),
                        "conversationId", execution.request().conversationId()
                ))
                .internalToolExecutionEnabled(false)
                .build();
    }

    private List<ToolCallback> childToolCallbacks(TaskRole role, String conversationId) {
        RolePolicy policy = ROLE_POLICIES.get(role);
        List<Object> toolBeans = new ArrayList<>(List.of(dateTimeTools, basicTools, calculationTools));
        if (policy.toolNames().contains("ragSearch") && hasKnowledgeDocuments(conversationId)) {
            toolBeans.add(ragTools);
        }
        return Arrays.stream(ToolCallbacks.from(toolBeans.toArray()))
                .filter(callback -> policy.toolNames().contains(callback.getToolDefinition().name()))
                .toList();
    }

    private boolean hasKnowledgeDocuments(String conversationId) {
        try {
            return !ragService.listDocuments(conversationId).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<ExecutedToolSummary> executedToolSummaries(ToolExecutionResult result) {
        return result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((left, right) -> right)
                .map(ToolResponseMessage::getResponses)
                .orElse(List.of())
                .stream()
                .map(response -> new ExecutedToolSummary(response.name(), preview(response.responseData())))
                .toList();
    }

    private String formatToolSummaries(List<ExecutedToolSummary> tools) {
        if (tools.isEmpty()) {
            return "(无)";
        }
        return tools.stream()
                .map(tool -> "- %s: %s".formatted(tool.name(), tool.outputPreview()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(无)");
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private void emitProgress(TaskExecution execution, String status, int round, String message,
                              List<String> tools, String detail) {
        Consumer<TaskProgressEvent> sink = execution.progressSink();
        try {
            TaskProgressEvent event = new TaskProgressEvent(
                    execution.taskId(),
                    execution.request().conversationId(),
                    execution.request().prompt(),
                    execution.role().name().toLowerCase(),
                    execution.mode().name().toLowerCase(),
                    execution.request().background(),
                    execution.parentRunId(),
                    execution.taskGroupId(),
                    execution.maxRounds(),
                    round,
                    status,
                    message,
                    tools == null ? List.of() : tools,
                    detail == null ? "" : detail
            );
            rememberProgress(event);
            if (sink != null) {
                sink.accept(event);
            }
        } catch (Exception ignored) {
        }
    }

    private void rememberProgress(TaskProgressEvent event) {
        List<String> taskIds = taskIdsByConversation
                .computeIfAbsent(event.conversationId(), ignored -> new CopyOnWriteArrayList<>());
        if (!taskIds.contains(event.taskId())) {
            taskIds.add(event.taskId());
        }
        taskProgressById.compute(event.taskId(), (taskId, current) -> {
            List<TaskProgressEvent> events = new ArrayList<>(current == null ? List.of() : current.events());
            events.add(event);
            if (events.size() > 50) {
                events = events.subList(events.size() - 50, events.size());
            }
            TaskGroupState group = taskGroupsById.get(event.taskGroupId());
            TaskGroupSnapshot groupSnapshot = group == null
                    ? TaskGroupSnapshot.empty(event.parentRunId(), event.taskGroupId())
                    : group.snapshot();
            return new TaskProgressSnapshot(
                    event.taskId(),
                    event.conversationId(),
                    event.prompt(),
                    event.role(),
                    event.mode(),
                    event.background(),
                    event.parentRunId(),
                    event.taskGroupId(),
                    groupSnapshot.status(),
                    groupSnapshot.completedCount(),
                    groupSnapshot.failedCount(),
                    groupSnapshot.totalCount(),
                    groupSnapshot.readyToResume(),
                    groupSnapshot.resumed(),
                    event.maxRounds(),
                    event.round(),
                    event.status(),
                    event.message(),
                    events
            );
        });
    }

    private TaskProgressSnapshot withGroupState(TaskProgressSnapshot snapshot) {
        TaskGroupState group = taskGroupsById.get(snapshot.taskGroupId());
        if (group == null) {
            return snapshot;
        }
        return group.enrich(snapshot);
    }

    private void registerBackgroundTask(TaskExecution execution) {
        String groupId = execution.taskGroupId();
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        taskGroupIdsByConversation
                .computeIfAbsent(execution.request().conversationId(), ignored -> new CopyOnWriteArrayList<>());
        if (!taskGroupIdsByConversation.get(execution.request().conversationId()).contains(groupId)) {
            taskGroupIdsByConversation.get(execution.request().conversationId()).add(groupId);
        }
        taskGroupsById.compute(groupId, (key, current) -> {
            TaskGroupState state = current == null
                    ? new TaskGroupState(groupId, execution.request().conversationId(), execution.parentRunId())
                    : current;
            state.registerTask(execution.taskId());
            return state;
        });
    }

    private void completeBackgroundTask(TaskExecution execution, boolean failed) {
        String groupId = execution.taskGroupId();
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        taskGroupsById.computeIfPresent(groupId, (key, current) -> {
            current.completeTask(execution.taskId(), failed);
            return current;
        });
    }

    private boolean isNotificationReady(BackgroundTaskNotification notification, String conversationId) {
        String groupId = notification.taskGroupId();
        if (groupId == null || groupId.isBlank()) {
            return true;
        }
        TaskGroupState state = taskGroupsById.get(groupId);
        return state == null || (state.conversationId.equals(conversationId) && state.isReadyToResume());
    }

    private void markGroupResumed(String taskGroupId) {
        if (taskGroupId == null || taskGroupId.isBlank()) {
            return;
        }
        taskGroupsById.computeIfPresent(taskGroupId, (key, current) -> {
            current.markResumed();
            return current;
        });
    }

    private String safeParentRunId(String parentRunId, String conversationId) {
        if (parentRunId != null && !parentRunId.isBlank()) {
            return parentRunId;
        }
        String normalizedConversationId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        return normalizedConversationId + "-" + UUID.randomUUID();
    }

    @PreDestroy
    void shutdown() {
        backgroundExecutor.shutdownNow();
    }

    public record TaskRequest(String prompt,
                              String role,
                              String mode,
                              boolean background,
                              Integer maxRounds,
                              String conversationId,
                              String workingDirectory,
                              AgentSandboxContext sandboxContext,
                              List<Message> parentMessages,
                              String parentRunId,
                              String modelId) {
    }

    public record TaskProgressEvent(String taskId,
                                    String conversationId,
                                    String prompt,
                                    String role,
                                    String mode,
                                    boolean background,
                                    String parentRunId,
                                    String taskGroupId,
                                    int maxRounds,
                                    int round,
                                    String status,
                                    String message,
                                    List<String> tools,
                                    String detail) {
    }

    public record TaskProgressSnapshot(String taskId,
                                       String conversationId,
                                       String prompt,
                                       String role,
                                       String mode,
                                       boolean background,
                                       String parentRunId,
                                       String taskGroupId,
                                       String groupStatus,
                                       int groupCompleted,
                                       int groupFailed,
                                       int groupTotal,
                                       boolean readyToResume,
                                       boolean resumed,
                                       int maxRounds,
                                       int round,
                                       String status,
                                       String message,
                                       List<TaskProgressEvent> events) {
    }

    public enum TaskRole {
        EXPLORER(false),
        REVIEWER(false),
        PLANNER(false),
        TESTER(true),
        IMPLEMENTER(true);

        private final boolean canMutate;

        TaskRole(boolean canMutate) {
            this.canMutate = canMutate;
        }

        public boolean canMutate() {
            return canMutate;
        }

        static TaskRole from(String value) {
            if (value == null || value.isBlank()) {
                return EXPLORER;
            }
            try {
                return TaskRole.valueOf(value.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的 task role：" + value);
            }
        }
    }

    private enum TaskMode {
        DEFAULT,
        FORK;

        static TaskMode from(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT;
            }
            try {
                return TaskMode.valueOf(value.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的 task mode：" + value);
            }
        }
    }

    private record RolePolicy(int maxRounds, Set<String> toolNames) {
    }

    private record TaskExecution(TaskRequest request, TaskRole role, TaskMode mode, int maxRounds,
                                 String taskId, String taskGroupId, Consumer<TaskProgressEvent> progressSink) {
        private String parentRunId() {
            return request.parentRunId();
        }
    }

    private record ExecutedToolSummary(String name, String outputPreview) {
    }

    private record BackgroundTaskNotification(String taskId, String taskGroupId, String role, String summary) {
    }

    public record TaskGroupSnapshot(String taskGroupId,
                                    String conversationId,
                                    String parentRunId,
                                    String status,
                                    int totalCount,
                                    int completedCount,
                                    int failedCount,
                                    boolean readyToResume,
                                    boolean resumed,
                                    List<String> taskIds) {
        static TaskGroupSnapshot empty(String parentRunId, String taskGroupId) {
            return new TaskGroupSnapshot(taskGroupId, "", parentRunId, "pending", 0, 0, 0, false, false, List.of());
        }
    }

    private static final class TaskGroupState {
        private final String taskGroupId;
        private final String conversationId;
        private final String parentRunId;
        private final List<String> taskIds = new CopyOnWriteArrayList<>();
        private final Set<String> completedTaskIds = ConcurrentHashMap.newKeySet();
        private final Set<String> failedTaskIds = ConcurrentHashMap.newKeySet();
        private volatile boolean resumed;

        private TaskGroupState(String taskGroupId, String conversationId, String parentRunId) {
            this.taskGroupId = taskGroupId;
            this.conversationId = conversationId;
            this.parentRunId = parentRunId;
        }

        private void registerTask(String taskId) {
            if (taskId != null && !taskId.isBlank() && !taskIds.contains(taskId)) {
                taskIds.add(taskId);
            }
        }

        private void completeTask(String taskId, boolean failed) {
            if (taskId == null || taskId.isBlank()) {
                return;
            }
            completedTaskIds.add(taskId);
            if (failed) {
                failedTaskIds.add(taskId);
            }
        }

        private void markResumed() {
            resumed = true;
        }

        private boolean isReadyToResume() {
            return !resumed && !taskIds.isEmpty() && completedTaskIds.size() >= taskIds.size();
        }

        private TaskGroupSnapshot snapshot() {
            String status = resumed
                    ? "resumed"
                    : completedTaskIds.size() >= taskIds.size() && !taskIds.isEmpty()
                    ? (failedTaskIds.isEmpty() ? "completed" : "failed")
                    : "running";
            return new TaskGroupSnapshot(
                    taskGroupId,
                    conversationId,
                    parentRunId,
                    status,
                    taskIds.size(),
                    completedTaskIds.size(),
                    failedTaskIds.size(),
                    isReadyToResume(),
                    resumed,
                    List.copyOf(taskIds));
        }

        private TaskProgressSnapshot enrich(TaskProgressSnapshot snapshot) {
            TaskGroupSnapshot group = snapshot();
            return new TaskProgressSnapshot(
                    snapshot.taskId(),
                    snapshot.conversationId(),
                    snapshot.prompt(),
                    snapshot.role(),
                    snapshot.mode(),
                    snapshot.background(),
                    snapshot.parentRunId(),
                    snapshot.taskGroupId(),
                    group.status(),
                    group.completedCount(),
                    group.failedCount(),
                    group.totalCount(),
                    group.readyToResume(),
                    group.resumed(),
                    snapshot.maxRounds(),
                    snapshot.round(),
                    snapshot.status(),
                    snapshot.message(),
                    snapshot.events());
        }
    }
}

