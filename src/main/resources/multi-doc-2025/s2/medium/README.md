# Multi-Doc-2025 S2 Medium 子集

- 来源：`../test.json` 中固定抽样的 30 条测试问题
- 随机种子：`20250602`
- 关联 HTML 文档数：`22`
- 用途：先验证 S2 RAG 的向量化、检索、LLM 回答和评估结果落库链路

HTML 文档不在本目录重复复制，脚本会从 `../original_doc/` 按 `required-docs.txt` 读取。

## 运行方式

脚本位置：`../evaluate_s2_rag.py`

```bash
python ../evaluate_s2_rag.py --subset medium --rebuild --build-index
python ../evaluate_s2_rag.py --subset medium --eval
```

当前向量模型最大输入为 512 token。由于脚本没有接入该模型的真实 tokenizer，默认使用更保守的 embedding 输入上限：

- `--chunk-chars 1400`
- `--chunk-overlap 180`
- `--embedding-max-tokens 384`

即使手动把 chunk 调大，脚本在调用 embedding 前也会按 `--embedding-max-tokens` 做一次保守截断。若 Ollama 仍返回 context length 超限，脚本会继续自动缩短并重试。

首次只想跑少量数据时可以加 `--limit`：

```bash
python ../evaluate_s2_rag.py --subset medium --rebuild --build-index --limit 1
python ../evaluate_s2_rag.py --subset medium --eval --limit 3
```

默认评估会调用 `mimo-v2.5-pro` 作为裁判模型，判断答案正确性、检索上下文是否包含答案证据、回答是否基于上下文，并在结果 JSON 的 `summary` 中输出：

- `llm_accuracy`：LLM 裁判的答案正确率
- `context_recall`：检索上下文包含答案证据的比例
- `grounded_rate`：回答被检索上下文支撑的比例
- `calculation_accuracy`：需要计算的问题中，计算是否正确
- `retrieval_coverage`：是否检索到 chunk
- `retrieval_source_hit_rate`：是否命中该问题对应的公司/年份源文档
- `average_top_score`：top1 检索相似度均值
- `average_latency_ms`：平均耗时

如果只想看检索结果，不调用 LLM，可加 `--no-llm --no-judge`。

脚本会读取项目 `application.yaml` 中的 embedding、LLM 和 PostgreSQL 配置。写入的 pgvector 表为 `multidoc_s2_medium_chunks`，评估结果表为 `multidoc_s2_medium_eval_results`，不会和 Spring AI 默认向量表冲突。

Python 环境需要安装 `psycopg` 或 `psycopg2`，用于连接 PostgreSQL。
