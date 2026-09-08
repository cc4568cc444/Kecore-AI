# Multi-Doc evaluation metrics

The current report format is identified by `metric_version: deterministic-v3`.

- `strict_exact_match`: legacy normalized equality including the complete generated response. It is retained only as a strict baseline.
- `exact_match`: normalized equality after removing citation markers, Markdown decoration, and the Sources appendix.
- `contains_expected_rate`: normalized gold-answer containment in the answer body.
- `token_precision`, `token_recall`, `token_f1`, `rouge_l_f1`: answer-body overlap metrics; citation and source formatting does not lower these scores.
- `numeric_precision`, `numeric_recall`, `numeric_f1`: one-to-one numeric matching with a small rounding tolerance. Calendar dates, filing years, Item/Note numbers, and citation IDs are excluded; written counts such as `three years` are recognized.
- `fact_extraction_rate`: applies only to questions whose gold answer contains a real numeric value.
- `fact_value_recall`: measures whether structured `facts` contain gold-answer values, rather than only checking whether any fact object exists; million/billion scale equivalents are normalized before comparison.
- `calculation_production_rate` and `calculation_result_match`: apply only to questions marked `requires_calculation`.
- `document_recall`, `retrieval_task_coverage`, `citation_valid_rate`: retrieval and grounding diagnostics, kept separate from answer similarity.
- `answer_task_coverage`: fraction of planned retrieval tasks represented by matching citations in the answer body; citations appearing only in the Sources appendix do not count.
- `rag_quality_score`: project-level diagnostic score comprising 50% answer score, 30% average document/task coverage, and 20% citation grounding. This is not an official Multi-Doc-2025 metric.
- `clean_overall`: excludes only question IDs recorded in `known_issues.json`. `overall` always retains every official sample for transparent comparison.

Every result includes compact `evidence_trace`, `fact_trace`, and `calculation_trace` fields for auditability.

Existing reports that already contain these traces can be rescored without any API calls:

```powershell
python tools/multidoc/evaluate_multidoc.py --rescore-report evaluation-results/multidoc/previous-report.json
```
