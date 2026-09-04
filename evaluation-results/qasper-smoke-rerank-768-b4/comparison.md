# QASPER RAG Evaluation

- Generated: 2026-08-28T07:52:26.543012+00:00
- Cases: 1
- Generator: `deepseek`
- Judge: `gpt`
- Top-K: 8
- Context: `parent-child`
- Cases SHA-256: `9d9ead09a52a46b3b9412b528b63e5957a560c7677b1a678d4785a8870f08360`
- Server config: `{"embeddingDimensions": 1024, "embeddingModel": "qwen3-embedding:0.6b", "generatorModel": "deepseek-v4-flash", "generatorModelId": "deepseek", "hybridTopK": 20, "judgeModel": "gpt-5.5", "judgeModelId": "gpt", "rerankerDocChars": 3200, "rerankerEnabled": true, "rerankerModel": "Qwen3-Reranker-0.6B", "rerankerRuntime": {"batch_size": 4, "device": "cuda", "max_length": 768, "model": "Qwen/Qwen3-Reranker-0.6B", "status": "ok"}, "rerankerUrl": "http://127.0.0.1:8010", "table": "qasper_paper_chunks", "topK": 8}`

| Mode | Success | Gold Evidence Recall@K (N) | Evidence F1 | Answer F1 (N) | Semantic Acc. (N) | Numeric Acc. (N) | Abstention Acc. (N) | Claim Faithfulness (claims) | Retrieval P50/P95 ms | Answer P50/P95 ms | Reranker applied |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hybrid_rerank | 1/1 | 100.00% (1) | 18.36% | - (0) | - (0) | - (0) | - (0) | - (0/0) | 22708.0/22708.0 | -/- | 100.00% |

> Gold Evidence Recall@K is token coverage of QASPER human evidence by returned Top-K citation text.
> Claim Faithfulness is supported atomic claims / all judged atomic claims. Always report judge model and claim count.
> Semantic Accuracy is judged against QASPER gold answers and is reported separately from token Answer F1.
> A hybrid_rerank result is valid only when `reranker_applied_rate` is 100% or the fallback rate is disclosed.
