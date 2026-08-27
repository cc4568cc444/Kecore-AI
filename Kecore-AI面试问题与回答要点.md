# Kecore AI 面试问题与回答要点

> 本文基于当前项目源码整理。项目应明确拆成两条主线：**Agent 应用**与 **RAG 应用（金融年报智能问答系统）**。此外，项目还包含一套通用 PDF 父子分块知识库。

## 目录

- [1. 项目介绍](#1-项目介绍)
- [2. Agent 应用高频问题](#2-agent-应用高频问题)
- [3. 金融年报 RAG 高频问题](#3-金融年报-rag-高频问题)
- [4. 通用 PDF RAG 高频问题](#4-通用-pdf-rag-高频问题)
- [5. Java、Spring 与系统设计问题](#5-javaspring-与系统设计问题)
- [6. 测试与评估问题](#6-测试与评估问题)
- [7. 项目缺陷与生产化追问](#7-项目缺陷与生产化追问)
- [8. 面试前重点准备](#8-面试前重点准备)

---

## 1. 项目介绍

### 1.1 一分钟项目介绍

Kecore AI 是一个基于 Spring Boot、Spring AI 和 Vue 3 的大模型应用平台，主要分为 Agent 和金融 RAG 两部分。

Agent 部分实现了模型多轮工具调用、危险工具审批、拒绝后的重新规划、SSE 流式输出、子 Agent、MCP、长期记忆、Skill 和上下文压缩。

RAG 部分包含通用 PDF 父子分块知识库，以及面向 SEC 10-K 年报的金融问答系统。金融 RAG 使用 LLM 进行查询规划，并结合公司和年份过滤、向量检索、BM25、RRF 融合和可选 reranker，最后把 Top-K 证据交给模型生成答案。

### 1.2 技术栈

- 后端：Java 17、Spring Boot 3.5.14、Spring AI 1.1.6。
- 前端：Vue 3、Pinia、Vite、KaTeX。
- 模型：OpenAI-compatible Chat Completions API。
- Embedding：Ollama、Qwen3-Embedding 0.6B、1024 维。
- 数据库：PostgreSQL、PGVector、JdbcTemplate。
- Agent 扩展：MCP、Skill、Memory、子 Agent。
- 通信：REST、SSE。
- 评测：JUnit、Python Agent 评测脚本、RAG 评测数据集。

### 1.3 项目的主要亮点

1. Agent 不只是调用工具，还实现了审批、拒绝后的重新规划和挂起任务恢复。
2. 支持长对话上下文估算、完整摘要压缩和工具输出轻量压缩。
3. 子 Agent 按 explorer、reviewer、planner、tester、implementer 等角色隔离工具权限。
4. 金融 RAG 针对公司、年份、指标、表格和数字查询设计混合检索。
5. 通用 PDF RAG 使用“小块检索、大块返回”的父子分块方案。
6. 前端可以展示模型流式输出、工具轨迹、审批请求和子任务进度。

---

## 2. Agent 应用高频问题

### 2.1 Agent 和普通聊天有什么本质区别？

普通聊天通常是：

```text
用户问题 → 模型 → 文本答案
```

Agent 是一个多轮决策循环：

```text
用户目标
→ 模型决定是否调用工具
→ 系统检查工具权限
→ 自动执行或等待用户审批
→ 工具结果写回上下文
→ 模型继续决策
→ 输出最终答案或达到循环上限
```

项目中的 `AgentController` 实际承担了 Agent 状态机和工作流编排器的职责。

### 2.2 Agent 工具调用循环如何实现？

回答要点：

1. 把用户消息、系统提示、历史消息和工具定义组装成 Prompt。
2. 调用模型并检查 AssistantMessage 中是否存在 ToolCall。
3. 没有 ToolCall 时直接返回最终答案。
4. 有 ToolCall 时交给 `AgentToolPolicy` 判断是否需要审批。
5. 只读或低风险工具可以直接执行。
6. 高风险工具返回 `approval_required` SSE 事件并保存 PendingRun。
7. 工具结果封装成 ToolResponseMessage 并写入 ChatMemory。
8. 模型读取工具结果后进入下一轮决策。
9. 达到轮次上限后，由用户决定继续还是停止。

### 2.3 工具审批后如何恢复原来的任务？

系统通过 runId 保存 PendingRun，其中包含：

- 当前 Prompt。
- 模型产生的 ToolCall。
- conversationId。
- 工作目录与沙箱信息。
- 模型配置及运行参数。
- 已执行活动和当前轮次。

用户批准后调用 `/agent/approve/stream`，后端根据 runId 获取 PendingRun，执行工具，并从原来的上下文继续 Agent 循环。

当前 PendingRun 存在 `ConcurrentHashMap` 中，因此存在以下边界：

- 服务重启后挂起任务丢失。
- 不适合直接进行多实例部署。
- 需要额外解决重复审批和幂等问题。

### 2.4 用户拒绝工具后为什么 Agent 还继续回答？

拒绝的是本次工具操作，不是整个用户任务。

系统会生成一条虚拟 ToolResponseMessage，告诉模型用户拒绝了该操作。模型可以重新选择：

- 使用其他低风险工具。
- 提供只读方案。
- 基于已有信息回答。
- 说明由于权限限制无法继续。

这比拒绝后直接终止任务更符合 Agent 的自主规划逻辑。

### 2.5 如何防止 Agent 无限调用工具？

- 设置总工具轮数上限。
- 达到上限后要求用户确认是否增加轮次。
- `ragSearch` 最多连续调用 3 次。
- 子 Agent 有独立最大轮数。
- 截断过长工具输出，避免上下文无限膨胀。
- 高风险工具必须经过审批。

### 2.6 项目包含哪些工具？如何划分风险？

- 文件读取、目录浏览：通常为只读工具，可以自动执行。
- 文件写入、替换、复制：会改变系统状态，需要更严格策略。
- Shell：风险最高，通常要求用户确认。
- Calculation：纯计算，一般可自动执行。
- RagSearch：只读检索，可以自动执行，但有次数限制。
- AskUser：暂停 Agent，等待用户补充信息。
- Task：派发子 Agent。
- Memory：读取或写入长期记忆。
- Skill：浏览和渲染本地 Skill。
- MCP：调用外部 MCP Server 提供的工具。

### 2.7 SSE 在 Agent 中承担什么作用？

SSE 不只传输模型文本，还用于发送：

- 模型文本增量。
- reasoning 增量。
- 工具执行状态。
- `approval_required` 审批事件。
- 子 Agent 进度。
- 上下文用量。
- 最终完成或错误事件。
- 心跳事件。

心跳可以避免模型长时间推理或工具长时间执行时，连接被代理或浏览器提前断开。

### 2.8 为什么使用 SSE 而不是 WebSocket？

- 当前主要是服务器向浏览器单向推送，SSE 更简单。
- SSE 基于标准 HTTP，代理兼容和调试成本较低。
- 用户审批和补充输入可以通过普通 POST 请求提交。
- 如果未来需要高频双向通信、实时协作或更复杂的中途控制，可以考虑 WebSocket。

### 2.9 长对话上下文如何处理？

项目支持两类压缩：

1. **完整压缩**：把较早历史交给模型生成摘要，同时保留最近消息。
2. **轻量压缩**：把较大的旧工具输出替换为占位信息，保留工具名称和必要摘要。

压缩时不能切断以下完整交换：

```text
Assistant ToolCall → ToolResponse
```

否则模型可能看到没有响应的 ToolCall，导致 API 校验或推理异常。

### 2.10 Token 使用量如何估算？

- 优先记录模型响应提供的实际 Prompt Token。
- 预估阶段会序列化消息和工具定义。
- 根据模型名称选择 tokenizer。
- tokenizer 不可用时使用字符数近似。
- 同时统计工具定义和工具输出的上下文开销。

估算结果无法保证与模型服务端完全一致，因为服务端可能使用不同的消息模板和特殊 token。

### 2.11 子 Agent 是如何设计的？

项目定义了多种角色：

- explorer：探索代码和信息。
- reviewer：代码或方案审查。
- planner：制定实现计划。
- tester：执行验证。
- implementer：执行修改。

每种角色都有独立的：

- System Prompt。
- 最大轮数。
- 工具白名单。
- 是否允许修改文件。
- 是否允许后台执行。

只有 explorer 和 reviewer 允许作为后台任务运行。任务完成后，结果会作为系统消息注入父 Agent 的后续上下文。

### 2.12 为什么子 Agent 需要工具白名单？

- 遵循最小权限原则。
- 防止只负责探索的 Agent 修改文件。
- 降低 Prompt Injection 导致危险操作的风险。
- 控制工具选择空间，减少模型误调用。
- 便于按角色评测和审计。

### 2.13 MCP 是什么？项目为什么需要 MCP？

MCP 将外部能力抽象成统一工具协议。项目通过 Spring AI MCP Client：

1. 连接 HTTP 或 stdio MCP Server。
2. 获取服务端工具定义。
3. 转换为 Spring AI ToolCallback。
4. 注册到统一工具列表。
5. 将模型生成的参数传给 MCP Server。
6. 将 MCP 返回值规范化为模型可读文本。

这样新增邮件、日历等外部能力时，不需要把具体业务代码直接写进 AgentController。

### 2.14 Skill 和 Tool 有什么区别？

- Tool 是可以执行的函数，有明确参数和运行结果。
- Skill 更像可复用的提示词、领域知识和标准工作流。
- Tool 解决“执行什么操作”，Skill 解决“应该按什么步骤完成任务”。
- 项目支持 Skill 的发现、解析、渲染、编辑和直接注入。

### 2.15 Agent 模块目前最大的工程问题是什么？

- `AgentController` 超过 2000 行，职责过重。
- PendingRun、审批状态和沙箱上下文保存在单机内存。
- 服务重启后无法恢复挂起任务。
- 多实例之间无法共享 Agent 状态。
- 工具权限主要依赖工具名规则，粒度仍然较粗。
- 当前沙箱更接近应用层路径约束，不是操作系统级隔离。

可行的重构方向：

- 拆分 `AgentOrchestrator`、`RunRepository`、`ApprovalService` 和 `StreamEventPublisher`。
- 使用 Redis 或数据库持久化运行状态。
- 引入 run version 或 CAS，保证审批幂等。
- 使用容器或受限进程实现真正的 Shell 沙箱。

---

## 3. 金融年报 RAG 高频问题

### 3.1 金融 RAG 的完整处理流程是什么？

```text
用户问题
→ LLM 检索规划与问题改写
→ 生成最多 5 条检索查询
→ 推断公司和年份
→ Metadata Filter
→ PGVector 向量召回
→ 候选集 BM25 评分
→ RRF 排名融合
→ 去重
→ 可选 Qwen3-Reranker
→ Top-8 上下文
→ LLM 流式生成答案
```

### 3.2 为什么金融年报不能只用向量检索？

财务问题包含大量精确匹配信息：

- 公司名和股票代码。
- 年份。
- revenue、net income 等指标名称。
- 数字和百分比。
- 财务表表头。
- thousand、million、billion 等单位。

Embedding 擅长语义相似，但不一定能稳定区分：

- 2023 与 2024。
- net income 与 operating income。
- total assets 与 current assets。
- 数值单位和财务口径。

因此需要 Metadata Filter、BM25、RRF 和 reranker 补足精确性。

### 3.3 为什么先做公司和年份过滤？

这是先缩小搜索空间，再做相关性排序：

- 防止 Apple 的问题召回 Microsoft 年报。
- 防止 2024 问题召回 2022 数据。
- 降低候选噪声。
- 提高检索和 rerank 效率。
- 对财务数字类问题尤其重要。

### 3.4 项目如何识别公司？

- 使用常见公司别名表。
- 对查询进行小写和格式归一化。
- 从索引表读取已知 company 值。
- 别名或公司名称命中后生成查询过滤条件。

当前静态别名表的扩展性有限。生产系统可以维护证券实体表，记录公司名称、ticker、CIK 和历史名称。

### 3.5 项目如何识别年份？有什么边界？

当前正则只识别 2022、2023、2024。这是明显的硬编码边界，新年度年报可能无法正确过滤。

改进方式：

- 使用通用四位年份正则。
- 再根据索引中实际存在的年份验证范围。
- 区分 filing year、fiscal year 和表格 period。

### 3.6 BM25 是在数据库中执行的吗？

不是严格意义上的 PostgreSQL 全文检索。当前处理大致是：

1. 从数据库取得向量候选。
2. 在 Java 内存中对候选内容分词。
3. 计算 BM25 和关键词加权。
4. 与向量排名进行 RRF 融合。

优点是实现简单，缺点是 BM25 只作用于已取得的候选，整体召回上限仍受向量检索影响。

生产优化可以使用 PostgreSQL `tsvector`、Elasticsearch、OpenSearch 或 Lucene，分别执行全局关键词检索和向量检索后再融合。

### 3.7 什么是 RRF？为什么选择 RRF？

RRF，即 Reciprocal Rank Fusion，根据候选在不同检索器中的排名进行融合：

```text
score(d) = Σ 1 / (k + rank_i(d))
```

优点：

- 不需要归一化 BM25 分数和向量相似度。
- 只依赖排名，稳定性较好。
- 实现简单。
- 可以兼顾语义相关性和关键词精确匹配。

### 3.8 Reranker 位于哪一层？为什么有效？

Reranker 位于粗召回之后、最终 Top-K 之前：

- Embedding 双塔模型负责快速召回。
- Reranker 同时读取 query 和 document，相关性判断更精确。
- Reranker 计算成本更高，因此只对有限候选执行。
- 项目使用本地 Qwen3-Reranker-0.6B 服务。

如果 reranker 不可用，系统会回退到 RRF 排名，避免整个问答链路不可用。

### 3.9 为什么最终使用 Top-8？

Top-K 是召回率、噪声和上下文成本之间的折中：

- 太少可能遗漏关键表格或对比年份。
- 太多会增加上下文噪声，并提高数字串行风险。
- Top-8 是当前经验配置，不是理论最优值。

生产中应通过评测集比较不同 Top-K 下的 Recall、答案准确率、时延和 Token 成本。

### 3.10 金融 RAG 如何处理多轮对话？

检索规划阶段会读取最近会话上下文，用于补全省略的信息。例如：

```text
用户：Apple 2024 年 revenue 是多少？
用户：相比上一年呢？
```

第二问没有明确公司和指标，需要利用历史上下文改写为完整查询。

风险是历史信息可能把旧公司错误带入新问题，因此需要限制历史窗口，并识别用户是否显式切换公司或指标。

### 3.11 LLM 查询规划失败怎么办？

项目包含规则回退方案：

- 使用原始问题作为基础查询。
- 扩展 annual report、10-K、financial statements 等关键词。
- 补齐固定数量的检索查询。
- 使用规则推断 intent、company 和 year。

体现了一个重要原则：LLM 查询规划是增强能力，不能成为系统单点故障。

### 3.12 full 表和 medium 表有什么关系？

默认完整索引表：

```text
multidoc_s2_full_chunks
```

回退索引表：

```text
multidoc_s2_medium_chunks
```

完整表不存在时，服务会尝试使用 medium 子集。开发环境下比较方便，但生产环境更适合通过启动健康检查明确暴露索引缺失，而不是静默降低数据覆盖。

### 3.13 动态表名如何防止 SQL 注入？

- 表名来自配置，无法使用 JDBC 占位符绑定，需要先进行安全表名校验，再拼接 SQL。
- company、year、vector、limit 等查询值应继续使用预编译参数绑定。
- 不允许用户直接通过请求参数指定任意表名。

### 3.14 金融表格为什么难以切分？

- 表头和数据行可能分离。
- 表格可能跨页。
- 年份列容易错位。
- 单位通常只出现在表格标题中。
- HTML 存在合并单元格。
- 同一指标可能在多个章节和不同口径下出现。

项目索引流程通过表格解析、重复表头和 period metadata，让每个表格 chunk 尽可能自包含。

### 3.15 这套 RAG 能否保证财务数字绝对正确？

不能。仍然可能出现：

- 召回正确但模型抄错数字。
- 单位转换错误。
- 年份列错位。
- 指标口径混淆。
- 多家公司证据串行。
- 同比变化计算错误。

改进方式：

- 返回结构化来源和引用。
- 答案中的数字必须能映射到检索 chunk。
- 使用 BigDecimal 工具计算变化，不让 LLM 心算。
- 对公司、年份、指标和单位进行答案后校验。
- 表格问题优先使用结构化解析或 SQL 查询。

---

## 4. 通用 PDF RAG 高频问题

### 4.1 为什么采用父子分块？

项目配置：

- Parent chunk：256 tokens。
- Child chunk：64 tokens。
- Child 写入向量库。
- Parent 保存在关系表。

检索时使用较小的 Child 提高匹配精度，然后根据 parentChunkId 加载完整 Parent，为模型提供更连贯的上下文。

### 4.2 为什么不直接把 Parent 向量化？

Parent 较长，可能包含多个主题，Embedding 表达容易被稀释。Child 较短，更容易精确命中查询。

但是直接把 Child 返回给模型又容易缺少上下文，因此使用“小块检索、大块返回”。

### 4.3 如何实现不同会话之间的知识库隔离？

PDF chunk 元数据包含：

- conversationId。
- documentId。
- parentChunkId。
- fileName。

检索时按 conversationId 过滤。删除会话时，同时删除文档元数据、父块和向量数据。

### 4.4 PDF 上传失败如何保证数据一致性？

上传链路跨越：

- 临时文件。
- PDF 解析。
- Embedding 服务。
- VectorStore 写入。
- Parent 表写入。
- Document 表写入。

这些操作没有天然分布式事务，中途失败可能留下部分数据。

改进方案：

- 先创建 `indexing` 状态的任务记录。
- 全部写入成功后更新为 `ready`。
- 失败时根据 documentId 执行补偿删除。
- 将索引构建改为后台异步任务。
- 保证索引任务幂等。

### 4.5 为什么通用 PDF RAG 和金融 RAG 没有完全复用？

- 通用 PDF 强调语义检索和上下文连续性。
- 金融年报强调公司、年份、指标、数字和表格结构。
- 金融检索需要 Metadata Filter、BM25、RRF 和 reranker。
- 强行共用一套实现会损害领域效果。

可以抽象统一的 RetrievalStrategy 接口，但应保留不同领域的检索实现。

---

## 5. Java、Spring 与系统设计问题

### 5.1 为什么使用 ConcurrentHashMap 保存 Agent 状态？

SSE、审批请求和模型执行可能位于不同线程，需要线程安全访问。

但 ConcurrentHashMap 只保证单次操作线程安全，无法自动解决：

- 读取、判断、删除等复合操作的原子性。
- 同一个 run 被重复审批。
- 多服务实例的数据一致性。
- 服务重启后的状态恢复。

生产环境应使用 Redis 或数据库，并设计原子状态转换。

### 5.2 ThreadLocal 在项目中有什么作用和风险？

ThreadLocal 用于传递当前请求的：

- modelId。
- RuntimeOptions。
- conversationId。
- 轻量压缩时间点。

风险：

- 在线程池中不执行 `remove()` 可能污染后续请求。
- 异步切换线程时 ThreadLocal 不会自动传播。
- Reactor 流程更适合使用 Reactor Context。

### 5.3 为什么 ChatMemory 需要持久化？

如果只保存在内存中：

- 服务重启后历史丢失。
- 多实例无法共享会话。
- 工具审批后无法稳定恢复原上下文。

项目使用 JDBC 保存 Spring AI Message，并处理 UserMessage、AssistantMessage、ToolCall 和 ToolResponse 等不同消息类型。

### 5.4 为什么项目定制了 OpenAiChatModel 和 OpenAiApi？

主要用于适配上游 Spring AI 在 reasoning、流式工具调用或 OpenAI-compatible 接口上的行为。

同包名覆盖依赖类存在风险：

- Spring AI 升级时容易不兼容。
- 上游安全修复不会自动合并。
- 容易产生类加载和维护问题。
- 新版本 API 变化需要手动同步。

更合适的方式是使用 Adapter、继承扩展或向上游提交补丁。

### 5.5 项目如何支持模型切换？

ModelConfigService 管理多个模型配置：

- base URL。
- API Key。
- model name。
- temperature 等运行参数。

请求携带 modelId，后端动态选择 ChatModel 和 Options。需要避免把 API Key 返回前端或写入日志。

### 5.6 项目目前有哪些可观测性能力？

- SSE 事件耗时。
- TokenUsage。
- translation、retrieval 等阶段耗时。
- Agent 工具执行轨迹。
- 子 Agent 任务进度。
- MCP 连接诊断。

可以继续增加：

- 每轮模型调用 traceId。
- 工具成功率和耗时。
- 检索 Recall@K。
- reranker 前后排名变化。
- 模型调用成本。
- 挂起审批数量。
- OpenTelemetry 分布式追踪。

### 5.7 如何支持多实例部署？

需要将以下内存状态迁移到共享存储：

- PendingRun。
- approvalModes。
- sandboxContexts。
- actualPromptUsages。
- 子 Agent 任务状态和进度。

可以配合：

- Redis 或数据库。
- runId 幂等键。
- 状态版本号。
- CAS、分布式锁或数据库条件更新。
- Redis Stream 或 Kafka 转发 SSE 事件。
- 对象存储或共享文件存储。

---

## 6. 测试与评估问题

### 6.1 Agent 应该如何评测？

可以评估：

- 任务完成率。
- 工具选择准确率。
- 工具参数准确率。
- 是否违反安全策略。
- 审批绕过率。
- 拒绝工具后的恢复成功率。
- 平均工具调用轮数。
- 循环超限率。
- Token 成本和响应时延。

### 6.2 RAG 应该如何评测？

检索层指标：

- Recall@K。
- MRR。
- nDCG。
- 公司和年份过滤准确率。
- 正确表格召回率。

生成层指标：

- Exact Match。
- 数值误差。
- 单位正确率。
- 年份对齐正确率。
- 引用正确率。
- Faithfulness。
- 无答案问题拒答率。

### 6.3 如何证明混合检索优于纯向量检索？

进行消融实验：

1. 纯向量检索。
2. 向量检索 + Metadata Filter。
3. 向量检索 + BM25 + RRF。
4. 向量检索 + BM25 + RRF + reranker。
5. 比较普通分块和父子分块。

在同一数据集上比较 Recall@8、MRR、数字答案准确率、时延和 Token 成本。

### 6.4 当前项目测试执行结果

实际执行 Maven 测试结果：

```text
Tests run: 75
Failures: 3
Errors: 0
Skipped: 1
```

当前失败包括：

1. Embedding 距离测试把期望值硬编码为 0，但实际依赖外部 embedding 服务。
2. 大数乘法测试的预期结果与输入计算结果不一致。
3. Windows 打开文件命令的测试预期与当前实现不一致。

面试时不要说“所有测试都通过”。可以回答：

> 核心模块已经有单元测试，但测试套件中还混入了真实外部服务和操作系统相关行为。后续应将 embedding、数据库、reranker 和系统命令隔离成 mock 或独立集成测试，同时修正错误断言。

---

## 7. 项目缺陷与生产化追问

### 7.1 项目最大的安全风险是什么？

- Shell 工具可以执行系统命令。
- 文件工具可能访问工作目录以外的路径。
- MCP Server 属于外部信任边界。
- Prompt Injection 可能诱导 Agent 调用危险工具。
- 上传的 PDF 可能包含恶意提示词。
- 当前缺少完整的认证、授权和租户隔离。
- 配置中存在固定数据库账号和局域网服务地址等开发环境信息。

### 7.2 如何防御 Prompt Injection？

不能只依赖 System Prompt，应结合：

- 工具审批。
- 工具白名单。
- 文件路径和命令策略。
- 将检索文档标记为不可信数据。
- 不允许文档内容修改系统规则。
- 敏感操作二次确认。
- 工具参数校验。
- 最小权限运行。
- 工具执行审计。
- Prompt Injection 专项评测集。

### 7.3 当前沙箱是真正的安全沙箱吗？

更准确地说，当前是应用层工作目录约束，不是强安全隔离。

真正的 Shell 沙箱可以使用：

- 容器。
- 非特权用户。
- 只读文件系统。
- CPU、内存、进程和执行时间限制。
- 网络隔离。
- syscall 限制。
- 独立临时工作目录。

### 7.4 QPS 上升后可能出现哪些瓶颈？

- 模型调用时延。
- 单个问题生成多条查询和 embedding。
- reranker 串行调用。
- Java 内存中执行 BM25。
- PGVector 索引性能。
- 大量 SSE 长连接。
- 子 Agent 使用无界 cachedThreadPool。
- PDF 同步解析和 embedding。
- PendingRun 等状态只存在单机内存中。

### 7.5 如果重构项目，优先做什么？

1. 拆分超大的 AgentController。
2. 将 Agent Run 建模为显式状态机。
3. 持久化 PendingRun 和审批状态。
4. 增加幂等、超时和过期状态清理。
5. 将 PDF 索引改为异步任务。
6. 为金融 RAG 建立完整消融评测。
7. 增加答案引用和数字校验。
8. 移除硬编码公司和年份规则。
9. 清理同包名覆盖的 Spring AI 源码。
10. 隔离外部服务测试和单元测试。

---

## 8. 面试前重点准备

### 8.1 Agent 流程图

```text
请求 → Prompt → 模型 → ToolCall
                   ↓
              ToolPolicy
          ↙ 自动执行  ↘ 等待审批
         ToolResponse ← 用户批准/拒绝
              ↓
          写入 ChatMemory
              ↓
           下一轮模型
```

### 8.2 金融 RAG 流程图

```text
问题 → 查询规划 → 公司/年份过滤
                   ↓
          Vector Search + BM25
                   ↓
                 RRF
                   ↓
               Reranker
                   ↓
                Top-8
                   ↓
            带证据生成答案
```

### 8.3 PDF 父子分块流程图

```text
PDF → Parent 256 tokens → Child 64 tokens → Child 向量化
                                              ↓
查询 → Child 相似度召回 → parentChunkId → 加载完整 Parent
```

### 8.4 复习优先级

1. Agent 工具循环和审批恢复。
2. 金融 RAG 为什么需要混合检索。
3. 父子分块的设计原因。
4. SSE 流式状态管理。
5. 上下文压缩和 Token 估算。
6. 子 Agent、MCP 和 Skill。
7. Agent 与 RAG 的评测方法。
8. 当前实现的不足及生产化改进。

### 8.5 回答项目问题时的注意事项

- 不要把项目描述成简单的聊天机器人。
- 不要只说“使用了向量数据库”，要说清楚过滤、召回、融合和重排。
- 不要声称测试全部通过。
- 不要声称当前沙箱是操作系统级安全隔离。
- 不要回避内存状态无法支持重启恢复和多实例的问题。
- 讲完实现后，主动补充当前边界和下一步改进，通常比只讲功能更加分。
