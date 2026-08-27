package com.cc.springai.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.AgentSandboxContext;
import com.cc.springai.service.TaskAgentService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TaskTools {

    private final TaskAgentService taskAgentService;
    private final ObjectMapper objectMapper;

    public TaskTools(TaskAgentService taskAgentService, ObjectMapper objectMapper) {
        this.taskAgentService = taskAgentService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = """
            运行一个受限子 agent 来完成明确的子任务。role 支持 explorer、reviewer、planner、tester、implementer。
            mode=default 时子 agent 不继承父上下文；mode=fork 时会继承父 agent 当前上下文摘要。
            background=true 表示后台异步执行，父 agent 不等待；后台模式只允许 explorer/reviewer 只读角色，避免并发写入状态污染。
            如果需要多个并行子 agent，应在同一轮连续多次调用 task，并且每个并行 task 都设置 background=true；这些后台 task 会组成任务组，全部完成后再注入父 agent 后续上下文。
            子 agent 没有 task 工具，不能继续嵌套派生。每个子任务的 prompt 必须边界清晰、互不重复。
            """)
    public String task(
            @ToolParam(description = "子 agent 要完成的具体任务，必须清晰、边界明确") String prompt,
            @ToolParam(description = "子 agent 角色：explorer、reviewer、planner、tester、implementer") String role,
            @ToolParam(description = "上下文模式：default 不继承父上下文；fork 继承父上下文", required = false) String mode,
            @ToolParam(description = "是否后台异步执行；true 时父 agent 不等待结果，默认 false", required = false) Boolean background,
            @ToolParam(description = "最大工具轮数；默认 10，会按角色上限截断", required = false) Integer maxRounds,
            ToolContext toolContext) {
        try {
            Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
            String conversationId = (String) context.getOrDefault("conversationId", "default");
            String workingDirectory = (String) context.get(BasicTools.WORKING_DIRECTORY_CONTEXT_KEY);
            List<Message> parentMessages = parentMessages(context.get(TaskAgentService.PARENT_MESSAGES_CONTEXT_KEY));
            Object parentRunIdValue = context.get(TaskAgentService.PARENT_RUN_ID_CONTEXT_KEY);
            String parentRunId = parentRunIdValue == null ? "" : String.valueOf(parentRunIdValue);
            Object modelIdValue = context.get(TaskAgentService.MODEL_ID_CONTEXT_KEY);
            String modelId = modelIdValue == null ? "" : String.valueOf(modelIdValue);
            Object sandboxValue = context.get(AgentSandboxContext.TOOL_CONTEXT_KEY);
            AgentSandboxContext sandbox = sandboxValue instanceof AgentSandboxContext value
                    ? value
                    : AgentSandboxContext.of(true, workingDirectory);
            return taskAgentService.run(new TaskAgentService.TaskRequest(
                    prompt,
                    role,
                    mode,
                    Boolean.TRUE.equals(background),
                    maxRounds,
                    conversationId,
                    workingDirectory,
                    sandbox,
                    parentMessages,
                    parentRunId,
                    modelId
            ));
        } catch (Exception e) {
            return "task 执行失败：" + e.getMessage();
        }
    }

    public List<org.springframework.ai.chat.messages.SystemMessage> drainCompletedTaskMessages(String conversationId) {
        return taskAgentService.drainCompletedTaskMessages(conversationId);
    }

    public boolean isAutoApprovable(String arguments) {
        try {
            Map<String, Object> values = parseArguments(arguments);
            String role = values.containsKey("role") ? String.valueOf(values.get("role")) : "";
            boolean background = asBoolean(values.get("background"));
            return taskAgentService.isAutoApprovable(role, background);
        } catch (Exception ignored) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Message> parentMessages(Object value) {
        if (value instanceof List<?> list && list.stream().allMatch(Message.class::isInstance)) {
            return (List<Message>) list;
        }
        return List.of();
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

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof String text && Boolean.parseBoolean(text);
    }
}
