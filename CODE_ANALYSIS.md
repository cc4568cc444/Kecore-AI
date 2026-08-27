# Kecore AI 全项目代码解析

> 基于当前工作区源码生成。本文不是只列目录，而是先解释端到端调用流程，再按包/目录逐文件列出函数，最后给出前后端绑定和依赖索引。`target/`、编译后的 `static/vue/assets/*.js`、`package-lock.json` 属于构建产物或依赖锁文件，不把压缩产物中的匿名函数当作手写业务函数重复解析；它们与源文件的关系仍在“构建与部署”章节说明。

## 1. 推荐阅读方式与组织结论

本项目最适合采用“调用流程 + 按包逐文件”的混合组织，而不是二选一。

- **先读调用流程**：能快速理解 Vue 组件、Pinia、API、Controller、Service、数据库/向量库/大模型之间的边界。
- **再按包查文件**：Controller、Service、Tool、Agent DTO、Skill、前端 Store 等天然按职责分包，适合逐文件核对和维护。
- **前后端不要完全拆成两本书**：先分别讲清内部结构，但必须用接口绑定表把前端函数与后端方法重新接上，否则很难追踪一次用户操作。
- **Controller → Service 并非全部流程**：Agent 模式还会经过 Tool Policy、Tool Registry、MCP、ChatMemory、上下文压缩和 SSE；前端则通常是 Component → Pinia Store → `api.js` → Controller。

## 2. 技术栈与运行边界

- 后端：Java 17、Spring Boot 3.5.14、Spring AI 1.1.6、Spring MVC/SSE、JdbcTemplate、MySQL、PostgreSQL/pgvector。
- 模型：OpenAI 兼容 Chat Completions 接口；项目内放置了定制版 `OpenAiChatModel` 和 `OpenAiApi`。
- 前端：Vue 3、Pinia、Vite、KaTeX；生产构建输出到 Spring Boot 静态资源目录。
- RAG：普通会话 PDF 使用 pgvector 父子分块；金融 RAG 使用自建表、向量相似度、重排与查询规划。
- Agent：工具调用循环、审批模式、沙箱工作目录、子任务、Skill、Memory、MCP、上下文压缩。
- 辅助程序：Python Email MCP Server 与离线 Agent 评测脚本。

## 3. 总体架构

```text
浏览器 / Vue 组件
  └─ Pinia Store（session/chat/model/workspace/knowledge/migration）
       └─ services/api.js + services/config.js
            └─ Spring MVC Controller
                 ├─ SimpleChatService / FinancialRagService / RagService
                 ├─ AgentController（Agent 主循环与 SSE 编排）
                 │    ├─ ModelConfigService → OpenAiChatModel → OpenAI 兼容服务
                 │    ├─ AgentToolOptionsFactory → 本地 Tools / MCP Registry
                 │    ├─ AgentToolPolicy → 审批决策
                 │    ├─ TaskAgentService → 子 Agent
                 │    ├─ ChatMemory / ConversationCompactionService
                 │    └─ MemoryService / AgentSkillService / RagService
                 └─ JdbcTemplate / MySQL、VectorStore / PostgreSQL pgvector、文件系统
```

## 4. 主要端到端调用流程

### 4.1 普通聊天

`Composer.vue` 收集文本 → `chat` Store 的发送动作组装参数 → `api.streamChat` 请求 `/ai/chat/stream` → `ChatController` 建立 SSE → `SimpleChatService` 根据 `modelId` 和运行参数取得模型与选项 → ChatClient 使用 `ChatMemory` 补齐历史 → 模型增量输出 → 前端 `readSse` 持续更新 assistant 消息。停止/重试时，前端 AbortController 中止连接，后端 `ChatMemoryRewindService` 负责需要时回退历史。

### 4.2 Agent 对话与工具审批

`Composer.vue`/`chat` Store 构造 `FormData`（消息、会话、工作目录、模型、审批模式、附件）→ `api.streamAgentChat` → `AgentController.chatStream`。控制器解析 `AgentChatRequest`、注入工作目录/Memory/Skill/RAG 上下文，使用 `AgentToolOptionsFactory` 组装可用工具，经 `AgentToolPolicy` 判断工具是否可直接执行。若需要审批，SSE 返回待审批事件并缓存 run；前端弹出 `AgentInteractionModal`，再调用 `/agent/approve/stream`、`/agent/reject` 或 `/agent/interact/stream` 续跑。工具结果进入 ChatMemory，模型继续下一轮，直到正常回答、交互暂停或达到轮次/检索预算。

### 4.3 PDF 知识库

`KnowledgeModal.vue` → `knowledge` Store → `api.uploadKnowledgeFile` → `UploadController.upload` → `RagService.uploadPdf`。服务校验 PDF、读取页文档、切分父子块，把子块向量写入 pgvector，并把文档/父块元数据写入关系表。Agent 会话有文档时，`AgentToolOptionsFactory` 才启用 `RagTools`；工具调用 `RagService.search` 先召回子块，再回表加载父块，返回更完整上下文。

### 4.4 金融 RAG

`chat` Store 在 finance 模式调用 `api.streamFinanceChat` → `FinancialRagController.chatStream` → `FinancialRagService`。服务先由模型生成检索计划/改写问题，再做多查询召回、向量检索、去重与 rerank，拼装上下文后生成答案；索引和评测入口由 `RagEvaluationTest` 与资源目录中的数据集支撑。

### 4.5 会话、模型、工作区与迁移

`session` Store 与 `/api/sessions` 同步会话；`model` Store 与 `/api/models` 管理 OpenAI 兼容模型配置；`workspace` Store 聚合工作目录、Memory、Skill、Agent 上下文和任务进度；`migration` Store 在 localStorage 与服务端数据之间导入导出。删除会话时 `SessionController` 同时触发会话持久化删除、ChatMemory 清理和 RAG 文档清理。

## 5. 前后端接口绑定总表

| 前端 API 函数 | HTTP | 后端入口 | 主要下游 |
|---|---|---|---|
| `persistSessions` / `listSessions` / `deleteSession` | PUT/GET/DELETE `/api/sessions` | `SessionController` | `SessionPersistenceService`、`RagService`、ChatMemory |
| `listModels` / `saveModel` / `deleteModel` / `exportModels` / `importModels` | `/api/models/**` | `ModelConfigController` | `ModelConfigService` |
| `streamChat` | GET `/ai/chat/stream` | `ChatController` | `SimpleChatService` |
| `streamFinanceChat` | GET `/finance/chat/stream` | `FinancialRagController` | `FinancialRagService` |
| `streamAgentChat` | POST `/agent/chat/stream` | `AgentController` | Agent 主循环、模型、工具、Memory、Skill、RAG |
| `streamToolApproval` / `decideToolAction` | POST `/agent/approve/stream`、`/approve`、`/reject` | `AgentController` | 暂存 run 与工具执行续跑 |
| `submitAgentInteraction` | POST `/agent/interact/stream` | `AgentController` | `UserInteractionTools` 续跑 |
| `loadAgentContext*` / `compactAgentContext` / `lightCompactAgentContext` | `/agent/context/**` | `AgentController` | `ConversationCompactionService` |
| `taskProgress` / `taskGroups` | GET `/agent/tasks/**` | `AgentController` | `TaskAgentService` |
| `loadMemoryFiles` / `saveMemoryFile` / `createMemory` | `/api/memory` | `MemoryController` | `MemoryService`、文件系统 |
| `loadSkills` / `renderSkill` / `loadSkillFile` / `saveSkillFile` / `deleteSkill` | `/skills/**` | `SkillController` | `AgentSkillService` |
| `loadKnowledgeDocuments` / `uploadKnowledgeFile` / `deleteKnowledgeDocument` | `/rag/**` | `UploadController` | `RagService`、pgvector |
| `loadMcpServers` | GET `/api/mcp/servers` | `McpController` | Spring AI MCP Clients |
| `openLocalFile` | POST `/api/files/open` | `FileOpenController` | OS 文件打开命令 |

## 6. 后端逐包、逐文件、逐函数解析

### 后端 `根包` 包

#### `src/main/java/com/cc/springai/SpringaiApplication.java`

`SpringaiApplication` 对应的项目代码文件。 行数约 14。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 9 | `public static void main(String[] args)` | 应用或脚本入口；完成初始化并把控制权交给框架/主流程。 | 名称过于通用，需结合所属对象和调用链判断 |

### 后端 `constants` 包

#### `src/main/java/com/cc/springai/constants/SystemConstants.java`

