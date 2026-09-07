import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("evaluate_multidoc.py")
SPEC = importlib.util.spec_from_file_location("evaluate_multidoc", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class EvaluateMultiDocTest(unittest.TestCase):
    def test_numeric_accuracy_normalizes_commas(self):
        self.assertEqual(1.0, MODULE.numeric_accuracy("$2,186 million", "The value was $2186 million [E1]."))

    def test_report_separates_cross_year_and_cross_company(self):
        rows = [
            MODULE.evaluate_row({"id": "1", "subset": "S3", "answer": "10%"}, "10% [E1]", 10),
            MODULE.evaluate_row({"id": "2", "subset": "S4", "answer": "A"}, "A [E1]", 20),
        ]
        report = MODULE.report(rows)
        self.assertEqual("cross-year", report["by_subset"]["S3"]["task_type"])
        self.assertEqual("cross-company", report["by_subset"]["S4"]["task_type"])
        self.assertEqual(2, report["overall"]["count"])

    def test_detects_citation_blocked_answer(self):
        row = MODULE.evaluate_row(
            {"id": "1", "subset": "S5", "answer": "expected"},
            "当前回答未通过引用校验，因此未输出未经证实的结论。",
            15,
        )
        self.assertTrue(row["citation_blocked"])
        self.assertTrue(row["refusal"])

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
            "facts": [{}, {}], "calculations": [{}],
        }
        result = MODULE.evaluate_row(row, "20% [C1][E1][E2]", 30, analysis=analysis)
        self.assertEqual(1.0, result["document_recall"])
        self.assertEqual(1.0, result["task_coverage"])
        self.assertTrue(result["citation_valid"])

    def test_limit_is_balanced_across_requested_subsets(self):
        rows = ([{"id": f"s3-{i}", "subset": "S3"} for i in range(5)]
                + [{"id": f"s4-{i}", "subset": "S4"} for i in range(5)]
                + [{"id": f"s5-{i}", "subset": "S5"} for i in range(5)])

        selected = MODULE.balanced_limit(rows, 6, ["S3", "S4", "S5"])

        self.assertEqual(["S3", "S4", "S5", "S3", "S4", "S5"], [row["subset"] for row in selected])


if __name__ == "__main__":
    unittest.main()
