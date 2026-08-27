package com.cc.springai.agent;

import com.cc.springai.tools.TaskTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

@Component
public class AgentToolPolicy {

    public static final int MAX_CONSECUTIVE_RAG_SEARCH_CALLS = 3;
    public static final String RAG_SEARCH_TOOL = "ragSearch";
    public static final String TASK_TOOL = "task";
    public static final String ASK_USER_TOOL = "askUser";
    public static final String EXECUTE_SHELL_TOOL = "executeShellCommand";

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "getDateTime", "readFile", "listFiles", "grep", "ragSearch", "calculateExpression", "calculateBigNumber",
            "read_memory", "listSkills", "useSkill", "readSkillResource",
            "dateTimeTools_getDateTime", "DateTimeTools_getDateTime");
    private static final Set<String> WORKSPACE_FILE_TOOLS = Set.of(
            "readFile", "listFiles", "grep", "openFile", "writeFile", "replaceText", "copyFile");
    private static final Set<String> USER_INTERACTION_TOOLS = Set.of(ASK_USER_TOOL);

    private final ObjectProvider<TaskTools> taskToolsProvider;
    private final ObjectMapper objectMapper;

    public AgentToolPolicy(ObjectProvider<TaskTools> taskToolsProvider, ObjectMapper objectMapper) {
        this.taskToolsProvider = taskToolsProvider;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public boolean requiresApproval(List<ToolRequest> tools) {
        return tools.stream().anyMatch(tool -> {
            if (TASK_TOOL.equals(tool.name())) {
                TaskTools taskTools = taskToolsProvider.getIfAvailable();
                return taskTools == null || !taskTools.isAutoApprovable(tool.arguments());
            }
            return !READ_ONLY_TOOLS.contains(tool.name());
        });
    }

    public boolean requiresWorkspaceApproval(List<ToolRequest> tools, AgentSandboxContext sandbox) {
        if (sandbox == null) {
            return false;
        }
        return tools.stream().anyMatch(tool -> !isWorkspaceAutoApprovable(tool, sandbox));
    }

    private boolean isWorkspaceAutoApprovable(ToolRequest tool, AgentSandboxContext sandbox) {
        if (tool == null || tool.name() == null) {
            return false;
        }
        if (READ_ONLY_TOOLS.contains(tool.name()) && !WORKSPACE_FILE_TOOLS.contains(tool.name())) {
            return true;
        }
        if (TASK_TOOL.equals(tool.name())) {
            TaskTools taskTools = taskToolsProvider.getIfAvailable();
            return taskTools == null || !taskTools.isAutoApprovable(tool.arguments());
        }
        if (EXECUTE_SHELL_TOOL.equals(tool.name())) {
            return sandbox.commandLooksWorkspaceScopedForApproval(asText(parseArguments(tool.arguments()).get("command")));
        }
        if (!WORKSPACE_FILE_TOOLS.contains(tool.name())) {
            return false;
        }
        Map<String, Object> arguments = parseArguments(tool.arguments());
        return switch (tool.name()) {
            case "copyFile" -> sandbox.containsPath(asText(arguments.get("sourcePath")))
                    && sandbox.containsPath(asText(arguments.get("targetPath")));
            case "grep" -> sandbox.containsPath(asText(arguments.getOrDefault("path", ".")));
            case "readFile", "listFiles", "openFile", "writeFile", "replaceText" ->
                    sandbox.containsPath(asText(arguments.get("path")));
            default -> false;
        };
    }

    public boolean requiresUserInteraction(List<ToolRequest> tools) {
        return tools.stream().anyMatch(tool -> USER_INTERACTION_TOOLS.contains(tool.name()));
    }

    public boolean isUserInteractionTool(String toolName) {
        return USER_INTERACTION_TOOLS.contains(toolName);
    }

    public boolean exceedsConsecutiveRagSearchLimit(List<ToolRequest> tools, List<ExecutedToolBatch> executions) {
        if (tools.stream().noneMatch(tool -> RAG_SEARCH_TOOL.equals(tool.name()))) {
            return false;
        }

        int consecutiveRagSearches = 0;
        ListIterator<ExecutedToolBatch> iterator = executions.listIterator(executions.size());
        while (iterator.hasPrevious()) {
            ExecutedToolBatch batch = iterator.previous();
            boolean hasRagSearch = batch.tools().stream()
                    .anyMatch(tool -> RAG_SEARCH_TOOL.equals(tool.name()));
            if (!hasRagSearch) {
                break;
            }
            consecutiveRagSearches++;
        }

        return consecutiveRagSearches >= MAX_CONSECUTIVE_RAG_SEARCH_CALLS;
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
