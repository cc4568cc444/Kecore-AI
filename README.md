# Kecore AI

基于 Spring AI 的 Kecore AI，集成普通对话、任务型 Agent、PDF 知识库 RAG、金融年报 RAG 问答、MCP 工具调用、子 Agent 任务派发、上下文压缩、长期记忆、Skill 管理和前端工具审批能力。

项目当前主要面向 Agent 应用开发与 RAG 检索增强场景，后端使用 Spring Boot + Spring AI，前端使用原生 HTML/CSS/JavaScript + jQuery，向量存储使用 PostgreSQL + PGVector。

## 功能概览

### 1. 普通对话任务

- 接口：`/ai/chat`
- 支持流式文本输出。
- 使用 Spring AI OpenAI Chat Model 接入外部大模型。
- 支持会话持久化和历史记录展示。

### 2. Agent 任务执行

- 接口：`/agent/chat/stream`
- 支持工具调用、工具审批、用户补充输入、任务执行轨迹展示。
- 支持多轮工具调用循环，自动处理只读工具，风险工具需要用户确认。
- 用户拒绝工具调用后，系统会把“用户拒绝了某个工具”的信息反馈给模型，让模型重新决策，而不是直接结束任务。
- 支持上下文用量估算和手动压缩。
- 支持长期记忆管理。
- 审批模式支持在运行中切换，并会同步到当前 Agent 会话的后续工具判断。

Agent 可用工具包括：

- 文件工具：读取文件、列目录、打开文件、写文件、替换文本、复制文件。
- Shell 工具：执行命令，需要用户确认。
- 计算工具：普通表达式计算和高精度大数计算。
- RAG 工具：检索当前会话上传的 PDF 知识库。
- 用户交互工具：让前端弹出选项或输入框。
- Task 工具：派发子 Agent，支持 explorer、reviewer、planner、tester、implementer 等角色。
- MCP 工具：通过 Spring AI MCP Client 接入外部 MCP Server。
- Skill 管理：浏览、渲染、编辑和插入本地 skills。

### 3. PDF 知识库 RAG

- 上传接口：`/rag/upload`
- 文档列表接口：`/rag/documents`
- 删除接口：`/rag/documents/{documentId}`
- 支持 PDF 上传后进行父子 chunk 切分。
- child chunk 写入 PGVector，用于向量召回。
- parent chunk 保存到数据库，用于召回后扩展上下文。
- Agent 在回答已上传文档相关问题时，可以调用 `ragSearch` 工具检索文档片段。

### 4. 多文档金融研究与问答

- 前端模式：`金融问答`
- 接口：`/finance/chat`
- 数据来源：Multi-Doc-2025 SEC 10-K filings 数据集。
- 目标查询表：`multidoc_full_chunks`
- 兼容查询表：`multidoc_s2_full_chunks`、`multidoc_s2_medium_chunks`

金融 RAG 检索链路基本参考 `src/main/resources/multi-doc-2025/s2/evaluate_s2_rag.py`：

```text
用户问题
-> Entity-Temporal Query Planner
-> 公司 × 财年 × 指标 Typed Sub-tasks
-> 子任务级 Metadata Filter
-> Vector Search
-> BM25 Keyword Search
-> RRF Hybrid Fusion
-> Qwen3-Reranker 可选重排
-> Top-K 原始证据
-> Evidence Ledger 结构化事实抽取与原文校验
-> BigDecimal 确定性计算（差值、增长率、比率、合计）
-> 带 [E#] 原始证据引用的答案
```

适合查询：

- 公司 10-K 表格数据
- 财务指标
- 年度变化
- revenue / net income / total assets 等字段
- 表格 period 对齐问题

示例问题：

```text
Apple 2024 年的 revenue 是多少，相比 2023 年变化多少？
MSFT 2024 年 net income 是多少？
NVDA 2024 年 total assets 是多少？
```

### 5. 科研论文问答（保留代码，前端隐藏）

- 前端不再展示入口，已有论文模式会话会迁移到金融问答模式。
- 流式接口：`/paper/chat/stream`
- 数据来源：QASPER v0.3 科研论文问答数据集
- 独立索引表：`qasper_paper_chunks`
- 官方评测：Answer F1、Evidence F1，并额外统计数字准确率和拒答准确率