`SystemConstants` 对应的项目代码文件。 行数约 119。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `private SystemConstants()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 后端 `config` 包

#### `src/main/java/com/cc/springai/config/CommonConfiguration.java`

Spring 配置：声明 Bean、属性或底层客户端装配规则。 行数约 89。

**直接依赖**：`com.cc.springai.embedding.OllamaLegacyEmbeddingModel`、`com.cc.springai.constants.SystemConstants`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 23 | `public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory)` | 处理 `client` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | `src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |
| 34 | `public ChatClient gameChatClient(OpenAiChatModel model, ChatMemory chatMemory)` | 处理 `chat client` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | `src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |
| 46 | `public ChatClient financeChatClient(OpenAiChatModel model, ChatMemory chatMemory)` | 处理 `chat client` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 62 | `public ChatClient agentClient(OpenAiChatModel model, ChatMemory chatMemory)` | 处理 `client` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 74 | `public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository)` | 处理 `memory` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 82 | `public EmbeddingModel embeddingModel( @Value("${app.embedding.ollama.base-url}") String baseUrl,` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/config/McpHttpClientConfiguration.java`

Spring 配置：声明 Bean、属性或底层客户端装配规则。 行数约 59。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 21 | `public McpSyncHttpClientRequestCustomizer mcpHttpHeaderCustomizer(McpHttpHeaderProperties properties)` | 处理 `http header customizer` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 31 | `public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator()` | 处理 `tool name prefix generator` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 36 | `public McpSyncClientCustomizer mcpClientInfoCustomizer()` | 处理 `client info customizer` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Bean` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 45 | `public Map<String, Map<String, String>> getHeaders()` | 读取 `headers` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2 |
| 49 | `public void setHeaders(Map<String, Map<String, String>> headers)` | 设置 `headers` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/config/ShellToolConfiguration.java`

Spring 配置：声明 Bean、属性或底层客户端装配规则。 行数约 26。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 17 | `public Duration getCommandTimeout()` | 读取 `command timeout` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 21 | `public void setCommandTimeout(Duration commandTimeout)` | 设置 `command timeout` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 后端 `controller` 包

#### `src/main/java/com/cc/springai/controller/AgentController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 2130。

**直接依赖**：`com.cc.springai.agent.AgentApprovalModeRequest`、`com.cc.springai.agent.AgentApprovalRequest`、`com.cc.springai.agent.AgentAutoCompactionUsage`、`com.cc.springai.agent.AgentChatRequest`、`com.cc.springai.agent.AgentContextRequest`、`com.cc.springai.agent.AgentContextPreview`、`com.cc.springai.agent.AgentContextUsage`、`com.cc.springai.agent.AgentInteractionResponse`、`com.cc.springai.agent.AgentLightCompactionResult`、`com.cc.springai.agent.AgentReply`、`com.cc.springai.agent.AgentSandboxContext`、`com.cc.springai.agent.AgentToolOptionsFactory`、`com.cc.springai.agent.AgentToolPolicy`、`com.cc.springai.agent.ExecutedTool`、`com.cc.springai.agent.ExecutedToolBatch`、`com.cc.springai.agent.InteractionRequest`、`com.cc.springai.agent.ModelRuntimeOptions`、`com.cc.springai.agent.TokenUsage`、`com.cc.springai.agent.ToolRequest`、`com.cc.springai.constants.SystemConstants`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 143 | `public AgentContextUsage contextUsage(@RequestParam String conversationId, @RequestParam String workingDirectory, @RequestParam(required = false) Long lightCompactedBefore, @Reque…` | 处理 `usage` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/context")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 153 | `public AgentContextPreview contextPreview(@RequestParam String conversationId, @RequestParam String workingDirectory, @RequestParam(required = false) Long lightCompactedBefore, @R…` | 处理 `preview` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/context/preview")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 184 | `public AgentContextUsage compactContext(@RequestBody AgentContextRequest request)` | 压缩 `context` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/context/compact")` | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2 |
| 195 | `public AgentLightCompactionResult lightCompactContext(@RequestBody AgentContextRequest request)` | 处理 `compact context` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/context/light-compact")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 204 | `public List<TaskAgentService.TaskProgressSnapshot> taskProgress(@RequestParam String conversationId)` | 处理 `progress` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/tasks/progress")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 209 | `public List<TaskAgentService.TaskGroupSnapshot> taskGroups(@RequestParam String conversationId)` | 处理 `groups` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/tasks/groups")` | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 214 | `public Map<String, String> updateApprovalMode(@RequestBody AgentApprovalModeRequest request)` | 更新 `approval mode` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/approval-mode")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 231 | `public AgentReply chat(@ModelAttribute AgentChatRequest request)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/chat")` | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 258 | `public SseEmitter chatStream(@ModelAttribute AgentChatRequest request)` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `src/main/java/com/cc/springai/controller/ChatController.java` |
| 302 | `private UserMessage buildUserMessage(AgentChatRequest request, String conversationId, String workingDirectory)` | 构造 `user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 337 | `private String displayFileName(MultipartFile file)` | 处理 `file name` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 345 | `private void rewindLatestTurnIfRegenerating(AgentChatRequest request, String conversationId)` | 回退 `latest turn if regenerating` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 351 | `private List<Message> buildInitialMessages(String conversationId, String workingDirectory, UserMessage userMessage, Long lightCompactedBefore)` | 构造 `initial messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 359 | `private List<Message> buildCurrentContextMessages(String conversationId, String workingDirectory)` | 构造 `current context messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 363 | `private List<Message> buildCurrentContextMessages(String conversationId, String workingDirectory, Long lightCompactedBefore)` | 构造 `current context messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 390 | `private List<Message> buildDirectSkillMessages(String workingDirectory, String userText)` | 构造 `direct skill messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 401 | `private SystemMessage directSkillMessage(RenderedSkill renderedSkill)` | 处理 `skill message` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 410 | `private List<Message> lightCompactMessagesForModel(List<Message> messages, Long lightCompactedBefore)` | 处理 `compact messages for model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 419 | `private Message lightCompactMessageForModel(Message message, long lightCompactedBefore)` | 处理 `compact message for model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 442 | `private boolean shouldLightCompactToolResponse(ToolResponseMessage message, long lightCompactedBefore)` | 判断是否应当 `light compact tool response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 450 | `private String extractUserPrompt(String userText)` | 处理 `user prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 466 | `private String buildKnowledgeContext(String conversationId)` | 构造 `knowledge context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 485 | `private AgentContextUsage contextUsageResponse(String conversationId, String workingDirectory, Long lightCompactedBefore, String modelId, boolean compacted)` | 处理 `usage response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 531 | `private AgentContextPreview.ContextMessage contextMessage(int index, Message message)` | 处理 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 552 | `private List<AgentContextPreview.ToolCallPreview> toolCallPreviews(Message message)` | 处理 `call previews` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 567 | `private List<AgentContextPreview.ToolResponsePreview> toolResponsePreviews(Message message)` | 处理 `response previews` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 581 | `private List<AgentContextPreview.ToolDefinitionPreview> toolPreviews(String workingDirectory, String conversationId, List<Message> parentMessages, AgentSandboxContext sandboxConte…` | 处理 `previews` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 599 | `private StreamContext newStreamContext(long startedAtMs)` | 新建 `stream context` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 619 | `private void startHeartbeat(StreamContext stream)` | 启动 `heartbeat` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 635 | `private boolean sendEvent(StreamContext stream, String eventName, Object data)` | 发送 `event` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×23、`src/main/java/com/cc/springai/controller/ChatController.java`×3、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×3 |
| 657 | `private boolean sendActivity(StreamContext stream, List<String> activities, List<ExecutedToolBatch> executions)` | 发送 `activity` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 663 | `private boolean sendLoopProgress(StreamContext stream, int round, int toolRoundLimit, String status)` | 发送 `loop progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×13 |
| 678 | `private Object timingPayload(StreamContext stream, String eventName, Object data)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 705 | `private long elapsedMs(long startedAtMs)` | 处理 `ms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×3、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 709 | `private void completeStream(StreamContext stream)` | 完成 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×17 |
| 718 | `private void processAgentLoopStream(StreamContext stream, Prompt prompt, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, Appro…` | 处理 `agent loop stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 732 | `private void processAgentLoopAfterStream(StreamContext stream, Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCoun…` | 处理 `agent loop after stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 834 | `public AgentReply approve(@RequestBody AgentApprovalRequest request)` | 批准 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/approve")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 860 | `public SseEmitter approveStream(@RequestBody AgentApprovalRequest request)` | 批准 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@PostMapping(value = "/approve/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 920 | `public AgentReply reject(@RequestBody AgentApprovalRequest request)` | 拒绝 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/reject")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 956 | `public AgentReply interact(@RequestBody AgentInteractionResponse request)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/interact")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 992 | `public SseEmitter interactStream(@RequestBody AgentInteractionResponse request)` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@PostMapping(value = "/interact/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1054 | `private void handleLoopLimitInteractionStream(StreamContext stream, PendingRun pending, String value, ApprovalMode approvalMode) throws Exception` | 处理 `loop limit interaction stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1077 | `private void continueAgentLoopStream(StreamContext stream, Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, i…` | 处理 `agent loop stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1086 | `private void continueAgentLoopStream(StreamContext stream, Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, i…` | 处理 `agent loop stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1187 | `private AgentReply continueAgentLoop(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, App…` | 处理 `agent loop` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 1196 | `private AgentReply continueAgentLoop(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, App…` | 处理 `agent loop` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 1259 | `private AgentReply handleToolRoundLimit(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, …` | 处理 `tool round limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 1268 | `private AgentReply handleToolRoundLimit(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, …` | 处理 `tool round limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 1291 | `private AgentReply handleLoopLimitInteraction(PendingRun pending, String value, ApprovalMode approvalMode)` | 处理 `loop limit interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1308 | `private InteractionRequest buildLoopLimitInteraction(String runId, int round, int currentLimit, int nextLimit)` | 构造 `loop limit interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1321 | `int nextToolRoundLimit(int currentLimit)` | 处理 `tool round limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×8 |
| 1326 | `private List<ToolRequest> toolRequests(ChatResponse response)` | 处理 `requests` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×8 |
| 1332 | `private boolean requiresUserInteraction(List<ToolRequest> tools)` | 处理 `user interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 1336 | `private InteractionRequest buildInteractionRequest(String runId, List<ToolRequest> tools)` | 构造 `interaction request` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1369 | `private Map<String, Object> parseArguments(String arguments)` | 解析 `arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/tools/McpToolCallback.java`、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 1381 | `private String asText(Object value, String fallback)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4、`src/main/java/com/cc/springai/service/SessionPersistenceService.java`×2、`src/main/java/com/cc/springai/tools/McpToolCallback.java`×2 |
| 1385 | `private List<String> asStringList(Object value)` | 处理 `string list` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1404 | `private List<String> appendCustomOption(List<String> options)` | 处理 `custom option` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1416 | `private String userInteractionOutput(List<ToolRequest> tools, String value)` | 处理 `interaction output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1425 | `private void persistToolExecution(String conversationId, ChatResponse response, ToolExecutionResult result)` | 持久化 `tool execution` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 1434 | `private List<Message> persistAndReturnToolConversation(String conversationId, List<Message> messages)` | 持久化 `and return tool conversation` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1439 | `private void persistToolConversationDelta(String conversationId, List<Message> messages)` | 持久化 `tool conversation delta` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1466 | `private void persistToolExchange(String conversationId, AssistantMessage assistantMessage, ToolResponseMessage toolResponseMessage)` | 持久化 `tool exchange` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1479 | `private ToolResponseMessage toolResponseWithRecordedAt(ToolResponseMessage message)` | 处理 `response with recorded at` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1488 | `private ToolResponseMessage lastToolResponseMessage(List<Message> messages)` | 处理 `tool response message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1500 | `private List<Message> conversationWithManualToolResponse(Prompt prompt, ChatResponse response, String output)` | 处理 `with manual tool response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1512 | `private List<Message> conversationWithRejectedToolResponse(Prompt prompt, ChatResponse response, String output)` | 处理 `with rejected tool response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1529 | `private String rejectedToolOutput(List<ToolRequest> tools)` | 处理 `tool output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1537 | `private ExecutedToolBatch rejectedToolBatch(List<ToolRequest> tools, String output)` | 处理 `tool batch` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1543 | `private String toolNames(List<ToolRequest> tools)` | 处理 `names` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3 |
| 1552 | `private Prompt promptWithTaskNotifications(List<Message> messages, String conversationId, String workingDirectory, List<String> activities)` | 处理 `with task notifications` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×9 |
| 1559 | `private Prompt promptWithTaskNotifications(List<Message> messages, String conversationId, String workingDirectory, AgentSandboxContext sandboxContext, List<String> activities)` | 处理 `with task notifications` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×9 |
| 1573 | `private List<Message> compactToolResponsesForModel(List<Message> messages)` | 压缩 `tool responses for model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1582 | `private Message compactToolResponseForModel(Message message)` | 压缩 `tool response for model` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1598 | `private String truncateToolResponseForModel(String value)` | 处理 `tool response for model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1606 | `private ToolExecutionResult executeToolCallsWithTaskProgress(StreamContext stream, Prompt prompt, ChatResponse response)` | 执行 `tool calls with task progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1616 | `private ExecutedToolBatch executedUserInteractionBatch(List<ToolRequest> tools, String output)` | 处理 `user interaction batch` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1623 | `private ExecutedToolBatch executedBatch(List<ToolRequest> tools, ToolExecutionResult result)` | 处理 `batch` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 1640 | `private ExecutedTool eventTool(String name, String arguments, String output)` | 处理 `tool` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1648 | `private String truncateForEvent(String value, int maxChars)` | 处理 `for event` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1656 | `boolean requiresApproval(List<ToolRequest> tools)` | 处理 `approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×9 |
| 1660 | `boolean requiresApproval(List<ToolRequest> tools, ApprovalMode approvalMode)` | 处理 `approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×9 |
| 1664 | `boolean requiresApproval(List<ToolRequest> tools, ApprovalMode approvalMode, AgentSandboxContext sandboxContext)` | 处理 `approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×9 |
| 1672 | `private ApprovalMode currentApprovalMode(String conversationId, ApprovalMode fallback)` | 处理 `approval mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 1677 | `private ApprovalMode approvalModeFromRequest(String value, ApprovalMode fallback)` | 处理 `mode from request` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 1684 | `private AgentSandboxContext currentSandboxContext(String conversationId, String workingDirectory, ApprovalMode approvalMode)` | 处理 `sandbox context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 1690 | `private AgentSandboxContext sandboxContext(String workingDirectory, Boolean sandboxEnabled, ApprovalMode approvalMode)` | 处理 `context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×17、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 1697 | `boolean exceedsConsecutiveRagSearchLimit(List<ToolRequest> tools, List<ExecutedToolBatch> executions)` | 处理 `consecutive rag search limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3 |
| 1701 | `private AgentReply finalAnswerFromExistingResults(String conversationId, List<String> activities, List<ExecutedToolBatch> executions, ChatResponse response)` | 处理 `answer from existing results` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1710 | `private AgentReply finalAnswerFromExistingResults(Prompt prompt, String conversationId, List<String> activities, List<ExecutedToolBatch> executions)` | 处理 `answer from existing results` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1723 | `private AgentReply streamFinalAnswerFromExistingResults(StreamContext stream, Prompt prompt, String conversationId, List<String> activities, List<ExecutedToolBatch> executions) th…` | 流式处理 `final answer from existing results` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1738 | `private AgentReply streamFinalAnswer(StreamContext stream, Prompt prompt, String conversationId, List<String> activities, List<ExecutedToolBatch> executions, String fallbackConten…` | 流式处理 `final answer` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1752 | `private AgentReply completeStreamedAnswer(String conversationId, List<String> activities, List<ExecutedToolBatch> executions, ChatResponse response)` | 完成 `streamed answer` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1760 | `private ChatResponse streamChatResponse(StreamContext stream, Prompt prompt)` | 流式处理 `chat response` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 1811 | `private AssistantMessage assistantMessage(String content, String reasoning)` | 处理 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1821 | `private String reasoningText(ChatResponse response)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1828 | `private String reasoningText(AssistantMessage output)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1839 | `private ChatResponse callModel(Prompt prompt)` | 调用 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 1857 | `private long promptTextLength(Prompt prompt)` | 处理 `text length` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1865 | `private long promptToolResponseCount(Prompt prompt)` | 处理 `tool response count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1873 | `private TokenUsage tokenUsage(ChatResponse response)` | 处理 `usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×12 |
| 1881 | `private void rememberActualPromptUsage(TokenUsage tokenUsage)` | 处理 `actual prompt usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1897 | `private ActualPromptUsage matchingActualPromptUsage(String conversationId, Long lightCompactedBefore, String modelName)` | 处理 `actual prompt usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1916 | `private void mergeToolCalls(Map<String, ToolCallAccumulator> accumulators, List<AssistantMessage.ToolCall> toolCalls)` | 合并 `tool calls` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1925 | `? keyByPosition(accumulators, index, positionalKey) : toolCall.id();` | 处理 `by position` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1939 | `private String keyByPosition(Map<String, ToolCallAccumulator> accumulators, int index, String fallback)` | 处理 `by position` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1946 | `private String responseText(ChatResponse response)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 1954 | `private ModelRuntimeOptions runtimeOptions(AgentChatRequest request)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×9 |
| 1958 | `private ModelRuntimeOptions runtimeOptions(AgentApprovalRequest request)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×9 |
| 1962 | `private ModelRuntimeOptions runtimeOptions(AgentInteractionResponse request)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×9 |
| 1966 | `private OpenAiChatOptions activeModelOptions()` | 处理 `model options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 1972 | `private String resolvedModelName(String modelId)` | 处理 `model name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1982 | `private ChatModel selectedChatModel(String modelId)` | 处理 `chat model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 1990 | `private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 1996 | `private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId, List<Message> parentMessages)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 2001 | `private OpenAiChatOptions agentOptions(String workingDirectory, String conversationId, List<Message> parentMessages, AgentSandboxContext sandboxContext)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 2004 | `sandboxContext, activeModelOptions());` | 处理 `model options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 2007 | `private List<RagService.KnowledgeDocumentSummary> knowledgeDocuments(String conversationId)` | 处理 `documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 2015 | `private PendingRun takePendingRun(String runId)` | 处理 `pending run` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 2023 | `private String normalizeConversationId(String conversationId)` | 规范化 `conversation id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×13、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×2 |
| 2029 | `private String requireWorkingDirectory(String workingDirectory)` | 校验并返回 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 2042 | `private void merge(AssistantMessage.ToolCall toolCall)` | 合并 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java`×3、`src/main/java/com/cc/springai/controller/AgentController.java` |
| 2049 | `private AssistantMessage.ToolCall toToolCall()` | 转换 `tool call` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2053 | `private static String mergeSegment(String current, String incoming)` | 合并 `segment` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 2073 | `private synchronized void replace(TokenUsage next)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/components/layout/Sidebar.vue`、`src/main/frontend/src/stores/migration.js`、`src/main/frontend/src/utils/agent.js`×5、`src/main/frontend/src/utils/markdown.js`×18、`src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 2079 | `private synchronized TokenUsage snapshot()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 2084 | `private record StreamContext(SseEmitter emitter, AtomicBoolean open, long startedAtMs, TokenUsageAccumulator tokenUsage)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 2088 | `private record ActualPromptUsage(long promptTokens, String modelName, Long lightCompactedBefore, long recordedAtMs)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 2092 | `private record PendingRun(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, long startedAt…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 2096 | `private PendingRun(Prompt prompt, ChatResponse response, String conversationId, String workingDirectory, int round, int interactionCount, int toolRoundLimit, ApprovalMode approval…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7 |
| 2116 | `static ApprovalMode from(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 2125 | `String value()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×7、`src/main/java/com/cc/springai/service/SimpleChatService.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java` |

#### `src/main/java/com/cc/springai/controller/ChatController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 109。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`、`com.cc.springai.service.ChatMemoryRewindService`、`com.cc.springai.service.SimpleChatService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 25 | `public ChatController(SimpleChatService simpleChatService, ChatMemoryRewindService chatMemoryRewindService, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 34 | `public Flux<String> chat(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId, @RequestParam(default…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@RequestMapping(value = "/chat", produces = "text/plain; charset=utf-8")` | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 45 | `public SseEmitter chatStream(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId, @RequestParam(req…` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `src/main/java/com/cc/springai/controller/ChatController.java` |
| 59 | `private SseEmitter stream(Flux<SimpleChatService.ModelStreamEvent> events)` | 流式处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×10、`src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×31、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×10 |
| 80 | `private boolean sendEvent(SseEmitter emitter, AtomicBoolean open, String eventName, Object data)` | 发送 `event` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×23、`src/main/java/com/cc/springai/controller/ChatController.java`×3、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×3 |
| 100 | `private void complete(SseEmitter emitter, AtomicBoolean open)` | 完成 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/ChatController.java`×4、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×4 |

#### `src/main/java/com/cc/springai/controller/FileOpenController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 86。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `public FileOpenResponse openFile(@RequestBody FileOpenRequest request)` | 打开 `file` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping("/open")` | `src/main/java/com/cc/springai/tools/BasicTools.java`、`src/test/java/com/cc/springai/controller/FileOpenControllerTest.java` |
| 35 | `private Path resolveFile(String path)` | 解析并确定 `file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FileOpenController.java` |
| 53 | `private void openWithDefaultApplication(Path target) throws Exception` | 打开 `with default application` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FileOpenController.java` |
| 73 | `List<String> buildWindowsOpenCommand(Path target)` | 构造 `windows open command` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FileOpenController.java` |
| 80 | `public record FileOpenRequest(String path)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/FileOpenControllerTest.java` |
| 83 | `public record FileOpenResponse(boolean success, String message, String path)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/FileOpenController.java`×4 |

#### `src/main/java/com/cc/springai/controller/FinancialRagController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 150。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`、`com.cc.springai.service.ChatMemoryRewindService`、`com.cc.springai.service.FinancialRagService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 26 | `public FinancialRagController(FinancialRagService financialRagService, ChatMemoryRewindService chatMemoryRewindService, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 35 | `public Flux<String> chat(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId, @RequestParam(require…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@GetMapping(value = "/chat", produces = "text/plain; charset=utf-8")` | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 49 | `public SseEmitter chatStream(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId, @RequestParam(req…` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `src/main/java/com/cc/springai/controller/ChatController.java` |
| 113 | `private boolean sendEvent(SseEmitter emitter, AtomicBoolean open, String eventName, Object data)` | 发送 `event` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×23、`src/main/java/com/cc/springai/controller/ChatController.java`×3、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×3 |
| 133 | `private void complete(SseEmitter emitter, AtomicBoolean open)` | 完成 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/ChatController.java`×4、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×4 |
| 142 | `private long elapsedMs(long startedAtMs)` | 处理 `ms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×3、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 146 | `private String normalizeConversationId(String conversationId)` | 规范化 `conversation id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×13、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×2 |

#### `src/main/java/com/cc/springai/controller/GameController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 93。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`、`com.cc.springai.service.SimpleChatService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 23 | `public GameController(SimpleChatService simpleChatService, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 29 | `public Flux<String> chat(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@GetMapping(value = "/chat", produces = "text/plain; charset=utf-8")` | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 36 | `public SseEmitter chatStream(@RequestParam String prompt, @RequestParam(defaultValue = "default") String conv_id, @RequestParam(required = false) String modelId, @RequestParam(req…` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` | `src/main/java/com/cc/springai/controller/ChatController.java` |
| 64 | `private boolean sendEvent(SseEmitter emitter, AtomicBoolean open, String eventName, Object data)` | 发送 `event` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`×23、`src/main/java/com/cc/springai/controller/ChatController.java`×3、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×3 |
| 84 | `private void complete(SseEmitter emitter, AtomicBoolean open)` | 完成 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/ChatController.java`×4、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×4、`src/main/java/com/cc/springai/controller/GameController.java`×4 |

#### `src/main/java/com/cc/springai/controller/McpController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 252。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 25 | `public McpController(ObjectProvider<List<McpSyncClient>> syncClientsProvider, Environment environment)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 32 | `public List<McpServerStatus> servers()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/servers")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 57 | `private McpServerStatus toStatus(McpSyncClient client, McpSchema.Implementation serverInfo, McpSchema.Implementation clientInfo, List<McpToolInfo> tools, ConfiguredMcpConnection c…` | 转换 `status` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 83 | `private McpServerStatus configuredOnlyStatus(ConfiguredMcpConnection configured, int index)` | 处理 `only status` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 101 | `private boolean safeInitialized(McpSyncClient client)` | 处理 `initialized` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 109 | `private McpSchema.Implementation safeServerInfo(McpSyncClient client)` | 处理 `server info` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 117 | `private McpSchema.Implementation safeClientInfo(McpSyncClient client)` | 处理 `client info` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 125 | `private List<McpToolInfo> safeTools(McpSyncClient client)` | 处理 `tools` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 139 | `private List<ConfiguredMcpConnection> configuredConnections()` | 处理 `connections` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 146 | `private List<ConfiguredMcpConnection> configuredHttpConnections(String prefix, String type)` | 处理 `http connections` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 158 | `private List<ConfiguredMcpConnection> configuredStdioConnections(String prefix, String type)` | 处理 `stdio connections` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 166 | `private Map<String, Object> bindConnectionMap(String prefix)` | 处理 `connection map` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 173 | `private Map<String, Object> connectionConfig(Object value)` | 处理 `config` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@SuppressWarnings("unchecked")` | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 177 | `private ConfiguredMcpConnection matchConfiguredConnection(McpSchema.Implementation clientInfo, List<ConfiguredMcpConnection> candidates)` | 处理 `configured connection` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 196 | `private ConfiguredMcpConnection firstMatching(List<ConfiguredMcpConnection> candidates, java.util.function.Predicate<ConfiguredMcpConnection> predicate)` | 处理 `matching` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 206 | `private int configuredOrder(String configuredName, List<ConfiguredMcpConnection> connections)` | 处理 `order` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 218 | `private String firstText(String primary, String fallback)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 222 | `private String text(Object value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`、`src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×2、`src/main/java/com/cc/springai/service/RagService.java`、`src/main/java/com/cc/springai/tools/RagTools.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`×3 |
| 226 | `private String normalize(String value)` | 规范化 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`×7、`src/main/java/com/cc/springai/controller/FileOpenController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×5、`src/main/java/com/cc/springai/service/MemoryService.java`×10、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×10、`src/main/java/com/cc/springai/tools/BasicTools.java`×5、`src/test/java/com/cc/springai/controller/FileOpenControllerTest.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`×2 |
| 230 | `public record McpServerStatus( String name, String configuredName, String serverName, String title, String version, String clientName, boolean initialized, String loadState, Strin…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |
| 246 | `public record McpToolInfo(String name, String title, String description)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/McpController.java` |
| 249 | `private record ConfiguredMcpConnection(String name, String type, String target)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/McpController.java`×2 |

#### `src/main/java/com/cc/springai/controller/MemoryController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 57。

**直接依赖**：`com.cc.springai.service.MemoryService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 20 | `public MemoryController(MemoryService memoryService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 25 | `public List<MemoryService.MemoryFile> listMemoryFiles(@RequestParam String workingDirectory)` | 列出 `memory files` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping` | `src/main/java/com/cc/springai/controller/MemoryController.java` |
| 30 | `public MemoryService.MemoryFile saveMemoryFile(@RequestBody MemoryUpdateRequest request)` | 保存 `memory file` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PutMapping` | `src/main/frontend/src/stores/workspace.js` |
| 35 | `public MemorySaveResponse saveMemory(@RequestBody MemoryCreateRequest request)` | 保存 `memory` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping` | `src/main/java/com/cc/springai/controller/MemoryController.java`、`src/main/java/com/cc/springai/tools/MemoryTools.java`、`src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java`×3 |
| 47 | `public record MemoryUpdateRequest(String workingDirectory, String fileId, String content)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 50 | `public record MemoryCreateRequest(String workingDirectory, String name, String description, String type, String opportunity, String content)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 54 | `public record MemorySaveResponse(String message)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/MemoryController.java` |

#### `src/main/java/com/cc/springai/controller/ModelConfigController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 69。

**直接依赖**：`com.cc.springai.service.ModelConfigService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 21 | `public ModelConfigController(ModelConfigService modelConfigService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 26 | `public List<ModelConfigService.ModelConfigView> listModels()` | 列出 `models` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping` | `src/main/frontend/src/stores/model.js` |
| 31 | `public List<ModelConfigService.ModelConfigExportView> exportModels()` | 导出 `models` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/export")` | `src/main/frontend/src/stores/migration.js` |
| 36 | `public List<ModelConfigService.ModelConfigView> importModels( @RequestBody List<ModelConfigService.ModelConfigRequest> requests)` | 导入 `models` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PutMapping("/import")` | `src/main/frontend/src/stores/migration.js` |
| 42 | `public ModelConfigService.ModelConfigView createModel(@RequestBody ModelConfigService.ModelConfigRequest request)` | 创建 `model` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostMapping` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 47 | `public ModelConfigService.ModelConfigView updateModel(@PathVariable String modelId, @RequestBody ModelConfigService.ModelConfigRequest request)` | 更新 `model` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PutMapping("/{modelId}")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 65 | `public void deleteModel(@PathVariable String modelId)` | 删除 `model` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@DeleteMapping("/{modelId}")` | `src/main/frontend/src/stores/model.js` |

#### `src/main/java/com/cc/springai/controller/SessionController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 69。

**直接依赖**：`com.cc.springai.service.RagService`、`com.cc.springai.service.SessionPersistenceService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 31 | `public SessionController(SessionPersistenceService sessionPersistenceService, RagService ragService, ObjectMapper objectMapper, ChatMemory chatMemory)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 42 | `public List<JsonNode> listSessions() throws Exception` | 列出 `sessions` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping` | `src/main/frontend/src/stores/session.js` |
| 51 | `public Map<String, String> defaultWorkingDirectory()` | 处理 `working directory` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/default-working-directory")` | `src/main/frontend/src/stores/session.js` |
| 56 | `public void saveSessions(@RequestBody List<JsonNode> sessions) throws Exception` | 保存 `sessions` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PutMapping` | `src/main/frontend/src/components/chat/Composer.vue`×3、`src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2、`src/main/frontend/src/main.js`、`src/main/frontend/src/stores/chat.js`×9、`src/main/frontend/src/stores/session.js`×7、`src/main/frontend/src/stores/workspace.js`×2 |
| 63 | `public void deleteSession(@PathVariable String sessionId)` | 删除 `session` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@DeleteMapping("/{sessionId}")` | `src/main/frontend/src/components/layout/Sidebar.vue`、`src/main/frontend/src/stores/session.js`×2 |

#### `src/main/java/com/cc/springai/controller/SkillController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 63。

**直接依赖**：`com.cc.springai.skill.AgentSkillService`、`com.cc.springai.skill.AgentSkillFile`、`com.cc.springai.skill.AgentSkillSummary`、`com.cc.springai.skill.RenderedSkill`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 23 | `public SkillController(AgentSkillService skillService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 28 | `public List<AgentSkillSummary> list(@RequestParam String workingDirectory)` | 列出 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping` | `src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×2、`src/main/java/com/cc/springai/tools/BasicTools.java`、`src/main/resources/agent_test/evaluate_agent.py` |
| 33 | `public RenderedSkill render(@RequestParam String workingDirectory, @RequestParam String commandName, @RequestParam(required = false) String arguments)` | 渲染 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/render")` | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 41 | `public AgentSkillFile file(@RequestParam String workingDirectory, @RequestParam String commandName)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/file")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 47 | `public AgentSkillFile save(@RequestBody SkillFileRequest request)` | 保存 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PutMapping("/file")` | `src/main/java/com/cc/springai/controller/ModelConfigController.java`×2、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java` |
| 52 | `public DeleteSkillResponse delete(@RequestParam String workingDirectory, @RequestParam String commandName)` | 删除 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@DeleteMapping` | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/service/MemoryService.java`、`src/main/java/com/cc/springai/service/RagService.java`×2 |
| 57 | `public record SkillFileRequest(String workingDirectory, String commandName, String content)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 60 | `public record DeleteSkillResponse(boolean deleted)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/SkillController.java` |

#### `src/main/java/com/cc/springai/controller/UploadController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 40。

**直接依赖**：`com.cc.springai.service.RagService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `public UploadController(RagService ragService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 24 | `public RagService.UploadResult upload(@RequestParam("conversationId") String conversationId, @RequestParam("file") MultipartFile file) throws IOException` | 上传 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。；框架标记：`@PostMapping({"/upload", "/rag/upload"})` | `src/main/frontend/src/components/modals/KnowledgeModal.vue` |
| 30 | `public List<RagService.KnowledgeDocumentSummary> listDocuments(@RequestParam("conversationId") String conversationId)` | 列出 `documents` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping("/rag/documents")` | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/UploadController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×5 |
| 35 | `public void deleteDocument(@RequestParam("conversationId") String conversationId, @PathVariable String documentId)` | 删除 `document` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@DeleteMapping("/rag/documents/{documentId}")` | `src/main/frontend/src/components/modals/KnowledgeModal.vue`、`src/main/java/com/cc/springai/controller/UploadController.java` |

#### `src/main/java/com/cc/springai/controller/VueFrontendController.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 19。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 10 | `public String root()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping({"/", "/index.html"})` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 15 | `public String vueIndex()` | 处理 `index` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@GetMapping({"/vue", "/vue/"})` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 后端 `service` 包

#### `src/main/java/com/cc/springai/service/ChatMemoryRewindService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 44。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 16 | `public ChatMemoryRewindService(ChatMemory chatMemory)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 20 | `public void rewindLatestUserTurn(String conversationId)` | 回退 `latest user turn` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/ChatController.java`×2、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×2 |
| 35 | `private int lastUserMessageIndex(List<Message> history)` | 处理 `user message index` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ChatMemoryRewindService.java` |

#### `src/main/java/com/cc/springai/service/ConversationCompactionService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 689。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 52 | `public ConversationCompactionService(ChatMemory chatMemory, ChatModel chatModel)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×15 |
| 57 | `public boolean compactIfNeeded(String conversationId)` | 压缩 `if needed` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×4 |
| 61 | `public boolean compactIfNeeded(String conversationId, ChatModel compactionModel)` | 压缩 `if needed` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×4 |
| 65 | `public boolean compactNow(String conversationId)` | 压缩 `now` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×5 |
| 69 | `public boolean compactNow(String conversationId, ChatModel compactionModel)` | 压缩 `now` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×5 |
| 73 | `public LightCompactionResult estimateLightCompactToolResponses(String conversationId, Long lightCompactedBefore)` | 估算 `light compact tool responses` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×3 |
| 101 | `private boolean shouldLightCompactToolResponse(ToolResponseMessage message, long lightCompactedBefore)` | 判断是否应当 `light compact tool response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 109 | `public static boolean shouldSkipLightCompaction(String toolName, String responseData)` | 判断是否应当 `skip light compaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 120 | `public static String lightToolOutputPlaceholder(String toolName, String output)` | 处理 `tool output placeholder` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 127 | `public ContextUsage estimateUsage(List<Message> messages)` | 估算 `usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×3 |
| 131 | `public ContextUsage estimatePromptUsage(List<Message> messages, List<ToolCallback> toolCallbacks, String modelName)` | 估算 `prompt usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 152 | `public AutoCompactionProgress estimateAutoCompactionProgress(List<Message> messages)` | 估算 `auto compaction progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×2 |
| 176 | `private boolean compact(String conversationId, boolean force, ChatModel compactionModel)` | 压缩 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 213 | `boolean shouldCompact(List<Message> messages)` | 判断是否应当 `compact` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 227 | `private int recentMessageCount(int conversationMessageCount, boolean force)` | 处理 `message count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 237 | `private int includePendingToolResponses(List<Message> messages, int compactUntil)` | 处理 `pending tool responses` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 245 | `private boolean splitsToolExchange(List<Message> messages, int compactUntil)` | 处理 `tool exchange` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 257 | `private String summarize(String previousSummary, List<Message> messagesToCompact, ChatModel compactionModel)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 296 | `private String fallbackSummary(String previousSummary, List<Message> messagesToCompact)` | 处理 `summary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 320 | `private String mergeSummaries(List<Message> summaryMessages)` | 合并 `summaries` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 337 | `private boolean isSummaryMessage(Message message)` | 判断 `summary message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 343 | `private String formatMessages(List<Message> messages)` | 格式化 `messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 358 | `private String formatMessageBody(Message message)` | 格式化 `message body` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 391 | `private void appendSectionHeader(StringBuilder body, String header)` | 处理 `section header` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 398 | `private String roleOf(Message message)` | 处理 `of` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 411 | `private int totalTextLength(List<Message> messages)` | 处理 `text length` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 422 | `private int conversationMessageCount(List<Message> messages)` | 处理 `message count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 431 | `private long estimateTokens(List<Message> messages)` | 估算 `tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 442 | `private long estimateMessageTokens(List<Message> messages)` | 估算 `message tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 451 | `private long estimateMessageTokens(Message message)` | 估算 `message tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 484 | `private long estimateTextTokens(long chars)` | 估算 `text tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 491 | `private long estimatePromptTokens(List<Message> messages, List<ToolCallback> toolCallbacks, String modelName)` | 估算 `prompt tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 502 | `private long countTokens(String value, String modelName)` | 统计 `tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 510 | `private Encoding encodingFor(String modelName)` | 处理 `for` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 520 | `private Encoding defaultEncoding()` | 处理 `encoding` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 528 | `private String serializePromptForTokenCount(List<Message> messages, List<ToolCallback> toolCallbacks, String modelName) throws Exception` | 处理 `prompt for token count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 544 | `private Map<String, Object> messagePayload(Message message)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 572 | `private Map<String, Object> toolCallPayload(AssistantMessage.ToolCall toolCall)` | 处理 `call payload` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 583 | `private Map<String, Object> toolResponsePayload(ToolResponseMessage.ToolResponse response)` | 处理 `response payload` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 591 | `private List<Map<String, Object>> toolPayloads(List<ToolCallback> toolCallbacks)` | 处理 `payloads` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 602 | `private Map<String, Object> toolPayload(ToolDefinition definition)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 613 | `private Object schemaPayload(String inputSchema)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 624 | `private double percentage(long value, long threshold)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 631 | `public record ContextUsage(long usedTokens, long maxTokens, double percent, long usedChars, int messageCount, int toolCount, String tokenizerModel, String tokenSource)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 635 | `public record AutoCompactionProgress(double percent, double tokenPercent, double messagePercent, double textPercent, int messageCount, int messageThreshold, int messagesUntilAutoC…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 641 | `public record LightCompactionResult(int compactedToolResponses, long compactedChars)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 644 | `private String normalizeSummary(String summary)` | 规范化 `summary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2 |
| 652 | `private String stripMarker(String value)` | 处理 `marker` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×3 |
| 659 | `private String blankToNone(String value)` | 处理 `to none` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×8 |
| 663 | `private String oneLine(String value)` | 处理 `line` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 670 | `private String truncateMiddle(String value, int maxChars)` | 处理 `middle` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java` |
| 681 | `private String truncateEnd(String value, int maxChars)` | 处理 `end` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java` |

#### `src/main/java/com/cc/springai/service/FinancialRagService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 1170。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 113 | `public FinancialRagService(EmbeddingModel embeddingModel, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, @Qualifier("financeChatClient") ChatClient financeChatClient, ChatM…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/FinancialRagServiceTest.java` |
| 127 | `public Flux<String> chat(String prompt, String conversationId, String modelId)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 131 | `public Flux<String> chat(String prompt, String conversationId, String modelId, FinancialRetrievalMode retrievalMode)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 166 | `public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId)` | 处理 `chat` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 170 | `public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId, ModelRuntimeOptions runtimeOptions)` | 处理 `chat` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 175 | `public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId, ModelRuntimeOptions runtimeOptions, FinancialRetrievalMode retrievalMode)` | 处理 `chat` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 222 | `private FinancialRetrievalPlan planRetrieval(String prompt, String conversationId, String modelId)` | 处理 `retrieval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 272 | `private List<ModelStreamEvent> eventsFromResponse(ChatResponse response)` | 处理 `from response` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 293 | `private String reasoningText(AssistantMessage output)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 304 | `FinancialRetrievalPlan parseRetrievalPlan(String prompt, String planned)` | 解析 `retrieval plan` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/test/java/com/cc/springai/service/FinancialRagServiceTest.java`×2 |
| 339 | `private FinancialRetrievalPlan fallbackRetrievalPlan(String prompt)` | 处理 `retrieval plan` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |
| 351 | `private String recentConversationContext(String conversationId)` | 处理 `conversation context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 376 | `private String extractJsonObject(String value)` | 处理 `json object` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 386 | `private void addQuery(List<String> queries, String query)` | 处理 `query` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×16 |
| 393 | `private List<String> normalizeQueryCount(List<String> queries)` | 规范化 `query count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 410 | `private String expandQueryTerms(String query)` | 处理 `query terms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 433 | `private String inferIntent(String query)` | 处理 `intent` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 452 | `private String retrievalPlanPrompt(FinancialRetrievalPlan plan)` | 处理 `plan prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 462 | `private ChatClient financeChatClient(String modelId)` | 处理 `chat client` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 479 | `private long elapsedMs(long startedAtMs)` | 处理 `ms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×3、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 483 | `private List<FinancialChunk> search(FinancialRetrievalPlan plan, FinancialRetrievalMode retrievalMode)` | 检索 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/RagTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×10 |
| 508 | `private List<FinancialChunk> search(String query)` | 检索 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/RagTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×10 |
| 512 | `private List<FinancialChunk> search(String query, int resultLimit)` | 检索 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/RagTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×10 |
| 516 | `private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode)` | 检索 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/RagTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×10 |
| 548 | `private String resolveTableName()` | 解析并确定 `table name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 558 | `private boolean tableExists(String table)` | 处理 `exists` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 561 | `SELECT EXISTS ( SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ? ) """, Boolean.class, safe);` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 570 | `private RetrievalFilters inferFilters(String query, String table)` | 处理 `filters` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 584 | `private Optional<String> inferCompany(String query, String normalized, String table)` | 处理 `company` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 601 | `private Set<String> knownCompanies(String table)` | 处理 `companies` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 608 | `private Optional<String> inferYear(String query)` | 处理 `year` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 616 | `private boolean sourceFileExists(String table, String sourceFile)` | 处理 `file exists` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 625 | `private List<FinancialChunk> fetchVectorCandidates(String table, String vector, RetrievalFilters filters, int limit)` | 处理 `vector candidates` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 644 | `private List<FinancialChunk> fetchMetadataCandidates(String table, RetrievalFilters filters)` | 处理 `metadata candidates` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 658 | `private SqlWhere whereClause(RetrievalFilters filters)` | 处理 `clause` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 676 | `private FinancialChunk mapChunk(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException` | 映射 `chunk` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 688 | `private List<FinancialChunk> rankVectorRows(List<FinancialChunk> rows, int limit)` | 处理 `vector rows` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 702 | `private List<FinancialChunk> rankBm25Rows(List<FinancialChunk> rows, String query, int limit)` | 处理 `bm25 rows` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 737 | `private List<FinancialChunk> hybridRrf(List<FinancialChunk> vectorRows, List<FinancialChunk> bm25Rows, int limit)` | 处理 `rrf` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 768 | `private List<FinancialChunk> rerank(String query, List<FinancialChunk> rows, int finalTopK, FinancialRetrievalMode retrievalMode)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 819 | `private String rerankDocumentText(FinancialChunk chunk, FinancialRetrievalMode retrievalMode)` | 处理 `document text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 828 | `private String retrievedContext(List<FinancialChunk> chunks, FinancialRetrievalMode retrievalMode)` | 处理 `context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 842 | `private String retrievalContent(FinancialChunk chunk, FinancialRetrievalMode retrievalMode)` | 处理 `content` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 856 | `private List<FinancialChunk> collapseParentChildRows(List<FinancialChunk> rows, int limit)` | 处理 `parent child rows` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 872 | `private String metadataPrefix(FinancialChunk chunk)` | 处理 `prefix` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 889 | `private String parentCollapseKey(FinancialChunk chunk)` | 处理 `collapse key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 901 | `private void addMetadata(List<String> parts, String key, FinancialChunk chunk)` | 处理 `metadata` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×6 |
| 908 | `private String searchableText(FinancialChunk chunk)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 921 | `private void addListMetadata(List<String> values, Object value)` | 处理 `list metadata` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×3 |
| 927 | `private List<String> tokenize(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 939 | `private double bm25Score(List<String> queryTerms, List<String> documentTerms, Map<String, Integer> documentFrequency, int documentCount, double averageLength)` | 处理 `score` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 965 | `private double keywordBoost(FinancialChunk chunk, String query, List<String> queryTerms)` | 处理 `boost` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 987 | `private Map<String, Object> readMetadata(String value)` | 读取 `metadata` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/RagService.java` |
| 999 | `private String vectorLiteral(float[] values)` | 处理 `literal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1010 | `private String safeTableName(String table)` | 处理 `table name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×8 |
| 1018 | `private String trimTrailingSlash(String value)` | 处理 `trailing slash` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×4 |
| 1022 | `private String cleanLine(String value)` | 处理 `line` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×6 |
| 1026 | `private String truncateEnd(String value, int maxChars)` | 处理 `end` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ConversationCompactionService.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1033 | `private record RetrievalFilters(String sourceFile, String company, String year)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 1034 | `boolean isEmpty()` | 判断 `empty` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java`×6、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×22、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×17、`src/main/java/com/cc/springai/service/FinancialRagService.java`×16、`src/main/java/com/cc/springai/service/MemoryService.java`×2、`src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 1038 | `boolean hasCompanyOrYear()` | 判断是否存在 `company or year` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1042 | `RetrievalFilters withoutSourceFile()` | 处理 `source file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1047 | `private record SqlWhere(String sql, List<Object> params)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1050 | `record FinancialRetrievalPlan(String translatedQuestion, String resolvedQuestion, String intent, List<String> queries)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 1054 | `String displayQuery()` | 处理 `query` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/test/java/com/cc/springai/service/FinancialRagServiceTest.java` |
| 1059 | `public record ModelStreamEvent(String type, String content)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |
| 1062 | `public record FinancialAnswerStream(Flux<ModelStreamEvent> content, long translationMs, long retrievalMs, boolean modelRequested, String retrievalQuery)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |
| 1070 | `public static FinancialRetrievalMode fromConfig(String value)` | 处理 `config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5 |
| 1074 | `public static FinancialRetrievalMode fromValue(String value)` | 处理 `value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/FinancialRagController.java`×2、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 1101 | `private FinancialChunk(String chunkId, String sourceFile, String chunkType, String content, Map<String, Object> metadata, double score)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2 |
| 1115 | `private FinancialChunk copy()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/BasicTools.java` |
| 1127 | `private String chunkId()` | 处理 `id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×9 |
| 1131 | `private String sourceFile()` | 处理 `file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×3 |
| 1135 | `private String chunkType()` | 处理 `type` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |
| 1139 | `private String content()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×2、`src/main/java/com/cc/springai/controller/GameController.java`、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×5 |
| 1143 | `private Map<String, Object> metadata()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×3、`src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4、`src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×5、`src/main/java/com/cc/springai/service/RagService.java`×9、`src/main/java/com/cc/springai/tools/RagTools.java`、`src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java` |
| 1147 | `private double score()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2、`src/main/java/com/cc/springai/tools/RagTools.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 1151 | `private String metadataText(String key)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×7 |
| 1156 | `private double finalScore()` | 处理 `score` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5 |

#### `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 192。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 25 | `public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java` |
| 31 | `void initSchema()` | 处理 `schema` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostConstruct` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 34 | `conversation_id VARCHAR(128) NOT NULL, message_index INTEGER NOT NULL, message_type VARCHAR(32) NOT NULL, content TEXT NOT NULL, PRIMARY KEY (conversation_id, message_index) ) """…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`、`src/main/java/com/cc/springai/service/RagService.java`×3、`src/main/java/com/cc/springai/service/SessionPersistenceService.java` |
| 45 | `public List<String> findConversationIds()` | 查找 `conversation ids` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 53 | `public List<Message> findByConversationId(String conversationId)` | 查找 `by conversation id` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 68 | `public void saveAll(String conversationId, List<Message> messages)` | 保存 `all` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 80 | `public void deleteByConversationId(String conversationId)` | 删除 `by conversation id` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 84 | `private void addPayloadColumnIfMissing()` | 处理 `payload column if missing` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 92 | `private String safeText(Message message)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×4 |
| 97 | `private String toPayload(Message message)` | 转换 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 116 | `private MessagePayload assistantPayload(AssistantMessage message)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 126 | `private MessagePayload toolPayload(ToolResponseMessage message)` | 处理 `payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 136 | `private Message toMessage(String messageType, String content, String payload)` | 转换 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×2 |
| 147 | `private Message toMessage(MessagePayload payload, String legacyContent)` | 转换 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×2 |
| 173 | `private Message legacyMessage(String messageType, String content)` | 处理 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java` |
| 185 | `private record MessagePayload(String type, String content, Map<String, Object> metadata, List<AssistantMessage.ToolCall> toolCalls, List<ToolResponseMessage.ToolResponse> toolResp…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×3 |

#### `src/main/java/com/cc/springai/service/MemoryService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 436。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 34 | `public MemoryService()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/MemoryServiceTest.java`、`src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java` |
| 38 | `MemoryService(Path applicationRoot)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/MemoryServiceTest.java`、`src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java` |
| 42 | `public String loadPromptMemory(String workingDirectory)` | 加载 `prompt memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`、`src/test/java/com/cc/springai/service/MemoryServiceTest.java` |
| 83 | `public List<MemoryFile> listMemoryFiles(String workingDirectory)` | 列出 `memory files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/MemoryController.java` |
| 95 | `private List<MemoryFile> listRuleFiles(String workingDirectory)` | 列出 `rule files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 104 | `public MemoryFile writeMemoryFile(String workingDirectory, String fileId, String content)` | 写入 `memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/MemoryController.java` |
| 124 | `public String saveMemory(String workingDirectory, String name, String description, String type, String opportunity, String content)` | 保存 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/MemoryController.java`、`src/main/java/com/cc/springai/tools/MemoryTools.java`、`src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java`×3 |
| 167 | `public String readMemory(String workingDirectory, String name, String type)` | 读取 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/MemoryTools.java`×2 |
| 183 | `public String forgetMemory(String workingDirectory, String name, String type)` | 处理 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/MemoryTools.java` |
| 209 | `private List<MemoryFile> listStoredMemoryFiles(Path root)` | 列出 `stored memory files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 231 | `private String readMemoryIndexForPrompt(Path indexPath) throws IOException` | 读取 `memory index for prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 248 | `private void updateMemoryIndex(Path indexPath, String name, String type, String savedAt, String opportunity, String description) throws IOException` | 更新 `memory index` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 278 | `private int removeMemoryIndexEntry(Path indexPath, String name, String type) throws IOException` | 移除 `memory index entry` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 294 | `private Path memoryPath(Path root, String type, String name)` | 处理 `path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3 |
| 298 | `private Path rootForMemoryFile(String workingDirectory, String fileId)` | 处理 `for memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 305 | `private MemoryFile readMemoryFile(Path root, String id, String label, String relativePath)` | 读取 `memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×9 |
| 317 | `private Path resolveWorkingDirectory(String workingDirectory)` | 解析并确定 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×4、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 331 | `private Path resolveGlobalMemoryRoot()` | 解析并确定 `global memory root` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×6 |
| 338 | `private void ensureInsideRoot(Path root, Path target)` | 处理 `inside root` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×9 |
| 344 | `private String relativePathFor(String fileId)` | 处理 `path for` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 366 | `private String labelFor(String fileId)` | 处理 `for` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 381 | `private String normalizeType(String type)` | 规范化 `type` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3 |
| 390 | `private String normalizeName(String name)` | 规范化 `name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3 |
| 401 | `private String normalizeDescription(String description)` | 规范化 `description` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 408 | `private String normalizeOpportunity(String opportunity)` | 规范化 `opportunity` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 415 | `private String normalizeContent(String content)` | 规范化 `content` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 422 | `private String nowAsMinute()` | 处理 `as minute` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 426 | `private String truncate(String value, int maxChars)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java` |
| 433 | `public record MemoryFile(String id, String label, String relativePath, String content, boolean exists)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/MemoryService.java`×2 |

#### `src/main/java/com/cc/springai/service/ModelConfigService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 584。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 63 | `public ModelConfigService(JdbcTemplate jdbcTemplate, OpenAiChatModel defaultModel, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 70 | `void initSchema()` | 处理 `schema` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostConstruct` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 73 | `id VARCHAR(128) PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL, base_url TEXT NOT NULL, completions_path TEXT NOT NULL, api_key TEXT, model TEXT NOT NULL, temperature DOU…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`、`src/main/java/com/cc/springai/service/RagService.java`×3、`src/main/java/com/cc/springai/service/SessionPersistenceService.java` |
| 95 | `public List<ModelConfigView> list()` | 列出 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×2、`src/main/java/com/cc/springai/tools/BasicTools.java`、`src/main/resources/agent_test/evaluate_agent.py` |
| 121 | `public List<ModelConfigExportView> exportAll()` | 导出 `all` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java` |
| 149 | `public List<ModelConfigView> importAll(List<ModelConfigRequest> requests)` | 导入 `all` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java` |
| 159 | `public ModelConfigView save(ModelConfigRequest request)` | 保存 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`×2、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java` |
| 193 | `ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, provider = EXCLUDED.provider, base_url = EXCLUDED.base_url, completions_path = EXCLUDED.completions_path, api_key = EXCLUDED.a…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 215 | `public void delete(String id)` | 删除 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/service/MemoryService.java`、`src/main/java/com/cc/springai/service/RagService.java`×2 |
| 224 | `public OpenAiChatModel chatModel(String modelId)` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/service/FinancialRagService.java`×2、`src/main/java/com/cc/springai/service/SimpleChatService.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 229 | `public OpenAiChatOptions chatOptions(String modelId)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2、`src/main/java/com/cc/springai/service/SimpleChatService.java` |
| 233 | `public OpenAiChatOptions chatOptions(String modelId, ModelRuntimeOptions runtimeOptions)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2、`src/main/java/com/cc/springai/service/SimpleChatService.java` |
| 252 | `public ResolvedModelConfig resolve(String modelId)` | 解析并确定 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/service/MemoryService.java`×10、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×21、`src/main/java/com/cc/springai/tools/BasicTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`×3、`src/main/resources/mcp/email/email_mcp_server.py` |
| 262 | `private OpenAiChatModel buildModel(ResolvedModelConfig config)` | 构造 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 274 | `private ResolvedModelConfig findFirstEnabled()` | 查找 `first enabled` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 287 | `private ResolvedModelConfig find(String id)` | 查找 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue`、`src/main/frontend/src/components/layout/Sidebar.vue`、`src/main/frontend/src/stores/chat.js`×2、`src/main/frontend/src/stores/knowledge.js`、`src/main/frontend/src/stores/model.js`×2、`src/main/frontend/src/stores/session.js`×5、`src/main/frontend/src/stores/workspace.js`×4、`src/main/frontend/src/utils/agent.js` |
| 301 | `private ResolvedModelConfig mapConfig(java.sql.ResultSet rs) throws java.sql.SQLException` | 映射 `config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×3 |
| 319 | `private void ensureDefaultConfigs()` | 处理 `default configs` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 353 | `private void ensureDefaultConfig(ResolvedModelConfig config)` | 处理 `default config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 378 | `private ResolvedModelConfig defaultConfig()` | 处理 `config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 382 | `model2ApiKey, defaultText(model2Name, "gpt-5.5"), model2Temperature, null, null, null, true, now, now);` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×8 |
| 386 | `private ModelConfigView toView(ResolvedModelConfig config)` | 转换 `view` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 393 | `private void addColumnIfMissing(String name, String type)` | 处理 `column if missing` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×3 |
| 400 | `private String normalizeId(String value)` | 规范化 `id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×3 |
| 404 | `private String required(String value, String field)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×3 |
| 411 | `private String defaultText(String value, String fallback)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×8 |
| 415 | `private boolean hasText(String value)` | 判断是否存在 `text` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×5、`org/springframework/ai/openai/OpenAiChatModel.java`×4、`src/main/java/com/cc/springai/service/ModelConfigService.java`×13 |
| 419 | `private String trimTrailingSlash(String value)` | 处理 `trailing slash` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×4 |
| 427 | `private String normalizeCompletionsPath(String value)` | 规范化 `completions path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×4 |
| 432 | `private String normalizeReasoningEffort(String value)` | 规范化 `reasoning effort` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 443 | `private String normalizeThinkingType(String value)` | 规范化 `thinking type` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 454 | `private String normalizeExtraBody(String value)` | 规范化 `extra body` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 466 | `private Map<String, Object> mergedExtraBody(ResolvedModelConfig config, ModelRuntimeOptions runtimeOptions)` | 处理 `extra body` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 482 | `private void putAllExtraBody(Map<String, Object> target, String json)` | 处理 `all extra body` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 493 | `private String maskApiKey(String value)` | 处理 `api key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 504 | `public record ModelConfigRequest( String id, String name, String provider, String baseUrl, String completionsPath, String apiKey, String model, Double temperature, String reasonin…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java` |
| 519 | `public record ModelConfigView( String id, String name, String provider, String baseUrl, String completionsPath, String maskedApiKey, boolean hasApiKey, String model, Double temper…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 537 | `public record ModelConfigExportView( String id, String name, String provider, String baseUrl, String completionsPath, String apiKey, String model, Double temperature, String reaso…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 554 | `public record ResolvedModelConfig( String id, String name, String provider, String baseUrl, String completionsPath, String apiKey, String model, Double temperature, String reasoni…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/ModelConfigService.java`×5 |
| 570 | `String cacheKey()` | 处理 `key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |

#### `src/main/java/com/cc/springai/service/RagService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 411。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 39 | `public RagService(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 46 | `void initSchema()` | 处理 `schema` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostConstruct` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 49 | `conversation_id VARCHAR(128) NOT NULL, document_id VARCHAR(128) NOT NULL, file_name TEXT NOT NULL, chunk_count INTEGER NOT NULL, chunk_ids TEXT NOT NULL, created_at BIGINT NOT NUL…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`、`src/main/java/com/cc/springai/service/RagService.java`×3、`src/main/java/com/cc/springai/service/SessionPersistenceService.java` |
| 60 | `conversation_id VARCHAR(128) NOT NULL, document_id VARCHAR(128) NOT NULL, parent_chunk_id VARCHAR(128) NOT NULL, parent_index INTEGER NOT NULL, text TEXT NOT NULL, metadata TEXT N…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`、`src/main/java/com/cc/springai/service/RagService.java`×3、`src/main/java/com/cc/springai/service/SessionPersistenceService.java` |
| 72 | `ON rag_parent_chunks (conversation_id, parent_chunk_id) """);` | 处理 `parent chunks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java`×2 |
| 76 | `public UploadResult uploadPdf(String conversationId, MultipartFile file) throws IOException` | 上传 `pdf` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。 | `src/main/java/com/cc/springai/controller/UploadController.java` |
| 113 | `private ParentChildChunks splitParentChildChunks(List<Document> documents)` | 处理 `parent child chunks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 153 | `private String validatePdf(MultipartFile file)` | 校验 `pdf` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 170 | `private void rememberDocument(String conversationId, String documentId, String fileName, List<Document> splitDocuments)` | 处理 `document` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 178 | `ON CONFLICT (conversation_id, document_id) DO UPDATE SET file_name = EXCLUDED.file_name, chunk_count = EXCLUDED.chunk_count, chunk_ids = EXCLUDED.chunk_ids, created_at = EXCLUDED.…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 186 | `private void rememberParentChunks(String conversationId, String documentId, List<ParentChunk> parentChunks)` | 处理 `parent chunks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 193 | `ON CONFLICT (conversation_id, document_id, parent_chunk_id) DO UPDATE SET parent_index = EXCLUDED.parent_index, text = EXCLUDED.text, metadata = EXCLUDED.metadata, created_at = EX…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 209 | `public List<KnowledgeDocumentSummary> listDocuments(String conversationId)` | 列出 `documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/UploadController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×5 |
| 228 | `public void deleteDocument(String conversationId, String documentId)` | 删除 `document` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/KnowledgeModal.vue`、`src/main/java/com/cc/springai/controller/UploadController.java` |
| 261 | `public void deleteConversation(String conversationId)` | 删除 `conversation` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/SessionController.java` |
| 280 | `public List<RagChunk> search(String query, String conversationId, int topK)` | 检索 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/tools/RagTools.java`×2、`src/main/resources/agent_test/evaluate_agent.py`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×10 |
| 309 | `private List<Document> searchChildChunks(String query, String conversationId, int topK)` | 检索 `child chunks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 320 | `private List<Document> searchLegacyChunks(String query, String conversationId, int topK)` | 检索 `legacy chunks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 330 | `private RagChunk loadParentChunk(String conversationId, String parentChunkId, Document childDocument)` | 加载 `parent chunk` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 350 | `private String asString(Object value)` | 处理 `string` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 354 | `private String escapeFilterValue(String value)` | 处理 `filter value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java`×2 |
| 358 | `private String writeChunkIds(List<String> chunkIds)` | 写入 `chunk ids` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 366 | `private String writeMetadata(Map<String, Object> metadata)` | 写入 `metadata` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 374 | `private Map<String, Object> readMetadata(String value)` | 读取 `metadata` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/RagService.java` |
| 383 | `private List<String> readChunkIds(String value)` | 读取 `chunk ids` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/RagService.java`×2 |
| 392 | `public record UploadResult(String documentId, String fileName, int pageDocumentCount, int chunkCount)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 395 | `public record KnowledgeDocumentSummary(String documentId, String fileName, int chunkCount)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/RagService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3 |
| 398 | `public record RagChunk( String text, Double score, Map<String, Object> metadata )` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/RagService.java`×3、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×3 |
| 405 | `private record ParentChildChunks(List<ParentChunk> parentChunks, List<Document> childChunks)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/RagService.java` |
| 408 | `private record ParentChunk(String id, int index, String text, Map<String, Object> metadata)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/RagService.java` |

#### `src/main/java/com/cc/springai/service/SessionPersistenceService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 68。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 15 | `public SessionPersistenceService(JdbcTemplate jdbcTemplate)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 20 | `void initSchema()` | 处理 `schema` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PostConstruct` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 23 | `id VARCHAR(128) PRIMARY KEY, mode VARCHAR(32) NOT NULL, title TEXT NOT NULL, updated_at BIGINT NOT NULL, payload TEXT NOT NULL ) """);` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`、`src/main/java/com/cc/springai/service/RagService.java`×3、`src/main/java/com/cc/springai/service/SessionPersistenceService.java` |
| 32 | `public List<String> listPayloads()` | 列出 `payloads` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/SessionController.java` |
| 39 | `public void save(JsonNode session, String payload)` | 保存 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`×2、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java` |
| 48 | `ON CONFLICT (id) DO UPDATE SET mode = EXCLUDED.mode, title = EXCLUDED.title, updated_at = EXCLUDED.updated_at, payload = EXCLUDED.payload """, id, mode, title, updatedAt, payload);` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 56 | `public void delete(String sessionId)` | 删除 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/service/MemoryService.java`、`src/main/java/com/cc/springai/service/RagService.java`×2 |
| 60 | `private String requiredText(JsonNode node, String field)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/SessionPersistenceService.java`×2 |

#### `src/main/java/com/cc/springai/service/SimpleChatService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 115。

**直接依赖**：`com.cc.springai.agent.ModelRuntimeOptions`、`com.cc.springai.constants.SystemConstants`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 22 | `public SimpleChatService(ChatMemory chatMemory, ModelConfigService modelConfigService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 27 | `public Flux<String> chat(String prompt, String conversationId, String modelId)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 31 | `public Flux<String> game(String prompt, String conversationId, String modelId)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/GameController.java` |
| 35 | `public Flux<ModelStreamEvent> chatStream(String prompt, String conversationId, String modelId, ModelRuntimeOptions runtimeOptions)` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/ChatController.java` |
| 40 | `public Flux<ModelStreamEvent> gameStream(String prompt, String conversationId, String modelId, ModelRuntimeOptions runtimeOptions)` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/java/com/cc/springai/controller/GameController.java` |
| 45 | `private Flux<ModelStreamEvent> stream(ChatClient client, String prompt, String conversationId, String modelId, ModelRuntimeOptions runtimeOptions)` | 流式处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×10、`src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×31、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×10 |
| 57 | `private java.util.List<ModelStreamEvent> eventsFromResponse(ChatResponse response)` | 处理 `from response` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 78 | `private String reasoningContent(AssistantMessage output)` | 处理 `content` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/service/SimpleChatService.java` |
| 86 | `private ChatClient chatClient(String modelId)` | 处理 `client` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |
| 96 | `private ChatClient gameChatClient(String modelId)` | 处理 `chat client` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |
| 106 | `private IllegalStateException modelRequestException(WebClientResponseException e)` | 处理 `request exception` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 112 | `public record ModelStreamEvent(String type, String content)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×5、`src/main/java/com/cc/springai/service/SimpleChatService.java`×2 |

#### `src/main/java/com/cc/springai/service/TaskAgentService.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 830。

**直接依赖**：`com.cc.springai.agent.AgentSandboxContext`、`com.cc.springai.tools.BasicTools`、`com.cc.springai.tools.CalculationTools`、`com.cc.springai.tools.DateTimeTools`、`com.cc.springai.tools.RagTools`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 85 | `public TaskAgentService(OpenAiChatModel model, ModelConfigService modelConfigService, ToolCallingManager toolCallingManager, DateTimeTools dateTimeTools, BasicTools basicTools, Ca…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@Autowired` | `src/test/java/com/cc/springai/service/TaskAgentServiceTest.java` |
| 103 | `public TaskAgentService(OpenAiChatModel model, ToolCallingManager toolCallingManager, DateTimeTools dateTimeTools, BasicTools basicTools, CalculationTools calculationTools, RagToo…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/TaskAgentServiceTest.java` |
| 113 | `public String run(TaskRequest request)` | 运行 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 名称过于通用，需结合所属对象和调用链判断 |
| 164 | `public List<SystemMessage> drainCompletedTaskMessages(String conversationId)` | 处理 `completed task messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 194 | `public List<TaskGroupSnapshot> taskGroups(String conversationId)` | 处理 `groups` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 205 | `public List<TaskProgressSnapshot> progressSnapshots(String conversationId)` | 处理 `snapshots` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 216 | `public boolean isAutoApprovable(String role, boolean background)` | 判断 `auto approvable` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/tools/TaskTools.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3、`src/test/java/com/cc/springai/service/TaskAgentServiceTest.java`×7 |
| 224 | `public <T> T withProgressSink(Consumer<TaskProgressEvent> sink, Supplier<T> action)` | 处理 `progress sink` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 242 | `int effectiveMaxRounds(TaskRole role, Integer requestedMaxRounds)` | 处理 `max rounds` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/service/TaskAgentServiceTest.java`×7 |
| 247 | `private String runChildAgent(TaskExecution execution)` | 运行 `child agent` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 326 | `private ChatResponse callModel(Prompt prompt, String modelId)` | 调用 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 339 | `private String summarizeToolEvidenceAtRoundLimit(TaskExecution execution, List<ExecutedToolSummary> tools)` | 处理 `tool evidence at round limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 357 | `private List<Message> childInitialMessages(TaskExecution execution)` | 处理 `initial messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 378 | `private String childSystemPrompt(TaskExecution execution)` | 处理 `system prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 402 | `private String formatParentContext(List<Message> parentMessages)` | 格式化 `parent context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 421 | `private OpenAiChatOptions childOptions(TaskExecution execution)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 433 | `private List<ToolCallback> childToolCallbacks(TaskRole role, String conversationId)` | 处理 `tool callbacks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 444 | `private boolean hasKnowledgeDocuments(String conversationId)` | 判断是否存在 `knowledge documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 452 | `private List<ExecutedToolSummary> executedToolSummaries(ToolExecutionResult result)` | 处理 `tool summaries` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 464 | `private String formatToolSummaries(List<ExecutedToolSummary> tools)` | 格式化 `tool summaries` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×3 |
| 474 | `private String preview(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 482 | `private void emitProgress(TaskExecution execution, String status, int round, String message, List<String> tools, String detail)` | 处理 `progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×6 |
| 510 | `private void rememberProgress(TaskProgressEvent event)` | 处理 `progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 550 | `private TaskProgressSnapshot withGroupState(TaskProgressSnapshot snapshot)` | 处理 `group state` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 558 | `private void registerBackgroundTask(TaskExecution execution)` | 处理 `background task` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 577 | `private void completeBackgroundTask(TaskExecution execution, boolean failed)` | 完成 `background task` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 588 | `private boolean isNotificationReady(BackgroundTaskNotification notification, String conversationId)` | 判断 `notification ready` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 597 | `private void markGroupResumed(String taskGroupId)` | 处理 `group resumed` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 607 | `private String safeParentRunId(String parentRunId, String conversationId)` | 处理 `parent run id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 616 | `void shutdown()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@PreDestroy` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 620 | `public record TaskRequest(String prompt, String role, String mode, boolean background, Integer maxRounds, String conversationId, String workingDirectory, AgentSandboxContext sandb…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/tools/TaskTools.java` |
| 633 | `public record TaskProgressEvent(String taskId, String conversationId, String prompt, String role, String mode, boolean background, String parentRunId, String taskGroupId, int maxR…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 649 | `public record TaskProgressSnapshot(String taskId, String conversationId, String prompt, String role, String mode, boolean background, String parentRunId, String taskGroupId, Strin…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 679 | `TaskRole(boolean canMutate)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 683 | `public boolean canMutate()` | 判断能否 `mutate` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 687 | `static TaskRole from(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 703 | `static TaskMode from(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 715 | `private record RolePolicy(int maxRounds, Set<String> toolNames)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×5 |
| 718 | `private record TaskExecution(TaskRequest request, TaskRole role, TaskMode mode, int maxRounds, String taskId, String taskGroupId, Consumer<TaskProgressEvent> progressSink)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 720 | `private String parentRunId()` | 处理 `run id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×7 |
| 725 | `private record ExecutedToolSummary(String name, String outputPreview)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 728 | `private record BackgroundTaskNotification(String taskId, String taskGroupId, String role, String summary)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 731 | `public record TaskGroupSnapshot(String taskGroupId, String conversationId, String parentRunId, String status, int totalCount, int completedCount, int failedCount, boolean readyToR…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 741 | `static TaskGroupSnapshot empty(String parentRunId, String taskGroupId)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×8 |
| 755 | `private TaskGroupState(String taskGroupId, String conversationId, String parentRunId)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 761 | `private void registerTask(String taskId)` | 处理 `task` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 767 | `private void completeTask(String taskId, boolean failed)` | 完成 `task` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 777 | `private void markResumed()` | 处理 `resumed` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 781 | `private boolean isReadyToResume()` | 判断 `ready to resume` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 785 | `private TaskGroupSnapshot snapshot()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 804 | `private TaskProgressSnapshot enrich(TaskProgressSnapshot snapshot)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java` |

### 后端 `agent` 包

#### `src/main/java/com/cc/springai/agent/AgentApprovalModeRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 6。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentApprovalModeRequest(String conversationId, String approvalMode, Boolean sandboxEnabled, String workingDirectory)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/agent/AgentApprovalRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 10。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentApprovalRequest(String runId, String approvalMode, String modelId, String reasoningEffort, String thinkingType, String extraBody)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/agent/AgentAutoCompactionUsage.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 15。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentAutoCompactionUsage(double percent, double tokenPercent, double messagePercent, double textPercent, int messageCount, int messageThreshold, int messagesUntilAut…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |

#### `src/main/java/com/cc/springai/agent/AgentChatRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 117。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 21 | `public String prompt()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×9、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/SimpleChatService.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`×6 |
| 25 | `public void setPrompt(String prompt)` | 设置 `prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue`、`src/main/frontend/src/stores/workspace.js`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×2 |
| 29 | `public String conversationId()` | 处理 `id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×35、`src/main/java/com/cc/springai/service/TaskAgentService.java`×12 |
| 33 | `public void setConversationId(String conversationId)` | 设置 `conversation id` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 37 | `public String workingDirectory()` | 处理 `directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×19、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4、`src/main/java/com/cc/springai/tools/MemoryTools.java`×3、`src/main/java/com/cc/springai/tools/SkillsTools.java`×3 |
| 41 | `public void setWorkingDirectory(String workingDirectory)` | 设置 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 45 | `public String approvalMode()` | 处理 `mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×13 |
| 49 | `public void setApprovalMode(String approvalMode)` | 设置 `approval mode` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 53 | `public Boolean sandboxEnabled()` | 处理 `enabled` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 57 | `public void setSandboxEnabled(Boolean sandboxEnabled)` | 设置 `sandbox enabled` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 61 | `public Long lightCompactedBefore()` | 处理 `compacted before` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×8 |
| 65 | `public void setLightCompactedBefore(Long lightCompactedBefore)` | 设置 `light compacted before` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 69 | `public String modelId()` | 处理 `id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×13、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2 |
| 73 | `public void setModelId(String modelId)` | 设置 `model id` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 77 | `public String reasoningEffort()` | 处理 `effort` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×8 |
| 81 | `public void setReasoningEffort(String reasoningEffort)` | 设置 `reasoning effort` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 85 | `public String thinkingType()` | 处理 `type` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×7 |
| 89 | `public void setThinkingType(String thinkingType)` | 设置 `thinking type` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 93 | `public String extraBody()` | 处理 `body` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×7 |
| 97 | `public void setExtraBody(String extraBody)` | 设置 `extra body` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 101 | `public Boolean regenerate()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 105 | `public void setRegenerate(Boolean regenerate)` | 设置 `regenerate` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 109 | `public List<MultipartFile> files()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 113 | `public void setFiles(List<MultipartFile> files)` | 设置 `files` 相关数据或流程；具体参数和返回类型见签名。 涉及上传文件的读取与校验。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |

#### `src/main/java/com/cc/springai/agent/AgentContextPreview.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 30。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `public record AgentContextPreview(String conversationId, String workingDirectory, long usedTokens, long usedChars, int messageCount, List<ContextMessage> messages, List<ToolDefini…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 13 | `public record ContextMessage(int index, String role, String text, int chars, List<ToolCallPreview> toolCalls, List<ToolResponsePreview> toolResponses)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 21 | `public record ToolCallPreview(String id, String type, String name, String arguments)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 24 | `public record ToolResponsePreview(String id, String name, String responseData)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 27 | `public record ToolDefinitionPreview(String name, String description, String inputSchema)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |

#### `src/main/java/com/cc/springai/agent/AgentContextRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 6。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentContextRequest(String conversationId, String workingDirectory, Long lightCompactedBefore, String modelId)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/agent/AgentContextUsage.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 16。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentContextUsage(long usedTokens, long maxTokens, double percent, long usedChars, int messageCount, boolean compacted, long estimatedTokens, Integer actualPromptTok…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |

#### `src/main/java/com/cc/springai/agent/AgentInteractionResponse.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 11。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentInteractionResponse(String runId, String value, String approvalMode, String modelId, String reasoningEffort, String thinkingType, String extraBody)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/agent/AgentLightCompactionResult.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 5。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentLightCompactionResult(int compactedToolResponses, long compactedChars)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java` |

#### `src/main/java/com/cc/springai/agent/AgentReply.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 54。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `public record AgentReply(String status, String content, String runId, List<ToolRequest> tools, List<String> activities, List<ExecutedToolBatch> executions, InteractionRequest inte…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/agent/AgentReply.java`×6 |
| 15 | `public static AgentReply completed(String content, List<String> activities, List<ExecutedToolBatch> executions)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentReply.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×11 |
| 19 | `public static AgentReply completed(String content, List<String> activities, List<ExecutedToolBatch> executions, TokenUsage tokenUsage)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentReply.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×11 |
| 24 | `public static AgentReply approvalRequired(String runId, List<ToolRequest> tools, List<String> activities, List<ExecutedToolBatch> executions)` | 处理 `required` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 31 | `public static AgentReply interactionRequired(String runId, List<ToolRequest> tools, InteractionRequest interaction, List<String> activities, List<ExecutedToolBatch> executions)` | 处理 `required` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |
| 39 | `public static AgentReply loopLimitRequired(String runId, InteractionRequest interaction, List<String> activities, List<ExecutedToolBatch> executions)` | 处理 `limit required` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 46 | `public AgentReply withLatency(long latencyMs)` | 处理 `latency` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×5 |
| 50 | `public AgentReply withTokenUsage(TokenUsage tokenUsage)` | 处理 `token usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2 |

#### `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 84。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 7 | `public record AgentSandboxContext(boolean enabled, String workingDirectory)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java` |
| 11 | `public static AgentSandboxContext of(boolean enabled, String workingDirectory)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 名称过于通用，需结合所属对象和调用链判断 |
| 15 | `public Path workspaceRoot()` | 处理 `root` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`×4 |
| 26 | `public Path resolveWorkspacePath(String requestedPath)` | 解析并确定 `workspace path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×9 |
| 38 | `public boolean containsPath(String requestedPath)` | 处理 `path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×4 |
| 53 | `public void ensureInsideWorkspace(Path target)` | 处理 `inside workspace` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java` |
| 59 | `public boolean commandLooksWorkspaceScoped(String command)` | 处理 `looks workspace scoped` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`、`src/main/java/com/cc/springai/tools/BasicTools.java` |
| 80 | `public boolean commandLooksWorkspaceScopedForApproval(String command)` | 处理 `looks workspace scoped for approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java` |

#### `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 129。

**直接依赖**：`com.cc.springai.registry.McpToolRegistry`、`com.cc.springai.service.RagService`、`com.cc.springai.service.TaskAgentService`、`com.cc.springai.tools.BasicTools`、`com.cc.springai.tools.CalculationTools`、`com.cc.springai.tools.DateTimeTools`、`com.cc.springai.tools.MemoryTools`、`com.cc.springai.tools.RagTools`、`com.cc.springai.tools.SkillsTools`、`com.cc.springai.tools.TaskTools`、`com.cc.springai.tools.UserInteractionTools`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 40 | `public AgentToolOptionsFactory(DateTimeTools dateTimeTools, BasicTools basicTools, CalculationTools calculationTools, RagTools ragTools, TaskTools taskTools, RagService ragService…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 62 | `public OpenAiChatOptions create(String workingDirectory, String conversationId)` | 创建 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×4、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 66 | `public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages)` | 创建 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×4、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 71 | `public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages, String modelId)` | 创建 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×4、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 77 | `public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages, String modelId, AgentSandboxContext sandboxContext)` | 创建 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×4、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 83 | `public OpenAiChatOptions create(String workingDirectory, String conversationId, List<Message> parentMessages, String modelId, AgentSandboxContext sandboxContext, OpenAiChatOptions…` | 创建 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×4、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 121 | `private boolean hasKnowledgeDocuments(String conversationId)` | 判断是否存在 `knowledge documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java` |

#### `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 129。

**直接依赖**：`com.cc.springai.tools.TaskTools`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 35 | `public AgentToolPolicy(ObjectProvider<TaskTools> taskToolsProvider, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 40 | `public boolean requiresApproval(List<ToolRequest> tools)` | 处理 `approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×9 |
| 50 | `public boolean requiresWorkspaceApproval(List<ToolRequest> tools, AgentSandboxContext sandbox)` | 处理 `workspace approval` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 57 | `private boolean isWorkspaceAutoApprovable(ToolRequest tool, AgentSandboxContext sandbox)` | 判断 `workspace auto approvable` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java` |
| 85 | `public boolean requiresUserInteraction(List<ToolRequest> tools)` | 处理 `user interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×6 |
| 89 | `public boolean isUserInteractionTool(String toolName)` | 判断 `user interaction tool` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4 |
| 93 | `public boolean exceedsConsecutiveRagSearchLimit(List<ToolRequest> tools, List<ExecutedToolBatch> executions)` | 处理 `consecutive rag search limit` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3 |
| 113 | `private Map<String, Object> parseArguments(String arguments)` | 解析 `arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/tools/McpToolCallback.java`、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 125 | `private String asText(Object value)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4、`src/main/java/com/cc/springai/service/SessionPersistenceService.java`×2、`src/main/java/com/cc/springai/tools/McpToolCallback.java`×2 |

#### `src/main/java/com/cc/springai/agent/ExecutedTool.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 5。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record ExecutedTool(String name, String arguments, String output)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×8 |

#### `src/main/java/com/cc/springai/agent/ExecutedToolBatch.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 7。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `public record ExecutedToolBatch(List<ExecutedTool> tools)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×8 |

#### `src/main/java/com/cc/springai/agent/InteractionRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 13。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `public record InteractionRequest(String runId, String type, String question, List<String> options, String placeholder, String description, boolean allowCustomInput)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3 |

#### `src/main/java/com/cc/springai/agent/ModelRuntimeOptions.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 7。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record ModelRuntimeOptions(String reasoningEffort, String thinkingType, String extraBody)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`、`src/main/java/com/cc/springai/controller/GameController.java` |

#### `src/main/java/com/cc/springai/agent/TokenUsage.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 140。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 8 | `public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×2 |
| 12 | `public static TokenUsage from(Usage usage)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 43 | `private static Integer chooseTokenValue(Integer standardValue, Integer nativeValue, boolean emptyStandardUsage)` | 处理 `token value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×3 |
| 53 | `public TokenUsage plus(TokenUsage other)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 63 | `private static Integer add(Integer left, Integer right)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/agent/TokenUsage.java`×3、`src/main/java/com/cc/springai/controller/AgentController.java`×53、`src/main/java/com/cc/springai/controller/McpController.java`×2、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 73 | `private static Integer extractInt(Object source, String... names)` | 处理 `int` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×4 |
| 106 | `private static boolean isZero(Integer value)` | 判断 `zero` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×4、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×7、`src/test/java/com/cc/springai/SpringaiApplicationTests.java`、`src/test/java/com/cc/springai/utils/VectorUtilsTest.java` |
| 110 | `private static Integer invokeInt(Object source, String methodName)` | 处理 `int` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×2 |
| 119 | `private static String getterName(String name)` | 处理 `name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java` |
| 126 | `private static Integer toInteger(Object value)` | 转换 `integer` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/TokenUsage.java`×2 |

#### `src/main/java/com/cc/springai/agent/ToolRequest.java`

Agent 领域模型/运行策略：描述请求、响应、上下文、审批或工具执行状态。 行数约 5。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record ToolRequest(String name, String arguments)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×24 |

### 后端 `tools` 包

#### `src/main/java/com/cc/springai/tools/BasicTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 680。

**直接依赖**：`com.cc.springai.agent.AgentSandboxContext`、`com.cc.springai.config.ShellToolConfiguration`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 60 | `public BasicTools(ShellToolConfiguration.ShellToolProperties properties)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java`、`src/test/java/com/cc/springai/service/TaskAgentServiceTest.java`、`src/test/java/com/cc/springai/tools/BasicToolsTest.java` |
| 117 | `private void configureNonInteractiveEnvironment(ProcessBuilder processBuilder)` | 处理 `non interactive environment` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 124 | `private void closeChildInput(Process process)` | 处理 `child input` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 131 | `private long elapsedMs(long startedAtMs)` | 处理 `ms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×3、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 135 | `private boolean isWindows()` | 判断 `windows` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 139 | `private String windowsPowerShellBootstrap()` | 处理 `power shell bootstrap` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 153 | `private ProcessBuilder windowsShellProcessBuilder(String command)` | 处理 `shell process builder` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 171 | `private boolean looksLikeCmdCommand(String command)` | 处理 `like cmd command` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 198 | `public String listFiles( @ToolParam(description = "Directory path, for example . or src/main/java") String path, ToolContext toolContext)` | 列出 `files` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "List files and subdirectories in a directory. Relative paths are resolved against the selected working directory; pass . for the current workspace.")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 371 | `private Path resolveWorkspacePath(String requestedPath, ToolContext toolContext)` | 解析并确定 `workspace path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×9 |
| 385 | `private Path resolveWorkingDirectory(ToolContext toolContext)` | 解析并确定 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×4、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 402 | `private AgentSandboxContext sandboxContext(ToolContext toolContext)` | 处理 `context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×17、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 412 | `private String displayPath(Path target, ToolContext toolContext)` | 处理 `path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×6 |
| 419 | `private void createParentDirectory(Path target) throws IOException` | 创建 `parent directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 426 | `private void writeTextFile(Path target, String content) throws IOException` | 写入 `text file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×2 |
| 440 | `private List<Path> collectSearchFiles(Path root, String fileGlob) throws IOException` | 处理 `search files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 453 | `private boolean matchesGlob(Path base, Path path, PathMatcher matcher, String fileGlob)` | 处理 `glob` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 467 | `private List<String> searchFile(Path file, Pattern pattern, int remainingMatches, ToolContext toolContext)` | 检索 `file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 487 | `private String clipLine(String line)` | 处理 `line` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 495 | `private int countOccurrences(String content, String target)` | 统计 `occurrences` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java`×6 |
| 505 | `private String readLimitedOutput(InputStream inputStream) throws IOException` | 读取 `limited output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 523 | `private String sanitizeShellOutput(String output)` | 处理 `shell output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 543 | `private String stripCliXmlBlocks(String output)` | 处理 `cli xml blocks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 556 | `private String decodeProcessOutput(byte[] bytes)` | 处理 `process output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 595 | `private boolean startsWith(byte[] bytes, byte... prefix)` | 处理 `with` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`、`src/main/frontend/src/stores/workspace.js`、`src/main/frontend/src/utils/agent.js`、`src/main/frontend/src/utils/sse.js`×2、`src/main/java/com/cc/springai/agent/AgentSandboxContext.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×2、`src/main/java/com/cc/springai/service/MemoryService.java`×6 |
| 607 | `private Charset likelyUtf16Charset(byte[] bytes)` | 处理 `utf16 charset` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 630 | `private String decodeStrict(byte[] bytes, Charset charset)` | 处理 `strict` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 642 | `private int readabilityScore(String text)` | 处理 `score` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |
| 664 | `private int mojibakePenalty(String text)` | 处理 `penalty` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/BasicTools.java` |

#### `src/main/java/com/cc/springai/tools/CalculationTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 291。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 23 | `public String calculateExpression( @ToolParam(description = "需要计算的表达式，例如 (12.5 + 7.5) * 3 或 2 ^ 10") String expression)` | 计算 `expression` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "计算算术表达式的结果。支持整数、小数、科学计数法、括号以及 +、-、*、/、%、^ 运算符；除法结果最多保留 34 位有效数字。")` | `src/test/java/com/cc/springai/tools/CalculationToolsTest.java`×4 |
| 40 | `public String calculateBigNumber( @ToolParam(description = "操作类型：add、subtract、multiply、divide、remainder 或 power") String operation, @ToolParam(description = "左操作数，可为超长整数或高精度小数") S…` | 计算 `big number` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "执行任意精度大数计算。operation 支持 add、subtract、multiply、divide、remainder 和 power；除法不能整除时请提供 scale 指定小数位数。")` | `src/test/java/com/cc/springai/tools/CalculationToolsTest.java`×3 |
| 64 | `private static BigDecimal divide(BigDecimal left, BigDecimal right, Integer scale)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×5 |
| 81 | `private static BigDecimal power(BigDecimal base, BigDecimal exponent)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 95 | `private static BigDecimal parseNumber(String text)` | 解析 `number` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×3 |
| 112 | `private static String formatResult(BigDecimal result)` | 格式化 `result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×2 |
| 121 | `private static void ensurePowerResultIsBounded(BigDecimal base, int exponent)` | 处理 `power result is bounded` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×3 |
| 128 | `private static void ensureResultIsBounded(BigDecimal result)` | 处理 `result is bounded` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×4 |
| 140 | `private ExpressionParser(String expression)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 144 | `private BigDecimal parse()` | 解析 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`×4、`src/main/frontend/src/stores/model.js`、`src/main/frontend/src/stores/session.js`、`src/main/frontend/src/utils/agent.js`×2、`src/main/frontend/src/utils/sse.js`、`src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 153 | `private BigDecimal parseAdditive()` | 解析 `additive` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×2 |
| 167 | `private BigDecimal parseMultiplicative()` | 解析 `multiplicative` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×3 |
| 191 | `private BigDecimal parseUnary()` | 解析 `unary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×7 |
| 201 | `private BigDecimal parsePower()` | 解析 `power` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 227 | `private BigDecimal parsePrimary()` | 解析 `primary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 238 | `private BigDecimal parseLiteral()` | 解析 `literal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java` |
| 259 | `private boolean consumeDigits()` | 处理 `digits` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×3 |
| 267 | `private boolean match(char expected)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/agent.js`×2、`src/main/frontend/src/utils/markdown.js`×3、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/tools/CalculationTools.java`×10 |
| 272 | `private boolean consume(char expected)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×6 |
| 280 | `private void skipWhitespace()` | 处理 `whitespace` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/CalculationTools.java`×3 |
| 286 | `private IllegalArgumentException error(String message)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/frontend/src/utils/sse.js`、`src/main/java/com/cc/springai/tools/CalculationTools.java`×6 |

#### `src/main/java/com/cc/springai/tools/DateTimeTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 20。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 13 | `public String getDateTime()` | 读取 `date time` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(name = "getDateTime", description = "查询当前的日期和时间")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/tools/McpToolCallback.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 139。

**直接依赖**：`com.cc.springai.mcp.McpClient`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 28 | `public McpToolCallback(McpClient client, McpToolDefinition tool, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 39 | `public ToolDefinition getToolDefinition()` | 读取 `tool definition` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 44 | `public ToolMetadata getToolMetadata()` | 读取 `tool metadata` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 49 | `public String call(String toolInput)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 54 | `public String call(String toolInput, ToolContext toolContext)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 65 | `private ToolDefinition buildToolDefinition(McpToolDefinition tool)` | 构造 `tool definition` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/McpToolCallback.java` |
| 77 | `private JsonNode parseArguments(String toolInput) throws Exception` | 解析 `arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/tools/McpToolCallback.java`、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 92 | `private String normalizeResult(JsonNode result) throws Exception` | 规范化 `result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/McpToolCallback.java` |
| 118 | `private String toJson(Object value)` | 转换 `json` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/McpToolCallback.java` |
| 126 | `private String blankToDefault(String value, String defaultValue)` | 处理 `to default` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/McpToolCallback.java`×2 |
| 130 | `public record McpToolDefinition( String serverName, String originalName, String exposedName, String description, String inputSchemaJson )` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/java/com/cc/springai/tools/MemoryTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 78。

**直接依赖**：`com.cc.springai.service.MemoryService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 14 | `public MemoryTools(MemoryService memoryService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 29 | `public String saveMemory( @ToolParam(description = "Stable memory identifier") String name, @ToolParam(description = "One-line memory summary for MEMORY.md") String description, @…` | 保存 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/MemoryController.java`、`src/main/java/com/cc/springai/tools/MemoryTools.java`、`src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java`×3 |
| 47 | `public String forgetMemory( @ToolParam(description = "Stable memory identifier to delete") String name, @ToolParam(description = "Memory type: user, feedback, project, or referenc…` | 处理 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/MemoryTools.java` |
| 57 | `或者在达到MEMORY Index中某条记忆的触发时机(opportunity)且当前上下文没有读取过相应记忆时读取相应记忆。 Parameters: - name: stable identifier of the memory. - type: one of user, feedback, project, reference. This tool i…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 70 | `private String workingDirectory(ToolContext toolContext)` | 处理 `directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×19、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4、`src/main/java/com/cc/springai/tools/MemoryTools.java`×3、`src/main/java/com/cc/springai/tools/SkillsTools.java`×3 |

#### `src/main/java/com/cc/springai/tools/RagTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 73。

**直接依赖**：`com.cc.springai.service.RagService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `public RagTools(RagService ragService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×3 |
| 24 | `public String ragSearch( @ToolParam(description = "用户的问题或需要检索的关键词。多条查询可用 \| 分隔，例如：问题一\|问题二") String query, ToolContext toolContext )` | 处理 `search` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "从已上传的 PDF 知识库中检索与用户问题最相关的文档片段。回答 PDF、文档、资料库、知识库相关问题前应优先调用此工具。支持用 | 分隔多条查询进行批量检索。")` | `src/test/java/com/cc/springai/tools/RagToolsTest.java`×3 |
| 43 | `private List<String> splitQueries(String query)` | 处理 `queries` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/RagTools.java` |
| 58 | `private String formatSearchResult(List<RagService.RagChunk> chunks)` | 格式化 `search result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/RagTools.java`×2 |

#### `src/main/java/com/cc/springai/tools/SkillsTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 51。

**直接依赖**：`com.cc.springai.skill.AgentSkillService`、`com.cc.springai.skill.AgentSkillSummary`、`com.cc.springai.skill.RenderedSkill`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `public SkillsTools(AgentSkillService skillService)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 24 | `public List<AgentSkillSummary> listSkills(ToolContext toolContext)` | 列出 `skills` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "List available Agent Skills for the selected working directory. This is read-only.")` | `src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/tools/SkillsTools.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java`×2 |
| 29 | `public String useSkill( @ToolParam(description = "Skill command name, with or without leading slash") String commandName, @ToolParam(description = "Arguments to pass to the skill,…` | 处理 `skill` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "Load the full instructions for an Agent Skill by command name. Call this before following a relevant skill. This is read-only.")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 39 | `public String readSkillResource( @ToolParam(description = "Skill command name, with or without leading slash") String commandName, @ToolParam(description = "Relative path inside t…` | 读取 `skill resource` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Tool(description = "Read a file bundled inside a skill directory, such as references or assets. This is read-only.")` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 46 | `private String workingDirectory(ToolContext toolContext)` | 处理 `directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×19、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4、`src/main/java/com/cc/springai/tools/MemoryTools.java`×3、`src/main/java/com/cc/springai/tools/SkillsTools.java`×3 |

#### `src/main/java/com/cc/springai/tools/TaskTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 114。

**直接依赖**：`com.cc.springai.agent.AgentSandboxContext`、`com.cc.springai.service.TaskAgentService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 22 | `public TaskTools(TaskAgentService taskAgentService, ObjectMapper objectMapper)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 34 | `public String task( @ToolParam(description = "子 agent 要完成的具体任务，必须清晰、边界明确") String prompt, @ToolParam(description = "子 agent 角色：explorer、reviewer、planner、tester、implementer") Strin…` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 72 | `public List<org.springframework.ai.chat.messages.SystemMessage> drainCompletedTaskMessages(String conversationId)` | 处理 `completed task messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 76 | `public boolean isAutoApprovable(String arguments)` | 判断 `auto approvable` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/tools/TaskTools.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3、`src/test/java/com/cc/springai/service/TaskAgentServiceTest.java`×7 |
| 88 | `private List<Message> parentMessages(Object value)` | 处理 `messages` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@SuppressWarnings("unchecked")` | `src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 95 | `private Map<String, Object> parseArguments(String arguments)` | 解析 `arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/tools/McpToolCallback.java`、`src/main/java/com/cc/springai/tools/TaskTools.java` |
| 107 | `private boolean asBoolean(Object value)` | 处理 `boolean` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/TaskTools.java` |

#### `src/main/java/com/cc/springai/tools/UserInteractionTools.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 28。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 20 | `public String askUser( @ToolParam(description = "要展示给用户的简短问题") String question, @ToolParam(description = "可选项列表，每个option必须使用Emoji开头；如果为空则让用户直接输入", required = false) List<String> o…` | 处理 `user` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 后端 `registry` 包

#### `src/main/java/com/cc/springai/registry/McpToolRegistry.java`

工具注册表：聚合并暴露 MCP 工具回调。 行数约 10。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 8 | `List<ToolCallback> toolCallbacks();` | 处理 `callbacks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java` |

#### `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`

工具注册表：聚合并暴露 MCP 工具回调。 行数约 118。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 25 | `public SpringAiMcpToolRegistry(ObjectProvider<SyncMcpToolCallbackProvider> syncProvider)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 30 | `public List<ToolCallback> toolCallbacks()` | 处理 `callbacks` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 41 | `private ToolCallback sanitizeToolCallback(ToolCallback callback, Set<String> usedNames)` | 处理 `tool callback` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 51 | `private String sanitizeToolName(String name)` | 处理 `tool name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 61 | `private String uniqueToolName(String name, Set<String> usedNames)` | 处理 `tool name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 72 | `private String trimToolName(String name)` | 处理 `tool name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`×4 |
| 76 | `private String trimToolName(String name, int reservedSuffixLength)` | 处理 `tool name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`×4 |
| 86 | `private SanitizedToolCallback(ToolCallback delegate, String sanitizedName, String originalName)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 98 | `public ToolDefinition getToolDefinition()` | 读取 `tool definition` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java` |
| 103 | `public ToolMetadata getToolMetadata()` | 读取 `tool metadata` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java` |
| 108 | `public String call(String toolInput)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 113 | `public String call(String toolInput, ToolContext toolContext)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |

### 后端 `mcp` 包

#### `src/main/java/com/cc/springai/mcp/McpClient.java`

`McpClient` 对应的项目代码文件。 行数约 8。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 6 | `JsonNode callTool(String serverName, String toolName, JsonNode arguments) throws Exception;` | 调用 `tool` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/McpToolCallback.java` |

### 后端 `skill` 包

#### `src/main/java/com/cc/springai/skill/AgentSkill.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 37。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 6 | `public record AgentSkill( String commandName, String name, String description, String whenToUse, boolean disableModelInvocation, boolean userInvocable, String argumentHint, List<S…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 21 | `public boolean modelInvocable()` | 处理 `invocable` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillSummary.java` |
| 25 | `public String discoveryText()` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |

#### `src/main/java/com/cc/springai/skill/AgentSkillFile.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 10。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentSkillFile( String commandName, String path, String content, boolean exists, boolean editable)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×3 |

#### `src/main/java/com/cc/springai/skill/AgentSkillService.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 562。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 34 | `public List<AgentSkillSummary> listSkills(String workingDirectory)` | 列出 `skills` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/tools/SkillsTools.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java`×2 |
| 40 | `public String buildCatalogMessage(String workingDirectory)` | 构造 `catalog message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java`×2 |
| 63 | `public Optional<RenderedSkill> renderDirectInvocation(String workingDirectory, String prompt)` | 渲染 `direct invocation` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java`×3 |
| 75 | `public Optional<RenderedSkill> renderSkill(String workingDirectory, String commandName, String arguments)` | 渲染 `skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/skill/AgentSkillService.java`、`src/main/java/com/cc/springai/tools/SkillsTools.java` |
| 88 | `public AgentSkillFile readSkillFile(String workingDirectory, String commandName)` | 读取 `skill file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/SkillController.java` |
| 108 | `public AgentSkillFile writeSkillFile(String workingDirectory, String commandName, String content)` | 写入 `skill file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/SkillController.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java` |
| 128 | `public boolean deleteSkill(String workingDirectory, String commandName)` | 删除 `skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java` |
| 148 | `public String readResource(String workingDirectory, String commandName, String relativePath)` | 读取 `resource` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/tools/SkillsTools.java` |
| 174 | `List<AgentSkill> discoverSkills(String workingDirectory)` | 处理 `skills` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×3 |
| 189 | `private Optional<AgentSkill> skillByCommand(String workingDirectory, String commandName)` | 处理 `by command` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×6 |
| 196 | `private List<SkillRoot> skillRoots(String workingDirectory)` | 处理 `roots` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 212 | `private Path resolveWorkingDirectory(String workingDirectory)` | 解析并确定 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/MemoryService.java`×3、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×4、`src/main/java/com/cc/springai/tools/BasicTools.java`×3 |
| 223 | `private Path projectRootFor(Path cwd)` | 处理 `root for` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×3 |
| 234 | `private Path defaultProjectSkillFile(String workingDirectory, String commandName)` | 处理 `project skill file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 239 | `private Path editableSkillPath(String workingDirectory, String commandName, Path projectRoot)` | 处理 `skill path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 247 | `private String normalizeCommandName(String commandName)` | 规范化 `command name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×3 |
| 258 | `private void tryDeleteDirectoryIfEmpty(Path directory)` | 处理 `delete directory if empty` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 267 | `private List<Path> projectDirectories(Path cwd)` | 处理 `directories` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 280 | `private List<AgentSkill> scanRoot(SkillRoot root)` | 处理 `root` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 295 | `private Optional<AgentSkill> loadSkill(Path directory, SkillRoot root)` | 加载 `skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 331 | `private SkillDocument parseSkillDocument(String raw)` | 解析 `skill document` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 345 | `private Map<String, Object> parseFrontmatter(String text)` | 解析 `frontmatter` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 377 | `private Object parseScalarOrInlineList(String value)` | 解析 `scalar or inline list` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 393 | `private String stripComment(String value)` | 处理 `comment` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 398 | `private Optional<String> stringValue(Map<String, Object> frontmatter, String key)` | 处理 `value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×5 |
| 412 | `private boolean booleanValue(Map<String, Object> frontmatter, String key, boolean defaultValue)` | 处理 `value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 418 | `private List<String> listValue(Map<String, Object> frontmatter, String key)` | 列出 `value` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 436 | `private Optional<String> firstParagraph(String body)` | 处理 `paragraph` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 446 | `private RenderedSkill render(AgentSkill skill, String rawArguments)` | 渲染 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 468 | `private String replaceIndexedArguments(String content, Pattern pattern, List<String> tokens)` | 处理 `indexed arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 480 | `private String replaceNamedArguments(String content, List<String> names, List<String> tokens)` | 处理 `named arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 489 | `private List<String> splitArguments(String arguments)` | 处理 `arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 507 | `private Invocation parseInvocation(String prompt)` | 解析 `invocation` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 524 | `private int firstWhitespace(String value)` | 处理 `whitespace` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |
| 533 | `private String clip(String value, int maxLength)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 541 | `private String unquote(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×2 |
| 553 | `private record SkillRoot(Path path, String source, int priority)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×4 |
| 556 | `private record SkillDocument(Map<String, Object> frontmatter, String body)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java`×3 |
| 559 | `private record Invocation(String commandName, String arguments)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |

#### `src/main/java/com/cc/springai/skill/AgentSkillSummary.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 25。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record AgentSkillSummary( String commandName, String name, String description, String whenToUse, boolean modelInvocable, boolean userInvocable, String source, String path)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillSummary.java` |
| 13 | `static AgentSkillSummary from(AgentSkill skill)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |

#### `src/main/java/com/cc/springai/skill/RenderedSkill.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 10。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 3 | `public record RenderedSkill( String commandName, String arguments, String content, String source, String path)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/skill/AgentSkillService.java` |

### 后端 `embedding` 包

#### `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`

`OllamaLegacyEmbeddingModel` 对应的项目代码文件。 行数约 83。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 21 | `public OllamaLegacyEmbeddingModel(String baseUrl, String model, int dimensions)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/config/CommonConfiguration.java` |
| 30 | `public EmbeddingResponse call(EmbeddingRequest request)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 52 | `public float[] embed(Document document)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java`×2 |
| 57 | `public int dimensions()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 61 | `private String resolveModel(EmbeddingRequest request)` | 解析并确定 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 70 | `private static String trimTrailingSlash(String baseUrl)` | 处理 `trailing slash` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×4 |
| 77 | `private record OllamaEmbeddingRequest(String model, String prompt)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 80 | `private record OllamaEmbeddingResponse(float[] embedding)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 后端 `utils` 包

#### `src/main/java/com/cc/springai/utils/VectorUtils.java`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 24。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 5 | `private VectorUtils()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 8 | `public static double euclideanDistance(float[] first, float[] second)` | 处理 `distance` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/SpringaiApplicationTests.java`、`src/test/java/com/cc/springai/utils/VectorUtilsTest.java`×4 |

### Spring AI 定制源码

#### `org/springframework/ai/openai/api/OpenAiApi.java`

项目内覆盖/定制的 Spring AI 上游类；参与底层 OpenAI 协议和模型调用。 行数约 2136。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 82 | `public Builder mutate()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/service/ModelConfigService.java`、`src/main/java/com/cc/springai/service/RagService.java`×3 |
| 86 | `public static Builder builder()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×4、`org/springframework/ai/openai/OpenAiChatModel.java`×13、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/config/CommonConfiguration.java`×9、`src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |
| 132 | `public OpenAiApi(String baseUrl, ApiKey apiKey, MultiValueMap<String, String> headers, String completionsPath, String embeddingsPath, RestClient.Builder restClientBuilder, WebClie…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 171 | `public static String getTextContent(List<ChatCompletionMessage.MediaContent> content)` | 读取 `text content` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 186 | `public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest)` | 处理 `completion entity` 相关数据或流程；具体参数和返回类型见签名。 返回 HTTP 响应对象。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 198 | `public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest, MultiValueMap<String, String> additionalHttpHeader)` | 处理 `completion entity` 相关数据或流程；具体参数和返回类型见签名。 返回 HTTP 响应对象。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 224 | `public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest)` | 处理 `completion stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 236 | `public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest, MultiValueMap<String, String> additionalHttpHeader)` | 处理 `completion stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 300 | `public <T> ResponseEntity<EmbeddingList<Embedding>> embeddings(EmbeddingRequest<T> embeddingRequest)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 返回 HTTP 响应对象。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 332 | `private void addDefaultHeadersIfMissing(HttpHeaders headers)` | 处理 `default headers if missing` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2 |
| 339 | `String getBaseUrl()` | 读取 `base url` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 343 | `ApiKey getApiKey()` | 读取 `api key` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 351 | `String getCompletionsPath()` | 读取 `completions path` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 355 | `String getEmbeddingsPath()` | 读取 `embeddings path` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 359 | `ResponseErrorHandler getResponseErrorHandler()` | 读取 `response error handler` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 741 | `ChatModel(String value)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 745 | `public String getValue()` | 读取 `value` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×2、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 750 | `public String getName()` | 读取 `name` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/java/com/cc/springai/controller/AgentController.java` |
| 819 | `EmbeddingModel(String value)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 823 | `public String getValue()` | 读取 `value` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×2、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 848 | `public FunctionTool()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 857 | `public FunctionTool(Type type, Function function)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 866 | `public FunctionTool(Function function)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 870 | `public Type getType()` | 读取 `type` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 874 | `public Function getFunction()` | 读取 `function` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 878 | `public void setType(Type type)` | 设置 `type` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 882 | `public void setFunction(Function function)` | 设置 `function` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 924 | `private Function()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@SuppressWarnings("unused")` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 941 | `public Function(String description, String name, Map<String, Object> parameters, Boolean strict)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 954 | `public Function(String description, String name, String jsonSchema)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 958 | `public String getDescription()` | 读取 `description` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 962 | `public String getName()` | 读取 `name` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/java/com/cc/springai/controller/AgentController.java` |
| 966 | `public Map<String, Object> getParameters()` | 读取 `parameters` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 970 | `public void setDescription(String description)` | 设置 `description` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 974 | `public void setName(String name)` | 设置 `name` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 978 | `public void setParameters(Map<String, Object> parameters)` | 设置 `parameters` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 982 | `public Boolean getStrict()` | 读取 `strict` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 986 | `public void setStrict(Boolean strict)` | 设置 `strict` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 990 | `public String getJsonSchema()` | 读取 `json schema` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 994 | `public void setJsonSchema(String jsonSchema)` | 设置 `json schema` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1110 | `record ChatCompletionRequest(// @formatter:off @JsonProperty("messages")` | 处理 `completion request` 相关数据或流程；具体参数和返回类型见签名。 这是不可变数据载体声明。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1161 | `public ChatCompletionRequest(List<ChatCompletionMessage> messages, String model, Double temperature)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1174 | `public ChatCompletionRequest(List<ChatCompletionMessage> messages, String model, AudioParameters audio, boolean stream)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1190 | `public ChatCompletionRequest(List<ChatCompletionMessage> messages, String model, Double temperature, boolean stream)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1205 | `public ChatCompletionRequest(List<ChatCompletionMessage> messages, String model, List<FunctionTool> tools, Object toolChoice)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1219 | `public ChatCompletionRequest(List<ChatCompletionMessage> messages, Boolean stream)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`、`org/springframework/ai/openai/OpenAiChatModel.java` |
| 1231 | `public ChatCompletionRequest streamOptions(StreamOptions streamOptions)` | 流式处理 `options` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/OpenAiChatModel.java`×4 |
| 1246 | `public Map<String, Object> extraBody()` | 处理 `body` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@JsonAnyGetter` | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×7 |
| 1258 | `private void setExtraBodyProperty(String key, Object value)` | 设置 `extra body property` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@JsonAnySetter` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1280 | `public static Object function(String functionName)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 1291 | `public record AudioParameters( @JsonProperty("voice") Voice voice, @JsonProperty("format") AudioResponseFormat format)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1346 | `public record StreamOptions( @JsonProperty("include_usage") Boolean includeUsage)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 1359 | `public record WebSearchOptions(@JsonProperty("search_context_size") SearchContextSize searchContextSize, @JsonProperty("user_location") UserLocation userLocation)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1395 | `public record UserLocation(@JsonProperty("type") String type, @JsonProperty("approximate") Approximate approximate)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1399 | `public record Approximate(@JsonProperty("city") String city, @JsonProperty("country") String country, @JsonProperty("region") String region, @JsonProperty("timezone") String timez…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1433 | `ServiceTier(String value)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1437 | `public String getValue()` | 读取 `value` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×2、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3、`src/main/java/com/cc/springai/service/TaskAgentService.java` |
| 1465 | `public record ChatCompletionMessage(// @formatter:off @JsonProperty("content") Object rawContent, @JsonProperty("role") Role role, @JsonProperty("name") String name, @JsonProperty…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java`×3 |
| 1483 | `public ChatCompletionMessage(Object content, Role role)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×3 |
| 1490 | `public String content()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×3、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/FinancialRagController.java`×2、`src/main/java/com/cc/springai/controller/GameController.java`、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×5 |
| 1541 | `public record MediaContent(// @formatter:off @JsonProperty("type") String type, @JsonProperty("text") String text, @JsonProperty("image_url") ImageUrl imageUrl, @JsonProperty("inp…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java`×5 |
| 1552 | `public MediaContent(String text)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5 |
| 1560 | `public MediaContent(ImageUrl imageUrl)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5 |
| 1568 | `public MediaContent(InputAudio inputAudio)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5 |
| 1576 | `public MediaContent(InputFile inputFile)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5 |
| 1586 | `public record InputAudio(// @formatter:off @JsonProperty("data") String data, @JsonProperty("format") Format format)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 1607 | `public record ImageUrl(@JsonProperty("url") String url, @JsonProperty("detail") String detail)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL)` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1609 | `public ImageUrl(String url)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1622 | `public record InputFile(@JsonProperty("filename") String filename, @JsonProperty("file_data") String fileData)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1642 | `public record ToolCall(// @formatter:off @JsonProperty("index") Integer index, @JsonProperty("id") String id, @JsonProperty("type") String type, @JsonProperty("function") ChatComp…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×3、`src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java`×2 |
| 1648 | `public ToolCall(String id, String type, ChatCompletionFunction function)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/controller/AgentController.java`、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×3、`src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java`×2 |
| 1663 | `public record ChatCompletionFunction(// @formatter:off @JsonProperty("name") String name, @JsonProperty("arguments") String arguments) { // @formatter:on` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1679 | `public record AudioOutput(// @formatter:off @JsonProperty("id") String id, @JsonProperty("data") String data, @JsonProperty("expires_at") Long expiresAt, @JsonProperty("transcript…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1691 | `public record Annotation(@JsonProperty("type") String type, @JsonProperty("url_citation") UrlCitation urlCitation)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(JsonInclude.Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1704 | `public record UrlCitation(@JsonProperty("end_index") Integer endIndex, @JsonProperty("start_index") Integer startIndex, @JsonProperty("title") String title, @JsonProperty("url") S…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(JsonInclude.Include.NON_NULL)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1731 | `public record ChatCompletion(// @formatter:off @JsonProperty("id") String id, @JsonProperty("choices") List<Choice> choices, @JsonProperty("created") Long created, @JsonProperty("…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1752 | `public record Choice(// @formatter:off @JsonProperty("finish_reason") ChatCompletionFinishReason finishReason, @JsonProperty("index") Integer index, @JsonProperty("message") ChatC…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 1769 | `public record LogProbs(@JsonProperty("content") List<Content> content, @JsonProperty("refusal") List<Content> refusal)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1788 | `public record Content(// @formatter:off @JsonProperty("token") String token, @JsonProperty("logprob") Float logprob, @JsonProperty("bytes") List<Integer> probBytes, @JsonProperty(…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1807 | `public record TopLogProbs(// @formatter:off @JsonProperty("token") String token, @JsonProperty("logprob") Float logprob, @JsonProperty("bytes") List<Integer> probBytes) { // @form…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1832 | `public record Usage(// @formatter:off @JsonProperty("completion_tokens") Integer completionTokens, @JsonProperty("prompt_tokens") Integer promptTokens, @JsonProperty("total_tokens…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1840 | `public Usage(Integer completionTokens, Integer promptTokens, Integer totalTokens)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1852 | `public record PromptTokensDetails(// @formatter:off @JsonProperty("audio_tokens") Integer audioTokens, @JsonProperty("cached_tokens") Integer cachedTokens) { // @formatter:on` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1869 | `public record CompletionTokenDetails(// @formatter:off @JsonProperty("reasoning_tokens") Integer reasoningTokens, @JsonProperty("accepted_prediction_tokens") Integer acceptedPredi…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1898 | `public record ChatCompletionChunk(// @formatter:off @JsonProperty("id") String id, @JsonProperty("choices") List<ChunkChoice> choices, @JsonProperty("created") Long created, @Json…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `org/springframework/ai/openai/api/OpenAiApi.java` |
| 1918 | `public record ChunkChoice(// @formatter:off @JsonProperty("finish_reason") ChatCompletionFinishReason finishReason, @JsonProperty("index") Integer index, @JsonProperty("delta") Ch…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 1938 | `public record Embedding(// @formatter:off @JsonProperty("index") Integer index, @JsonProperty("embedding") @JsonDeserialize(using = OpenAiEmbeddingDeserializer.class) float[] embe…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。；框架标记：`@JsonInclude(Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)` | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 1952 | `public Embedding(Integer index, float[] embedding)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 1957 | `public boolean equals(Object o)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/api/OpenAiApi.java`×4、`org/springframework/ai/openai/OpenAiChatModel.java`×4、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×13、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×5 |
| 1969 | `public int hashCode()` | 处理 `code` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/api/OpenAiApi.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java` |
| 2008 | `public EmbeddingRequest(T input, String model)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2017 | `public EmbeddingRequest(T input)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2043 | `public Builder()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 2047 | `public Builder(OpenAiApi api)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 2074 | `public Builder baseUrl(String baseUrl)` | 处理 `url` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×7 |
| 2080 | `public Builder apiKey(ApiKey apiKey)` | 处理 `key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×10 |
| 2086 | `public Builder apiKey(String simpleApiKey)` | 处理 `key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×10 |
| 2091 | `public Builder headers(MultiValueMap<String, String> headers)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×3 |
| 2097 | `public Builder completionsPath(String completionsPath)` | 处理 `path` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/ModelConfigController.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×7 |
| 2103 | `public Builder embeddingsPath(String embeddingsPath)` | 处理 `path` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2109 | `public Builder restClientBuilder(RestClient.Builder restClientBuilder)` | 处理 `client builder` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2115 | `public Builder webClientBuilder(WebClient.Builder webClientBuilder)` | 处理 `client builder` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2121 | `public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler)` | 处理 `error handler` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 2127 | `public OpenAiApi build()` | 构造 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×14、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/config/CommonConfiguration.java`×9、`src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |

#### `org/springframework/ai/openai/OpenAiChatModel.java`

项目内覆盖/定制的 Spring AI 上游类；参与底层 OpenAI 协议和模型调用。 行数约 828。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 154 | `public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions, ToolCallingManager toolCallingManager, RetryTemplate retryTemplate, ObservationRegistry observationRe…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 160 | `public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions, ToolCallingManager toolCallingManager, RetryTemplate retryTemplate, ObservationRegistry observationRe…` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 178 | `public ChatResponse call(Prompt prompt)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 185 | `public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse)` | 处理 `call` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 264 | `public Flux<ChatResponse> stream(Prompt prompt)` | 流式处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@Override` | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×10、`src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×31、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×10 |
| 271 | `public Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse)` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 406 | `private MultiValueMap<String, String> getAdditionalHttpHeaders(Prompt prompt)` | 读取 `additional http headers` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 416 | `private Generation buildGeneration(Choice choice, Map<String, Object> metadata, ChatCompletionRequest request)` | 构造 `generation` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 461 | `private String getFinishReasonJson(OpenAiApi.ChatCompletionFinishReason finishReason)` | 读取 `finish reason json` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×3 |
| 469 | `private ChatResponseMetadata from(OpenAiApi.ChatCompletion result, RateLimit rateLimit, Usage usage)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 483 | `private ChatResponseMetadata from(ChatResponseMetadata chatResponseMetadata, Usage usage)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×5、`src/main/frontend/src/stores/chat.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×6、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4 |
| 500 | `private OpenAiApi.ChatCompletion chunkToChatCompletion(OpenAiApi.ChatCompletionChunk chunk)` | 处理 `to chat completion` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 511 | `private DefaultUsage getDefaultUsage(OpenAiApi.Usage usage)` | 读取 `default usage` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 515 | `Prompt buildRequestPrompt(Prompt prompt)` | 构造 `request prompt` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 568 | `private Map<String, String> mergeHttpHeaders(Map<String, String> runtimeHttpHeaders, Map<String, String> defaultHttpHeaders)` | 合并 `http headers` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 575 | `private Map<String, Object> mergeExtraBody(Map<String, Object> runtimeExtraBody, Map<String, Object> defaultExtraBody)` | 合并 `extra body` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 593 | `ChatCompletionRequest createRequest(Prompt prompt, boolean stream)` | 创建 `request` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 669 | `private MediaContent mapToMediaContent(Media media)` | 映射 `to media content` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 689 | `private String fromAudioData(Object audioData)` | 处理 `audio data` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 696 | `private String fromMediaData(MimeType mimeType, Object mediaContentData)` | 处理 `media data` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 712 | `private List<OpenAiApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions)` | 读取 `function tools` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java` |
| 721 | `public ChatOptions getDefaultOptions()` | 读取 `default options` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 726 | `public String toString()` | 转换 `string` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/frontend/src/services/api.js`×3、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`、`src/main/java/com/cc/springai/controller/AgentController.java`×11、`src/main/java/com/cc/springai/controller/FileOpenController.java`×5、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×4、`src/main/java/com/cc/springai/service/FinancialRagService.java`×3 |
| 734 | `public void setObservationConvention(ChatModelObservationConvention observationConvention)` | 设置 `observation convention` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 739 | `public static Builder builder()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×4、`org/springframework/ai/openai/OpenAiChatModel.java`×13、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/config/CommonConfiguration.java`×9、`src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |
| 746 | `public Builder mutate()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×2、`src/main/java/com/cc/springai/service/ModelConfigService.java`、`src/main/java/com/cc/springai/service/RagService.java`×3 |
| 751 | `public OpenAiChatModel clone()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/api/OpenAiApi.java`×2 |
| 758 | `public Builder(OpenAiChatModel model)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 782 | `private Builder()` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×2 |
| 785 | `public Builder openAiApi(OpenAiApi openAiApi)` | 打开 `ai api` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 790 | `public Builder defaultOptions(OpenAiChatOptions defaultOptions)` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/ModelConfigService.java` |
| 795 | `public Builder toolCallingManager(ToolCallingManager toolCallingManager)` | 处理 `calling manager` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 800 | `public Builder toolExecutionEligibilityPredicate( ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate)` | 处理 `execution eligibility predicate` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 806 | `public Builder retryTemplate(RetryTemplate retryTemplate)` | 处理 `template` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 811 | `public Builder observationRegistry(ObservationRegistry observationRegistry)` | 处理 `registry` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 816 | `public OpenAiChatModel build()` | 构造 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/api/OpenAiApi.java`×2、`org/springframework/ai/openai/OpenAiChatModel.java`×14、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/config/CommonConfiguration.java`×9、`src/main/java/com/cc/springai/controller/AgentController.java`×12、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java`、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/FinancialRagService.java`×4 |

## 7. 前端逐文件逐函数解析

### 前端 `src`

#### `src/main/frontend/src/App.vue`

`App` 对应的项目代码文件。 行数约 26。

**直接依赖**：`./components/layout/AppShell.vue`、`./components/modals/WorkspaceModal.vue`、`./components/modals/AgentInteractionModal.vue`、`./components/modals/ContextDrawer.vue`、`./components/modals/MemoryModal.vue`、`./components/modals/SkillsModal.vue`、`./components/modals/KnowledgeModal.vue`、`./components/modals/ImagePreviewModal.vue`、`./components/modals/ModelModal.vue`、`./components/modals/MigrationModal.vue`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/main.js`

`main` 对应的项目代码文件。 行数约 32。

**直接依赖**：`vue`、`pinia`、`./App.vue`、`./stores/model`、`./stores/session`、`./stores/workspace`、`./services/config`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

### 前端 `services`

#### `src/main/frontend/src/services/api.js`

前端服务适配层；集中定义后端端点与 fetch 请求。 行数约 276。

**直接依赖**：`./config`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 9 | `function query(params)` | 查询 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/services/api.js`×9、`src/main/java/com/cc/springai/service/FinancialRagService.java`×2、`src/main/java/com/cc/springai/service/JdbcChatMemoryRepository.java`×2、`src/main/java/com/cc/springai/service/ModelConfigService.java`×4、`src/main/java/com/cc/springai/service/RagService.java`×6、`src/main/java/com/cc/springai/service/SessionPersistenceService.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java` |
| 13 | `function requireOk(response, fallbackMessage = "HTTP")` | 校验并返回 `ok` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/services/api.js`×4 |
| 20 | `async function requireOkPromise(responsePromise, fallbackMessage = "HTTP")` | 校验并返回 `ok promise` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/services/api.js`×2 |
| 24 | `async function json(responsePromise, fallbackMessage)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/services/api.js`×27 |
| 32 | `persistSessions(sessions) {` | 持久化 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`、`src/main/frontend/src/stores/session.js`×2 |
| 39 | `listSessions() {` | 列出 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js` |
| 43 | `deleteSession(sessionId) {` | 删除 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue`、`src/main/frontend/src/stores/session.js`×2 |
| 47 | `defaultWorkingDirectory() {` | 处理 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js` |
| 51 | `listModels() {` | 列出 `models` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js` |
| 55 | `exportModels() {` | 导出 `models` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 59 | `importModels(models) {` | 导入 `models` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 67 | `saveModel(payload) {` | 保存 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js` |
| 77 | `async deleteModel(modelId) {` | 删除 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js` |
| 81 | `streamChat(params, signal) {` | 流式处理 `chat` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js` |
| 88 | `streamFinanceChat(params, signal) {` | 流式处理 `finance chat` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js` |
| 95 | `streamAgentChat(formData, signal) {` | 流式处理 `agent chat` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js` |
| 104 | `updateAgentApprovalMode(conversationId, approvalMode, sandboxEnabled, workingDirectory) {` | 更新 `agent approval mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 112 | `streamToolApproval(runId, approvalMode, modelId, runtimeOptions, signal) {` | 流式处理 `tool approval` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js` |
| 124 | `decideToolAction(action, runId, approvalMode, modelId, runtimeOptions, signal) {` | 处理 `tool action` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2、`src/main/frontend/src/stores/chat.js` |
| 133 | `submitAgentInteraction(runId, value, approvalMode, modelId, runtimeOptions, signal) {` | 处理 `agent interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/AgentInteractionModal.vue`、`src/main/frontend/src/stores/chat.js` |
| 145 | `loadAgentContext(params) {` | 加载 `agent context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 149 | `loadAgentContextPreview(params) {` | 加载 `agent context preview` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 153 | `compactAgentContext(payload) {` | 压缩 `agent context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 161 | `lightCompactAgentContext(payload) {` | 处理 `compact agent context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 169 | `async taskProgress(conversationId) {` | 处理 `progress` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 176 | `async taskGroups(conversationId) {` | 处理 `groups` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java` |
| 183 | `loadMemoryFiles(workingDirectory) {` | 加载 `memory files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×3 |
| 187 | `saveMemoryFile(payload) {` | 保存 `memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 195 | `createMemory(payload) {` | 创建 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 203 | `loadSkills(workingDirectory) {` | 加载 `skills` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×3 |
| 207 | `renderSkill(workingDirectory, commandName, argumentsText) {` | 渲染 `skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/skill/AgentSkillService.java`、`src/main/java/com/cc/springai/tools/SkillsTools.java` |
| 215 | `loadSkillFile(workingDirectory, commandName) {` | 加载 `skill file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 221 | `saveSkillFile(payload) {` | 保存 `skill file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 229 | `deleteSkill(workingDirectory, commandName) {` | 删除 `skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java` |
| 236 | `loadKnowledgeDocuments(conversationId) {` | 加载 `knowledge documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/knowledge.js` |
| 242 | `uploadKnowledgeFile(conversationId, file) {` | 上传 `knowledge file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/knowledge.js` |
| 253 | `async deleteKnowledgeDocument(conversationId, documentId) {` | 删除 `knowledge document` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/knowledge.js` |
| 259 | `loadMcpServers() {` | 加载 `mcp servers` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/workspace.js` |
| 263 | `async openLocalFile(path) {` | 打开 `local file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |

#### `src/main/frontend/src/services/config.js`

前端服务适配层；集中定义后端端点与 fetch 请求。 行数约 68。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 63 | `export function visibleMode(value)` | 处理 `mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/session.js`×6 |

### 前端 `stores`

#### `src/main/frontend/src/stores/chat.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 731。

**直接依赖**：`pinia`、`../services/api`、`../services/config`、`../utils/sse`、`./model`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 26 | `function wantsReasoningOutput(modelStore)` | 处理 `reasoning output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×4 |
| 31 | `function mergeAgentFinalText(existingText, finalText)` | 合并 `agent final text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 54 | `function touchSession(session, sessions)` | 处理 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×4 |
| 59 | `function touchSessionSoft(session)` | 处理 `session soft` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 63 | `function createStreamUpdateScheduler(session, sessions, delay = 800)` | 创建 `stream update scheduler` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×2 |
| 67 | `touch({ persist = false } = {}) {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×8 |
| 82 | `flush() {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×16 |
| 89 | `function createStreamTextBuffer(onFlush, delay = 80)` | 创建 `stream text buffer` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×2 |
| 93 | `const flush = () =>` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×16 |
| 104 | `append(content) {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/services/api.js`×2、`src/main/frontend/src/stores/chat.js`×14、`src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×49、`src/main/java/com/cc/springai/service/FinancialRagService.java`×16、`src/main/java/com/cc/springai/service/MemoryService.java`×12、`src/main/java/com/cc/springai/service/TaskAgentService.java`×5、`src/main/java/com/cc/springai/skill/AgentSkill.java`×3 |
| 116 | `function prepareMessageStreamingState(message)` | 处理 `message streaming state` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×4 |
| 129 | `async function contextCompactionInProgress()` | 处理 `compaction in progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×4 |
| 149 | `setPrompt(value) {` | 设置 `prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue`、`src/main/frontend/src/stores/workspace.js`、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×2 |
| 152 | `addFiles(fileList) {` | 处理 `files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue` |
| 155 | `removeFile(index) {` | 移除 `file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue` |
| 158 | `clearAttachments() {` | 清理 `attachments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 161 | `stop() {` | 停止 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/frontend/src/components/chat/Composer.vue` |
| 164 | `async submitPrompt(text = this.prompt) {` | 处理 `prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 227 | `async regenerateUserMessage(message) {` | 处理 `user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 291 | `async submitModelPrompt(prompt, session, assistant, requestMode, regenerate = false) {` | 处理 `model prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 373 | `async submitAgentPrompt(prompt, session, assistant, options = {}) {` | 处理 `agent prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 408 | `async processAgentStream(response, assistant, session) {` | 处理 `agent stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×3 |
| 413 | `const appendAgentToken = (content) =>` | 处理 `agent token` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 425 | `const finishStream = () =>` | 处理 `stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×3 |
| 546 | `findAssistantByRunId(runId) {` | 查找 `assistant by run id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 558 | `async decideToolAction(runId, action) {` | 处理 `tool action` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2、`src/main/frontend/src/stores/chat.js` |
| 609 | `applyAgentReply(reply, message, session, handledRunId = null, handledStatus = "approved") {` | 应用 `agent reply` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 652 | `openInteraction(interaction) {` | 打开 `interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 655 | `closeInteraction() {` | 处理 `interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 658 | `async submitAgentInteraction(runId, value) {` | 处理 `agent interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/AgentInteractionModal.vue`、`src/main/frontend/src/stores/chat.js` |
| 701 | `applyStreamError(error, message, sessions) {` | 应用 `stream error` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/frontend/src/stores/chat.js`×4 |
| 723 | `openImagePreview(url) {` | 打开 `image preview` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 726 | `closeImagePreview() {` | 处理 `image preview` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/stores/knowledge.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 74。

**直接依赖**：`pinia`、`../services/api`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 14 | `sessionTitle() {` | 处理 `title` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 20 | `async openForSession(sessionId) {` | 打开 `for session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue` |
| 25 | `close() {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue`、`src/main/frontend/src/composables/useFloatingSelect.js`、`src/main/java/com/cc/springai/tools/BasicTools.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java`×2 |
| 28 | `async loadDocuments() {` | 加载 `documents` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/knowledge.js`×3 |
| 42 | `async upload(file) {` | 上传 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/KnowledgeModal.vue` |
| 57 | `async deleteDocument(documentId) {` | 删除 `document` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/KnowledgeModal.vue`、`src/main/java/com/cc/springai/controller/UploadController.java` |

#### `src/main/frontend/src/stores/MessagesPanel.vue`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 227。

**直接依赖**：`vue`、`../../stores/chat`、`../../stores/session`、`../../stores/workspace`、`./MessageBubble.vue`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 41 | `function valueSize(value)` | 处理 `size` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×5、`src/main/frontend/src/components/chat/MessagesPanel.vue`×5、`src/main/frontend/src/stores/MessagesPanel.vue`×5 |
| 55 | `function agentTraceSignal(message)` | 处理 `trace signal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 110 | `function isElementPinnedToBottom()` | 判断 `element pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×2、`src/main/frontend/src/stores/MessagesPanel.vue`×2 |
| 118 | `function setPinnedToBottom(value)` | 设置 `pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×3、`src/main/frontend/src/stores/MessagesPanel.vue`×3 |
| 123 | `function syncPinnedToBottom()` | 处理 `pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 127 | `function scheduleScrollToBottom({ force = false } = {})` | 处理 `scroll to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×2、`src/main/frontend/src/stores/MessagesPanel.vue`×2 |
| 166 | `function submitSuggestion(prompt)` | 处理 `suggestion` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 175 | `function isLatestUserMessage(index)` | 判断 `latest user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 181 | `function retryUserMessage(message)` | 处理 `user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 191 | `function messageKey(message, index)` | 处理 `key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |

#### `src/main/frontend/src/stores/migration.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 274。

**直接依赖**：`pinia`、`../services/api`、`../services/config`、`../utils/session`、`./model`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 9 | `function readJsonStorage(key, fallback)` | 读取 `json storage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`×2、`src/main/frontend/src/stores/session.js`×3 |
| 18 | `function downloadJson(filename, payload)` | 处理 `json` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 32 | `function normalizeModels(value)` | 规范化 `models` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`×2 |
| 58 | `function parseImportPayload(value)` | 解析 `import payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 99 | `function mergeSessions(currentSessions, incomingSessions, strategy)` | 合并 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 115 | `async function exportModelsFromServer()` | 导出 `models from server` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 119 | `async function importModelsToServer(models)` | 导入 `models to server` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 138 | `readLocalSnapshot() {` | 读取 `local snapshot` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`×3 |
| 147 | `refreshLocalSummary() {` | 处理 `local summary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js` |
| 150 | `show() {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 155 | `close() {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue`、`src/main/frontend/src/composables/useFloatingSelect.js`、`src/main/java/com/cc/springai/tools/BasicTools.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java`×2 |
| 161 | `async exportData() {` | 导出 `data` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 191 | `async readImportFile(file) {` | 读取 `import file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/MigrationModal.vue` |
| 222 | `async importData() {` | 导入 `data` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/stores/model.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 199。

**直接依赖**：`pinia`、`../services/api`、`../services/config`、`../utils/session`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 6 | `function blankModel()` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`×3 |
| 38 | `activeModel(state) {` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 41 | `selectedModel(state) {` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 45 | `modelOptions(state) {` | 处理 `options` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 54 | `async loadModels() {` | 加载 `models` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/migration.js`、`src/main/frontend/src/stores/model.js`×3 |
| 84 | `setActiveModel(modelId) {` | 设置 `active model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`×2 |
| 88 | `runtimeOptionsPayload() {` | 处理 `options payload` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×6 |
| 103 | `openModal() {` | 打开 `modal` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 110 | `closeModal() {` | 处理 `modal` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 113 | `resetForm() {` | 处理 `form` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 118 | `selectModel(modelId) {` | 处理 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/ModelModal.vue` |
| 122 | `loadSelectedModel() {` | 加载 `selected model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`×2 |
| 143 | `payloadFromForm() {` | 处理 `from form` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js` |
| 166 | `async saveModel() {` | 保存 `model` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js` |
| 180 | `async deleteSelectedModel() {` | 删除 `selected model` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/stores/session.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 220。

**直接依赖**：`pinia`、`../services/api`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `function readJsonStorage(key, fallback)` | 读取 `json storage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`×2、`src/main/frontend/src/stores/session.js`×3 |
| 28 | `function syncDocumentMode(mode)` | 处理 `document mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js`×3 |
| 54 | `activeSession(state) {` | 处理 `session` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 57 | `hasMessages() {` | 判断是否存在 `messages` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 62 | `setStatus(text) {` | 设置 `status` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`×3、`src/main/frontend/src/components/chat/MessagesPanel.vue`×2、`src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2、`src/main/frontend/src/stores/chat.js`×25、`src/main/frontend/src/stores/MessagesPanel.vue`×2、`src/main/frontend/src/stores/session.js`×2、`src/main/frontend/src/stores/workspace.js`×4 |
| 65 | `async initialize() {` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js` |
| 85 | `loadLocalSessions() {` | 加载 `local sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/migration.js`、`src/main/frontend/src/stores/session.js` |
| 91 | `ensureSession(mode = this.mode) {` | 处理 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/chat.js`、`src/main/frontend/src/stores/session.js`×3 |
| 109 | `changeMode(nextMode) {` | 处理 `mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue`×3、`src/main/frontend/src/stores/shell.js` |
| 117 | `switchSession(sessionId) {` | 处理 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue` |
| 128 | `startNewSession({ workingDirectory = "", approvalMode = "work-auto", sandboxEnabled = true } = {}) {` | 启动 `new session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/WorkspaceModal.vue`、`src/main/frontend/src/stores/session.js` |
| 140 | `requestNewSession() {` | 处理 `new session` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 148 | `clearCurrentSession() {` | 清理 `current session` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 166 | `async deleteSession(sessionId) {` | 删除 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue`、`src/main/frontend/src/stores/session.js`×2 |
| 184 | `saveSessions() {` | 保存 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`×3、`src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2、`src/main/frontend/src/main.js`、`src/main/frontend/src/stores/chat.js`×9、`src/main/frontend/src/stores/session.js`×7、`src/main/frontend/src/stores/workspace.js`×2 |
| 195 | `async persistSessions() {` | 持久化 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/migration.js`、`src/main/frontend/src/stores/session.js`×2 |
| 202 | `updateActiveSession(patch) {` | 更新 `active session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/WorkspaceModal.vue` |
| 210 | `openWorkspaceModal(createNewSession = false) {` | 打开 `workspace modal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 214 | `closeWorkspaceModal() {` | 处理 `workspace modal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/WorkspaceModal.vue` |

#### `src/main/frontend/src/stores/shell.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 26。

**直接依赖**：`pinia`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 14 | `isModeActive(mode) {` | 判断 `mode active` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 17 | `changeMode(mode) {` | 处理 `mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue`×3、`src/main/frontend/src/stores/shell.js` |
| 22 | `export function resolveInitialMode()` | 解析并确定 `initial mode` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/stores/workspace.js`

Pinia 状态模块；集中管理业务状态、异步流程和跨组件动作。 行数约 481。

**直接依赖**：`pinia`、`../services/api`、`../services/config`、`./chat`、`./model`、`./session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 7 | `function formatContextSize(chars)` | 格式化 `context size` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×5 |
| 18 | `function normalizeContextUsage(usage)` | 规范化 `context usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×2 |
| 54 | `function normalizeLightCompactResult(result)` | 规范化 `light compact result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 64 | `function arrayFromResponse(response)` | 处理 `from response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 80 | `function memoryFileGroup(file)` | 处理 `file group` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×2 |
| 91 | `function memoryFileName(file)` | 处理 `file name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 103 | `function normalizeMemoryFile(file)` | 规范化 `memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 122 | `function normalizeMemoryFiles(response)` | 规范化 `memory files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 126 | `function preferredMemoryFileId(files, currentId)` | 处理 `memory file id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 136 | `function memoryGroupOrder(group)` | 处理 `group order` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×2 |
| 176 | `workingDirectory() {` | 处理 `directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×19、`src/main/java/com/cc/springai/controller/MemoryController.java`×2、`src/main/java/com/cc/springai/controller/SkillController.java`、`src/main/java/com/cc/springai/service/TaskAgentService.java`×4、`src/main/java/com/cc/springai/tools/MemoryTools.java`×3、`src/main/java/com/cc/springai/tools/SkillsTools.java`×3 |
| 179 | `contextUsage() {` | 处理 `usage` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 182 | `contextPercent() {` | 处理 `percent` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 192 | `contextUsageLabel() {` | 处理 `usage label` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 195 | `contextUsagePillLabel() {` | 处理 `usage pill label` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 198 | `contextUsageDetail() {` | 处理 `usage detail` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 209 | `autoCompactionPercent() {` | 处理 `compaction percent` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 214 | `autoCompactionLabel() {` | 处理 `compaction label` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 221 | `autoCompactionPillLabel() {` | 处理 `compaction pill label` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 228 | `autoCompactionDetail() {` | 处理 `compaction detail` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 236 | `selectedMemoryFile() {` | 处理 `memory file` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 239 | `memoryFileOptions() {` | 处理 `file options` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 246 | `memoryFileGroups() {` | 处理 `file groups` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 259 | `filteredSkills() {` | 处理 `skills` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 269 | `selectedSkill() {` | 处理 `skill` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 274 | `contextParams() {` | 处理 `params` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×3 |
| 283 | `async loadContextUsage() {` | 加载 `context usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2、`src/main/frontend/src/stores/chat.js`×2、`src/main/frontend/src/stores/workspace.js` |
| 295 | `async openContextDrawer() {` | 打开 `context drawer` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 305 | `closeContextDrawer() {` | 处理 `context drawer` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 308 | `async compactContext(light = false) {` | 压缩 `context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2 |
| 344 | `async loadMcpServers() {` | 加载 `mcp servers` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/main.js`、`src/main/frontend/src/stores/workspace.js` |
| 351 | `async openMemory() {` | 打开 `memory` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 355 | `closeMemory() {` | 处理 `memory` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 358 | `async loadMemoryFiles() {` | 加载 `memory files` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×3 |
| 369 | `loadSelectedMemoryFile() {` | 加载 `selected memory file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/MemoryModal.vue`、`src/main/frontend/src/stores/workspace.js` |
| 373 | `async saveSelectedMemoryFile() {` | 保存 `selected memory file` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 387 | `async createMemory() {` | 创建 `memory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 396 | `async openSkills() {` | 打开 `skills` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 400 | `closeSkills() {` | 处理 `skills` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js` |
| 403 | `async loadSkills() {` | 加载 `skills` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/workspace.js`×3 |
| 416 | `async loadSelectedSkill() {` | 加载 `selected skill` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/SkillsModal.vue`、`src/main/frontend/src/stores/workspace.js` |
| 426 | `async renderSelectedSkill() {` | 渲染 `selected skill` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 433 | `async saveSelectedSkillFile() {` | 保存 `selected skill file` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 444 | `newSkillFile() {` | 新建 `skill file` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 454 | `async deleteSelectedSkill() {` | 删除 `selected skill` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 463 | `insertSkillInvocation() {` | 处理 `skill invocation` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 472 | `async copySkillPreview() {` | 处理 `skill preview` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 前端 `components/layout`

#### `src/main/frontend/src/components/layout/AppShell.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 24。

**直接依赖**：`./Sidebar.vue`、`./Topbar.vue`、`../chat/GameScore.vue`、`../chat/MessagesPanel.vue`、`../chat/Composer.vue`、`../../stores/session`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/layout/Sidebar.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 111。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../../stores/knowledge`、`../../stores/session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 11 | `function sessionPreview(session)` | 处理 `preview` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/layout/Sidebar.vue`×2 |

#### `src/main/frontend/src/components/layout/Topbar.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 91。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../../stores/migration`、`../../stores/model`、`../../stores/session`、`../../stores/workspace`、`../workspace/AgentWorkspace.vue`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

### 前端 `components/chat`

#### `src/main/frontend/src/components/chat/Composer.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 180。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../common/FloatingSelect.vue`、`../../stores/chat`、`../../stores/model`、`../../stores/session`、`../../stores/workspace`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 63 | `function resizeInput()` | 处理 `input` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`×2 |
| 77 | `function updatePrompt(event)` | 更新 `prompt` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 82 | `function submit()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/modals/AgentInteractionModal.vue`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/BasicTools.java` |
| 91 | `function handleKeydown(event)` | 处理 `keydown` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 98 | `function saveFinanceRetrievalStrategy()` | 保存 `finance retrieval strategy` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/components/chat/GameScore.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 20。

**直接依赖**：`vue`、`../../stores/session`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/chat/MessageBubble.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 498。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../../services/api`、`../../stores/chat`、`../../stores/workspace`、`../../utils/agent`、`../../utils/markdown`、`../../utils/session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 47 | `function isLiveMessage(message)` | 判断 `live message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×3 |
| 59 | `function valueSize(value)` | 处理 `size` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×5、`src/main/frontend/src/components/chat/MessagesPanel.vue`×5、`src/main/frontend/src/stores/MessagesPanel.vue`×5 |
| 115 | `function isAgentTracePinnedToBottom()` | 判断 `agent trace pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×3 |
| 123 | `function syncAgentTracePinnedToBottom()` | 处理 `agent trace pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 127 | `function scheduleAgentTraceScrollToBottom({ force = false } = {})` | 处理 `agent trace scroll to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×3 |
| 146 | `function stopTraceResizeObserver()` | 停止 `trace resize observer` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 185 | `function formatLatency(ms)` | 格式化 `latency` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×4 |
| 204 | `function formatCompactTokenCount(value)` | 格式化 `compact token count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×3 |
| 220 | `function tokenMetrics(message)` | 处理 `metrics` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 236 | `function formatTokenUsage(message)` | 格式化 `token usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 245 | `function formatTokenRate(message)` | 格式化 `token rate` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 256 | `function isFinanceTiming(message)` | 判断 `finance timing` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 260 | `function timingText(message)` | 处理 `text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 278 | `function retryMessage()` | 处理 `message` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 284 | `function roundProgress(progress)` | 处理 `progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×7 |
| 304 | `function toolOutputText(output)` | 处理 `output text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×4 |
| 308 | `function shouldCollapseToolResult(text)` | 判断是否应当 `collapse tool result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 313 | `function toolResultKey(batch, tool, toolIndex)` | 处理 `result key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×4 |
| 317 | `function isToolResultExpanded(key)` | 判断 `tool result expanded` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×3 |
| 321 | `function toggleToolResult(key)` | 处理 `tool result` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 331 | `function openInteraction(batch)` | 打开 `interaction` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 335 | `async function handleBubbleClick(event)` | 处理 `bubble click` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/components/chat/MessagesPanel.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 227。

**直接依赖**：`vue`、`../../stores/chat`、`../../stores/session`、`../../stores/workspace`、`./MessageBubble.vue`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 41 | `function valueSize(value)` | 处理 `size` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×5、`src/main/frontend/src/components/chat/MessagesPanel.vue`×5、`src/main/frontend/src/stores/MessagesPanel.vue`×5 |
| 55 | `function agentTraceSignal(message)` | 处理 `trace signal` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 110 | `function isElementPinnedToBottom()` | 判断 `element pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×2、`src/main/frontend/src/stores/MessagesPanel.vue`×2 |
| 118 | `function setPinnedToBottom(value)` | 设置 `pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×3、`src/main/frontend/src/stores/MessagesPanel.vue`×3 |
| 123 | `function syncPinnedToBottom()` | 处理 `pinned to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 127 | `function scheduleScrollToBottom({ force = false } = {})` | 处理 `scroll to bottom` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`×2、`src/main/frontend/src/stores/MessagesPanel.vue`×2 |
| 166 | `function submitSuggestion(prompt)` | 处理 `suggestion` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 175 | `function isLatestUserMessage(index)` | 判断 `latest user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 181 | `function retryUserMessage(message)` | 处理 `user message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |
| 191 | `function messageKey(message, index)` | 处理 `key` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessagesPanel.vue`、`src/main/frontend/src/stores/MessagesPanel.vue` |

### 前端 `components/common`

#### `src/main/frontend/src/components/common/AppIcon.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 151。

**直接依赖**：`vue`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/common/FloatingSelect.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 158。

**直接依赖**：`vue`、`./AppIcon.vue`、`../../composables/useFloatingSelect`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 71 | `function setOpen(value)` | 设置 `open` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue`×2 |
| 81 | `function chooseOption(option)` | 处理 `option` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue` |
| 90 | `function handleNativeChange(event)` | 处理 `native change` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 前端 `components/modals`

#### `src/main/frontend/src/components/modals/AgentInteractionModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 57。

**直接依赖**：`vue`、`../../stores/chat`、`../../stores/workspace`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 31 | `function submit(value = textValue.value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/modals/AgentInteractionModal.vue`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/BasicTools.java` |

#### `src/main/frontend/src/components/modals/ContextDrawer.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 42。

**直接依赖**：`../common/AppIcon.vue`、`../../stores/workspace`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/ImagePreviewModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 29。

**直接依赖**：`../common/AppIcon.vue`、`../../stores/chat`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/KnowledgeModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 43。

**直接依赖**：`../common/AppIcon.vue`、`../../stores/knowledge`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/MemoryModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 115。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../common/FloatingSelect.vue`、`../../stores/workspace`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/MigrationModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 80。

**直接依赖**：`vue`、`../../stores/migration`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 14 | `function chooseFile()` | 处理 `file` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 18 | `function handleFileChange(event)` | 处理 `file change` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/main/frontend/src/components/modals/ModelModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 124。

**直接依赖**：`../common/AppIcon.vue`、`../common/FloatingSelect.vue`、`../../stores/model`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/SkillsModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 97。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../../stores/workspace`。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/main/frontend/src/components/modals/WorkspaceModal.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 82。

**直接依赖**：`vue`、`../common/FloatingSelect.vue`、`../../services/config`、`../../utils/session`、`../../stores/session`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 29 | `function chooseApprovalMode(value)` | 处理 `approval mode` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 34 | `function submit()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/Composer.vue`、`src/main/frontend/src/components/modals/AgentInteractionModal.vue`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/main/java/com/cc/springai/tools/BasicTools.java` |

### 前端 `components/workspace`

#### `src/main/frontend/src/components/workspace/AgentWorkspace.vue`

Vue 展示/交互组件；通过 Store 或 API 与应用状态及后端交互。 行数约 269。

**直接依赖**：`vue`、`../common/AppIcon.vue`、`../common/FloatingSelect.vue`、`../../services/api`、`../../services/config`、`../../utils/session`、`../../stores/session`、`../../stores/workspace`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 34 | `function updateWorkingDirectory(value)` | 更新 `working directory` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 42 | `async function saveApprovalSettings()` | 保存 `approval settings` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 63 | `async function saveWorkspaceSettings()` | 保存 `workspace settings` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 68 | `function mcpTools(server)` | 处理 `tools` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×3 |
| 72 | `function mcpLoadStateText(server)` | 处理 `load state text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2 |
| 85 | `function mcpToolLabel(tool)` | 处理 `tool label` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 89 | `function mcpDisplayName(server)` | 处理 `display name` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue`×2 |
| 93 | `function mcpMeta(server)` | 处理 `meta` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 102 | `function mcpToolPreview(server)` | 处理 `tool preview` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 109 | `function mcpTooltip(server)` | 处理 `tooltip` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |
| 131 | `function mcpReady(server)` | 处理 `ready` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/workspace/AgentWorkspace.vue` |

### 前端 `composables`

#### `src/main/frontend/src/composables/useFloatingSelect.js`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 91。

**直接依赖**：`vue`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 2 | `export function useFloatingSelect(root = ref(null)` | 创建并返回可复用的组合式状态/行为，供 Vue 组件调用。 | `src/main/frontend/src/components/common/FloatingSelect.vue` |
| 10 | `function close()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/common/FloatingSelect.vue`、`src/main/frontend/src/composables/useFloatingSelect.js`、`src/main/java/com/cc/springai/tools/BasicTools.java`、`src/test/java/com/cc/springai/SpringaiApplicationTests.java`×2 |
| 14 | `function updatePlacement()` | 更新 `placement` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/composables/useFloatingSelect.js` |
| 46 | `function toggle()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 53 | `function handleOutsideClick(event)` | 处理 `outside click` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 59 | `function handleViewportChange(event)` | 处理 `viewport change` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

### 前端 `utils`

#### `src/main/frontend/src/utils/agent.js`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 242。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 1 | `export function parseToolArguments(argumentsText)` | 解析 `tool arguments` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×5、`src/main/frontend/src/utils/agent.js` |
| 11 | `export function renderToolOutputText(value)` | 渲染 `tool output text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/utils/agent.js`×3 |
| 16 | `const decodeControlEscapes = (content) =>` | 处理 `control escapes` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/agent.js` |
| 41 | `export function taskRoleLabel(role)` | 处理 `role label` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2 |
| 51 | `export function toolBatchStatusText(status)` | 处理 `batch status text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/utils/agent.js` |
| 67 | `export function taskStatusLabel(batchStatus, output)` | 处理 `status label` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 84 | `export function parseTaskOutput(output)` | 解析 `task output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2、`src/main/frontend/src/utils/agent.js` |
| 117 | `function taskIdFromTool(tool)` | 处理 `id from tool` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/agent.js`×3 |
| 128 | `function mergeToolBatchesWithExistingProgress(existingBatches, executionBatches)` | 合并 `tool batches with existing progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/agent.js` |
| 158 | `export function toolExecutionKey(tool)` | 处理 `execution key` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 162 | `export function mergeToolBatchesPreservingHistory(existingBatches = [], executionBatches = [])` | 合并 `tool batches preserving history` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×3 |
| 187 | `export function applyTaskProgress(message, data)` | 应用 `task progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 202 | `export function applyAgentRoundProgress(message, data)` | 应用 `agent round progress` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js` |
| 227 | `export function agentRoundProgressStatusText(status)` | 处理 `round progress status text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/stores/chat.js` |

#### `src/main/frontend/src/utils/markdown.js`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 171。

**直接依赖**：`katex`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 6 | `export function escapeHtml(value)` | 处理 `html` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×9 |
| 15 | `function isImageUrl(url)` | 判断 `image url` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×2 |
| 19 | `function localFileUrl(path)` | 处理 `file url` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js` |
| 23 | `function stashHtml(html, stash)` | 处理 `html` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×7 |
| 29 | `function renderMathFormula(source, displayMode)` | 渲染 `math formula` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×4 |
| 42 | `function renderInline(value)` | 渲染 `inline` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×3 |
| 73 | `export function renderMarkdown(value)` | 渲染 `markdown` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |
| 86 | `const flushParagraph = () =>` | 处理 `paragraph` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×4 |
| 93 | `const flushList = () =>` | 处理 `list` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js`×3 |
| 99 | `const flushCode = () =>` | 处理 `code` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/markdown.js` |
| 153 | `export function plainTextPreview(value, length = 80)` | 处理 `text preview` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 158 | `export function tailTextPreview(value, length = 96)` | 处理 `text preview` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue` |

#### `src/main/frontend/src/utils/session.js`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 270。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 7 | `export function createId()` | 创建 `id` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/session.js`×3 |
| 11 | `export function normalizeApprovalMode(value)` | 规范化 `approval mode` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/WorkspaceModal.vue`×2、`src/main/frontend/src/components/workspace/AgentWorkspace.vue`、`src/main/frontend/src/stores/chat.js`×4、`src/main/frontend/src/stores/session.js`、`src/main/frontend/src/utils/session.js`×2 |
| 15 | `export function normalizeSandboxEnabled(value, approvalMode = "work-auto")` | 规范化 `sandbox enabled` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/modals/WorkspaceModal.vue`×3、`src/main/frontend/src/components/workspace/AgentWorkspace.vue`、`src/main/frontend/src/stores/chat.js`、`src/main/frontend/src/stores/session.js`、`src/main/frontend/src/utils/session.js` |
| 22 | `export function normalizeReasoningEffort(value)` | 规范化 `reasoning effort` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 27 | `export function normalizeThinkingType(value)` | 规范化 `thinking type` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/model.js`、`src/main/frontend/src/utils/session.js`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2 |
| 32 | `export function normalizeLightCompactionHours(value)` | 规范化 `light compaction hours` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/session.js` |
| 40 | `export function normalizedTimestamp(value)` | 处理 `timestamp` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/session.js`×11 |
| 45 | `export function createSession(mode)` | 创建 `session` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js`×3、`src/main/frontend/src/utils/session.js` |
| 72 | `export function normalizeSession(session)` | 规范化 `session` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 102 | `export function normalizeActiveSessionIds(value)` | 规范化 `active session ids` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js`×2 |
| 111 | `export function normalizeMessage(message)` | 规范化 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/utils/session.js`×2 |
| 144 | `export function userMessage(text, attachments = [])` | 创建并返回可复用的组合式状态/行为，供 Vue 组件调用。 | `src/main/frontend/src/stores/chat.js` |
| 153 | `export function assistantMessage()` | 处理 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2、`src/main/java/com/cc/springai/controller/AgentController.java`×2 |
| 162 | `export function serializableSessions(sessions)` | 处理 `sessions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/session.js`×2 |
| 173 | `export function validLatencyMs(value)` | 处理 `latency ms` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×2、`src/main/frontend/src/utils/session.js`×6 |
| 178 | `export function validTokenCount(value)` | 处理 `token count` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`×4、`src/main/frontend/src/utils/session.js`×5 |
| 183 | `export function normalizeTokenUsage(value)` | 规范化 `token usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/utils/session.js`×2 |
| 199 | `export function applyTokenUsage(message, tokenUsage)` | 应用 `token usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×5 |
| 206 | `export function finishMessageLatency(message, serverLatencyMs = null)` | 处理 `message latency` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×13 |
| 219 | `export function prepareReasoningDisplay(message, wantsReasoning)` | 处理 `reasoning display` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×4 |
| 232 | `export function finishReasoningDisplay(message)` | 处理 `reasoning display` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×12 |
| 238 | `export function appendReasoning(message, content)` | 处理 `reasoning` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×3 |
| 249 | `export function markTokenOutput(message, content)` | 处理 `token output` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 260 | `export function estimateTokensFromText(text)` | 估算 `tokens from text` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/frontend/src/stores/chat.js`×2、`src/main/frontend/src/utils/session.js` |

#### `src/main/frontend/src/utils/sse.js`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 50。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 1 | `export async function readSse(response, onEvent)` | 读取 `sse` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/frontend/src/stores/chat.js`×2 |
| 10 | `const processLine = (line) =>` | 处理 `line` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

## 8. 辅助与测试逐文件逐函数解析

### Python MCP

#### `src/main/resources/mcp/email/email_mcp_server.py`

独立 Python 辅助程序，用于 MCP 服务或 Agent 评测。 行数约 312。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 42 | `def _load_config_file() -> dict[str, Any]:` | 加载 `config file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py` |
| 52 | `def _env_or_file(name: str, config: dict[str, Any], default: Any = "") -> Any:` | 处理 `or file` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×9 |
| 62 | `def _config_keys(name: str) -> list[str]:` | 处理 `keys` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py` |
| 74 | `def _parse_bool(value: Any, default: bool = False) -> bool:` | 解析 `bool` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×2 |
| 82 | `def _parse_int(value: Any, default: int) -> int:` | 解析 `int` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×2 |
| 91 | `def load_config() -> MailConfig:` | 加载 `config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×3 |
| 129 | `def sanitize_config(config: MailConfig) -> dict[str, Any]:` | 处理 `config` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py` |
| 143 | `def parse_recipients(value: str) -> list[str]:` | 解析 `recipients` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×8 |
| 149 | `def add_attachment(message: EmailMessage, attachment_path: str) -> None:` | 处理 `attachment` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×2 |
| 164 | `def build_message( config: MailConfig, to: str, subject: str, body: str, body_format: str = "text", cc: str = "", bcc: str = "", reply_to: str = "", attachments: str = "", ) -> tu…` | 构造 `message` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py` |
| 210 | `def open_smtp_connection(config: MailConfig) -> smtplib.SMTP:` | 打开 `smtp connection` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/mcp/email/email_mcp_server.py`×2 |
| 234 | `def get_mail_config() -> dict[str, Any]:` | 读取 `mail config` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 240 | `def test_smtp_connection() -> dict[str, Any]:` | 处理 `smtp connection` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 261 | `def send_email( to: str, subject: str, body: str, body_format: str = "text", cc: str = "", bcc: str = "", reply_to: str = "", attachments: str = "", ) -> dict[str, Any]:` | 发送 `email` 相关数据或流程；具体参数和返回类型见签名。 | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 304 | `def main() -> None:` | 应用或脚本入口；完成初始化并把控制权交给框架/主流程。 | 名称过于通用，需结合所属对象和调用链判断 |

### Agent 评测脚本

#### `src/main/resources/agent_test/evaluate_agent.py`

独立 Python 辅助程序，用于 MCP 服务或 Agent 评测。 行数约 601。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 32 | `def main() -> int:` | 应用或脚本入口；完成初始化并把控制权交给框架/主流程。 | 名称过于通用，需结合所属对象和调用链判断 |
| 101 | `def run_task(task: dict[str, Any], workspace: Path, args: argparse.Namespace) -> dict[str, Any]:` | 运行 `task` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 189 | `def build_agent_prompt(task: dict[str, Any]) -> str:` | 构造 `agent prompt` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 200 | `def post_agent_stream( url: str, *, form: dict[str, str] \| None = None, json_body: dict[str, Any] \| None = None, timeout: int, ) -> dict[str, Any]:` | 处理 `agent stream` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。 | `src/main/resources/agent_test/evaluate_agent.py`×2 |
| 262 | `def dispatch_sse_event(event_name: str, data_lines: list[str]) -> dict[str, Any] \| None:` | 处理 `sse event` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py`×2 |
| 273 | `def collect_executions(phases: list[dict[str, Any]], final_payload: dict[str, Any]) -> list[dict[str, Any]]:` | 处理 `executions` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 299 | `def count_model_steps(phases: list[dict[str, Any]]) -> int:` | 统计 `model steps` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 310 | `def collect_token_usage( phases: list[dict[str, Any]], final_payload: dict[str, Any], prompt: str, final_content: str, ) -> tuple[dict[str, int], str]:` | 处理 `token usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 338 | `def normalize_usage(value: Any) -> dict[str, int] \| None:` | 规范化 `usage` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 353 | `def int_or_zero(value: Any) -> int:` | 处理 `or zero` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py`×3 |
| 361 | `def estimate_tokens(text: str) -> int:` | 估算 `tokens` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py`×2 |
| 369 | `def run_expected_checks(task: dict[str, Any], workspace: Path, final_content: str) -> dict[str, Any]:` | 运行 `expected checks` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 384 | `def judge_response( task: dict[str, Any], workspace: Path, final_content: str, executions: list[dict[str, Any]], expected_checks: dict[str, Any], args: argparse.Namespace, ) -> di…` | 处理 `response` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 446 | `def call_mimo_chat(base_url: str, model: str, api_key: str, messages: list[dict[str, str]]) -> dict[str, Any]:` | 调用 `mimo chat` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 471 | `def parse_json_object(text: str) -> dict[str, Any]:` | 解析 `json object` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 481 | `def workspace_snapshot(workspace: Path, max_files: int = 20, max_chars_per_file: int = 2000) -> dict[str, str]:` | 处理 `snapshot` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 491 | `def task_completed(status: str, expected_checks: dict[str, Any], judge: dict[str, Any] \| None) -> bool:` | 处理 `completed` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 501 | `def build_summary(dataset: dict[str, Any], results: list[dict[str, Any]], args: argparse.Namespace) -> dict[str, Any]:` | 构造 `summary` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 528 | `def render_markdown_report(report: dict[str, Any]) -> str:` | 渲染 `markdown report` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 558 | `def format_task_line(result: dict[str, Any]) -> str:` | 格式化 `task line` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 567 | `def prepare_workspace(workspace: Path, setup_files: dict[str, str]) -> None:` | 处理 `workspace` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 577 | `def safe_child(root: Path, relative: str) -> Path:` | 处理 `child` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 585 | `def read_json(path: Path) -> dict[str, Any]:` | 读取 `json` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |
| 589 | `def clamp_score(value: Any) -> float:` | 处理 `score` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/resources/agent_test/evaluate_agent.py` |

### 测试代码

#### `src/test/java/com/cc/springai/controller/AgentControllerTest.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 415。

**直接依赖**：`com.cc.springai.agent.AgentChatRequest`、`com.cc.springai.agent.AgentToolOptionsFactory`、`com.cc.springai.agent.AgentSandboxContext`、`com.cc.springai.agent.AgentToolPolicy`、`com.cc.springai.agent.ExecutedTool`、`com.cc.springai.agent.ExecutedToolBatch`、`com.cc.springai.agent.InteractionRequest`、`com.cc.springai.agent.ToolRequest`、`com.cc.springai.config.ShellToolConfiguration`、`com.cc.springai.registry.McpToolRegistry`、`com.cc.springai.service.ConversationCompactionService`、`com.cc.springai.service.MemoryService`、`com.cc.springai.service.RagService`、`com.cc.springai.service.TaskAgentService`、`com.cc.springai.skill.AgentSkillService`、`com.cc.springai.tools.BasicTools`、`com.cc.springai.tools.CalculationTools`、`com.cc.springai.tools.DateTimeTools`、`com.cc.springai.tools.MemoryTools`、`com.cc.springai.tools.RagTools`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 50 | `void setUp()` | 设置 `up` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@BeforeEach` | `src/test/java/com/cc/springai/service/MemoryServiceTest.java` |
| 62 | `void readOnlyToolsDoNotRequireApproval()` | 读取 `only tools do not require approval` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 75 | `void mutatingOrUnknownToolsRequireApproval()` | 处理 `or unknown tools require approval` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 91 | `void autoApprovalModeDoesNotRequireApprovalForAnyTool()` | 处理 `approval mode does not require approval for any tool` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 100 | `void workAutoAllowsWorkspaceScopedMutations()` | 处理 `auto allows workspace scoped mutations` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 110 | `void workAutoRequiresApprovalForCrossWorkspaceOperations()` | 处理 `auto requires approval for cross workspace operations` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 123 | `void invalidBackgroundWriteTaskDoesNotRequireApprovalBecauseToolRejectsIt()` | 处理 `background write task does not require approval because tool rejects it` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 131 | `void askUserChoiceInteractionIncludesOtherOptionForCustomInput()` | 处理 `user choice interaction includes other option for custom input` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 150 | `void askUserWithoutOptionsFallsBackToTextInput()` | 处理 `user without options falls back to text input` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 165 | `void ragSearchStopsAfterConsecutiveSearchBudgetIsUsed()` | 处理 `search stops after consecutive search budget is used` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 190 | `void nonRagSearchToolResetsConsecutiveSearchBudget()` | 处理 `rag search tool resets consecutive search budget` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 216 | `void toolRoundLimitGrowsByHalfUntilHardLimit()` | 处理 `round limit grows by half until hard limit` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 228 | `void ragSearchToolIsUnavailableWhenConversationHasNoKnowledgeDocuments()` | 处理 `search tool is unavailable when conversation has no knowledge documents` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 240 | `void ragSearchToolIsAvailableWhenConversationHasKnowledgeDocuments()` | 处理 `search tool is available when conversation has knowledge documents` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 254 | `void taskToolIsAvailableToParentAgent()` | 处理 `tool is available to parent agent` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 266 | `void userMessageKeepsOriginalPromptText()` | 处理 `message keeps original prompt text` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 281 | `void userMessageWithFilesIncludesFileListAndUserInstruction()` | 处理 `message with files includes file list and user instruction` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 302 | `void workingDirectoryIsInjectedAsSystemContext()` | 处理 `directory is injected as system context` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 326 | `void lightCompactionWhitelistKeepsReadMemoryOutput()` | 处理 `compaction whitelist keeps read memory output` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 346 | `void lightCompactionReplacesOnlyWhenPlaceholderIsShorter()` | 处理 `compaction replaces only when placeholder is shorter` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 364 | `void knowledgeContextMentionsUploadedFileNames()` | 处理 `context mentions uploaded file names` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 380 | `private AgentController configuredController(RagService ragService)` | 处理 `controller` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/controller/AgentControllerTest.java`×5 |
| 403 | `private AgentToolPolicy agentToolPolicy(TaskTools taskTools)` | 处理 `tool policy` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@SuppressWarnings("unchecked")` | `src/test/java/com/cc/springai/controller/AgentControllerTest.java`×2 |
| 409 | `private List<String> toolNames(OpenAiChatOptions options)` | 处理 `names` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/controller/AgentController.java`×2、`src/main/java/com/cc/springai/service/TaskAgentService.java`×2、`src/test/java/com/cc/springai/controller/AgentControllerTest.java`×3 |

#### `src/test/java/com/cc/springai/controller/FileOpenControllerTest.java`

HTTP 适配层：接收参数、调用业务服务，并把结果转换成 JSON、文本或 SSE 响应。 行数约 57。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 15 | `void invalidPathReturnsFailureResponseInsteadOfThrowing()` | 处理 `path returns failure response instead of throwing` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 27 | `void resolveFileAcceptsDirectoryPaths() throws Exception` | 解析并确定 `file accepts directory paths` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 39 | `void windowsOpenCommandSelectsFileOrDirectoryForegroundFriendly() throws Exception` | 处理 `open command selects file or directory foreground friendly` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 400。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 25 | `void compactsOldMessagesAndKeepsRecentMessages()` | 处理 `old messages and keeps recent messages` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 51 | `void doesNotCompactSmallHistory()` | 处理 `not compact small history` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 66 | `void manualCompactionCanCompactBeforeThreshold()` | 处理 `compaction can compact before threshold` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 82 | `void manualCompactionUsesProvidedModelWhenPresent()` | 处理 `compaction uses provided model when present` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 99 | `void compactionPromptIncludesToolCallsAndResponsesWhenTextIsEmpty()` | 处理 `prompt includes tool calls and responses when text is empty` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 138 | `void compactionDoesNotSplitToolCallFromResponseAtBoundary()` | 处理 `does not split tool call from response at boundary` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 179 | `void estimatesUsageAgainstTokenBudget()` | 处理 `usage against token budget` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 197 | `void estimatesAutoCompactionProgress()` | 处理 `auto compaction progress` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 215 | `void autoCompactionProgressReportsReadyAfterThreshold()` | 处理 `compaction progress reports ready after threshold` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 230 | `void replacesExistingSummaryInsteadOfStackingSummaries()` | 处理 `existing summary instead of stacking summaries` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 249 | `void compactionUsageCanBeEstimatedFromCompactedHistory()` | 处理 `usage can be estimated from compacted history` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 267 | `void fallsBackToLocalSummaryWhenModelFails()` | 处理 `back to local summary when model fails` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 285 | `void estimatesLightCompactedToolResponsesWithoutChangingChatMemory()` | 处理 `light compacted tool responses without changing chat memory` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 305 | `void doesNotCountShortOutputWhenPlaceholderWouldBeLonger()` | 处理 `not count short output when placeholder would be longer` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 322 | `void doesNotCountWhitelistedReadMemoryOutputAsLightCompactionTarget()` | 处理 `not count whitelisted read memory output as light compaction target` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 338 | `private List<Message> numberedMessages(int count)` | 处理 `messages` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×9 |
| 353 | `public void add(String conversationId, List<Message> messages)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `org/springframework/ai/openai/OpenAiChatModel.java`、`src/main/frontend/src/components/chat/MessageBubble.vue`、`src/main/java/com/cc/springai/agent/AgentToolOptionsFactory.java`×2、`src/main/java/com/cc/springai/agent/TokenUsage.java`×3、`src/main/java/com/cc/springai/controller/AgentController.java`×53、`src/main/java/com/cc/springai/controller/McpController.java`×2、`src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/embedding/OllamaLegacyEmbeddingModel.java` |
| 358 | `public List<Message> get(String conversationId)` | 读取 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 363 | `public void clear(String conversationId)` | 清理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | `src/main/java/com/cc/springai/controller/SessionController.java`、`src/main/java/com/cc/springai/service/ChatMemoryRewindService.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`、`src/main/java/com/cc/springai/service/ModelConfigService.java`×2、`src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java` |
| 375 | `private RecordingChatModel(String response)` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/ConversationCompactionServiceTest.java`×16 |
| 380 | `public ChatResponse call(Prompt prompt)` | 调用 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 名称过于通用，需结合所属对象和调用链判断 |
| 390 | `public Flux<ChatResponse> stream(Prompt prompt)` | 流式处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@Override` | `org/springframework/ai/openai/api/OpenAiApi.java`×3、`org/springframework/ai/openai/OpenAiChatModel.java`×10、`src/main/java/com/cc/springai/agent/AgentToolPolicy.java`×5、`src/main/java/com/cc/springai/controller/AgentController.java`×31、`src/main/java/com/cc/springai/controller/ChatController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×3、`src/main/java/com/cc/springai/registry/SpringAiMcpToolRegistry.java`、`src/main/java/com/cc/springai/service/ConversationCompactionService.java`×10 |
| 395 | `public ChatOptions getDefaultOptions()` | 读取 `default options` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Override` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/service/FinancialRagServiceTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 66。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 27 | `void parsesRetrievalPlanWithResolvedQuestionIntentAndSubQueries()` | 处理 `retrieval plan with resolved question intent and sub queries` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 54 | `void malformedPlannerOutputFallsBackToExpandedQuestion()` | 处理 `planner output falls back to expanded question` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 80。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 20 | `void preservesToolCallsAndToolResponsesAcrossReload()` | 处理 `tool calls and tool responses across reload` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 66 | `void readsLegacyToolRowsAsToolResponseMessages()` | 处理 `legacy tool rows as tool response messages` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 76 | `private JdbcChatMemoryRepository repository()` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/JdbcChatMemoryRepositoryTest.java`×2 |

#### `src/test/java/com/cc/springai/service/MemoryServiceTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 185。

未声明具名函数；该文件主要是声明式模板、常量、数据结构，或由框架/构建工具处理。

#### `src/test/java/com/cc/springai/service/MemoryServiceTimeTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 119。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 22 | `void setUp() throws IOException` | 设置 `up` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@BeforeEach` | `src/test/java/com/cc/springai/service/MemoryServiceTest.java` |
| 28 | `void tearDown() throws IOException` | 处理 `down` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@AfterEach` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 43 | `void saveMemoryWritesSavedAtIntoIndexAndMemoryFile() throws Exception` | 保存 `memory writes saved at into index and memory file` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 68 | `void saveMemoryKeepsSavedAtAtMinutePrecision()` | 保存 `memory keeps saved at at minute precision` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 83 | `void saveMemoryPreservesMemoryIndexFormatSectionAboveIndexSection() throws Exception` | 保存 `memory preserves memory index format section above index section` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/service/RagEvaluationTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 269。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 33 | `void evaluatesRagRetrievalAndWritesReport() throws IOException` | 处理 `rag retrieval and writes report` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 66 | `private List<EvaluationCase> readEvaluationCases()` | 读取 `evaluation cases` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 94 | `private EvaluationResult score(EvaluationCase evaluationCase, List<RagService.RagChunk> chunks)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/FinancialRagService.java`×2、`src/main/java/com/cc/springai/tools/RagTools.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 117 | `private boolean containsEvidence(String text, String evidence)` | 处理 `evidence` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java`×2 |
| 121 | `private String normalize(String value)` | 规范化 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/agent/AgentSandboxContext.java`×7、`src/main/java/com/cc/springai/controller/FileOpenController.java`、`src/main/java/com/cc/springai/controller/McpController.java`×5、`src/main/java/com/cc/springai/service/MemoryService.java`×10、`src/main/java/com/cc/springai/skill/AgentSkillService.java`×10、`src/main/java/com/cc/springai/tools/BasicTools.java`×5、`src/test/java/com/cc/springai/controller/FileOpenControllerTest.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java`×2 |
| 125 | `private String preview(String value)` | 处理 `当前对象/请求` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 130 | `private void writeReport(List<EvaluationResult> results) throws IOException` | 写入 `report` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 137 | `private String buildMarkdownReport(List<EvaluationResult> results)` | 构造 `markdown report` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 187 | `private String buildCsvReport(List<EvaluationResult> results)` | 构造 `csv report` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 205 | `private String formatRate(long numerator, long denominator)` | 格式化 `rate` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java`×3 |
| 212 | `private String escapeMarkdown(String value)` | 处理 `markdown` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java`×4 |
| 216 | `private String toCsv(String value)` | 转换 `csv` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java`×4 |
| 221 | `private List<String> parseCsvLine(String line)` | 解析 `csv line` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 247 | `private record EvaluationCase( String id, String type, String question, String expectedAnswer, String evidence )` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |
| 254 | `private boolean isNoAnswerCase()` | 判断 `no answer case` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java`×8 |
| 259 | `private record EvaluationResult( EvaluationCase evaluationCase, int returnedChunks, int retrievalScore, boolean firstHit, boolean anyHit, String firstChunkPreview )` | 构造实例并注入其依赖；建立该类后续操作所需的初始状态。 | `src/test/java/com/cc/springai/service/RagEvaluationTest.java` |

#### `src/test/java/com/cc/springai/service/TaskAgentServiceTest.java`

业务服务层：承载核心用例、数据访问编排或模型调用。 行数约 57。

**直接依赖**：`com.cc.springai.tools.BasicTools`、`com.cc.springai.tools.CalculationTools`、`com.cc.springai.tools.DateTimeTools`、`com.cc.springai.tools.RagTools`、`com.cc.springai.config.ShellToolConfiguration`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 28 | `void capsMaxRoundsByRole()` | 处理 `max rounds by role` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 37 | `void defaultsMaxRoundsToTen()` | 处理 `max rounds to ten` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 43 | `void onlyMutationCapableRolesRequireApproval()` | 处理 `mutation capable roles require approval` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 52 | `void backgroundMutationRolesAreAutoApprovableBecauseTheyAreRejectedByTool()` | 处理 `mutation roles are auto approvable because they are rejected by tool` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/skill/AgentSkillServiceTest.java`

Skill 子系统：发现、读取、渲染或维护项目级/用户级技能文件。 行数约 135。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 17 | `void discoversProjectClaudeSkillsAndBuildsCatalog() throws Exception` | 处理 `project claude skills and builds catalog` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 40 | `void rendersDirectInvocationWithArguments() throws Exception` | 处理 `direct invocation with arguments` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 66 | `void modelDisabledSkillIsHiddenFromCatalogButCanBeUserInvoked() throws Exception` | 处理 `disabled skill is hidden from catalog but can be user invoked` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 86 | `void rendersIndexedArgumentsBeforeFullArguments() throws Exception` | 处理 `indexed arguments before full arguments` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 110 | `void writesAndDeletesProjectSkillFiles() throws Exception` | 处理 `and deletes project skill files` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/SpringaiApplicationTests.java`

测试代码：验证对应生产模块的正常路径、边界条件或回归行为。 行数约 197。

**直接依赖**：`com.cc.springai.utils.VectorUtils`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 43 | `void calculatesDistancesBetweenTextAndCandidates()` | 处理 `distances between text and candidates` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 70 | `public void testVectorStore(@TempDir Path tempDir) throws IOException` | 处理 `vector store` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 122 | `private Path convertPomToPdf(Path tempDir) throws IOException` | 转换 `pom to pdf` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/SpringaiApplicationTests.java` |
| 151 | `private PDPageContentStream startNewPage(PDDocument document) throws IOException` | 启动 `new page` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/SpringaiApplicationTests.java`×2 |
| 163 | `public void quickSort(int[] array, int left, int right)` | 处理 `sort` 相关数据或流程；具体参数和返回类型见签名。 | `src/test/java/com/cc/springai/SpringaiApplicationTests.java`×3 |
| 185 | `void testQuickSort()` | 处理 `quick sort` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/tools/BasicToolsTest.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 97。

**直接依赖**：`com.cc.springai.config.ShellToolConfiguration`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 19 | `void decodesGbkShellOutputWhenUtf8WouldBeMojibake()` | 处理 `gbk shell output when utf8 would be mojibake` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 28 | `void decodesUtf16LittleEndianShellOutputWithoutBom()` | 处理 `utf16 little endian shell output without bom` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 37 | `void routesCmdCommandsToCmdHostSoAmpersandsAreNotParsedByPowerShell()` | 处理 `cmd commands to cmd host so ampersands are not parsed by power shell` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 53 | `void writesPowerShellScriptsWithUtf8BomForWindowsPowerShellCompatibility() throws Exception` | 处理 `power shell scripts with utf8 bom for windows power shell compatibility` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 69 | `void windowsPowerShellBootstrapSilencesProgressStreams()` | 处理 `power shell bootstrap silences progress streams` 相关数据或流程；具体参数和返回类型见签名。 该函数位于流式/SSE 链路中。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 79 | `void stripsPowerShellCliXmlProgressFromShellOutput()` | 处理 `power shell cli xml progress from shell output` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/tools/CalculationToolsTest.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 42。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 12 | `void evaluatesArithmeticExpressionsWithPrecedenceAndPowers()` | 处理 `arithmetic expressions with precedence and powers` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 20 | `void evaluatesDivisionWithUsefulPrecision()` | 处理 `division with useful precision` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 26 | `void calculatesArbitraryPrecisionNumbersExactly()` | 处理 `arbitrary precision numbers exactly` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 35 | `void returnsReadableErrorsForInvalidCalculations()` | 处理 `readable errors for invalid calculations` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |

#### `src/test/java/com/cc/springai/tools/RagToolsTest.java`

Agent 工具层：把本地能力封装为可供模型选择和调用的工具。 行数约 83。

**直接依赖**：`com.cc.springai.service.RagService`。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 21 | `void searchesEachPipeSeparatedQuery()` | 处理 `each pipe separated query` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 45 | `void ignoresBlankBatchItems()` | 处理 `blank batch items` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 63 | `void keepsSingleQueryResponseFormat()` | 处理 `single query response format` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 79 | `private ToolContext toolContext()` | 处理 `context` 相关数据或流程；具体参数和返回类型见签名。 | `src/main/java/com/cc/springai/service/TaskAgentService.java`、`src/test/java/com/cc/springai/tools/RagToolsTest.java`×3 |

#### `src/test/java/com/cc/springai/utils/VectorUtilsTest.java`

前端通用逻辑；提供格式化、解析、会话归一化、SSE 或组合式交互能力。 行数约 43。

| 行 | 函数/方法 | 作用 | 调用/关联文件 |
|---:|---|---|---|
| 11 | `void calculatesEuclideanDistance()` | 处理 `euclidean distance` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 20 | `void returnsZeroForEqualVectors()` | 处理 `zero for equal vectors` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 28 | `void rejectsVectorsWithDifferentDimensions()` | 处理 `vectors with different dimensions` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |
| 37 | `void rejectsNullVectors()` | 处理 `null vectors` 相关数据或流程；具体参数和返回类型见签名。；框架标记：`@Test` | 未发现直接静态调用；可能由 Spring/Vue/模型工具反射调用或仅在本文件内部声明 |


## 9. 配置、构建与非业务文件

### `pom.xml`

定义 Java 17/Spring Boot/Spring AI 版本和 Web、OpenAI、MySQL、MyBatis-Plus、PDF Reader、pgvector、MCP Client 等依赖。`spring-boot-maven-plugin` 负责打包可执行应用。根目录 `org/springframework/...` 源码与依赖中的同名全限定类重合，编译时项目源码优先，实质上是对 Spring AI 1.1.6 行为的本地覆盖；升级 Spring AI 时必须重点做差异合并。

### `application*.yaml`

`application.yaml` 是主配置，绑定数据库、OpenAI、embedding、pgvector、上传和 MCP 等参数；`application-docker.yaml` 覆盖容器网络地址；`application-email-mcp.yaml` 与 `application-utools-mcp.yaml` 增加特定 MCP Server。敏感值应来自环境变量，不能把真实密钥提交到仓库。

### Vue/Vite 构建

`src/main/frontend/package.json` 定义 Vue、Pinia、KaTeX 和 Vite；`vite.config.js` 把开发请求代理给 Spring Boot，并把生产包输出到 `src/main/resources/static/vue`。`static/vue/index.html` 与带 hash 的 CSS/JS 是构建产物；其逻辑来自 `src/main/frontend/src`，不应直接修改。`src/main/frontend/src/stores/MessagesPanel.vue` 与 `components/chat/MessagesPanel.vue` 内容/导入路径表现为误放副本，正常入口使用后者。

### Docker

`Dockerfile` 打包并运行 Spring Boot；`docker-compose.yml` 编排应用、MySQL、PostgreSQL/pgvector 等依赖；`.env` 提供环境变量。`DOCKER.md` 记录构建与运行方法。

## 10. 数据与状态关系

- **MySQL/JdbcTemplate**：会话 JSON、模型配置、ChatMemory、自建金融 RAG 元数据等。
- **PostgreSQL/pgvector**：普通 PDF RAG 子块向量；通过 `VectorStore` 检索。
- **文件系统**：工作区、Memory Markdown、`.claude/skills` 或兼容 Skill 目录、Agent 工具读写目标。
- **浏览器状态**：Pinia 运行时状态与 localStorage 迁移数据；最终会话可同步到后端。
- **进程内状态**：Agent 待审批 run、交互请求、子任务进度、模型实例缓存；重启后这类未持久化状态会丢失。

## 11. 关键设计点与维护风险

1. `AgentController` 超过两千行，同时负责 HTTP、SSE、Agent 循环、审批、上下文构建和状态缓存，是首要拆分候选。建议拆为 `AgentRunService`、`AgentSseWriter`、`AgentContextAssembler`、`PendingRunRepository`。
2. 项目覆盖了 Spring AI 同名类，能够快速兼容供应商扩展参数，但依赖升级时容易产生二进制/API 偏差；应保存上游版本和补丁说明。
3. 文件工具具有系统读写/执行能力，安全边界依赖 `AgentSandboxContext`、工作目录校验和 `AgentToolPolicy`；新增工具必须同时登记读写属性与审批策略。
4. SSE 事件协议由后端事件名和前端 `chat` Store 分支共同定义，修改任一侧都要同步并加契约测试。
5. RAG 有普通知识库和金融知识库两条实现，数据结构、召回和重排策略不同，不应误合并为单一 Service。
6. 前端核心业务集中在 `chat.js` 和 `workspace.js`，组件主要负责呈现；排查消息状态问题应先看 Store，而不是只看 `MessageBubble.vue`。
7. `stores/MessagesPanel.vue` 疑似无效副本，并且相对导入路径不合理；确认无入口引用后可删除，避免维护者误改。

## 12. 测试覆盖与验证入口

- Controller：Agent 工具审批、交互、RAG 工具开关、上下文与文件打开。
- Service：上下文压缩、Memory 时间规则、ChatMemory JDBC、金融 RAG 规划、Task Agent。
- Tool：基础文件/命令工具、计算器、RAG 批量查询。
- Skill/Utils：Skill 发现渲染与文件维护、向量距离。
- `SpringaiApplicationTests` 还包含 PDF/向量库集成实验和快速排序示例；依赖外部服务的测试与纯单元测试应使用 profile/tag 分离。
- Python `evaluate_agent.py` 对 `dataset.json` 执行 Agent 评测，关注任务成功率、工具选择、隔离与输出。

## 13. 一次改动应如何追踪

以“新增一个 Agent 工具”为例：先在 `tools` 包实现方法并添加工具注解 → 在 `AgentToolOptionsFactory` 注册 → 在 `AgentToolPolicy` 定义读写/审批属性 → 如涉及子 Agent，再同步 `TaskAgentService` 的工具集合 → 检查 `AgentController` 的轮次/事件处理 → 前端若需要专门展示，则更新 `utils/agent.js`、`MessageBubble.vue` 和 `chat.js` SSE 分支 → 添加 Tool 与 Controller 测试。

以“新增一个普通业务接口”为例：组件触发动作 → Pinia Store 管理 loading/error/data → `services/config.js` 定义端点 → `services/api.js` 封装请求 → Controller 做协议适配 → Service 实现业务 → 数据层/外部服务 → 补 Controller/Service 测试，并回到本文接口绑定表登记。

## 14. 本次文档生成时的验证结果

- 静态覆盖核对：纳入分析范围的 120 个 Java、JavaScript、Vue、Python 手写代码文件均能在逐文件章节中找到，缺失数为 0；共索引约 1,374 个具名方法、函数、构造器和 record 声明。
- Maven 编译阶段通过，说明当前生产代码和测试代码均可完成 Java 编译。
- 执行 `mvnw.cmd test` 时共运行 75 个测试：71 个通过、3 个失败、1 个跳过。失败并非本次文档改动造成，当前源码的失败点为：
  - `FileOpenControllerTest.windowsOpenCommandSelectsFileOrDirectoryForegroundFriendly`：测试期望 Windows 文件使用 `explorer.exe /select,`，实现实际返回 `cmd.exe /c start ""`。
  - `SpringaiApplicationTests.calculatesDistancesBetweenTextAndCandidates`：测试把首个候选距离断言为 `0.0`，实际 embedding 服务返回约 `0.82286`；该用例依赖外部 embedding 结果。
  - `CalculationToolsTest.calculatesArbitraryPrecisionNumbersExactly`：大整数乘法的期望值与实现返回值不一致，需要先核算测试数据和表达式解析逻辑。
- `RagEvaluationTest` 跳过属于条件式评测入口；它需要相应数据/外部服务环境，不等同于普通单元测试失败。
