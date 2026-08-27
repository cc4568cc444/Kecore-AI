# QASPER RAG Evaluation

- Generated: 2026-08-27T11:54:17.984862+00:00
- Cases: 2
- Model: `deepseek`
- Top-K: 8
- Context: `parent-child`

| Mode | Success | Context Recall@K | Evidence F1 | Answer F1 | Numeric Acc. | Abstention Acc. | Faithfulness | Retrieval P50/P95 ms | Answer P50/P95 ms | Reranker applied |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| vector | 2/2 | 91.31% | 30.54% | - | - | - | - | 1426.5/2576.2 | 1426.5/2576.2 | - |
| bm25 | 2/2 | 74.85% | 77.49% | - | - | - | - | 19.0/23.5 | 19.0/23.5 | - |
| hybrid | 2/2 | 97.27% | 32.10% | - | - | - | - | 166.0/175.0 | 166.0/175.0 | - |
| hybrid_rerank | 2/2 | 97.27% | 32.10% | - | - | - | - | 199.5/202.7 | 199.5/202.7 | 0.00% |

> Context Recall@K is token coverage of QASPER gold evidence by the returned Top-K citation text.
> Faithfulness is an optional LLM judge score. Always report judge model and sample size with this value.
> A hybrid_rerank result is valid only when `reranker_applied_rate` is 100% or the fallback rate is disclosed.
