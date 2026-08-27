package com.cc.springai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.AgentChatRequest;
import com.cc.springai.agent.AgentToolOptionsFactory;
import com.cc.springai.agent.AgentSandboxContext;
import com.cc.springai.agent.AgentToolPolicy;
import com.cc.springai.agent.ExecutedTool;
import com.cc.springai.agent.ExecutedToolBatch;
import com.cc.springai.agent.InteractionRequest;
import com.cc.springai.agent.ToolRequest;
import com.cc.springai.config.ShellToolConfiguration;
import com.cc.springai.registry.McpToolRegistry;
import com.cc.springai.service.ConversationCompactionService;
import com.cc.springai.service.MemoryService;
import com.cc.springai.service.RagService;
import com.cc.springai.service.TaskAgentService;
import com.cc.springai.skill.AgentSkillService;
import com.cc.springai.tools.BasicTools;
import com.cc.springai.tools.CalculationTools;
import com.cc.springai.tools.DateTimeTools;
import com.cc.springai.tools.MemoryTools;
import com.cc.springai.tools.RagTools;
import com.cc.springai.tools.SkillsTools;
import com.cc.springai.tools.TaskTools;
import com.cc.springai.tools.UserInteractionTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private final AgentController controller = new AgentController();

    @BeforeEach
    void setUp() {
        TaskTools taskTools = mock(TaskTools.class);
        when(taskTools.isAutoApprovable("{\"prompt\":\"inspect files\",\"role\":\"explorer\"}")).thenReturn(true);
        when(taskTools.isAutoApprovable("{\"prompt\":\"change files\",\"role\":\"implementer\"}")).thenReturn(false);
        when(taskTools.isAutoApprovable(
                "{\"prompt\":\"change files\",\"role\":\"implementer\",\"background\":true}")).thenReturn(true);
        ReflectionTestUtils.setField(controller, "taskTools", taskTools);
        ReflectionTestUtils.setField(controller, "agentToolPolicy", agentToolPolicy(taskTools));
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
    }

    @Test
    void readOnlyToolsDoNotRequireApproval() {
        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("listFiles", "{\"path\":\".\"}"),
                new ToolRequest("readFile", "{\"path\":\"README.md\"}"),
                new ToolRequest("getDateTime", "{}"),
                new ToolRequest("ragSearch", "{\"query\":\"Kecore AI\"}"),
                new ToolRequest("task", "{\"prompt\":\"inspect files\",\"role\":\"explorer\"}"),
                new ToolRequest("calculateExpression", "{\"expression\":\"2 + 2\"}"),
                new ToolRequest("calculateBigNumber", "{\"operation\":\"add\",\"leftOperand\":\"1\",\"rightOperand\":\"2\"}")
        ))).isFalse();
    }

    @Test
    void mutatingOrUnknownToolsRequireApproval() {
        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("listFiles", "{\"path\":\".\"}"),
                new ToolRequest("executeShellCommand", "{\"command\":\"del file.zip\"}")
        ))).isTrue();

        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("unregisteredTool", "{}")
        ))).isTrue();

        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("task", "{\"prompt\":\"change files\",\"role\":\"implementer\"}")
        ))).isTrue();
    }

    @Test
    void autoApprovalModeDoesNotRequireApprovalForAnyTool() {
        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("executeShellCommand", "{\"command\":\"del file.zip\"}"),
                new ToolRequest("unregisteredTool", "{}"),
                new ToolRequest("task", "{\"prompt\":\"change files\",\"role\":\"implementer\"}")
        ), AgentController.ApprovalMode.AUTO)).isFalse();
    }

    @Test
    void workAutoAllowsWorkspaceScopedMutations() {
        AgentSandboxContext sandbox = AgentSandboxContext.of(true, Path.of(System.getProperty("java.io.tmpdir")).toString());

        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("writeFile", "{\"path\":\"src/main/App.java\",\"content\":\"ok\"}"),
                new ToolRequest("executeShellCommand", "{\"command\":\"mvn test\"}")
        ), AgentController.ApprovalMode.WORK_AUTO, sandbox)).isFalse();
    }

    @Test
    void workAutoRequiresApprovalForCrossWorkspaceOperations() {
        AgentSandboxContext sandbox = AgentSandboxContext.of(true, Path.of(System.getProperty("java.io.tmpdir")).toString());

        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("writeFile", "{\"path\":\"D:\\\\outside\\\\App.java\",\"content\":\"ok\"}")
        ), AgentController.ApprovalMode.WORK_AUTO, sandbox)).isTrue();

        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("executeShellCommand", "{\"command\":\"Set-Location D:\\\\outside\"}")
        ), AgentController.ApprovalMode.WORK_AUTO, sandbox)).isTrue();
    }

    @Test
    void invalidBackgroundWriteTaskDoesNotRequireApprovalBecauseToolRejectsIt() {
        assertThat(controller.requiresApproval(List.of(
                new ToolRequest("task",
                        "{\"prompt\":\"change files\",\"role\":\"implementer\",\"background\":true}")
        ))).isFalse();
    }

    @Test
    void askUserChoiceInteractionIncludesOtherOptionForCustomInput() {
        InteractionRequest interaction = ReflectionTestUtils.invokeMethod(
                controller,
                "buildInteractionRequest",
                "run-1",
                List.of(new ToolRequest("askUser",
                        "{\"question\":\"你说的磊落不凡是在什么语境下提到的？\",\"options\":[\"小米产品系列名称\",\"网络红人或UP主的网名\"]}"))
        );

        assertThat(interaction.type()).isEqualTo("choice");
        assertThat(interaction.allowCustomInput()).isTrue();
        assertThat(interaction.options()).containsExactly(
                "小米产品系列名称",
                "网络红人或UP主的网名",
                "其他"
        );
    }

    @Test
    void askUserWithoutOptionsFallsBackToTextInput() {
        InteractionRequest interaction = ReflectionTestUtils.invokeMethod(
                controller,
                "buildInteractionRequest",
                "run-2",
                List.of(new ToolRequest("askUser",
                        "{\"question\":\"请补充一个简短说明\"}"))
        );

        assertThat(interaction.type()).isEqualTo("text");
        assertThat(interaction.allowCustomInput()).isFalse();
        assertThat(interaction.options()).isEmpty();
    }

    @Test
    void ragSearchStopsAfterConsecutiveSearchBudgetIsUsed() {
        List<ExecutedToolBatch> executions = List.of(
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"a\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"b\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"c\"}", "result")
                ))
        );

        assertThat(controller.exceedsConsecutiveRagSearchLimit(
                List.of(new ToolRequest("ragSearch", "{\"query\":\"d\"}")),
                executions
        )).isTrue();

        assertThat(controller.exceedsConsecutiveRagSearchLimit(
                List.of(new ToolRequest("listFiles", "{\"path\":\".\"}")),
                executions
        )).isFalse();
    }

    @Test
    void nonRagSearchToolResetsConsecutiveSearchBudget() {
        List<ExecutedToolBatch> executions = List.of(
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"a\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"b\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("listFiles", "{\"path\":\".\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"c\"}", "result")
                )),
                new ExecutedToolBatch(List.of(
                        new ExecutedTool("ragSearch", "{\"query\":\"d\"}", "result")
                ))
        );

        assertThat(controller.exceedsConsecutiveRagSearchLimit(
                List.of(new ToolRequest("ragSearch", "{\"query\":\"e\"}")),
                executions
        )).isFalse();
    }

    @Test
    void toolRoundLimitGrowsByHalfUntilHardLimit() {
        assertThat(controller.nextToolRoundLimit(16)).isEqualTo(24);
        assertThat(controller.nextToolRoundLimit(24)).isEqualTo(36);
        assertThat(controller.nextToolRoundLimit(36)).isEqualTo(54);
        assertThat(controller.nextToolRoundLimit(54)).isEqualTo(81);
        assertThat(controller.nextToolRoundLimit(81)).isEqualTo(122);
        assertThat(controller.nextToolRoundLimit(122)).isEqualTo(183);
        assertThat(controller.nextToolRoundLimit(183)).isEqualTo(256);
        assertThat(controller.nextToolRoundLimit(256)).isEqualTo(256);
    }

    @Test
    void ragSearchToolIsUnavailableWhenConversationHasNoKnowledgeDocuments() {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments("c1")).thenReturn(List.of());
        AgentController configuredController = configuredController(ragService);

        OpenAiChatOptions options = ReflectionTestUtils.invokeMethod(
                configuredController, "agentOptions", "D:\\workspace", "c1");

        assertThat(toolNames(options)).doesNotContain("ragSearch");
    }

    @Test
    void ragSearchToolIsAvailableWhenConversationHasKnowledgeDocuments() {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments("c1")).thenReturn(List.of(
                new RagService.KnowledgeDocumentSummary("doc-1", "manual.pdf", 12)
        ));
        AgentController configuredController = configuredController(ragService);

        OpenAiChatOptions options = ReflectionTestUtils.invokeMethod(
                configuredController, "agentOptions", "D:\\workspace", "c1");

        assertThat(toolNames(options)).contains("ragSearch");
    }

    @Test
    void taskToolIsAvailableToParentAgent() {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments("c1")).thenReturn(List.of());
        AgentController configuredController = configuredController(ragService);

        OpenAiChatOptions options = ReflectionTestUtils.invokeMethod(
                configuredController, "agentOptions", "D:\\workspace", "c1");

        assertThat(toolNames(options)).contains("task");
    }

    @Test
    void userMessageKeepsOriginalPromptText() {
        AgentChatRequest request = new AgentChatRequest();
        request.setPrompt("你好");

        UserMessage userMessage = ReflectionTestUtils.invokeMethod(
                controller,
                "buildUserMessage",
                request,
                "c1",
                "D:\\workspace");

        assertThat(userMessage.getText()).isEqualTo("你好");
    }

    @Test
    void userMessageWithFilesIncludesFileListAndUserInstruction() {
        AgentChatRequest request = new AgentChatRequest();
        request.setPrompt("看一下这张图");
        request.setFiles(List.of(new MockMultipartFile("files", "image.png", "image/png", new byte[] {1, 2, 3})));

        UserMessage userMessage = ReflectionTestUtils.invokeMethod(
                controller,
                "buildUserMessage",
                request,
                "c1",
                "D:\\workspace");

        assertThat(userMessage.getText())
                .contains("附件文件列表")
                .contains("image.png")
                .contains("请仔细查看附件中的图片内容，并回答用户的问题：看一下这张图")
                .doesNotContain("files")
                .doesNotContain("默认工作目录");
    }

    @Test
    void workingDirectoryIsInjectedAsSystemContext() {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments("c1")).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.loadPromptMemory("D:\\workspace")).thenReturn("");
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("c1")).thenReturn(List.of());
        AgentController configuredController = configuredController(ragService);
        ReflectionTestUtils.setField(configuredController, "memoryService", memoryService);
        ReflectionTestUtils.setField(configuredController, "chatMemory", chatMemory);

        List<Message> messages = ReflectionTestUtils.invokeMethod(
                configuredController,
                "buildCurrentContextMessages",
                "c1",
                "D:\\workspace",
                null);

        assertThat(messages.get(1).getText())
                .contains("当前运行上下文")
                .contains("默认工作目录：D:\\workspace");
    }

    @Test
    void lightCompactionWhitelistKeepsReadMemoryOutput() {
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("1", "read_memory", "memory body"),
                        new ToolResponseMessage.ToolResponse("2", "readFile", "file body")
                ))
                .build();

        ToolResponseMessage compacted = ReflectionTestUtils.invokeMethod(
                controller,
                "lightCompactMessageForModel",
                message,
                System.currentTimeMillis());

        assertThat(compacted.getResponses()).hasSize(2);
        assertThat(compacted.getResponses().get(0).responseData()).isEqualTo("memory body");
        assertThat(compacted.getResponses().get(1).responseData()).isEqualTo("file body");
    }

    @Test
    void lightCompactionReplacesOnlyWhenPlaceholderIsShorter() {
        String longOutput = "x".repeat(500);
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "readFile", longOutput)))
                .build();

        ToolResponseMessage compacted = ReflectionTestUtils.invokeMethod(
                controller,
                "lightCompactMessageForModel",
                message,
                System.currentTimeMillis());

        assertThat(compacted.getResponses().get(0).responseData())
                .contains(ConversationCompactionService.LIGHT_TOOL_OUTPUT_MARKER)
                .contains("Tool: readFile");
    }

    @Test
    void knowledgeContextMentionsUploadedFileNames() {
        RagService ragService = mock(RagService.class);
        when(ragService.listDocuments("c1")).thenReturn(List.of(
                new RagService.KnowledgeDocumentSummary("doc-1", "manual.pdf", 12),
                new RagService.KnowledgeDocumentSummary("doc-2", "faq.pdf", 3)
        ));
        AgentController configuredController = configuredController(ragService);

        String context = ReflectionTestUtils.invokeMethod(configuredController, "buildKnowledgeContext", "c1");

        assertThat(context)
                .contains("manual.pdf")
                .contains("faq.pdf")
                .contains("ragSearch");
    }

    private AgentController configuredController(RagService ragService) {
        AgentController configuredController = new AgentController();
        TaskTools taskTools = new TaskTools(mock(TaskAgentService.class), new ObjectMapper());
        ReflectionTestUtils.setField(configuredController, "taskTools", taskTools);
        ReflectionTestUtils.setField(configuredController, "ragService", ragService);
        McpToolRegistry mcpToolRegistry = mock(McpToolRegistry.class);
        when(mcpToolRegistry.toolCallbacks()).thenReturn(List.of());
        ReflectionTestUtils.setField(configuredController, "agentToolPolicy", agentToolPolicy(taskTools));
        ReflectionTestUtils.setField(configuredController, "agentToolOptionsFactory", new AgentToolOptionsFactory(
                new DateTimeTools(),
                new BasicTools(new ShellToolConfiguration.ShellToolProperties()),
                new CalculationTools(),
                new RagTools(ragService),
                taskTools,
                ragService,
                new MemoryTools(mock(MemoryService.class)),
                new UserInteractionTools(),
                new SkillsTools(new AgentSkillService()),
                mcpToolRegistry));
        return configuredController;
    }

    @SuppressWarnings("unchecked")
    private AgentToolPolicy agentToolPolicy(TaskTools taskTools) {
        ObjectProvider<TaskTools> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(taskTools);
        return new AgentToolPolicy(provider, new ObjectMapper());
    }

    private List<String> toolNames(OpenAiChatOptions options) {
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
