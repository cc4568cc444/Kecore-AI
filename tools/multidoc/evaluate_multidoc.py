#!/usr/bin/env python3
"""Evaluate the running financial RAG API by Multi-Doc-2025 task type."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = PROJECT_ROOT / "datasets" / "multi-doc-2025"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "evaluation-results" / "multidoc"
SUBSET_LABELS = {
    "S1": "single-doc-text",
    "S2": "single-doc-table",
    "S3": "cross-year",
    "S4": "cross-company",
    "S5": "cross-company-year-hybrid",
}
TOKEN = re.compile(r"[a-z0-9]+|[\u4e00-\u9fff]", re.IGNORECASE)
NUMBER = re.compile(r"[-+]?\d[\d,]*(?:\.\d+)?%?")
CITATION = re.compile(r"\[E\d+]", re.IGNORECASE)


def normalize_answer(value: str) -> str:
    return " ".join(TOKEN.findall((value or "").lower()))


def token_f1(expected: str, predicted: str) -> float:
    expected_tokens = TOKEN.findall((expected or "").lower())
    predicted_tokens = TOKEN.findall((predicted or "").lower())
    if not expected_tokens or not predicted_tokens:
        return 1.0 if expected_tokens == predicted_tokens else 0.0
    expected_counts: dict[str, int] = defaultdict(int)
    predicted_counts: dict[str, int] = defaultdict(int)
    for token in expected_tokens:
        expected_counts[token] += 1
    for token in predicted_tokens:
        predicted_counts[token] += 1
    common = sum(min(count, predicted_counts[token]) for token, count in expected_counts.items())
    if common == 0:
        return 0.0
    precision = common / len(predicted_tokens)
    recall = common / len(expected_tokens)
    return 2 * precision * recall / (precision + recall)


def numbers(value: str) -> set[str]:
    result: set[str] = set()
    for match in NUMBER.findall(value or ""):
        normalized = match.replace(",", "")
        suffix = "%" if normalized.endswith("%") else ""
        numeric = normalized.removesuffix("%")
        try:
            canonical = format(float(numeric), ".12g")
        except ValueError:
            continue
        result.add(canonical + suffix)
    return result


def numeric_accuracy(expected: str, predicted: str) -> float | None:
    expected_numbers = numbers(expected)
    if not expected_numbers:
        return None
    predicted_numbers = numbers(predicted)
    return len(expected_numbers & predicted_numbers) / len(expected_numbers)


def is_refusal(answer: str) -> bool:
    normalized = (answer or "").lower()
    return any(phrase in normalized for phrase in (
        "未通过引用校验", "无法从当前", "无法确定", "insufficient evidence", "cannot determine",
    ))


def expected_document_names(row: dict[str, Any]) -> set[str]:
    companies = [str(value) for value in row.get("companies") or []]
    if not companies:
        companies = [value for value in str(row.get("company") or "").split("+") if value]
    years = [str(value) for value in row.get("years_required") or []]
    if not years and row.get("year"):
        years = [str(row["year"])]
    if len(companies) <= 1 or len(years) <= 1:
        return {f"{company}_{year}.html" for company in companies for year in years}

    text = f"{row.get('question', '')} {row.get('answer', '')}"
    paired: set[str] = set()
    forward_companies: set[str] = set()
    for company in companies:
        for year in years:
            pattern = re.compile(rf"\b{re.escape(company)}\b(?P<between>.{{0,80}}?)\b(?:FY\s*)?{re.escape(year)}\b", re.IGNORECASE | re.DOTALL)
            match = pattern.search(text)
            other_company_between = match and any(
                other.upper() != company.upper()
                and re.search(rf"\b{re.escape(other)}\b", match.group("between"), re.IGNORECASE)
                for other in companies
            )
            if match and not other_company_between:
                paired.add(f"{company}_{year}.html")
                forward_companies.add(company.upper())
    for company in companies:
        if company.upper() in forward_companies:
            continue
        for year in years:
            pattern = re.compile(rf"\b(?:FY\s*)?{re.escape(year)}\b(?P<between>.{{0,80}}?)\b{re.escape(company)}\b", re.IGNORECASE | re.DOTALL)
            match = pattern.search(text)
            other_company_between = match and any(
                other.upper() != company.upper()
                and re.search(rf"\b{re.escape(other)}\b", match.group("between"), re.IGNORECASE)
                for other in companies
            )
            if match and not other_company_between:
                paired.add(f"{company}_{year}.html")
    return paired or {f"{company}_{year}.html" for company in companies for year in years}


def evaluate_row(row: dict[str, Any], prediction: str, latency_ms: int, error: str = "",
                 analysis: dict[str, Any] | None = None) -> dict[str, Any]:
    expected = str(row.get("answer") or "")
    numeric = numeric_accuracy(expected, prediction)
    analysis = analysis or {}
    retrieved_sources = sorted({
        str(item.get("sourceFile")) for item in analysis.get("evidence") or [] if item.get("sourceFile")
    })
    expected_sources = sorted(expected_document_names(row))
    source_hits = set(retrieved_sources) & set(expected_sources)
    document_recall = len(source_hits) / len(expected_sources) if expected_sources else None
    citation_audit = analysis.get("citationAudit") or {}
    retrieval_tasks = [task for task in analysis.get("tasks") or [] if str(task.get("operation") or "").lower() == "retrieve"]
    planned_task_ids = {str(task.get("id")) for task in retrieval_tasks if task.get("id")}
    matched_task_ids = {
        str(task_id)
        for evidence in analysis.get("evidence") or []
        for task_id in evidence.get("matchedTaskIds") or []
    }
    task_coverage = len(planned_task_ids & matched_task_ids) / len(planned_task_ids) if planned_task_ids else None
    return {
        "id": row.get("id"),
        "subset": str(row.get("subset") or "").upper(),
        "task_type": SUBSET_LABELS.get(str(row.get("subset") or "").upper(), "unknown"),
        "question": row.get("question"),
        "expected_answer": expected,
        "predicted_answer": prediction,
        "exact_match": normalize_answer(expected) == normalize_answer(prediction),
        "token_f1": token_f1(expected, prediction),
        "numeric_accuracy": numeric,
        "has_evidence_citation": bool(CITATION.search(prediction or "")),
        "citation_blocked": "未通过引用校验" in (prediction or ""),
        "citation_valid": citation_audit.get("valid"),
        "citation_issues": citation_audit.get("issues") or [],
        "refusal": is_refusal(prediction),
        "planner_intent": analysis.get("intent", ""),
        "task_count": len(analysis.get("tasks") or []),
        "retrieval_task_count": len(planned_task_ids),
        "matched_task_count": len(planned_task_ids & matched_task_ids),
        "task_coverage": task_coverage,
        "fact_count": len(analysis.get("facts") or []),
        "calculation_count": len(analysis.get("calculations") or []),
        "requires_calculation": bool(row.get("requires_calculation")),
        "expected_sources": expected_sources,
        "retrieved_sources": retrieved_sources,
        "retrieved_source_hits": sorted(source_hits),
        "document_recall": document_recall,
        "planning_ms": analysis.get("planningMs", 0),
        "retrieval_ms": analysis.get("retrievalMs", 0),
        "generation_ms": analysis.get("generationMs", 0),
        "latency_ms": latency_ms,
        "error": error,
    }


def average(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if not row["error"]]
    numeric_rows = [row for row in successful if row["numeric_accuracy"] is not None]
    retrieval_rows = [row for row in successful if row["document_recall"] is not None]
    audited_rows = [row for row in successful if row["citation_valid"] is not None]
    task_rows = [row for row in successful if row["task_coverage"] is not None]
    calculation_rows = [row for row in successful if row["requires_calculation"]]
    return {
        "count": len(rows),
        "successful": len(successful),
        "exact_match": average([float(row["exact_match"]) for row in successful]),
        "token_f1": average([float(row["token_f1"]) for row in successful]),
        "numeric_accuracy": average([float(row["numeric_accuracy"]) for row in numeric_rows]),
        "numeric_question_count": len(numeric_rows),
        "evidence_citation_rate": average([float(row["has_evidence_citation"]) for row in successful]),
        "citation_valid_rate": average([float(row["citation_valid"]) for row in audited_rows]),
        "citation_block_rate": average([float(row["citation_blocked"]) for row in successful]),
        "document_recall": average([float(row["document_recall"]) for row in retrieval_rows]),
        "retrieval_task_coverage": average([float(row["task_coverage"]) for row in task_rows]),
        "fact_extraction_rate": average([float(row["fact_count"] > 0) for row in successful]),
        "calculation_production_rate": average([float(row["calculation_count"] > 0) for row in calculation_rows]),
        "refusal_rate": average([float(row["refusal"]) for row in successful]),
        "average_latency_ms": average([float(row["latency_ms"]) for row in successful]),
    }


def report(results: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in results:
        groups[row["subset"]].append(row)
    return {
        "overall": summarize(results),
        "by_subset": {
            subset: {"task_type": SUBSET_LABELS.get(subset, "unknown"), **summarize(rows)}
            for subset, rows in sorted(groups.items())
        },
    }


def request_analysis(endpoint: str, row: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    params = {
        "prompt": str(row["question"]),
        "conv_id": f"multidoc-eval-{args.run_id}-{row['id']}",
        "retrievalStrategy": args.retrieval_strategy,
    }
    if args.model_id:
        params["modelId"] = args.model_id
    url = endpoint + ("&" if "?" in endpoint else "?") + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Kecore-AI-Eval/1.0"})
    with urllib.request.urlopen(request, timeout=args.timeout) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def parse_values(value: str, allowed: set[str]) -> list[str]:
    values = [item.strip().upper() for item in value.split(",") if item.strip()]
    invalid = sorted(set(values) - allowed)
    if invalid:
        raise argparse.ArgumentTypeError(f"unknown value(s): {', '.join(invalid)}")
    return values


def load_questions(data_dir: Path, split: str, subsets: list[str]) -> list[dict[str, Any]]:
    path = data_dir / f"{split}.json"
    if not path.exists():
        raise FileNotFoundError(f"Missing {path}; run multidoc_pipeline.py prepare first.")
    rows = json.loads(path.read_text(encoding="utf-8"))
    allowed = set(subsets)
    return [row for row in rows if str(row.get("subset") or "").upper() in allowed]


def balanced_limit(rows: list[dict[str, Any]], limit: int, subset_order: list[str]) -> list[dict[str, Any]]:
    if limit <= 0 or len(rows) <= limit:
        return rows
    buckets = {
        subset: [row for row in rows if str(row.get("subset") or "").upper() == subset]
        for subset in subset_order
    }
    selected: list[dict[str, Any]] = []
    offset = 0
    while len(selected) < limit:
        added = False
        for subset in subset_order:
            bucket = buckets[subset]
            if offset < len(bucket):
                selected.append(bucket[offset])
                added = True
                if len(selected) >= limit:
                    break
        if not added:
            break
        offset += 1
    return selected


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Evaluate financial RAG on Multi-Doc-2025 S1-S5")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8080/finance/analyze")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--split", choices=("train", "val", "test"), default="test")
    parser.add_argument("--subsets", type=lambda value: parse_values(value, set(SUBSET_LABELS)), default=["S3", "S4", "S5"])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--model-id", default="")
    parser.add_argument("--retrieval-strategy", choices=("default", "parent-child"), default="parent-child")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--workers", type=int, default=2, help="parallel evaluation requests")
    parser.add_argument("--run-id", default="", help="isolates evaluation conversations; defaults to a timestamp")
    parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if not args.run_id:
        args.run_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    try:
        questions = load_questions(args.data_dir, args.split, args.subsets)
    except (FileNotFoundError, json.JSONDecodeError) as exc:
        print(f"error: {exc}")
        return 1
    if args.limit:
        questions = balanced_limit(questions, args.limit, args.subsets)
    counts: dict[str, int] = defaultdict(int)
    for row in questions:
        counts[str(row["subset"]).upper()] += 1
    print(f"[selection] split={args.split}, questions={len(questions)}, by_subset={dict(sorted(counts.items()))}")
    if args.dry_run:
        return 0

    def run_one(index: int, row: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        started = time.perf_counter()
        prediction = error = ""
        analysis: dict[str, Any] = {}
        try:
            analysis = request_analysis(args.endpoint, row, args)
            prediction = str(analysis.get("answer") or "")
            error = str(analysis.get("error") or "")
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
            error = str(exc)
        latency_ms = int((time.perf_counter() - started) * 1000)
        result = evaluate_row(row, prediction, latency_ms, error, analysis)
        return index, result

    indexed_results: list[tuple[int, dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = [executor.submit(run_one, index, row) for index, row in enumerate(questions, start=1)]
        for completed, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            index, result = future.result()
            indexed_results.append((index, result))
            row = questions[index - 1]
            error = result["error"]
            recall = result["document_recall"]
            recall_text = "n/a" if recall is None else f"{recall:.3f}"
            print(f"[eval] {completed}/{len(questions)} {row['id']} {row['subset']} f1={result['token_f1']:.3f} doc_recall={recall_text} citation={result['has_evidence_citation']} error={bool(error)}", flush=True)
    results = [result for _, result in sorted(indexed_results, key=lambda item: item[0])]

    payload = {
        "dataset": "Multi-Doc-2025",
        "split": args.split,
        "subsets": args.subsets,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": report(results),
        "results": results,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    output = args.output_dir / f"{args.split}-{'-'.join(args.subsets).lower()}-{stamp}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
    print(f"[output] {output}")
    return 0 if payload["summary"]["overall"]["successful"] == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