论文问答与金融年报问答完全隔离：论文模式不读取或写入 `multidoc_s2_*` 表，金融模式也不会访问 `qasper_paper_chunks`。

数据准备和评测说明见：

```text
src/main/resources/qasper/README.md
```

## 技术栈

- Java 17+
- Spring Boot 3.5.14
- Spring AI 1.1.6
- OpenAI-compatible Chat Model
- Ollama Embedding Model
- PostgreSQL + PGVector
- MCP Client
- HTML / CSS / JavaScript / jQuery
- Python RAG Evaluation Script

## 项目结构

```text
src/main/java/com/cc/springai
├── agent/                  # Agent 请求、响应、工具策略、上下文统计 DTO
├── config/                 # ChatClient、EmbeddingModel、MCP 配置
├── controller/             # REST / SSE 接口
├── embedding/              # Ollama embedding 兼容封装
├── service/                # Agent、RAG、记忆、会话、金融问答服务
├── tools/                  # Agent 可调用工具
├── registry/               # MCP 工具注册
└── mcp/                    # MCP Client 抽象

src/main/resources/static
├── index.html              # 前端页面
├── app.js                  # 前端主逻辑
├── app-api.js              # 前端 API 封装
├── app-config.js           # 前端模式配置
└── styles.css              # 样式

src/main/resources/multi-doc-2025/s2
├── evaluate_s2_rag.py      # S2 RAG 建索引、检索、回答、评估脚本
├── README.md               # S2 数据与评估说明
└── medium/                 # 中等测试子集配置
```

## 环境准备

### 1. 基础环境

需要安装：

- JDK 17 或更高版本
- Maven
- PostgreSQL
- PGVector 扩展
- Ollama 或兼容 embedding 服务
- 可选：本地 reranker 服务

### 2. PostgreSQL 初始化

创建数据库：

```sql
CREATE DATABASE spring_agent;
```

启用 PGVector：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

项目默认数据库配置位于 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_agent
    username: postgres
    password: 123
```

如本地账号不同，请修改对应配置。

### 3. 大模型配置

项目使用 OpenAI-compatible Chat API。

配置位置：

```yaml
spring:
  ai:
    openai:
      chat:
        base-url: https://token-plan-cn.xiaomimimo.com
        api-key: ${MIMO_KEY}
        completions-path: /v1/chat/completions
        options:
          model: mimo-v2.5-pro
          temperature: 0.4
```

启动前需要设置环境变量：

```powershell
$env:MIMO_KEY="你的模型 API Key"
```

### 4. Embedding 配置

项目当前使用局域网 Ollama embedding 服务：

```yaml
app:
  embedding:
    ollama:
      base-url: http://172.16.1.114:11434
      model: qwen3-embedding:0.6b
      dimensions: 1024
```

请确保 embedding 服务可用。

测试示例：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://172.16.1.114:11434/api/embeddings" `
  -ContentType "application/json" `
  -Body '{"model":"qwen3-embedding:0.6b","prompt":"Why is the sky blue?"}'
```

### 5. Reranker 配置

金融问答默认开启本地 rerank：

```yaml
app:
  financial-rag:
    rerank:
      enabled: true
      url: http://127.0.0.1:8010
      model: Qwen3-Reranker-0.6B
      doc-chars: 3000
```

如果本地 reranker 未启动，可以临时关闭：

```yaml
app:
  financial-rag:
    rerank:
      enabled: false
```

Evidence Ledger 默认开启。事实抽取阶段只允许复制检索片段中的原文，Java 会验证引用是否真实存在；
未通过验证的数字不会进入计算器：

```yaml
app:
  financial-rag:
    evidence-ledger:
      enabled: true
      max-documents: 15
      max-content-chars: 6000
```

测试示例：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8010/v1/rerank" `
  -ContentType "application/json" `
  -Body '{"query":"What is the capital of China?","documents":["The capital of China is Beijing.","Gravity attracts two bodies."]}'
```

### 6. MCP 配置

项目默认配置了两个 MCP 连接：

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            utools:
              url: http://127.0.0.1:3501
              endpoint: /mcp
        stdio:
          connections:
            calculator:
              command: node
              args:
                - C:\Users\41760\calculator-mcp\index.js
