# QASPER 论文问答 RAG

本目录为独立的科研论文 RAG 数据准备与评测工具，不会读写金融问答使用的 `multidoc_s2_*` 表。

## 数据集

- QASPER v0.3
- 约 1,585 篇 NLP 论文和 5,049 个专家问题
- 官方 train/dev/test 划分
- 每道题包含标准答案和人工证据段落
- 原始项目：https://github.com/allenai/qasper-led-baseline

下载数据并导出标准化评测集：

```powershell
python prepare_qasper_rag.py --download --export-eval --split all
```

原始论文数据默认保存在项目根目录的 `datasets/qasper`，不会被打包进 Spring Boot 产物；可通过 `QASPER_DATA_DIR` 修改位置。

安装 PostgreSQL 驱动并构建独立索引：

```powershell
pip install "psycopg[binary]"
python prepare_qasper_rag.py --build-index --split all --rebuild
```

索引脚本默认读取项目 `src/main/resources/application.yaml` 中的 `spring.datasource.*`、`app.embedding.ollama.*` 和 `app.paper-rag.table` 配置，无需重复设置数据库密码或 embedding 地址。需要临时覆盖时，可使用 `QASPER_DB_HOST`、`QASPER_DB_PORT`、`QASPER_DB_NAME`、`QASPER_DB_USER`、`QASPER_DB_PASSWORD`、`QASPER_TABLE`、`QASPER_EMBEDDING_URL`、`QASPER_EMBEDDING_MODEL` 和 `QASPER_EMBEDDING_DIMENSIONS`。

快速验证时可限制论文数量：

```powershell
python prepare_qasper_rag.py --build-index --split dev --rebuild --limit-papers 10
```

## 评测

`--export-eval` 会在 `src/test/resources/qasper` 生成：

- `eval/qasper-dev.jsonl`
- `eval/qasper-test.jsonl`

预测文件使用 JSONL，每行格式：

```json
{"question_id":"...","answer":"...","evidence":["支持答案的原文段落"]}
```

运行评测：

```powershell
python evaluate_qasper_predictions.py ../../../test/resources/qasper/qasper-dev.jsonl predictions.jsonl
```

输出指标包括归一化 token Answer F1、自定义 Evidence Token F1、数字准确率和拒答准确率。这里的 Evidence Token F1 直接比较返回证据文本与 QASPER 人工证据文本，不冒充官方段落 ID Evidence F1。

## 自动化评测接口

Spring Boot 启动后，评测专用接口为：

```text
GET  /paper/evaluation/config
POST /paper/evaluation/query
```

`GET /paper/evaluation/config` 返回索引表、候选数、Top-K、embedding、reranker 运行状态以及默认生成/裁判模型。当前论文问答使用 Hybrid 候选 15、重排后 Top-8；金融问答配置不受影响。运行器会把该配置和评测集 SHA-256 写入报告，防止混用不同实验配置。

查询接口不使用聊天记忆或模型推断的论文标题，而是用 QASPER `paperId` 精确过滤，避免跨论文污染。示例：

```powershell
$body = @{
  questionId = "b6f15fb6279b82e34a5bf4828b7b5ddabfdf1d54"
  paperId = "1912.01214"
  question = "which multilingual approaches do they compare with?"
  retrievalMode = "hybrid_rerank"
  contextMode = "parent-child"
  topK = 8
  modelId = "deepseek"
  judgeModelId = "gpt"
  goldAnswers = @("Traditional transfer learning, pivot-based methods and multilingual NMT.")
  generateAnswer = $true
  judgeFaithfulness = $true
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8080/paper/evaluation/query" `
  -ContentType "application/json" `
  -Body $body
```

`retrievalMode` 支持 `vector`、`bm25`、`hybrid` 和 `hybrid_rerank`。响应会返回答案、Top-K chunk、chunk ID、各阶段分数、证据文本、reranker 是否真正生效，以及检索/生成/judge/总延迟。

## 自动化评测运行器

修改代码后必须重启 reranker 与 Spring Boot，然后检查实际配置：

```powershell
Invoke-RestMethod http://127.0.0.1:8010/health
Invoke-RestMethod http://127.0.0.1:8080/paper/evaluation/config | ConvertTo-Json -Depth 8
```

先用 10 道题验证完整链路；要求 `reranker_applied_rate=1.0`、`semantic_scored=10`、`total_claims>0`。`--judge-model-id` 必须指向一个真正可调用、并且最好不同于生成模型的配置；首题 judge 失败时运行器会立即停止，避免浪费整轮费用：

```powershell
python run_qasper_evaluation.py `
  --limit 10 `
  --modes hybrid_rerank `
  --model-id deepseek `
  --judge-model-id gpt `
  --judge `
  --output-dir "../../../../evaluation-results/qasper-smoke-v3"
```

只跑检索消融，不调用答案模型：

```powershell
python run_qasper_evaluation.py `
  --modes vector,bm25,hybrid,hybrid_rerank `
  --retrieval-only `
  --output-dir "../../../../evaluation-results/qasper-dev-retrieval-v3" `
  --resume
```

完整运行 dev 的答案与 Faithfulness 评测：

```powershell
python run_qasper_evaluation.py `
  --modes hybrid,hybrid_rerank `
  --model-id deepseek `
  --judge-model-id gpt `
  --judge `
  --output-dir "../../../../evaluation-results/qasper-dev-e2e-v3" `
  --resume
```

默认结果目录为项目根目录 `evaluation-results/qasper`，其中包含：

- `<mode>.results.jsonl`：完整逐题结构化响应，可断点续跑；
- `<mode>.predictions.jsonl`：兼容 `evaluate_qasper_predictions.py`；
- `<mode>.questions.csv`：逐题指标；
- `<mode>.report.json`：单模式汇总；
- `comparison.json` 和 `comparison.md`：消融对比报告。

`--resume` 只会复用评测模式、接口、生成/裁判模型、Top-K、上下文模式、服务器配置和数据集哈希完全一致的记录。检索专用结果不会被当作答案或 judge 结果复用。

报告同时给出以下真实分母：`context_scored`、`answer_scored`、`numeric_scored`、`semantic_scored`、`faithfulness_scored`、`supported_claims/total_claims`。其中：

- `context_recall_at_k` 与 `evidence_f1` 使用 QASPER 人工证据，不是 LLM 自评分；
- `answer_f1`、`numeric_accuracy`、`abstention_accuracy` 使用标准答案规则评分；
- `semantic_accuracy` 使用独立裁判模型比较预测与标准答案；
- `faithfulness` 是所有被支持原子事实数除以全部原子事实数，`faithfulness_macro` 是逐题比例的宏平均；
- `retrieval_latency_p50_ms/p95_ms` 和 `answer_latency_p50_ms/p95_ms` 分开统计。

正式测 P50/P95 延迟时不要同时构建 embedding 索引。若 `hybrid_rerank` 的 `reranker_applied_rate` 低于 100%，报告会保留降级事实，不应把该结果写成纯 reranker 指标。

简历只引用同一 `comparison.json` 中、同一题集和同一硬件下的优化前后数值，并写清测试范围，例如“QASPER dev 1,005 题、已知论文范围内检索、Recall@8”。不要直接照搬 MoneyBot 的指标口径。
