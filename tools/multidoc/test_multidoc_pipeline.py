import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("multidoc_pipeline.py")
SPEC = importlib.util.spec_from_file_location("multidoc_pipeline", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MultiDocPipelineTest(unittest.TestCase):
    def test_cross_company_year_candidates_use_safe_cross_product(self):
        row = {
            "company": "LIN+ECL",
            "year": "2024",
            "companies": ["LIN", "ECL"],
            "years_required": ["2022", "2024"],
        }
        self.assertEqual(
            {
                "LIN_2022.html", "LIN_2024.html",
                "ECL_2022.html", "ECL_2024.html",
            },
            MODULE.candidate_document_names(row),
        )

    def test_required_documents_intersects_official_manifest(self):
        rows = [{"companies": ["LIN", "ECL"], "years_required": ["2022", "2024"]}]
        manifest = [
            MODULE.RemoteDocument("original_doc/LIN_2024.html", 10),
            MODULE.RemoteDocument("original_doc/ECL_2022.html", 20),
            MODULE.RemoteDocument("original_doc/MSFT_2024.html", 30),
        ]
        selected = MODULE.required_documents(rows, manifest)
        self.assertEqual(["ECL_2022.html", "LIN_2024.html"], [item.name for item in selected])

    def test_document_metadata_keeps_task_dimensions(self):
        rows = [{
            "subset": "S5", "_split": "test", "companies": ["LIN", "ECL"],
            "years_required": ["2022", "2024"], "sector": "Materials",
            "is_cross_doc": True, "is_cross_year": True, "is_hybrid_modal": True,
            "requires_calculation": False, "evidence_section": "Item 7",
        }]
        metadata = MODULE.document_metadata(rows, ["LIN_2024.html"])[0]
        self.assertEqual(["S5"], metadata["subsets"])
        self.assertEqual(["test"], metadata["splits"])
        self.assertTrue(metadata["is_cross_doc"])
        self.assertTrue(metadata["is_cross_year"])

    def test_reused_parser_keeps_full_dataset_metadata(self):
        s2 = MODULE.load_s2_module()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "LIN_2024.html"
            path.write_text(
                "<html><body><h1>Item 7. Management Discussion</h1><p>"
                + ("Revenue increased because industrial demand improved. " * 8)
                + "</p></body></html>",
                encoding="utf-8",
            )
            chunks = s2.parse_html_to_chunks(
                path,
                {"company": "LIN", "year": "2024", "subset": "multi", "subsets": ["S3", "S5"], "split": "multi", "splits": ["test"]},
                chunk_chars=1400,
                overlap=180,
            )
        self.assertTrue(chunks)
        self.assertEqual("multi", chunks[0].subset)
        self.assertTrue(chunks[0].chunk_id.startswith("md2025-"))
        self.assertEqual(["S3", "S5"], chunks[0].metadata["subsets"])


if __name__ == "__main__":
    unittest.main()
