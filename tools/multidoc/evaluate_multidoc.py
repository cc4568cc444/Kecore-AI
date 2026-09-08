#!/usr/bin/env python3
"""Evaluate the running financial RAG API by Multi-Doc-2025 task type."""

from __future__ import annotations

import argparse
import concurrent.futures
import html
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = PROJECT_ROOT / "datasets" / "multi-doc-2025"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "evaluation-results" / "multidoc"
DEFAULT_KNOWN_ISSUES = Path(__file__).with_name("known_issues.json")
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
REFERENCE_MARKER = re.compile(r"\[(?:E|F|C)\d+(?:\s*[;,]\s*(?:E|F|C)?\d+)*]", re.IGNORECASE)
SOURCE_HEADING = re.compile(r"^\s*(?:[#>*_-]+\s*)*(?:sources?|来源)\s*[:：]?", re.IGNORECASE)
PARTIAL_REFUSAL = re.compile(
    r"(?:\b(?:comparison|answer)\b.{0,180}\b(?:is not possible|cannot be completed)\b|"
    r"\binsufficient\b.{0,100}\b(?:comparison|answer)\b)",
    re.IGNORECASE | re.DOTALL,
)
FILING_IDENTIFIER = re.compile(
    r"\b(?:FY\s*)?20\d{2}\b|\b(?:Item|Part|Section|Note)\s+\d+[A-Z]?(?:\.\d+)?\b|\b10-[KQ]\b",
    re.IGNORECASE,
)
CALENDAR_DATE = re.compile(
    r"\b(?:January|February|March|April|May|June|July|August|September|October|November|December)"
    r"\s+\d{1,2},\s+20\d{2}\b",
    re.IGNORECASE,
)
COUNT_WORD = re.compile(
    r"\b(one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:fiscal\s+)?years?\b",
    re.IGNORECASE,
)
COUNT_WORD_VALUES = {
    "one": 1.0, "two": 2.0, "three": 3.0, "four": 4.0, "five": 5.0,
    "six": 6.0, "seven": 7.0, "eight": 8.0, "nine": 9.0, "ten": 10.0,
}


def normalize_answer(value: str) -> str:
    return " ".join(TOKEN.findall((value or "").lower()))


def answer_main_text(value: str) -> str:
    lines: list[str] = []
    for line in html.unescape(value or "").splitlines():
        heading_candidate = re.sub(r"[*_`]", "", line)
        if SOURCE_HEADING.match(heading_candidate):
            break
        lines.append(line)
    return "\n".join(lines)


def answer_body(value: str) -> str:
    body = REFERENCE_MARKER.sub(" ", answer_main_text(value))
    body = re.sub(r"[`*_>#|]", " ", body)
    return re.sub(r"\s+", " ", body).strip()


def cited_task_coverage(prediction: str, evidence: list[dict[str, Any]], task_count: int) -> tuple[int, float | None]:
    if task_count <= 0:
        return 0, None
    cited_evidence_ids = {citation[1:-1].upper() for citation in CITATION.findall(answer_main_text(prediction))}
    answered_task_ids = {
        str(task_id)
        for item in evidence
        if str(item.get("evidenceId") or "").upper() in cited_evidence_ids
        for task_id in item.get("matchedTaskIds") or []
        if not str(task_id).startswith("retrieve_explicit_")
    }
    answered_count = min(task_count, len(answered_task_ids))
    return answered_count, answered_count / task_count


def token_overlap(expected: str, predicted: str) -> tuple[float, float, float]:
    expected_tokens = TOKEN.findall((expected or "").lower())
    predicted_tokens = TOKEN.findall((predicted or "").lower())
    if not expected_tokens or not predicted_tokens:
        score = 1.0 if expected_tokens == predicted_tokens else 0.0
        return score, score, score
    expected_counts: dict[str, int] = defaultdict(int)
    predicted_counts: dict[str, int] = defaultdict(int)
    for token in expected_tokens:
        expected_counts[token] += 1
    for token in predicted_tokens:
        predicted_counts[token] += 1
    common = sum(min(count, predicted_counts[token]) for token, count in expected_counts.items())
    if common == 0:
        return 0.0, 0.0, 0.0
    precision = common / len(predicted_tokens)
    recall = common / len(expected_tokens)
    return precision, recall, 2 * precision * recall / (precision + recall)


