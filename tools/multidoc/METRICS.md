# Multi-Doc evaluation metrics

The current report format is identified by `metric_version: deterministic-v6`.

- `token_precision`, `token_recall`, `token_f1`, `rouge_l_f1`: answer-body overlap metrics; citation and source formatting does not lower these scores.
- `numeric_precision`, `numeric_recall`, `numeric_f1`: one-to-one numeric matching with a small rounding tolerance. Calendar dates, filing years, Item/Note numbers, and citation IDs are excluded; written counts such as `three years` are recognized.
- `fact_extraction_rate`: applies only to questions whose gold answer contains a financial numeric value that belongs
  in the numerical fact ledger. Lexical counts of words, phrases, or distinct mentioned items still receive numeric
  answer scoring, but do not require a `FinancialFact`.
- `fact_value_recall`: measures whether structured `facts` contain gold-answer values, rather than only checking whether any fact object exists; million/billion scale equivalents are normalized before comparison.
- `structured_value_recall`: measures whether verified facts and deterministic calculations together cover the numerical values in the gold answer. This avoids penalizing a derived total merely because it correctly appears in `calculations` instead of `facts`.
- `calculation_production_rate` and `calculation_result_match`: apply to financial arithmetic questions marked
  `requires_calculation`. Textual enumeration/counting questions are excluded because they do not use the financial
  arithmetic ledger.
  Calculation results are checked against both the question premises and the gold answer, with million/billion normalization, because intermediate verification results may legitimately be stated only in the question.
- `retrieval_task_coverage`: counts user-facing retrieval subtasks only. Internal `retrieve_explicit_*` anchor queries improve recall but are excluded from the denominator so redundant retrieval does not lower task coverage.
- `document_recall`, `retrieval_task_coverage`, `citation_valid_rate`: retrieval and grounding diagnostics, kept separate from answer similarity.
- `document_recall_at_{1,3,5,10}`: fraction of all gold filing documents present in the ordered Top-K context.
  This is the primary retrieval metric for multi-document questions because finding only one of several filings is
  insufficient.
- `document_mrr_at_{1,3,5,10}`: reciprocal rank of the first relevant filing. It measures how early useful evidence
  appears, but should be reported together with Recall@K because MRR alone ignores missing companion documents.
- `document_map_at_{1,3,5,10}` and `document_ndcg_at_{1,3,5,10}`: binary-relevance ranking quality across every gold
  filing. Repeated chunks from the same filing are collapsed while preserving the final context order.
- `document_complete_recall_at_{1,3,5,10}`: fraction of questions for which every required filing is present in Top-K.
  This strict all-documents success rate is especially relevant to S3-S5 comparisons.
- `section_recall/mrr/map/ndcg/complete_recall_at_{1,3,5,10}`: the same ranking family over
  `(filing, Item)` pairs derived from `evidence_section`. These metrics expose cases where the right filing is
  retrieved but the wrong 10-K Item occupies the context. They are N/A when the dataset has no parseable Item label.
- `retrieval_task_recall/mrr/map/ndcg/complete_recall_at_{1,3,5,10}`: ranking quality over the planner's user-facing
  retrieval subtasks. Internal `retrieve_explicit_*` routing tasks are excluded. This measures whether every side of
  a comparison is represented early enough in the final evidence order.
- `retrieval_ranking_question_count`: denominator used for the document-ranking metrics.
- `latency_p50_ms`, `latency_p95_ms`: end-to-end client latency percentiles. Planning, retrieval, and generation
  P50/P95 values are also reported separately so model latency is not confused with retrieval latency.
- `answer_task_coverage`: fraction of planned retrieval tasks represented by matching citations in the answer body; citations appearing only in the Sources appendix do not count.
- `degraded_retrieval_rate`: fraction of requests whose backend reported a degraded retrieval path, such as an unavailable reranker or unresolved company/year/Item scope. Older reports without diagnostics remain N/A.
- `retrieval_warnings`: per-question structured retrieval degradation reasons emitted by the backend.
- `scope_item_match_rate`: fraction of task-linked evidence whose canonical Item matches the Item requested by that retrieval task.
- `cross_section_contamination_rate`: complement of `scope_item_match_rate`; lower is better.
- `rag_quality_score`: project-level diagnostic score comprising 50% answer score, 30% average document/task coverage, and 20% citation grounding. This is not an official Multi-Doc-2025 metric.
- `clean_overall`: excludes only question IDs recorded in `known_issues.json`. `overall` always retains every official sample for transparent comparison.

Exact-match metrics are intentionally omitted because valid cited RAG answers are generative and rarely reproduce the
reference wording verbatim. Every result includes compact `evidence_trace`, `fact_trace`, and `calculation_trace`
fields for auditability.

The document- and section-ranking metrics use Multi-Doc-2025's filing/evidence-section labels. They do not claim
chunk-level relevance because the dataset does not provide exhaustive relevant-chunk judgments. With dataset scope enabled, report that the
company/year scope came from dataset metadata; use `--no-dataset-scope` for a full-corpus retrieval ablation.

Existing reports that already contain these traces can be rescored without any API calls:

```powershell
python tools/multidoc/evaluate_multidoc.py --rescore-report evaluation-results/multidoc/previous-report.json
```

The console prints one compact summary line by default; complete metrics remain in the saved JSON report. Add
`--show-summary` to print the complete aggregate metric JSON. To also print each question, gold answer, complete
system answer, retrieved sources, tasks, and traces during a new run, add `--show-details`.

To inspect an existing report without calling the model or spending API credit:

```powershell
python tools/multidoc/evaluate_multidoc.py --inspect-report evaluation-results/multidoc/previous-report.json
```

Use `--question-ids id1,id2` with `--inspect-report` to display only selected questions.
