# Multi-Doc-2025 S2 子集

数据来源：https://huggingface.co/datasets/Anonymous-Team-HC-RAG/Multi-Doc-2025

本目录保存用于 `chat-wiki` RAG 模式的 Multi-Doc-2025 官方 S2 子集。

## 子集信息

- 名称：S2 / Single-Doc Table
- 难度：L1
- 问题数量：494
- 官方拆分：train 319 条，val 62 条，test 113 条
- 原始文档：10-K HTML 年报
- 任务特点：单公司、单年份、表格型问题，主要考察表格查找和表内计算能力

## 文件说明

- `qa.json`：全部 S2 问答数据
- `train.json`：官方训练集中过滤出的 S2 数据
- `val.json`：官方验证集中过滤出的 S2 数据
- `test.json`：官方测试集中过滤出的 S2 数据
- `required-docs.txt`：S2 问题实际引用的原始文档相对路径清单
- `original_doc/`：S2 需要的源 10-K HTML 年报文档
- `evaluate_s2_rag.py`：S2 RAG 索引、检索、回答和评测脚本
- `eval-results/`：评测结果输出目录
- `medium/`：用于快速验证链路的 30 题中等规模子集

## 运行方式

全量构建索引并评测：

```powershell
python evaluate_s2_rag.py --subset full --rebuild --build-index --max-chunks-per-doc 0 --embedding-max-tokens 1024 --chunk-chars 3000 --chunk-overlap 300; if ($LASTEXITCODE -eq 0) { python evaluate_s2_rag.py --subset full --eval --embedding-max-tokens 1024 --rerank --rerank-doc-chars 3000 }
```

如需在表格 chunk 中写入期间信息，例如 `June 30, 2022`、`Year Ended December 31, 2024` 或表头年份，可在重建索引时增加：

```powershell
--table-period-metadata
```

完整命令示例：

```powershell
python evaluate_s2_rag.py --subset full --rebuild --build-index --table-period-metadata --max-chunks-per-doc 0 --embedding-max-tokens 1024 --chunk-chars 3000 --chunk-overlap 300; if ($LASTEXITCODE -eq 0) { python evaluate_s2_rag.py --subset full --eval --embedding-max-tokens 1024 --rerank --rerank-doc-chars 3000 }
```

Parent-child chunking embeds child chunks, then expands retrieved hits with the same table or section parent context for rerank, answering, and evaluation.

```powershell
python evaluate_s2_rag.py --subset full --rebuild --build-index --chunk-strategy parent-child --parent-context-chars 6000 --table-period-metadata --max-chunks-per-doc 0 --embedding-max-tokens 1024 --chunk-chars 1200 --chunk-overlap 160; if ($LASTEXITCODE -eq 0) { python evaluate_s2_rag.py --subset full --eval --chunk-strategy parent-child --embedding-max-tokens 1024 --rerank --rerank-doc-chars 6000 }
```

该开关默认关闭，便于对比“无 period metadata”和“启用 period metadata”两组索引效果。开启后需要 `--rebuild --build-index` 重新建索引才会生效。

仅重新评测，不重建索引：

```powershell
python evaluate_s2_rag.py --subset full --eval --embedding-max-tokens 1024 --rerank --rerank-doc-chars 3000
```

当前脚本读取项目 `application.yaml` 中的 embedding、LLM 和 PostgreSQL 配置。写入的 pgvector 表与 Spring AI 默认向量表隔离，例如 `multidoc_s2_full_chunks` 和 `multidoc_s2_full_eval_results`。

## 模型配置

- Answer LLM: `gpt-5.5` via `https://api.caichen.online/v1/chat/completions`, key from `CAICHEN_KEY`
- Judge LLM: `gpt-5.5` via the same OpenAI-compatible endpoint
- Embedding model: `qwen3-embedding:0.6b`
- Rerank model: local `Qwen3-Reranker-0.6B`, endpoint `http://127.0.0.1:8010/v1/rerank`

建议 rerank 服务配置：

```powershell
QWEN3_RERANKER_MAX_LENGTH=7168
QWEN3_RERANKER_BATCH_SIZE=1
```

如果 8GB 显存环境出现 OOM，可降低 `--rerank-doc-chars`，例如从 `3000` 降到 `2000`。

## RAG 评测指标说明

`evaluate_s2_rag.py` 会在结果 JSON 的 `summary` 字段中输出以下指标。核心判断顺序建议优先看：`llm_accuracy`、`context_recall`、`grounded_rate`、`calculation_accuracy`，最后再看耗时。

- `total_questions`：本次评测的问题总数。
- `successful_questions`：完整完成检索、回答和评判的问题数。
- `failed_questions`：执行失败的问题数，通常由 embedding、rerank、LLM 或数据库调用异常导致。
- `llm_judged_questions`：成功经过 LLM-as-judge 评判的问题数。
- `llm_accuracy`：最终答案正确率，由裁判模型判断预测答案是否与标准答案语义一致。这是最核心的最终效果指标。
- `context_recall`：检索上下文是否包含足够回答证据的比例。该指标衡量检索阶段质量；值高但 `llm_accuracy` 低时，通常说明答案生成阶段选错行、列、年份或公式。
- `grounded_rate`：回答是否被检索上下文支撑的比例。该指标衡量幻觉风险，值越高说明回答越少脱离证据。
- `calculation_accuracy`：需要计算的问题中，计算过程或公式是否正确的比例。S2 以表格计算题为主，因此该指标很重要。
- `average_llm_score`：裁判模型给出的综合平均分，范围 0 到 1。它比 `llm_accuracy` 更平滑，但解释性略弱。
- `exact_match_rate`：预测答案与标准答案完全字符串匹配的比例。金融问答中格式差异较多，该指标参考价值较低。
- `contains_expected_rate`：预测答案是否包含标准答案字符串的比例。比 exact match 稍宽松，但仍容易受格式影响。
- `retrieval_coverage`：每个问题是否至少检索到一个 chunk。低于 1.0 通常表示检索或 rerank 阶段有异常。
- `retrieval_source_hit_rate`：检索结果是否命中目标公司/年份源文档。S2 是单文档表格问答，该指标主要用于确认 metadata filtering 是否正常。
- `average_top_score`：Top1 检索结果的平均分。启用 rerank 后通常表示 reranker 的 `relevance_score`，不能直接等同于答案正确率。
- `average_latency_ms`：平均每题耗时，包含检索、rerank、回答和评判调用。

当前全量 S2 测试的一次参考结果：

```json
{
  "total_questions": 113,
  "successful_questions": 113,
  "failed_questions": 0,
  "llm_judged_questions": 113,
  "llm_accuracy": 0.6637,
  "context_recall": 0.9558,
  "grounded_rate": 0.9469,
  "calculation_accuracy": 0.9417,
  "average_llm_score": 0.8183,
  "exact_match_rate": 0.0,
  "contains_expected_rate": 0.3186,
  "retrieval_coverage": 1.0,
  "retrieval_source_hit_rate": 1.0,
  "average_top_score": 0.8496,
  "average_latency_ms": 39109.5929
}
```

该结果说明：检索召回和证据支撑已经较好，主要瓶颈在答案生成阶段，例如年份/期间选择错误、表格行列选择错误，以及部分 gold answer 存在时间维度歧义。

## 使用说明

- 本地副本只保留官方 split 中的 S2 数据，以及 S2 问题实际引用的源文档。
- 数据集许可证为 CC-BY-4.0，使用时请遵守原数据集声明。
