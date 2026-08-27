# QASPER RAG Evaluation

- Generated: 2026-08-27T12:54:35.022783+00:00
- Cases: 10
- Model: `deepseek`
- Top-K: 8
- Context: `parent-child`

| Mode | Success | Context Recall@K (N) | Evidence F1 | Answer F1 (N) | Numeric Acc. (N) | Abstention Acc. (N) | Faithfulness (N) | Retrieval P50/P95 ms | Answer P50/P95 ms | Reranker applied |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hybrid_rerank | 10/10 | 96.42% (10) | 19.18% | 32.33% (10) | 100.00% (3) | 80.00% (10) | 100.00% (10) | 25056.5/35343.2 | 27429.0/38757.7 | 100.00% |

> Context Recall@K is token coverage of QASPER gold evidence by the returned Top-K citation text.
> Faithfulness is an optional LLM judge score. Always report judge model and sample size with this value.
> A hybrid_rerank result is valid only when `reranker_applied_rate` is 100% or the fallback rate is disclosed.