def token_f1(expected: str, predicted: str) -> float:
    return token_overlap(expected, predicted)[2]


def rouge_l_f1(expected: str, predicted: str) -> float:
    expected_tokens = TOKEN.findall((expected or "").lower())
    predicted_tokens = TOKEN.findall((predicted or "").lower())
    if not expected_tokens or not predicted_tokens:
        return 1.0 if expected_tokens == predicted_tokens else 0.0
    previous = [0] * (len(predicted_tokens) + 1)
    for expected_token in expected_tokens:
        current = [0]
        for index, predicted_token in enumerate(predicted_tokens, start=1):
            if expected_token == predicted_token:
                current.append(previous[index - 1] + 1)
            else:
                current.append(max(current[-1], previous[index]))
        previous = current
    lcs = previous[-1]
    precision = lcs / len(predicted_tokens)
    recall = lcs / len(expected_tokens)
    return 2 * precision * recall / (precision + recall) if lcs else 0.0


def numbers(value: str) -> set[str]:
    result: set[str] = set()
    without_filing_identifiers = FILING_IDENTIFIER.sub("", value or "")
    for match in NUMBER.findall(without_filing_identifiers):
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
    metrics = numeric_scores(expected, predicted)
    return metrics[1]


def numeric_scores(expected: str, predicted: str) -> tuple[float | None, float | None, float | None]:
    expected_numbers = _numeric_values(expected)
    if not expected_numbers:
        return None, None, None
    predicted_numbers = _numeric_values(predicted)
    used: set[int] = set()
    matched = 0
    for expected_value, expected_percent in expected_numbers:
        for index, (predicted_value, predicted_percent) in enumerate(predicted_numbers):
            if index in used or expected_percent != predicted_percent:
                continue
            if numeric_values_match(expected_value, predicted_value, expected_percent):
                used.add(index)
                matched += 1
                break
    precision = matched / len(predicted_numbers) if predicted_numbers else 0.0
    recall = matched / len(expected_numbers)
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def numeric_values_match(expected: float, predicted: float, percent: bool = False) -> bool:
    tolerance = max(0.15 if percent else 0.05, abs(expected) * 0.001)
    return abs(expected - predicted) <= tolerance


def _numeric_values(value: str) -> list[tuple[float, bool]]:
    cleaned = answer_body(value)
    word_counts = [COUNT_WORD_VALUES[match.lower()] for match in COUNT_WORD.findall(cleaned)]
    without_identifiers = FILING_IDENTIFIER.sub("", CALENDAR_DATE.sub("", cleaned))
    result: list[tuple[float, bool]] = []
    seen: set[tuple[float, bool]] = set()
    for word_count in word_counts:
        item = (word_count, False)
        if item not in seen:
            seen.add(item)
            result.append(item)
    for match in NUMBER.findall(without_identifiers):
        normalized = match.replace(",", "")
        percent = normalized.endswith("%")
        try:
            parsed = float(normalized.removesuffix("%"))
        except ValueError:
            continue
        item = (parsed, percent)
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def nullable_numeric_recall(expected: str, values: list[Any]) -> float | None:
    if not values:
        return None
    return numeric_accuracy(expected, " ".join(str(value) for value in values if value not in (None, "")))


