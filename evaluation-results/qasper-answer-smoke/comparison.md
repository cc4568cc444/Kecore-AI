# QASPER RAG Evaluation

- Generated: 2026-08-27T11:54:59.758945+00:00
- Cases: 1
- Model: `deepseek`
- Top-K: 8
- Context: `parent-child`

| Mode | Success | Context Recall@K | Evidence F1 | Answer F1 | Numeric Acc. | Abstention Acc. | Faithfulness | Retrieval P50/P95 ms | Answer P50/P95 ms | Reranker applied |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hybrid | 1/1 | 100.00% | 19.45% | 21.43% | - | 100.00% | 100.00% | 155.0/155.0 | 2334.0/2334.0 | - |

> Context Recall@K is token coverage of QASPER gold evidence by the returned Top-K citation text.
> Faithfulness is an optional LLM judge score. Always report judge model and sample size with this value.
> A hybrid_rerank result is valid only when `reranker_applied_rate` is 100% or the fallback rate is disclosed.