```

如果 `utools` MCP 需要密钥，请设置：

```powershell
$env:UTOOLS_MCP_KEY="你的 MCP Key"
```

如果没有 MCP 服务，可关闭 MCP 或删除对应连接配置。

前端顶部的 MCP 区域会显示连接名、加载状态、transport 类型、目标地址/命令和工具数量；`/api/mcp/servers` 也会返回这些诊断信息，方便确认是否真的加载成功。

这份诊断信息按 MCP client 的连接名和 `clientInfo` 匹配，不依赖启动顺序。

### 7. Skill 管理

项目内置 Skill 管理面板，可直接在前端查看、筛选、渲染和编辑当前工作目录下的 skills。

常用能力：

- 列出当前目录可用 skills
- 打开 skill 文件并查看原始内容
- 按参数渲染 skill 预览
- 将 skill 调用语句插入到输入框
- 新建、保存、删除 skill 文件

## 启动项目

### 1. 编译

```powershell
mvn "-Dmaven.repo.local=target/m2" -DskipTests package
```

### 2. 启动

```powershell
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8088
```

访问前端：

```text
http://localhost:8088/index.html
```

## 前端使用手册

### 1. 对话模式

点击左侧 `对话`：

- 输入普通问题。
- 后端调用 `/ai/chat`。
- 支持流式输出和会话历史。

### 2. 金融问答模式

点击左侧 `金融问答`：

- 输入跨公司、跨财年或文本/表格混合的 10-K 年报问题。
- 后端调用 `/finance/chat`。
- 系统优先基于 PGVector 中的 `multidoc_full_chunks` 进行检索。

注意：

- 使用前先运行 `tools/multidoc/multidoc_pipeline.py` 准备 S1-S5 数据并建立统一索引。
- 如果没有统一索引，系统会依次尝试 `multidoc_s2_full_chunks` 和 `multidoc_s2_medium_chunks`。
- 如果 reranker 服务不可用，可在配置中关闭 rerank。

### 3. Agent 模式

点击左侧 `Agent`：

1. 设置默认工作目录。
2. 输入任务目标。
3. Agent 会根据需要调用工具。
4. 风险工具调用会在前端展示确认按钮。
5. 用户可以批准或拒绝工具调用。
6. 拒绝后模型会继续尝试替代方案，或说明无法继续的原因。

Agent 支持附件上传，上传的文件会随请求提交给模型。

### 4. 知识库管理

在会话中可上传 PDF：

- 上传后系统进行父子 chunk 切分。
- child chunk 写入 PGVector。
- parent chunk 存储到数据库。
- Agent 可通过 `ragSearch` 查询当前会话知识库。

## 金融 RAG 索引与评测

### 1. Multi-Doc-2025 S1-S5 数据准备

先只下载 QA 元数据、读取官方 179 份文档清单：

```powershell
python tools/multidoc/multidoc_pipeline.py prepare
```

再按需下载原始 10-K 并构建统一索引：

```powershell
python tools/multidoc/multidoc_pipeline.py prepare --all-docs --download-docs
python tools/multidoc/multidoc_pipeline.py verify
python tools/multidoc/multidoc_pipeline.py index --all-docs --rebuild
```

完整说明见 `tools/multidoc/README.md`。以下 S2 脚本继续保留，用于旧基线复现和回归对比。

脚本位置：

```text
src/main/resources/multi-doc-2025/s2/evaluate_s2_rag.py
```

### 2. S2 medium 子集建索引

```powershell
cd src/main/resources/multi-doc-2025/s2