def structured_numeric_values(items: list[dict[str, Any]], value_keys: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for item in items:
        unit = str(item.get("unit") or "").lower()
        for key in value_keys:
            value = item.get(key)
            if value in (None, ""):
                continue
            rendered = str(value)
            if unit == "percent" and not rendered.endswith("%"):
                rendered += "%"
            result.append(rendered)
    return result


def structured_fact_numeric_values(items: list[dict[str, Any]]) -> list[str]:
    result = structured_numeric_values(items, ("rawValue", "value"))
    for item in items:
        try:
            value = float(str(item.get("value") or "").replace(",", ""))
        except ValueError:
            continue
        scale = str(item.get("scale") or "unit").lower()
        if scale == "million":
            result.append(f"{value / 1000:g}")
        elif scale == "billion":
            result.append(f"{value * 1000:g}")
    return result


def calculation_result_match(expected: str, calculations: list[dict[str, Any]]) -> float | None:
    gold_values = _numeric_values(expected)
    if not calculations or not gold_values:
        return None
    matched = 0
    comparable = 0
    plain_gold = [value for value, percent in gold_values if not percent]
    for calculation in calculations:
        rendered = structured_numeric_values([calculation], ("result", "displayResult"))
        result_values = _numeric_values(" ".join(rendered))
        if not result_values:
            continue
        comparable += 1
        result_value, result_percent = result_values[0]
        direct_match = any(
            result_percent == gold_percent and numeric_values_match(gold_value, result_value, gold_percent)
            for gold_value, gold_percent in gold_values
        )
        derived_match = False
        calculation_type = str(calculation.get("type") or "").lower()
        if not direct_match and len(plain_gold) >= 2 and calculation_type == "difference":
            derived_match = any(
                numeric_values_match(abs(right - left), abs(result_value))
                for index, left in enumerate(plain_gold)
                for right in plain_gold[index + 1:]
            )
        if not direct_match and len(plain_gold) >= 2 and calculation_type == "percentage_change":
            derived_match = any(
                left != 0 and numeric_values_match((right - left) / left * 100, result_value, True)
                for index, left in enumerate(plain_gold)
                for right in plain_gold[index + 1:]
            )
        if direct_match or derived_match:
            matched += 1
    return matched / comparable if comparable else None


def is_refusal(answer: str) -> bool:
    normalized = (answer or "").lower()
    english_refusals = (
        "insufficient evidence", "insufficient information", "cannot determine",
        "cannot identify", "unable to determine", "unable to identify",
        "full comparison is not possible", "full comparison cannot be completed",
        "evidence does not contain sufficient information",
    )
    if any(phrase in normalized for phrase in english_refusals):
        return True
    if PARTIAL_REFUSAL.search(normalized):
        return True
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


def retrieval_prompt(row: dict[str, Any], include_dataset_scope: bool = True) -> str:
    question = str(row.get("question") or "").strip()
    if not include_dataset_scope:
        return question
    companies = [str(value).strip().upper() for value in row.get("companies") or [] if str(value).strip()]
    if not companies:
        companies = [value.strip().upper() for value in str(row.get("company") or "").split("+") if value.strip()]
    years = [str(value).strip().upper().removeprefix("FY")
             for value in row.get("years_required") or [] if str(value).strip()]
    if not years and row.get("year"):
        years = [str(row["year"]).strip().upper().removeprefix("FY")]

    scopes: list[str] = []
    if len(companies) == 1:
        scopes = [f"{companies[0]} FY{year}" for year in years]
    elif len(years) == 1:
        scopes = [f"{company} FY{years[0]}" for company in companies]
    if not scopes:
        return question
    section = str(row.get("evidence_section") or "").strip()
    section_hint = f"; evidence section={section}" if section else ""
    return (f"{question}\n\nDataset retrieval scope (metadata only): "
            f"documentScopes={'; '.join(scopes)}{section_hint}.")


def evaluate_row(row: dict[str, Any], prediction: str, latency_ms: int, error: str = "",
                 analysis: dict[str, Any] | None = None) -> dict[str, Any]:
    expected = str(row.get("answer") or "")
    expected_body = answer_body(expected)
    predicted_body = answer_body(prediction)
    token_precision, token_recall, overlap_f1 = token_overlap(expected_body, predicted_body)
    numeric_precision, numeric_recall, numeric_f1 = numeric_scores(expected_body, predicted_body)
    analysis = analysis or {}
    facts = analysis.get("facts") or []
    calculations = analysis.get("calculations") or []
    fact_values = structured_fact_numeric_values(facts)
    fact_trace = [{
        key: fact.get(key)
        for key in ("factId", "evidenceId", "company", "fiscalYear", "metric", "rawValue", "value", "unit", "scale")
        if fact.get(key) not in (None, "", [])
    } for fact in facts]
    calculation_trace = [{
        key: calculation.get(key)
        for key in ("calculationId", "type", "expression", "result", "displayResult", "unit", "scale", "sourceFactIds")
        if calculation.get(key) not in (None, "", [])
    } for calculation in calculations]
    evidence_trace = [
        {
            key: item.get(key)
            for key in (
                "evidenceId", "chunkId", "sourceFile", "company", "fiscalYear",
                "modality", "item", "sectionTitle", "matchedTaskIds",
            )
            if item.get(key) not in (None, "", [])
        }
        for item in analysis.get("evidence") or []
    ]
    retrieved_sources = sorted({
        str(item.get("sourceFile")) for item in analysis.get("evidence") or [] if item.get("sourceFile")
    })
    expected_sources = sorted(expected_document_names(row))
    source_hits = set(retrieved_sources) & set(expected_sources)
    document_recall = len(source_hits) / len(expected_sources) if expected_sources else None
    citation_audit = analysis.get("citationAudit") or {}
    retrieval_tasks = [task for task in analysis.get("tasks") or [] if str(task.get("operation") or "").lower() == "retrieve"]
    planned_task_ids = {str(task.get("id")) for task in retrieval_tasks if task.get("id")}
    answerable_task_ids = {
        task_id for task_id in planned_task_ids if not task_id.startswith("retrieve_explicit_")
    }
    matched_task_ids = {
        str(task_id)
        for evidence in analysis.get("evidence") or []
        for task_id in evidence.get("matchedTaskIds") or []
    }
    task_coverage = len(planned_task_ids & matched_task_ids) / len(planned_task_ids) if planned_task_ids else None
    answered_task_count, answer_task_coverage = cited_task_coverage(
        prediction, analysis.get("evidence") or [], len(answerable_task_ids))
    answer_overlap = max(overlap_f1, rouge_l_f1(expected_body, predicted_body))
    answer_quality = numeric_f1 if numeric_f1 is not None else answer_overlap
    retrieval_components = [value for value in (document_recall, task_coverage) if value is not None]
    retrieval_quality = average(retrieval_components) if retrieval_components else 0.0
    grounding_quality = float(citation_audit.get("valid")) if citation_audit.get("valid") is not None \
        else float(bool(CITATION.search(prediction or "")))
    rag_quality_score = 0.5 * answer_quality + 0.3 * retrieval_quality + 0.2 * grounding_quality
    return {
        "id": row.get("id"),
        "subset": str(row.get("subset") or "").upper(),
        "task_type": SUBSET_LABELS.get(str(row.get("subset") or "").upper(), "unknown"),
        "question": row.get("question"),
        "expected_answer": expected,
        "predicted_answer": prediction,
        "strict_exact_match": normalize_answer(expected) == normalize_answer(prediction),
        "exact_match": normalize_answer(expected_body) == normalize_answer(predicted_body),
        "contains_expected": normalize_answer(expected_body) in normalize_answer(predicted_body),
        "token_precision": token_precision,
        "token_recall": token_recall,
        "token_f1": overlap_f1,
        "rouge_l_f1": rouge_l_f1(expected_body, predicted_body),
        "answer_overlap_score": answer_overlap,
        "numeric_precision": numeric_precision,
        "numeric_recall": numeric_recall,
        "numeric_f1": numeric_f1,
        "numeric_accuracy": numeric_recall,
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
        "answered_task_count": answered_task_count,
        "answer_task_coverage": answer_task_coverage,
        "fact_count": len(facts),
        "fact_trace": fact_trace,
        "fact_extracted": bool(facts) if numeric_recall is not None else None,
        "fact_value_recall": nullable_numeric_recall(expected_body, fact_values) if numeric_recall is not None else None,
        "calculation_count": len(calculations),
        "calculation_trace": calculation_trace,
        "calculation_result_match": calculation_result_match(expected_body, calculations),
        "requires_calculation": bool(row.get("requires_calculation")),
        "expected_sources": expected_sources,
        "retrieved_sources": retrieved_sources,
        "evidence_trace": evidence_trace,
        "retrieved_source_hits": sorted(source_hits),
        "document_recall": document_recall,
        "rag_quality_score": rag_quality_score,
        "planning_ms": analysis.get("planningMs", 0),
        "retrieval_ms": analysis.get("retrievalMs", 0),
        "generation_ms": analysis.get("generationMs", 0),
        "latency_ms": latency_ms,
        "error": error,
    }


def rescore_saved_result(saved: dict[str, Any], known_issues: dict[str, str]) -> dict[str, Any]:
    result = dict(saved)
    expected = answer_body(str(saved.get("expected_answer") or ""))
    predicted_raw = str(saved.get("predicted_answer") or "")
    predicted = answer_body(predicted_raw)
    token_precision, token_recall, overlap_f1 = token_overlap(expected, predicted)
    numeric_precision, numeric_recall, numeric_f1 = numeric_scores(expected, predicted)
    rouge = rouge_l_f1(expected, predicted)
    overlap = max(overlap_f1, rouge)
    facts = list(saved.get("fact_trace") or [])
    calculations = list(saved.get("calculation_trace") or [])
    fact_values = structured_fact_numeric_values(facts)
    document_recall = saved.get("document_recall")
    task_coverage = saved.get("task_coverage")
    saved_evidence = list(saved.get("evidence_trace") or [])
    explicit_task_ids = {
        str(task_id)
        for item in saved_evidence
        for task_id in item.get("matchedTaskIds") or []
        if str(task_id).startswith("retrieve_explicit_")
    }
    answerable_task_count = max(0, int(saved.get("retrieval_task_count") or 0) - len(explicit_task_ids))
    answered_task_count, answer_task_coverage = cited_task_coverage(
        predicted_raw, saved_evidence, answerable_task_count
    )
    retrieval_components = [float(value) for value in (document_recall, task_coverage) if value is not None]
    retrieval_quality = average(retrieval_components) if retrieval_components else 0.0
    citation_valid = saved.get("citation_valid")
    grounding_quality = float(citation_valid) if citation_valid is not None \
        else float(bool(CITATION.search(predicted_raw)))
    answer_quality = numeric_f1 if numeric_f1 is not None else overlap
    result.update({
        "strict_exact_match": normalize_answer(str(saved.get("expected_answer") or ""))
        == normalize_answer(predicted_raw),
        "exact_match": normalize_answer(expected) == normalize_answer(predicted),
        "contains_expected": normalize_answer(expected) in normalize_answer(predicted),
        "token_precision": token_precision,
        "token_recall": token_recall,
        "token_f1": overlap_f1,
        "rouge_l_f1": rouge,
        "answer_overlap_score": overlap,
        "numeric_precision": numeric_precision,
        "numeric_recall": numeric_recall,
        "numeric_f1": numeric_f1,
        "numeric_accuracy": numeric_recall,
        "fact_extracted": bool(facts) if numeric_recall is not None else None,
        "fact_value_recall": nullable_numeric_recall(expected, fact_values) if numeric_recall is not None else None,
        "calculation_result_match": calculation_result_match(expected, calculations),
        "answered_task_count": answered_task_count,
        "answer_task_coverage": answer_task_coverage,
        "refusal": is_refusal(predicted_raw),
        "rag_quality_score": 0.5 * answer_quality + 0.3 * retrieval_quality + 0.2 * grounding_quality,
        "known_issue": known_issues.get(str(saved.get("id") or "")),
    })
    return result


def rescore_report(path: Path, output_dir: Path, known_issues_path: Path) -> Path:
    payload = json.loads(path.read_text(encoding="utf-8"))
    known_issues = load_known_issues(known_issues_path)
    results = [rescore_saved_result(row, known_issues) for row in payload.get("results") or []]
    rescored = dict(payload)
    rescored.update({
        "metric_version": "deterministic-v3",
        "rescored_from": str(path.resolve()),
        "rescored_at": datetime.now(timezone.utc).isoformat(),
        "summary": report(results),
        "results": results,
    })
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    output = output_dir / f"{path.stem}-rescored-{stamp}.json"
    output.write_text(json.dumps(rescored, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


def average(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def applicable_average(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if not row["error"]]
    numeric_rows = [row for row in successful if row["numeric_recall"] is not None]
    fact_value_rows = [row for row in numeric_rows if row["fact_value_recall"] is not None]
    retrieval_rows = [row for row in successful if row["document_recall"] is not None]
    audited_rows = [row for row in successful if row["citation_valid"] is not None]
    task_rows = [row for row in successful if row["task_coverage"] is not None]
    answer_task_rows = [row for row in successful if row.get("answer_task_coverage") is not None]
    calculation_rows = [row for row in successful if row["requires_calculation"]]
    return {
        "count": len(rows),
        "successful": len(successful),
        "strict_exact_match": average([float(row["strict_exact_match"]) for row in successful]),
        "exact_match": average([float(row["exact_match"]) for row in successful]),
        "contains_expected_rate": average([float(row["contains_expected"]) for row in successful]),
        "token_precision": average([float(row["token_precision"]) for row in successful]),
        "token_recall": average([float(row["token_recall"]) for row in successful]),
        "token_f1": average([float(row["token_f1"]) for row in successful]),
        "rouge_l_f1": average([float(row["rouge_l_f1"]) for row in successful]),
        "answer_overlap_score": average([float(row["answer_overlap_score"]) for row in successful]),
        "numeric_precision": applicable_average([float(row["numeric_precision"]) for row in numeric_rows]),
        "numeric_recall": applicable_average([float(row["numeric_recall"]) for row in numeric_rows]),
        "numeric_f1": applicable_average([float(row["numeric_f1"]) for row in numeric_rows]),
        "numeric_accuracy": applicable_average([float(row["numeric_recall"]) for row in numeric_rows]),
        "numeric_question_count": len(numeric_rows),
        "evidence_citation_rate": average([float(row["has_evidence_citation"]) for row in successful]),
        "citation_valid_rate": average([float(row["citation_valid"]) for row in audited_rows]),
        "citation_block_rate": average([float(row["citation_blocked"]) for row in successful]),
        "document_recall": average([float(row["document_recall"]) for row in retrieval_rows]),
        "retrieval_task_coverage": average([float(row["task_coverage"]) for row in task_rows]),
        "answer_task_coverage": applicable_average(
            [float(row["answer_task_coverage"]) for row in answer_task_rows]
        ),
        "fact_extraction_rate": applicable_average([float(row["fact_extracted"]) for row in numeric_rows]),
        "fact_extraction_question_count": len(numeric_rows),
        "fact_value_recall": applicable_average([float(row["fact_value_recall"]) for row in fact_value_rows]),
        "fact_value_question_count": len(fact_value_rows),
        "calculation_production_rate": applicable_average(
            [float(row["calculation_count"] > 0) for row in calculation_rows]
        ),
        "calculation_question_count": len(calculation_rows),
        "calculation_result_match": applicable_average([
            float(row["calculation_result_match"])
            for row in calculation_rows if row["calculation_result_match"] is not None
        ]),
        "refusal_rate": average([float(row["refusal"]) for row in successful]),
        "rag_quality_score": average([float(row["rag_quality_score"]) for row in successful]),
        "average_latency_ms": average([float(row["latency_ms"]) for row in successful]),
    }


def report(results: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in results:
        groups[row["subset"]].append(row)
    clean_results = [row for row in results if not row.get("known_issue")]
    return {
        "overall": summarize(results),
        "clean_overall": summarize(clean_results),
        "known_issue_count": len(results) - len(clean_results),
        "by_subset": {
            subset: {
                "task_type": SUBSET_LABELS.get(subset, "unknown"),
                "known_issue_count": len([row for row in rows if row.get("known_issue")]),
                **summarize(rows),
            }
            for subset, rows in sorted(groups.items())
        },
    }


def evaluation_conversation_id(run_id: str, execution_id: str, question_id: str) -> str:
    return f"multidoc-eval-{run_id}-{execution_id}-{question_id}"


def request_analysis(endpoint: str, row: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    params = {
        "prompt": retrieval_prompt(row, args.use_dataset_scope),
        "conv_id": evaluation_conversation_id(args.run_id, args.execution_id, str(row["id"])),
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


def load_known_issues(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"known issues file must contain a JSON object: {path}")
    return {str(key): str(value) for key, value in payload.items()}


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


def select_question_ids(rows: list[dict[str, Any]], question_ids: list[str]) -> list[dict[str, Any]]:
    if not question_ids:
        return rows
    by_id = {str(row.get("id") or "").lower(): row for row in rows}
    missing = [question_id for question_id in question_ids if question_id.lower() not in by_id]
    if missing:
        raise ValueError(f"unknown question id(s): {', '.join(missing)}")
    return [by_id[question_id.lower()] for question_id in question_ids]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Evaluate financial RAG on Multi-Doc-2025 S1-S5")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8080/finance/analyze")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--known-issues", type=Path, default=DEFAULT_KNOWN_ISSUES,
                        help="audited label-conflict registry used for the additional clean summary")
    parser.add_argument("--rescore-report", type=Path,
                        help="recompute deterministic metrics for an existing JSON report without API calls")
    parser.add_argument("--split", choices=("train", "val", "test"), default="test")
    parser.add_argument("--subsets", type=lambda value: parse_values(value, set(SUBSET_LABELS)), default=["S3", "S4", "S5"])
    parser.add_argument("--question-ids", type=lambda value: [item.strip() for item in value.split(",") if item.strip()],
                        default=[], help="comma-separated question IDs for inexpensive targeted reruns")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--model-id", default="")
    parser.add_argument("--retrieval-strategy", choices=("default", "parent-child"), default="parent-child")
    parser.add_argument("--timeout", type=int, default=600,
                        help="per-question HTTP timeout in seconds; complex S5 requests can exceed five minutes")
    parser.add_argument("--workers", type=int, default=2, help="parallel evaluation requests")
    parser.add_argument("--run-id", default="", help="human-readable experiment label; defaults to a timestamp")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-dataset-scope", dest="use_dataset_scope", action="store_false",
                        help="omit company/year scope supplied by Multi-Doc-2025 metadata (ablation only)")
    parser.set_defaults(use_dataset_scope=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.rescore_report:
        try:
            output = rescore_report(args.rescore_report, args.output_dir, args.known_issues)
            payload = json.loads(output.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
            print(f"error: {exc}")
            return 1
        print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
        print(f"[output] {output}")
        return 0
    if not args.run_id:
        args.run_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    args.execution_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f") + "-" + uuid.uuid4().hex[:8]
    try:
        questions = load_questions(args.data_dir, args.split, args.subsets)
        questions = select_question_ids(questions, args.question_ids)
        known_issues = load_known_issues(args.known_issues)
    except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
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
        result["known_issue"] = known_issues.get(str(row.get("id") or ""))
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
            print(
                f"[eval] {completed}/{len(questions)} {row['id']} {row['subset']} "
                f"f1={result['token_f1']:.3f} quality={result['rag_quality_score']:.3f} "
                f"doc_recall={recall_text} citation={result['has_evidence_citation']} error={bool(error)}",
                flush=True,
            )
    results = [result for _, result in sorted(indexed_results, key=lambda item: item[0])]

    payload = {
        "dataset": "Multi-Doc-2025",
        "metric_version": "deterministic-v3",
        "split": args.split,
        "subsets": args.subsets,
        "run_id": args.run_id,
        "execution_id": args.execution_id,
        "dataset_scope_enabled": args.use_dataset_scope,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": report(results),
        "results": results,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    safe_run_id = re.sub(r"[^A-Za-z0-9._-]+", "-", args.run_id).strip("-.") or "run"
    output = args.output_dir / f"{args.split}-{'-'.join(args.subsets).lower()}-{safe_run_id}-{stamp}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
    print(f"[output] {output}")
    return 0 if payload["summary"]["overall"]["successful"] == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
