import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("evaluate_multidoc.py")
SPEC = importlib.util.spec_from_file_location("evaluate_multidoc", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class EvaluateMultiDocTest(unittest.TestCase):
    def test_ranked_document_names_preserves_context_order_and_deduplicates_chunks(self):
        ranked = MODULE.ranked_document_names([
            {"sourceFile": "B_2024.html", "chunkId": "b1"},
            {"sourceFile": "A_2024.html", "chunkId": "a1"},
            {"sourceFile": "B_2024.html", "chunkId": "b2"},
            {"sourceFile": "C_2024.html", "chunkId": "c1"},
        ])

        self.assertEqual(["B_2024.html", "A_2024.html", "C_2024.html"], ranked)

    def test_document_ranking_metrics_cover_multi_document_retrieval_quality(self):
        metrics = MODULE.document_ranking_metrics(
            {"A.html", "B.html", "C.html"}, ["noise.html", "A.html", "B.html", "C.html"], 3)

        self.assertAlmostEqual(2 / 3, metrics["recall"])
        self.assertEqual(0.5, metrics["mrr"])
        self.assertAlmostEqual((1 / 2 + 2 / 3) / 3, metrics["map"])
        self.assertGreater(metrics["ndcg"], 0.0)
        self.assertLess(metrics["ndcg"], 1.0)
        self.assertEqual(0.0, metrics["complete_recall"])

    def test_complete_document_recall_requires_every_gold_filing_in_top_k(self):
        expected = {"A.html", "B.html", "C.html"}

        self.assertEqual(0.0, MODULE.document_ranking_metrics(
            expected, ["A.html", "B.html", "noise.html", "C.html"], 3)["complete_recall"])
        self.assertEqual(1.0, MODULE.document_ranking_metrics(
            expected, ["A.html", "B.html", "noise.html", "C.html"], 5)["complete_recall"])

    def test_section_ranking_requires_the_right_item_within_each_filing(self):
        row = {
            "question": "Compare Item 7.", "evidence_section": "Item 7, Management's Discussion",
        }
        expected = MODULE.expected_section_keys(row, {"A.html", "B.html"})
        ranked = MODULE.ranked_section_keys([
            {"sourceFile": "A.html", "item": "Item 1"},
            {"sourceFile": "A.html", "item": "Item 7"},
            {"sourceFile": "B.html", "item": "Item 7"},
        ])
        metrics = MODULE.all_named_ranking_metrics("section", expected, ranked)

        self.assertEqual({("A.html", "item 7"), ("B.html", "item 7")}, expected)
        self.assertEqual(0.0, metrics["section_recall_at_1"])
        self.assertEqual(1.0, metrics["section_recall_at_3"])
        self.assertEqual(0.5, metrics["section_mrr_at_3"])

    def test_task_ranking_excludes_internal_scope_tasks_and_preserves_order(self):
        ranked = MODULE.ranked_task_ids([
            {"matchedTaskIds": ["task_b", "retrieve_explicit_2024"]},
            {"matchedTaskIds": ["task_a", "task_b"]},
        ])

        self.assertEqual(["task_b", "task_a"], ranked)
        metrics = MODULE.all_named_ranking_metrics("retrieval_task", {"task_a", "task_b"}, ranked)
        self.assertEqual(0.5, metrics["retrieval_task_recall_at_1"])
        self.assertEqual(1.0, metrics["retrieval_task_complete_recall_at_3"])

    def test_task_ranking_prefers_verified_task_ids(self):
        ranked = MODULE.ranked_task_ids([
            {"matchedTaskIds": ["task_unverified"], "verifiedTaskIds": ["task_verified"]},
        ])

        self.assertEqual(["task_verified"], ranked)

    def test_percentile_interpolates_latency_distribution(self):
        self.assertEqual(25.0, MODULE.percentile([10, 20, 30, 40], 50))
        self.assertAlmostEqual(38.5, MODULE.percentile([10, 20, 30, 40], 95))
        self.assertIsNone(MODULE.percentile([], 95))

    def test_answer_body_removes_citations_markdown_and_sources(self):
        cleaned = MODULE.answer_body("**ED differs from AEP** [E2].\n\nSources:\n[E2] ED_2024.html")

        self.assertEqual("ED differs from AEP .", cleaned)

    def test_overlap_metrics_ignore_source_appendix(self):
        expected = "ED explicitly discusses competition."
        predicted = "ED explicitly discusses competition [E2].\nSources:\n[E2] ED_2024.html"

        self.assertEqual(1.0, MODULE.token_f1(expected, MODULE.answer_body(predicted)))
        self.assertEqual(1.0, MODULE.rouge_l_f1(expected, MODULE.answer_body(predicted)))

    def test_numeric_accuracy_normalizes_commas(self):
        self.assertEqual(1.0, MODULE.numeric_accuracy("$2,186 million", "The value was $2186 million [E1]."))

    def test_numeric_metrics_accept_reasonable_rounding(self):
        precision, recall, f1 = MODULE.numeric_scores("The change was 2%.", "The change was 2.022% [E1].")

        self.assertEqual((1.0, 1.0, 1.0), (precision, recall, f1))

    def test_numeric_metrics_ignore_bare_ledger_ids_in_expressions(self):
        precision, recall, f1 = MODULE.numeric_scores(
            "$60,922 and $391,035; approximately 6.4 times",
            "$60,922 and $391,035; DIVIDE(F1 / F2) = 6.42 times [C1]",
        )

        self.assertEqual((1.0, 1.0, 1.0), (precision, recall, f1))

    def test_numeric_metrics_match_rounded_billions_to_exact_millions(self):
        self.assertEqual(1.0, MODULE.numeric_accuracy(
            "$2.4 billion; three years.", "$2,354 million and 3 fiscal years."))

    def test_numeric_metrics_count_written_number_of_years(self):
        precision, recall, f1 = MODULE.numeric_scores(
            "$2.4 billion; three years (2022, 2021, 2020).",
            "$2.4 billion, but the second clause was omitted.",
        )

        self.assertEqual(1.0, precision)
        self.assertEqual(0.5, recall)
        self.assertAlmostEqual(2 / 3, f1)

    def test_structured_percent_values_add_percent_marker(self):
        values = MODULE.structured_numeric_values(
            [{"result": 2.022, "unit": "percent"}], ("result",))

        self.assertEqual(["2.022%"], values)

    def test_calculation_result_matches_difference_derived_from_gold_values(self):
        score = MODULE.calculation_result_match(
            "How much did the executive's age change?",
            "The executive was 58 in one year and 59 in the next.",
            [{"type": "difference", "result": 1, "unit": "count"}],
        )

        self.assertEqual(1.0, score)

    def test_calculation_result_matches_rounded_percentage(self):
        score = MODULE.calculation_result_match(
            "What was the percentage change?",
            "Revenue rose from 28541.4 to 45042.7, or approximately 57.9%.",
            [{"type": "percentage_change", "result": 57.816, "unit": "percent"}],
        )

        self.assertEqual(1.0, score)

    def test_calculation_result_matches_derived_percentage_when_gold_rounds_more_coarsely(self):
        score = MODULE.calculation_result_match(
            "Calculate revenue growth.",
            "Revenue increased 3%, or $2.5 billion, to $91.4 billion. Prior revenue was $88.9 billion.",
            [{"type": "percentage_change", "result": 2.8, "unit": "percent"}],
        )

        self.assertEqual(1.0, score)

    def test_calculation_result_matches_scaled_intermediate_from_question(self):
        score = MODULE.calculation_result_match(
            "Net income increased by $2.6 billion. How many years are listed?",
            "$2.4 billion; three years.",
            [
                {"type": "difference", "result": 2618, "unit": "usd", "scale": "million"},
                {"type": "count", "result": 3, "unit": "count", "scale": "unit"},
            ],
        )

        self.assertEqual(1.0, score)

    def test_rescores_saved_result_without_model_analysis(self):
        saved = {
            "id": "q1", "subset": "S3", "expected_answer": "Age changed from 58 to 59.",
            "predicted_answer": "Age changed from 58 to 59 [E1].\nSources:\n[E1] filing",
            "fact_trace": [{"rawValue": 58}, {"rawValue": 59}],
            "calculation_trace": [{"type": "difference", "result": 1, "unit": "count"}],
            "document_recall": 1.0, "task_coverage": 1.0, "citation_valid": True,
        }

        result = MODULE.rescore_saved_result(saved, {})

        self.assertEqual(1.0, result["token_f1"])
        self.assertEqual(1.0, result["fact_value_recall"])
        self.assertEqual(1.0, result["structured_value_recall"])
        self.assertEqual(1.0, result["calculation_result_match"])

    def test_numeric_accuracy_ignores_filing_years_items_and_notes(self):
        self.assertIsNone(MODULE.numeric_accuracy(
            "ECL FY2022 Item 7 refers to Note 18 in its 10-K.",
            "ECL FY2022 Item 7 refers to Note 18 [E1].",
        ))

    def test_numeric_metrics_ignore_calendar_date_components(self):
        self.assertEqual(1.0, MODULE.numeric_accuracy(
            "As of December 28, 2024, the total was $5,132 million.",
            "The total was $5,132 million.",
        ))

    def test_fact_value_recall_compares_million_and_billion_scales(self):
        values = MODULE.structured_fact_numeric_values([
            {"value": "2354", "rawValue": "$2,354", "scale": "million", "unit": "usd"}
        ])

        self.assertEqual(1.0, MODULE.nullable_numeric_recall("approximately $2.4 billion", values))

    def test_report_separates_cross_year_and_cross_company(self):
        rows = [
            MODULE.evaluate_row({"id": "1", "subset": "S3", "answer": "10%"}, "10% [E1]", 10),
            MODULE.evaluate_row({"id": "2", "subset": "S4", "answer": "A"}, "A [E1]", 20),
        ]
        report = MODULE.report(rows)
        self.assertEqual("cross-year", report["by_subset"]["S3"]["task_type"])
        self.assertEqual("cross-company", report["by_subset"]["S4"]["task_type"])
        self.assertEqual(2, report["overall"]["count"])
        self.assertEqual(15.0, report["overall"]["latency_p50_ms"])
        self.assertAlmostEqual(19.5, report["overall"]["latency_p95_ms"])

    def test_report_keeps_raw_metrics_and_excludes_audited_issues_from_clean_summary(self):
        clean = MODULE.evaluate_row({"id": "good", "subset": "S4", "answer": "A"}, "A", 10)
        conflicted = MODULE.evaluate_row({"id": "bad", "subset": "S4", "answer": "B"}, "wrong", 10)
        clean["known_issue"] = None
        conflicted["known_issue"] = "audited label conflict"

        report = MODULE.report([clean, conflicted])

        self.assertEqual(2, report["overall"]["count"])
        self.assertEqual(1, report["clean_overall"]["count"])
        self.assertEqual(1, report["known_issue_count"])

    def test_detects_citation_blocked_answer(self):
        row = MODULE.evaluate_row(
            {"id": "1", "subset": "S5", "answer": "expected"},
            "当前回答未通过引用校验，因此未输出未经证实的结论。",
            15,
        )
        self.assertTrue(row["citation_blocked"])
        self.assertTrue(row["refusal"])

    def test_detects_english_insufficient_information_refusal(self):
        self.assertTrue(MODULE.is_refusal(
            "I cannot identify the answer because the evidence contains insufficient information."
        ))

    def test_detects_partial_comparison_refusal(self):
        self.assertTrue(MODULE.is_refusal(
            "A full comparison cannot be completed because one company's evidence is missing."
        ))

    def test_detects_qualified_partial_comparison_refusal(self):
        self.assertTrue(MODULE.is_refusal(
            "A direct comparison of the disclaimer content is not possible because ED evidence is incomplete."
        ))

    def test_detects_insufficient_for_full_comparison(self):
        self.assertTrue(MODULE.is_refusal(
            "The evidence is sufficient for AEP, but insufficient for a full comparison."
        ))

    def test_non_numeric_summary_marks_numeric_metrics_not_applicable(self):
        row = MODULE.evaluate_row(
            {"id": "1", "subset": "S4", "answer": "A differs from B."},
            "A differs from B [E1].",
            10,
        )

        summary = MODULE.summarize([row])

        self.assertIsNone(summary["numeric_accuracy"])
        self.assertEqual(0, summary["numeric_question_count"])
        self.assertIsNone(summary["fact_extraction_rate"])
        self.assertEqual(0, summary["fact_extraction_question_count"])
        self.assertIsNone(summary["calculation_production_rate"])
        self.assertEqual(0, summary["calculation_question_count"])

    def test_reports_backend_retrieval_degradation_separately_from_recall(self):
        row = MODULE.evaluate_row(
            {"id": "1", "subset": "S4", "answer": "A differs from B."},
            "A differs from B [E1].",
            10,
            analysis={
                "retrievalQuality": "DEGRADED",
                "retrievalWarnings": ["reranker_unavailable: lexical_vector_fallback"],
            },
        )

        summary = MODULE.summarize([row])

        self.assertEqual("DEGRADED", row["retrieval_quality_status"])
        self.assertEqual(1.0, summary["degraded_retrieval_rate"])
        self.assertEqual(1, summary["retrieval_diagnostic_count"])

    def test_measures_canonical_item_scope_contamination(self):
        result = MODULE.evaluate_row(
            {"id": "scope", "subset": "S4", "answer": "answer"},
            "answer [E1]",
            10,
            analysis={
                "tasks": [{"id": "retrieve_ed", "operation": "retrieve", "query": "ED FY2024 Item 7"}],
                "evidence": [
                    {"evidenceId": "E1", "item": "Item 7", "matchedTaskIds": ["retrieve_ed"]},
                    {"evidenceId": "E2", "item": "Item 8", "matchedTaskIds": ["retrieve_ed"]},
                ],
            },
        )

        self.assertEqual(0.5, result["scope_item_match_rate"])
        self.assertEqual(0.5, result["cross_section_contamination_rate"])

    def test_textual_count_keeps_numeric_scoring_without_requiring_financial_ledger(self):
        row = MODULE.evaluate_row(
            {
                "id": "text-count",
                "subset": "S5",
                "question": "How many distinct terms are mentioned? List the items.",
                "answer": "3 terms",
                "requires_calculation": True,
            },
            "3 terms [E1]",
            10,
            analysis={"facts": [], "calculations": []},
        )

        summary = MODULE.summarize([row])

        self.assertEqual(1, summary["numeric_question_count"])
        self.assertEqual(1.0, summary["numeric_accuracy"])
        self.assertIsNone(summary["fact_extraction_rate"])
        self.assertEqual(0, summary["fact_extraction_question_count"])
        self.assertIsNone(summary["calculation_production_rate"])
        self.assertEqual(0, summary["calculation_question_count"])

    def test_pairs_cross_company_year_documents_from_question(self):
        row = {
            "companies": ["LIN", "ECL"],
            "years_required": ["2022", "2024"],
            "question": "Compare ECL FY2022 with LIN FY2024.",
            "answer": "",
        }
        self.assertEqual(
            {"ECL_2022.html", "LIN_2024.html"},
            MODULE.expected_document_names(row),
        )

    def test_adds_single_company_multi_year_dataset_scope_without_answer_leakage(self):
        row = {
            "question": "Which years included Available Information?",
            "answer": "secret gold answer",
            "companies": ["LIN"],
            "years_required": ["2022", "2023", "2024"],
            "evidence_section": "Item 7",
        }

        prompt = MODULE.retrieval_prompt(row)

        self.assertIn("LIN FY2022; LIN FY2023; LIN FY2024", prompt)
        self.assertIn("evidence section=Item 7", prompt)
        self.assertNotIn("secret gold answer", prompt)

    def test_adds_cross_company_single_year_dataset_scope(self):
        prompt = MODULE.retrieval_prompt({
            "question": "Compare the disclaimers.",
            "companies": ["AEP", "ED"],
            "years_required": ["2024"],
        })

        self.assertIn("AEP FY2024; ED FY2024", prompt)

    def test_adds_explicit_pairs_for_cross_company_cross_year_scope(self):
        prompt = MODULE.retrieval_prompt({
            "question": "Compare ECL FY2022 with LIN FY2024.",
            "companies": ["LIN", "ECL"],
            "years_required": ["2022", "2024"],
        })

        self.assertIn("ECL FY2022", prompt)
        self.assertIn("LIN FY2024", prompt)
        self.assertNotIn("LIN FY2022", prompt)
        self.assertNotIn("ECL FY2024", prompt)

    def test_expands_joint_company_and_year_lists_to_cross_product_scope(self):
        prompt = MODULE.retrieval_prompt({
            "question": "Compare LLY and PFE for fiscal years 2022 and 2024.",
            "companies": ["LLY", "PFE"],
            "years_required": ["2022", "2024"],
        })

        for scope in ("LLY FY2022", "LLY FY2024", "PFE FY2022", "PFE FY2024"):
            self.assertIn(scope, prompt)

    def test_recognizes_transient_model_overload_errors(self):
        self.assertTrue(MODULE.transient_analysis_error(
            '429 - {"message":"The engine is currently overloaded"}'))
        self.assertTrue(MODULE.transient_analysis_error("503 Service Unavailable"))
        self.assertFalse(MODULE.transient_analysis_error("missing evidence"))

    def test_daily_token_quota_error_fails_fast_instead_of_retrying(self):
        self.assertFalse(MODULE.transient_analysis_error(
            "429 request reached organization TPD rate limit, current: 1504410, limit: 1500000"))

    def test_recognizes_transient_transport_errors(self):
        self.assertTrue(MODULE.transient_analysis_error("I/O error: connection reset by peer"))
        self.assertTrue(MODULE.transient_analysis_error("request timed out"))
        self.assertTrue(MODULE.transient_analysis_error("unexpected end of file from upstream"))

    def test_atomic_checkpoint_is_valid_json(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent) as directory:
            path = Path(directory) / "run.partial.json"
            MODULE.write_json_atomic(path, {"status": "in_progress", "completed_count": 3})

            self.assertEqual(
                {"status": "in_progress", "completed_count": 3},
                json.loads(path.read_text(encoding="utf-8")),
            )
            self.assertFalse(path.with_suffix(path.suffix + ".tmp").exists())

    def test_dataset_scope_can_be_disabled_for_ablation(self):
        row = {"question": "Standalone question", "companies": ["LIN"], "years_required": ["2024"]}

        self.assertEqual("Standalone question", MODULE.retrieval_prompt(row, include_dataset_scope=False))

    def test_execution_id_prevents_reused_run_label_from_reusing_conversation(self):
        first = MODULE.evaluation_conversation_id("concise", "exec-1", "q1")
        second = MODULE.evaluation_conversation_id("concise", "exec-2", "q1")

        self.assertNotEqual(first, second)
        self.assertTrue(first.endswith("exec-1-q1"))

    def test_structured_analysis_reports_document_recall(self):
        row = {
            "id": "1", "subset": "S3", "company": "AAPL", "companies": ["AAPL"],
            "years_required": ["2023", "2024"], "answer": "20%",
        }
        analysis = {
            "intent": "trend",
            "citationAudit": {"valid": True, "issues": []},
            "tasks": [
                {"id": "retrieve_2023", "operation": "retrieve"},
                {"id": "retrieve_2024", "operation": "retrieve"},
            ],
            "evidence": [
                {"sourceFile": "AAPL_2023.html", "matchedTaskIds": ["retrieve_2023"]},
                {"sourceFile": "AAPL_2024.html", "matchedTaskIds": ["retrieve_2024"]},
            ],
            "facts": [{"rawValue": "20%"}], "calculations": [{"result": "20%"}],
        }
        result = MODULE.evaluate_row(row, "20% [C1][E1][E2]", 30, analysis=analysis)
        self.assertEqual(1.0, result["document_recall"])
        self.assertEqual(0.5, result["document_recall_at_1"])
        self.assertEqual(1.0, result["document_recall_at_3"])
        self.assertEqual(1.0, result["document_mrr_at_10"])
        self.assertEqual(1.0, result["document_map_at_10"])
        self.assertEqual(1.0, result["document_ndcg_at_10"])
        self.assertEqual(1.0, result["document_complete_recall_at_3"])
        self.assertEqual(1.0, result["task_coverage"])
        self.assertTrue(result["citation_valid"])
        self.assertEqual(1.0, result["fact_value_recall"])
        self.assertEqual("20%", result["fact_trace"][0]["rawValue"])
        self.assertEqual("20%", result["calculation_trace"][0]["result"])
        self.assertGreater(result["rag_quality_score"], 0.0)

    def test_records_compact_evidence_trace_without_chunk_content(self):
        analysis = {
            "evidence": [{
                "evidenceId": "E1", "chunkId": "chunk-887", "sourceFile": "ED_2024.html",
                "company": "ED", "fiscalYear": "2024", "sectionTitle": "External Environment",
                "matchedTaskIds": ["retrieve_ed"], "content": "large filing passage",
            }]
        }

        result = MODULE.evaluate_row(
            {"id": "1", "subset": "S4", "answer": "answer"}, "answer [E1]", 10, analysis=analysis)

        self.assertEqual("chunk-887", result["evidence_trace"][0]["chunkId"])
        self.assertNotIn("content", result["evidence_trace"][0])

    def test_answer_task_coverage_ignores_citations_only_in_sources(self):
        analysis = {
            "tasks": [
                {"id": "task_a", "operation": "retrieve"},
                {"id": "task_b", "operation": "retrieve"},
            ],
            "evidence": [
                {"evidenceId": "E1", "matchedTaskIds": ["task_a"]},
                {"evidenceId": "E2", "matchedTaskIds": ["task_b"]},
            ],
        }

        result = MODULE.evaluate_row(
            {"id": "1", "subset": "S5", "answer": "A and B."},
            "A is supported [E1].\nSources:\n[E1] A\n[E2] B", 10, analysis=analysis,
        )

        self.assertEqual(1, result["answered_task_count"])
        self.assertEqual(0.5, result["answer_task_coverage"])

    def test_answer_task_coverage_excludes_metadata_only_scope_tasks(self):
        analysis = {
            "tasks": [
                {"id": "retrieve_2022", "operation": "retrieve"},
                {"id": "retrieve_2024", "operation": "retrieve"},
                {"id": "retrieve_explicit_lly_2023", "operation": "retrieve"},
            ],
            "evidence": [
                {"evidenceId": "E1", "matchedTaskIds": ["retrieve_2022"]},
                {"evidenceId": "E2", "matchedTaskIds": ["retrieve_2024"]},
                {"evidenceId": "E3", "matchedTaskIds": ["retrieve_explicit_lly_2023"]},
            ],
        }

        result = MODULE.evaluate_row(
            {"id": "1", "subset": "S3", "answer": "Revenue changed."},
            "Revenue changed [E1][E2].", 10, analysis=analysis,
        )

        self.assertEqual(1.0, result["answer_task_coverage"])
        self.assertEqual(1.0, result["task_coverage"])
        self.assertEqual(2, result["retrieval_task_count"])

    def test_answer_task_coverage_uses_company_year_when_cited_evidence_lost_task_ids(self):
        analysis = {
            "tasks": [
                {"id": "retrieve_amd", "operation": "retrieve", "companies": ["AMD"], "years": ["2024"]},
                {"id": "retrieve_msft", "operation": "retrieve", "companies": ["MSFT"], "years": ["2024"]},
            ],
            "evidence": [
                {"evidenceId": "E1", "company": "AMD", "fiscalYear": "2024",
                 "matchedTaskIds": ["retrieve_amd"]},
                {"evidenceId": "E2", "company": "MSFT", "fiscalYear": "2024", "matchedTaskIds": []},
            ],
        }

        result = MODULE.evaluate_row(
            {"id": "1", "subset": "S4", "answer": "AMD and Microsoft."},
            "AMD [E1]; Microsoft [E2].", 10, analysis=analysis,
        )

        self.assertEqual(1.0, result["answer_task_coverage"])

    def test_limit_is_balanced_across_requested_subsets(self):
        rows = ([{"id": f"s3-{i}", "subset": "S3"} for i in range(5)]
                + [{"id": f"s4-{i}", "subset": "S4"} for i in range(5)]
                + [{"id": f"s5-{i}", "subset": "S5"} for i in range(5)])

        selected = MODULE.balanced_limit(rows, 6, ["S3", "S4", "S5"])

        self.assertEqual(["S3", "S4", "S5", "S3", "S4", "S5"], [row["subset"] for row in selected])

    def test_selects_target_question_ids_in_requested_order(self):
        rows = [{"id": "q1"}, {"id": "q2"}, {"id": "q3"}]

        selected = MODULE.select_question_ids(rows, ["q3", "q1"])

        self.assertEqual(["q3", "q1"], [row["id"] for row in selected])

    def test_rejects_unknown_target_question_id(self):
        with self.assertRaisesRegex(ValueError, "unknown question id"):
            MODULE.select_question_ids([{"id": "q1"}], ["missing"])

    def test_formats_saved_system_answer_and_diagnostic_traces(self):
        detail = MODULE.format_result_details([{
            "id": "md2025_1558",
            "subset": "S4",
            "task_type": "cross-company",
            "question": "Compare AEP and ED.",
            "expected_answer": "ED has an explicit objective.",
            "predicted_answer": "ED seeks shareholder value [E2].",
            "token_f1": 0.5,
            "citation_valid": True,
            "retrieval_quality_status": "NORMAL",
            "retrieved_sources": ["AEP_2024.html", "ED_2024.html"],
            "task_trace": [{"id": "retrieve_ed"}],
            "evidence_trace": [{"evidenceId": "E2"}],
        }])

        self.assertIn("[Question]", detail)
        self.assertIn("[Expected answer]", detail)
        self.assertIn("[System answer]", detail)
        self.assertIn("ED seeks shareholder value [E2].", detail)
        self.assertIn("retrieve_ed", detail)
        self.assertIn("ED_2024.html", detail)

    def test_detailed_console_output_uses_portable_ascii_punctuation(self):
        detail = MODULE.format_result_details([{
            "id": "q1", "question": "AEP\u2019s objective", "expected_answer": "A\u2014B",
            "predicted_answer": "\u201cquoted\u201d", "retrieved_sources": [],
        }])

        self.assertIn("AEP's objective", detail)
        self.assertIn("A-B", detail)
        self.assertIn('"quoted"', detail)
        self.assertNotIn("\u2019", detail)
        self.assertNotIn("\u2014", detail)

    def test_parser_accepts_saved_report_inspection_options(self):
        args = MODULE.build_parser().parse_args([
            "--inspect-report", "saved.json", "--question-ids", "q2,q1", "--show-details"
        ])

        self.assertEqual(Path("saved.json"), args.inspect_report)
        self.assertEqual(["q2", "q1"], args.question_ids)
        self.assertTrue(args.show_details)

    def test_parser_accepts_resume_report(self):
        args = MODULE.build_parser().parse_args(["--resume-report", "run.partial.json"])

        self.assertEqual(Path("run.partial.json"), args.resume_report)

    def test_compact_summary_omits_exact_match_metrics(self):
        row = MODULE.evaluate_row(
            {"id": "q1", "subset": "S4", "answer": "expected wording"},
            "valid paraphrase [E1]", 25,
            analysis={"citationAudit": {"valid": True}, "evidence": [{"evidenceId": "E1"}]},
        )
        summary = MODULE.report([row])
        line = MODULE.format_summary_line(summary)

        self.assertNotIn("strict_exact_match", row)
        self.assertNotIn("exact_match", row)
        self.assertNotIn("contains_expected", row)
        self.assertNotIn("exact", line)
        self.assertIn("success=1/1", line)
        self.assertIn("f1=", line)

    def test_rescore_removes_legacy_exact_match_fields(self):
        rescored = MODULE.rescore_saved_result({
            "id": "q1", "subset": "S4", "question": "Compare", "expected_answer": "A",
            "predicted_answer": "A", "strict_exact_match": True, "exact_match": True,
            "contains_expected": True, "error": "", "document_recall": None,
            "citation_valid": None, "requires_calculation": False,
        }, {})

        self.assertNotIn("strict_exact_match", rescored)
        self.assertNotIn("exact_match", rescored)
        self.assertNotIn("contains_expected", rescored)


if __name__ == "__main__":
    unittest.main()