python evaluate_s2_rag.py --subset medium --rebuild --build-index --table-period-metadata --max-chunks-per-doc 0 --embedding-max-tokens 1024 --chunk-chars 3000 --chunk-overlap 300
```

### 3. S2 medium 子集评测

```powershell
python evaluate_s2_rag.py --subset medium --eval --embedding-max-tokens 1024 --rerank --rerank-doc-chars 3000
```

### 4. S2 full 建索引并评测

```powershell
python evaluate_s2_rag.py --subset full --rebuild --build-index --table-period-metadata --max-chunks-per-doc 0 --embedding-max-tokens 1024 --chunk-chars 3000 --chunk-overlap 300; if ($LASTEXITCODE -eq 0) { python evaluate_s2_rag.py --subset full --eval --embedding-max-tokens 1024 --rerank --rerank-doc-chars 3000 }
```

### 5. 表结构

脚本会创建隔离表，避免和项目普通 PGVector 表冲突：

```text
multidoc_s2_medium_chunks
multidoc_s2_medium_eval_results
multidoc_s2_full_chunks
multidoc_s2_full_eval_results
multidoc_full_chunks
multidoc_full_eval_results
```

### 6. 检索策略

索引构建：

- HTML 清洗
- SEC 10-K Item 章节识别
- 表格解析
- 表格 chunk 重复表头
- 可选提取 period metadata
- 文本 chunk 与表格 chunk 分开存储
- 写入 PGVector

检索流程：

- Metadata Filter：公司、年份、行业、source file。
- Vector Search：向量召回语义相关片段。
- BM25：强化公司名、指标名、年份、表头、数字关键词。
- RRF：融合向量和 BM25 排名。
- Rerank：本地 Qwen3-Reranker 对候选片段重排。
- Top-8：最终交给 LLM 回答。

## 主要接口

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/ai/chat` | GET | 普通对话 |
| `/game/chat` | GET | 保留的模拟器接口，前端默认隐藏 |
| `/finance/chat` | GET | 金融年报 RAG 问答 |
| `/paper/chat` | GET | 科研论文 RAG 问答 |
| `/paper/chat/stream` | GET | 科研论文 RAG 流式问答 |
| `/agent/chat` | POST | Agent 非流式接口 |
| `/agent/chat/stream` | POST | Agent SSE 流式接口 |
| `/agent/approval-mode` | POST | 同步当前会话的审批模式 |
| `/agent/approve` | POST | 批准工具调用 |
| `/agent/reject` | POST | 拒绝工具调用并让模型重新决策 |
| `/agent/interact` | POST | 提交用户补充输入 |
| `/agent/context` | GET | 查询上下文用量 |
| `/agent/context/compact` | POST | 手动压缩上下文 |
| `/agent/tasks/progress` | GET | 查询子 Agent 进度 |
| `/rag/upload` | POST | 上传 PDF 知识库 |
| `/rag/documents` | GET | 查看知识库文档 |
| `/rag/documents/{documentId}` | DELETE | 删除知识库文档 |
| `/api/sessions` | GET/PUT | 会话读取与保存 |
| `/api/memory` | GET/PUT | 长期记忆读取与保存 |
| `/api/mcp/servers` | GET | 查看 MCP 服务状态、连接名、加载状态和工具列表 |

## 常见问题

### 1. 启动时报 `Invalid x-mcp-key`

说明本地 MCP 服务需要正确的 `UTOOLS_MCP_KEY`。

处理方式：

- 设置正确的环境变量。
- 或临时关闭 / 删除 `utools` MCP 配置。

### 2. 金融问答没有结果

优先检查：

- 是否已经运行 S2 建索引命令。
- PostgreSQL 中是否存在 `multidoc_s2_full_chunks` 或 `multidoc_s2_medium_chunks`。
- embedding 服务是否可用。
- 问题中是否包含公司或年份。

### 3. Rerank 报错

可以先关闭：

```yaml
app:
  financial-rag:
    rerank:
      enabled: false
```

或者确认本地服务：

```text
http://127.0.0.1:8010/v1/rerank
```

### 4. Agent 拒绝工具后为什么还继续回答

这是预期行为。拒绝表示该工具调用不能执行，但不代表任务结束。系统会把拒绝结果写入上下文，让模型选择替代工具、只读方案，或说明当前限制。

### 5. 数据集为什么没有全部提交

原始 10-K HTML 文档、评测输出和本地 `.env` 属于大体积或本地运行产物，已通过 `.gitignore` 排除。项目提交保留脚本、说明和 medium 轻量测试配置。

## 开发建议

- 修改 Agent 工具策略时，优先查看 `AgentToolPolicy`。
- 修改前端模式入口时，优先查看 `app-config.js`。
- 修改前端接口调用时，优先查看 `app-api.js`。
- 修改金融 RAG 检索策略时，优先查看 `FinancialRagService` 和 `evaluate_s2_rag.py`。
- 修改 PDF RAG 上传和检索时，优先查看 `RagService` 和 `RagTools`。

## 许可证

本仓库保留远程 `main` 分支已有的 `LICENSE` 文件。
