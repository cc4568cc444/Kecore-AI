package com.cc.springai.agent;

import com.cc.springai.registry.McpToolRegistry;
import com.cc.springai.service.RagService;
import com.cc.springai.service.TaskAgentService;
import com.cc.springai.tools.BasicTools;
import com.cc.springai.tools.CalculationTools;
import com.cc.springai.tools.DateTimeTools;
import com.cc.springai.tools.MemoryTools;
import com.cc.springai.tools.RagTools;
import com.cc.springai.tools.SkillsTools;
import com.cc.springai.tools.TaskTools;
import com.cc.springai.tools.UserInteractionTools;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentToolOptionsFactory {

    private final DateTimeTools dateTimeTools;
    private final BasicTools basicTools;
    private final CalculationTools calculationTools;
    private final RagTools ragTools;
    private final TaskTools taskTools;
    private final RagService ragService;
    private final MemoryTools memoryTools;
    private final UserInteractionTools userInteractionTools;
    private final SkillsTools skillsTools;
    private final McpToolRegistry mcpToolRegistry;

    public AgentToolOptionsFactory(DateTimeTools dateTimeTools,
                                   BasicTools basicTools,
                                   CalculationTools calculationTools,
                                   RagTools ragTools,
                                   TaskTools taskTools,
                                   RagService ragService,
                                   MemoryTools memoryTools,
                                   UserInteractionTools userInteractionTools,
                                   SkillsTools skillsTools,
                                   McpToolRegistry mcpToolRegistry) {
        this.dateTimeTools = dateTimeTools;
        this.basicTools = basicTools;
        this.calculationTools = calculationTools;
        this.ragTools = ragTools;
        this.taskTools = taskTools;
        this.ragService = ragService;
        this.memoryTools = memoryTools;
        this.userInteractionTools = userInteractionTools;
        this.skillsTools = skillsTools;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    public OpenAiChatOptions create(String workingDirectory, String conversationId) {
        return create(workingDirectory, conversationId, List.of(), "", AgentSandboxContext.of(true, workingDirectory));
    }

    public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages) {
        return create(workingDirectory, conversationId, parentMessages, "",
                AgentSandboxContext.of(true, workingDirectory));
    }

    public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages,
                                    String modelId) {
        return create(workingDirectory, conversationId, parentMessages, modelId,
                AgentSandboxContext.of(true, workingDirectory));
    }

    public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages,
                                    String modelId, AgentSandboxContext sandboxContext) {
        return create(workingDirectory, conversationId, parentMessages, modelId, sandboxContext,
                OpenAiChatOptions.builder().build());
    }

    public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages,
                                    String modelId, AgentSandboxContext sandboxContext, OpenAiChatOptions baseOptions) {
        List<Message> safeParentMessages = parentMessages == null ? List.of() : List.copyOf(parentMessages);
        String parentRunId = UUID.randomUUID().toString();
        boolean hasKnowledgeDocuments = hasKnowledgeDocuments(conversationId);
        AgentSandboxContext sandbox = sandboxContext == null
                ? AgentSandboxContext.of(true, workingDirectory)
                : sandboxContext;
        List<Object> toolBeans = new ArrayList<>(List.of(
                dateTimeTools, basicTools, calculationTools, memoryTools, userInteractionTools, skillsTools));
        if (taskTools != null) {
            toolBeans.add(taskTools);
        }
        if (hasKnowledgeDocuments) {
            toolBeans.add(ragTools);
        }
        ToolCallback[] callbacks = ToolCallbacks.from(toolBeans.toArray());
        List<ToolCallback> allCallbacks = new ArrayList<>(Arrays.asList(callbacks));
        allCallbacks.addAll(mcpToolRegistry.toolCallbacks());
        if (!hasKnowledgeDocuments) {
            allCallbacks.removeIf(callback -> AgentToolPolicy.RAG_SEARCH_TOOL.equals(callback.getToolDefinition().name()));
        }
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(baseOptions == null
                ? OpenAiChatOptions.builder().build()
                : baseOptions);
        options.setToolCallbacks(allCallbacks);
        options.setToolContext(Map.of(
                BasicTools.WORKING_DIRECTORY_CONTEXT_KEY, workingDirectory,
                AgentSandboxContext.TOOL_CONTEXT_KEY, sandbox,
                "conversationId", conversationId,
                TaskAgentService.PARENT_MESSAGES_CONTEXT_KEY, safeParentMessages,
                TaskAgentService.PARENT_RUN_ID_CONTEXT_KEY, parentRunId,
                TaskAgentService.MODEL_ID_CONTEXT_KEY, modelId == null ? "" : modelId
        ));
        options.setInternalToolExecutionEnabled(false);
        return options;
    }

    private boolean hasKnowledgeDocuments(String conversationId) {
        try {
            return !ragService.listDocuments(conversationId).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
}
