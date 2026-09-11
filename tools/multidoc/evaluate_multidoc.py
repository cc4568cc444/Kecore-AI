#!/usr/bin/env python3
"""Evaluate the running financial RAG API by Multi-Doc-2025 task type."""

from __future__ import annotations

import argparse
import concurrent.futures
import html
import json
import math
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
BARE_LEDGER_REFERENCE = re.compile(r"\b(?:E|F|C)\d+\b", re.IGNORECASE)
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
ITEM_REFERENCE = re.compile(r"\bitem\s+(\d+[a-z]?)\b", re.IGNORECASE)
COUNT_WORD_VALUES = {
    "one": 1.0, "two": 2.0, "three": 3.0, "four": 4.0, "five": 5.0,
    "six": 6.0, "seven": 7.0, "eight": 8.0, "nine": 9.0, "ten": 10.0,
}
RETRIEVAL_CUTOFFS = (1, 3, 5, 10)


def requested_item(value: str) -> str:
    match = ITEM_REFERENCE.search(value or "")
    return f"item {match.group(1).lower()}" if match else ""


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
    body = BARE_LEDGER_REFERENCE.sub(" ", body)
    body = re.sub(r"[`*_>#|]", " ", body)
    return re.sub(r"\s+", " ", body).strip()


def cited_task_coverage(prediction: str, evidence: list[dict[str, Any]],
                        tasks: list[dict[str, Any]], facts: list[dict[str, Any]] | None = None
                        ) -> tuple[int, float | None]:
    if not tasks:
        return 0, None
    cited_evidence_ids = {citation[1:-1].upper() for citation in CITATION.findall(answer_main_text(prediction))}
    cited_evidence = [
        item for item in evidence if str(item.get("evidenceId") or "").upper() in cited_evidence_ids
    ]
    cited_fact_ids = {
        citation.upper() for citation in re.findall(
            r"(?i)(?<![A-Z0-9])F\d+(?![A-Z0-9])", answer_main_text(prediction))
    }
    cited_facts = [item for item in facts or [] if str(item.get("factId") or "").upper() in cited_fact_ids]
    answered_task_ids = {
        str(task_id)
        for item in evidence
        if str(item.get("evidenceId") or "").upper() in cited_evidence_ids
        for task_id in evidence_task_ids(item)
        if not str(task_id).startswith("retrieve_explicit_")
    }
    for task in tasks:
        task_id = str(task.get("id") or "")
        if not task_id or task_id in answered_task_ids:
            continue
        companies = {str(value).upper() for value in task.get("companies") or [] if value}
        years = {str(value) for value in task.get("years") or [] if value}
        scope_match = any(
            (not companies or str(item.get("company") or "").upper() in companies)
            and (not years or str(item.get("fiscalYear") or "") in years)
            for item in cited_evidence
        )
        scope_match = scope_match or any(
            (not companies or str(item.get("company") or "").upper() in companies)
            and (not years or str(item.get("fiscalYear") or "") in years)
            for item in cited_facts
        )
        if scope_match and (companies or years):
            answered_task_ids.add(task_id)
    answered_count = min(len(tasks), len(answered_task_ids))
    return answered_count, answered_count / len(tasks)


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
    if abs(expected - predicted) <= tolerance:
        return True
    if percent:
        return False
    # Financial gold answers frequently round billions while filings expose exact millions.
    # Compare the adjacent thousand-scale representations without relaxing ordinary values.
    if abs(predicted) >= 1000 and abs(expected) < 100:
        return abs(expected - predicted / 1000) <= tolerance
    if abs(expected) >= 1000 and abs(predicted) < 100:
        scaled_tolerance = max(50.0, abs(expected) * 0.001)
        return abs(expected - predicted * 1000) <= scaled_tolerance
    return False


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


def structured_calculation_numeric_values(item: dict[str, Any]) -> list[str]:
    result = structured_numeric_values([item], ("result", "displayResult"))
    try:
        value = float(str(item.get("result") or "").replace(",", ""))
    except ValueError:
        return result
    scale = str(item.get("scale") or "unit").lower()
    if scale == "million":
        result.append(f"{value / 1000:g}")
    elif scale == "billion":
        result.append(f"{value * 1000:g}")
    return result


def calculation_result_match(question: str, expected: str,
                             calculations: list[dict[str, Any]]) -> float | None:
    gold_values = _numeric_values(f"{question} {expected}")
    if not calculations or not gold_values:
        return None
    matched = 0
    comparable = 0
    plain_gold = [value for value, percent in gold_values if not percent]
    for calculation in calculations:
        rendered = structured_calculation_numeric_values(calculation)
        result_values = _numeric_values(" ".join(rendered))
        if not result_values:
            continue
        comparable += 1
        direct_match = any(
            result_percent == gold_percent and numeric_values_match(gold_value, result_value, gold_percent)
            for result_value, result_percent in result_values
            for gold_value, gold_percent in gold_values
        )
        derived_match = False
        calculation_type = str(calculation.get("type") or "").lower()
        if not direct_match and len(plain_gold) >= 2 and calculation_type == "difference":
            derived_match = any(
                numeric_values_match(abs(right - left), abs(result_value))
                for result_value, result_percent in result_values if not result_percent
                for index, left in enumerate(plain_gold)
                for right in plain_gold[index + 1:]
            )
        if not direct_match and len(plain_gold) >= 2 and calculation_type == "percentage_change":
            derived_match = any(
                left != 0 and numeric_values_match((right - left) / left * 100, result_value, True)
                for result_value, result_percent in result_values if result_percent
                for left_index, left in enumerate(plain_gold)
                for right_index, right in enumerate(plain_gold) if right_index != left_index
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


def ranked_document_names(evidence: list[dict[str, Any]]) -> list[str]:
    """Return the final context order with repeated chunks from one filing collapsed."""
    ranked: list[str] = []
    seen: set[str] = set()
    for item in evidence or []:
        source = str(item.get("sourceFile") or "").strip()
        if not source or source in seen:
            continue
        seen.add(source)
        ranked.append(source)
    return ranked


def document_ranking_metrics(expected: set[str], ranked: list[str], cutoff: int) -> dict[str, float] | None:
    """Compute binary-relevance document metrics for a ranked Top-K context."""
    if not expected:
        return None
    top_k = ranked[:cutoff]
    relevance = [1 if source in expected else 0 for source in top_k]
    relevant_count = sum(relevance)
    reciprocal_rank = next((1.0 / rank for rank, relevant in enumerate(relevance, start=1) if relevant), 0.0)
    precision_sum = sum(
        sum(relevance[:rank]) / rank
        for rank, relevant in enumerate(relevance, start=1)
        if relevant
    )
    average_precision = precision_sum / min(len(expected), cutoff)
    dcg = sum(relevant / math.log2(rank + 1) for rank, relevant in enumerate(relevance, start=1))
    ideal_count = min(len(expected), cutoff)
    ideal_dcg = sum(1.0 / math.log2(rank + 1) for rank in range(1, ideal_count + 1))
    return {
        "recall": relevant_count / len(expected),
        "mrr": reciprocal_rank,
        "map": average_precision,
        "ndcg": dcg / ideal_dcg if ideal_dcg else 0.0,
        "complete_recall": float(expected.issubset(set(top_k))),
    }


def all_document_ranking_metrics(expected: set[str], ranked: list[str]) -> dict[str, float | None]:
    metrics: dict[str, float | None] = {}
    for cutoff in RETRIEVAL_CUTOFFS:
        at_k = document_ranking_metrics(expected, ranked, cutoff)
        for name in ("recall", "mrr", "map", "ndcg", "complete_recall"):
            metrics[f"document_{name}_at_{cutoff}"] = at_k[name] if at_k is not None else None
    return metrics


def expected_section_keys(row: dict[str, Any], expected_sources: set[str]) -> set[tuple[str, str]]:
    """Build gold filing-section units from the dataset's document and evidence_section labels."""
    section = requested_item(str(row.get("evidence_section") or ""))
    if not section:
        section = requested_item(str(row.get("question") or ""))
    return {(source, section) for source in expected_sources} if section else set()


def ranked_section_keys(evidence: list[dict[str, Any]]) -> list[tuple[str, str]]:
    ranked: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for item in evidence or []:
        source = str(item.get("sourceFile") or "").strip()
        section = requested_item(str(item.get("item") or ""))
        key = (source, section)
        if not source or not section or key in seen:
            continue
        seen.add(key)
        ranked.append(key)
    return ranked


def ranked_task_ids(evidence: list[dict[str, Any]]) -> list[str]:
    ranked: list[str] = []
    seen: set[str] = set()
    for item in evidence or []:
        for value in evidence_task_ids(item):
            task_id = str(value)
            if not task_id or task_id.startswith("retrieve_explicit_") or task_id in seen:
                continue
            seen.add(task_id)
            ranked.append(task_id)
    return ranked


def evidence_task_ids(item: dict[str, Any]) -> list[Any]:
    """Prefer independently scope-verified task labels while retaining old-report compatibility."""
    if "verifiedTaskIds" in item:
        return list(item.get("verifiedTaskIds") or [])
    return list(item.get("matchedTaskIds") or [])


def all_named_ranking_metrics(prefix: str, expected: set[Any], ranked: list[Any]
                              ) -> dict[str, float | None]:
    metrics: dict[str, float | None] = {}
    for cutoff in RETRIEVAL_CUTOFFS:
        at_k = document_ranking_metrics(expected, ranked, cutoff)
        for name in ("recall", "mrr", "map", "ndcg", "complete_recall"):
            metrics[f"{prefix}_{name}_at_{cutoff}"] = at_k[name] if at_k is not None else None
    return metrics


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

    scopes = retrieval_document_scopes(question, companies, years)
    if not scopes:
        return question
    section = str(row.get("evidence_section") or "").strip()
    section_hint = f"; evidence section={section}" if section else ""
    return (f"{question}\n\nDataset retrieval scope (metadata only): "
            f"documentScopes={'; '.join(scopes)}{section_hint}.")


def retrieval_document_scopes(question: str, companies: list[str], years: list[str]) -> list[str]:
    if not companies or not years:
        return []
    if len(companies) == 1 or len(years) == 1:
        return [f"{company} FY{year}" for company in companies for year in years]

    paired: list[tuple[str, str]] = []
    for company in companies:
        for year in years:
            forward = re.search(
                rf"\b{re.escape(company)}(?:'s)?\s+(?:(?:in|for)\s+)?(?:FY\s*)?{re.escape(year)}\b",
                question, re.IGNORECASE,
            )
            reverse = re.search(
                rf"\b(?:FY\s*)?{re.escape(year)}\s+(?:filing\s+)?(?:of\s+)?{re.escape(company)}\b",
                question, re.IGNORECASE,
            )
            if forward or reverse:
                paired.append((company, year))
    paired_companies = {company.upper() for company, _ in paired}
    paired_years = {year for _, year in paired}
    selected = paired if paired_companies == {company.upper() for company in companies} \
        and paired_years == set(years) else [(company, year) for company in companies for year in years]
    return [f"{company} FY{year}" for company, year in selected]


def is_textual_count_question(question: str) -> bool:
    normalized = (question or "").lower()
    asks_for_count = bool(re.search(r"\b(?:how many|count)\b", normalized))
    counts_text_units = bool(re.search(
        r"\b(?:distinct\s+)?(?:items?|terms?|words?|phrases?|sentences?|mentions?|occurrences?)\b",
        normalized,
    ))
    return asks_for_count and counts_text_units


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
    calculation_plans = analysis.get("calculationPlans") or []
    fact_values = structured_fact_numeric_values(facts)
    calculation_values = [
        value for calculation in calculations for value in structured_calculation_numeric_values(calculation)
    ]
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
                "modality", "item", "sectionTitle", "matchedTaskIds", "verifiedTaskIds",
            )
            if item.get(key) not in (None, "", [])
        }
        for item in analysis.get("evidence") or []
    ]
    retrieved_sources = ranked_document_names(analysis.get("evidence") or [])
    expected_sources = sorted(expected_document_names(row))
    source_hits = set(retrieved_sources) & set(expected_sources)
    document_recall = len(source_hits) / len(expected_sources) if expected_sources else None
    ranking_metrics = all_document_ranking_metrics(set(expected_sources), retrieved_sources)
    expected_sections = expected_section_keys(row, set(expected_sources))
    section_ranking_metrics = all_named_ranking_metrics(
        "section", expected_sections, ranked_section_keys(analysis.get("evidence") or []))
    citation_audit = analysis.get("citationAudit") or {}
    retrieval_tasks = [task for task in analysis.get("tasks") or [] if str(task.get("operation") or "").lower() == "retrieve"]
    planned_task_ids = {str(task.get("id")) for task in retrieval_tasks if task.get("id")}
    answerable_task_ids = {
        task_id for task_id in planned_task_ids if not task_id.startswith("retrieve_explicit_")
    }
    matched_task_ids = {
        str(task_id)
        for evidence in analysis.get("evidence") or []
        for task_id in evidence_task_ids(evidence)
    }
    task_ranking_metrics = all_named_ranking_metrics(
        "retrieval_task", answerable_task_ids, ranked_task_ids(analysis.get("evidence") or []))
    task_items = {
        str(task.get("id")): requested_item(str(task.get("query") or ""))
        for task in retrieval_tasks if task.get("id")
    }
    scoped_evidence_pairs = [
        (requested, requested_item(str(evidence.get("item") or "")))
        for evidence in analysis.get("evidence") or []
        for task_id in evidence_task_ids(evidence)
        for requested in [task_items.get(str(task_id), "")]
        if requested
    ]
    scope_item_match_rate = (
        sum(expected == actual for expected, actual in scoped_evidence_pairs) / len(scoped_evidence_pairs)
        if scoped_evidence_pairs else None
    )
    task_coverage = (len(answerable_task_ids & matched_task_ids) / len(answerable_task_ids)
                     if answerable_task_ids else None)
    answerable_tasks = [task for task in retrieval_tasks if str(task.get("id") or "") in answerable_task_ids]
    answered_task_count, answer_task_coverage = cited_task_coverage(
        prediction, analysis.get("evidence") or [], answerable_tasks, facts)
    answer_overlap = max(overlap_f1, rouge_l_f1(expected_body, predicted_body))
    answer_quality = numeric_f1 if numeric_f1 is not None else answer_overlap
    structured_fact_applicable = numeric_recall is not None \
        and not is_textual_count_question(str(row.get("question") or ""))
    structured_calculation_required = bool(row.get("requires_calculation")) \
        and not is_textual_count_question(str(row.get("question") or ""))
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
        "task_trace": analysis.get("tasks") or [],
        "retrieval_task_count": len(answerable_task_ids),
        "matched_task_count": len(answerable_task_ids & matched_task_ids),
        "task_coverage": task_coverage,
        "scope_item_match_rate": scope_item_match_rate,
        "cross_section_contamination_rate": 1.0 - scope_item_match_rate
        if scope_item_match_rate is not None else None,
        "answered_task_count": answered_task_count,
        "answer_task_coverage": answer_task_coverage,
        "fact_count": len(facts),
        "fact_trace": fact_trace,
        "fact_extracted": bool(facts) if structured_fact_applicable else None,
        "fact_value_recall": nullable_numeric_recall(expected_body, fact_values)
        if structured_fact_applicable else None,
        "structured_value_recall": nullable_numeric_recall(expected_body, fact_values + calculation_values)
        if structured_fact_applicable else None,
        "calculation_count": len(calculations),
        "calculation_plan_count": len(calculation_plans),
        "calculation_plan_trace": calculation_plans,
        "calculation_plan_execution_rate": min(1.0, len(calculations) / len(calculation_plans))
        if calculation_plans else None,
        "calculation_trace": calculation_trace,
        "calculation_result_match": calculation_result_match(str(row.get("question") or ""), expected_body,
                                                               calculations)
        if structured_calculation_required else None,
        "requires_calculation": bool(row.get("requires_calculation")),
        "requires_structured_calculation": structured_calculation_required,
        "expected_sources": expected_sources,
        "expected_sections": [f"{source}::{section}" for source, section in sorted(expected_sections)],
        "retrieved_sources": retrieved_sources,
        "evidence_trace": evidence_trace,
        "retrieved_source_hits": sorted(source_hits),
        "document_recall": document_recall,
        **ranking_metrics,
        **section_ranking_metrics,
        **task_ranking_metrics,
        "retrieval_quality_status": str(analysis.get("retrievalQuality") or "UNKNOWN").upper(),
        "retrieval_warnings": analysis.get("retrievalWarnings") or [],
        "rag_quality_score": rag_quality_score,
        "planning_ms": analysis.get("planningMs", 0),
        "retrieval_ms": analysis.get("retrievalMs", 0),
        "generation_ms": analysis.get("generationMs", 0),
        "latency_ms": latency_ms,
        "error": error,
    }


def rescore_saved_result(saved: dict[str, Any], known_issues: dict[str, str]) -> dict[str, Any]:
    result = dict(saved)
    for legacy_metric in ("strict_exact_match", "exact_match", "contains_expected"):
        result.pop(legacy_metric, None)
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
    calculation_values = [
        value for calculation in calculations for value in structured_calculation_numeric_values(calculation)
    ]
    document_recall = saved.get("document_recall")
    saved_evidence = list(saved.get("evidence_trace") or [])
    ranked_sources = ranked_document_names(saved_evidence)
    if not ranked_sources:
        ranked_sources = list(dict.fromkeys(saved.get("retrieved_sources") or []))
    expected_sources = set(saved.get("expected_sources") or [])
    ranking_metrics = all_document_ranking_metrics(expected_sources, ranked_sources)
    expected_sections = expected_section_keys(saved, expected_sources)
    section_ranking_metrics = all_named_ranking_metrics(
        "section", expected_sections, ranked_section_keys(saved_evidence))
    explicit_task_ids = {
        str(task_id)
        for item in saved_evidence
        for task_id in evidence_task_ids(item)
        if str(task_id).startswith("retrieve_explicit_")
    }
    saved_tasks = [
        task for task in saved.get("task_trace") or []
        if str(task.get("operation") or "").lower() == "retrieve"
        and not str(task.get("id") or "").startswith("retrieve_explicit_")
    ]
    if not saved_tasks:
        inferred_scopes: dict[str, dict[str, Any]] = {}
        for item in saved_evidence:
            for task_id_value in evidence_task_ids(item):
                task_id = str(task_id_value)
                if task_id.startswith("retrieve_explicit_"):
                    continue
                scope = inferred_scopes.setdefault(task_id, {
                    "id": task_id, "operation": "retrieve", "companies": [], "years": []})
                company = item.get("company")
                year = item.get("fiscalYear")
                if company and company not in scope["companies"]:
                    scope["companies"].append(company)
                if year and year not in scope["years"]:
                    scope["years"].append(year)
        saved_tasks = list(inferred_scopes.values())
    saved_task_ids = {str(task.get("id") or "") for task in saved_tasks if task.get("id")}
    matched_task_ids = {
        str(task_id)
        for item in saved_evidence
        for task_id in evidence_task_ids(item)
        if not str(task_id).startswith("retrieve_explicit_")
    }
    task_items = {
        str(task.get("id")): requested_item(str(task.get("query") or ""))
        for task in saved_tasks if task.get("id")
    }
    scoped_evidence_pairs = [
        (requested, requested_item(str(evidence.get("item") or "")))
        for evidence in saved_evidence
        for task_id in evidence_task_ids(evidence)
        for requested in [task_items.get(str(task_id), "")]
        if requested
    ]
    scope_item_match_rate = (
        sum(expected == actual for expected, actual in scoped_evidence_pairs) / len(scoped_evidence_pairs)
        if scoped_evidence_pairs else None
    )
    task_coverage = (len(saved_task_ids & matched_task_ids) / len(saved_task_ids)
                     if saved_task_ids else saved.get("task_coverage"))
    task_ranking_metrics = all_named_ranking_metrics(
        "retrieval_task", saved_task_ids, ranked_task_ids(saved_evidence))
    answered_task_count, answer_task_coverage = cited_task_coverage(
        predicted_raw, saved_evidence, saved_tasks, facts
    )
    retrieval_components = [float(value) for value in (document_recall, task_coverage) if value is not None]
    retrieval_quality = average(retrieval_components) if retrieval_components else 0.0
    citation_valid = saved.get("citation_valid")
    grounding_quality = float(citation_valid) if citation_valid is not None \
        else float(bool(CITATION.search(predicted_raw)))
    answer_quality = numeric_f1 if numeric_f1 is not None else overlap
    structured_fact_applicable = numeric_recall is not None \
        and not is_textual_count_question(str(saved.get("question") or ""))
    structured_calculation_required = bool(saved.get("requires_calculation", calculations)) \
        and not is_textual_count_question(str(saved.get("question") or ""))
    result.update({
        "token_precision": token_precision,
        "token_recall": token_recall,
        "token_f1": overlap_f1,
        "rouge_l_f1": rouge,
        "answer_overlap_score": overlap,
        "numeric_precision": numeric_precision,
        "numeric_recall": numeric_recall,
        "numeric_f1": numeric_f1,
        "numeric_accuracy": numeric_recall,
        "fact_extracted": bool(facts) if structured_fact_applicable else None,
        "fact_value_recall": nullable_numeric_recall(expected, fact_values)
        if structured_fact_applicable else None,
        "structured_value_recall": nullable_numeric_recall(expected, fact_values + calculation_values)
        if structured_fact_applicable else None,
        "calculation_result_match": calculation_result_match(str(saved.get("question") or ""), expected,
                                                               calculations)
        if structured_calculation_required else None,
        "requires_structured_calculation": structured_calculation_required,
        "answered_task_count": answered_task_count,
        "answer_task_coverage": answer_task_coverage,
        "retrieval_task_count": len(saved_task_ids) if saved_task_ids else saved.get("retrieval_task_count", 0),
        "matched_task_count": len(saved_task_ids & matched_task_ids) if saved_task_ids
        else saved.get("matched_task_count", 0),
        "task_coverage": task_coverage,
        "retrieved_sources": ranked_sources,
        "expected_sections": [f"{source}::{section}" for source, section in sorted(expected_sections)],
        **ranking_metrics,
        **section_ranking_metrics,
        **task_ranking_metrics,
        "scope_item_match_rate": scope_item_match_rate,
        "cross_section_contamination_rate": 1.0 - scope_item_match_rate
        if scope_item_match_rate is not None else None,
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
        "metric_version": "deterministic-v6",
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


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    position = (len(ordered) - 1) * percent / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if not row["error"]]
    numeric_rows = [row for row in successful if row["numeric_recall"] is not None]
    fact_rows = [row for row in successful if row.get("fact_extracted") is not None]
    fact_value_rows = [row for row in successful if row.get("fact_value_recall") is not None]
    structured_value_rows = [row for row in successful if row.get("structured_value_recall") is not None]
    retrieval_rows = [row for row in successful if row["document_recall"] is not None]
    audited_rows = [row for row in successful if row["citation_valid"] is not None]
    task_rows = [row for row in successful if row["task_coverage"] is not None]
    section_scope_rows = [row for row in successful if row.get("scope_item_match_rate") is not None]
    answer_task_rows = [row for row in successful if row.get("answer_task_coverage") is not None]
    diagnostic_rows = [
        row for row in successful if str(row.get("retrieval_quality_status") or "UNKNOWN") != "UNKNOWN"
    ]
    calculation_rows = [
        row for row in successful
        if row.get("requires_structured_calculation", row["requires_calculation"])
    ]
    ranking_summary = {
        f"document_{metric}_at_{cutoff}": applicable_average([
            float(row[f"document_{metric}_at_{cutoff}"])
            for row in successful if row.get(f"document_{metric}_at_{cutoff}") is not None
        ])
        for cutoff in RETRIEVAL_CUTOFFS
        for metric in ("recall", "mrr", "map", "ndcg", "complete_recall")
    }
    section_ranking_summary = {
        f"section_{metric}_at_{cutoff}": applicable_average([
            float(row[f"section_{metric}_at_{cutoff}"])
            for row in successful if row.get(f"section_{metric}_at_{cutoff}") is not None
        ])
        for cutoff in RETRIEVAL_CUTOFFS
        for metric in ("recall", "mrr", "map", "ndcg", "complete_recall")
    }
    task_ranking_summary = {
        f"retrieval_task_{metric}_at_{cutoff}": applicable_average([
            float(row[f"retrieval_task_{metric}_at_{cutoff}"])
            for row in successful if row.get(f"retrieval_task_{metric}_at_{cutoff}") is not None
        ])
        for cutoff in RETRIEVAL_CUTOFFS
        for metric in ("recall", "mrr", "map", "ndcg", "complete_recall")
    }
    return {
        "count": len(rows),
        "successful": len(successful),
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
        "retrieval_ranking_question_count": len(retrieval_rows),
        **ranking_summary,
        **section_ranking_summary,
        **task_ranking_summary,
        "retrieval_task_coverage": average([float(row["task_coverage"]) for row in task_rows]),
        "scope_item_match_rate": applicable_average([
            float(row["scope_item_match_rate"]) for row in section_scope_rows
        ]),
        "cross_section_contamination_rate": applicable_average([
            float(row["cross_section_contamination_rate"]) for row in section_scope_rows
        ]),
        "answer_task_coverage": applicable_average(
            [float(row["answer_task_coverage"]) for row in answer_task_rows]
        ),
        "degraded_retrieval_rate": applicable_average([
            float(str(row.get("retrieval_quality_status") or "").upper() == "DEGRADED")
            for row in diagnostic_rows
        ]),
        "retrieval_diagnostic_count": len(diagnostic_rows),
        "fact_extraction_rate": applicable_average([float(row["fact_extracted"]) for row in fact_rows]),
        "fact_extraction_question_count": len(fact_rows),
        "fact_value_recall": applicable_average([float(row["fact_value_recall"]) for row in fact_value_rows]),
        "fact_value_question_count": len(fact_value_rows),
        "structured_value_recall": applicable_average(
            [float(row["structured_value_recall"]) for row in structured_value_rows]
        ),
        "structured_value_question_count": len(structured_value_rows),
        "calculation_production_rate": applicable_average(
            [float(row["calculation_count"] > 0) for row in calculation_rows]
        ),
        "calculation_plan_rate": applicable_average(
            [float(row.get("calculation_plan_count", 0) > 0) for row in calculation_rows]
        ),
        "calculation_plan_execution_rate": applicable_average([
            float(row["calculation_plan_execution_rate"])
            for row in calculation_rows if row.get("calculation_plan_execution_rate") is not None
        ]),
        "calculation_question_count": len(calculation_rows),
        "calculation_result_match": applicable_average([
            float(row["calculation_result_match"])
            for row in calculation_rows if row["calculation_result_match"] is not None
        ]),
        "refusal_rate": average([float(row["refusal"]) for row in successful]),
        "rag_quality_score": average([float(row["rag_quality_score"]) for row in successful]),
        "average_latency_ms": average([float(row["latency_ms"]) for row in successful]),
        "latency_p50_ms": percentile([float(row["latency_ms"]) for row in successful], 50),
        "latency_p95_ms": percentile([float(row["latency_ms"]) for row in successful], 95),
        "planning_latency_p50_ms": percentile([
            float(row.get("planning_ms") or 0) for row in successful
        ], 50),
        "planning_latency_p95_ms": percentile([
            float(row.get("planning_ms") or 0) for row in successful
        ], 95),
        "retrieval_latency_p50_ms": percentile([
            float(row.get("retrieval_ms") or 0) for row in successful
        ], 50),
        "retrieval_latency_p95_ms": percentile([
            float(row.get("retrieval_ms") or 0) for row in successful
        ], 95),
        "generation_latency_p50_ms": percentile([
            float(row.get("generation_ms") or 0) for row in successful
        ], 50),
        "generation_latency_p95_ms": percentile([
            float(row.get("generation_ms") or 0) for row in successful
        ], 95),
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


def format_result_details(results: list[dict[str, Any]]) -> str:
    """Render human-readable per-question output without making another API request."""
    sections: list[str] = []
    for row in results:
        metrics = {
            "token_f1": row.get("token_f1"),
            "rouge_l_f1": row.get("rouge_l_f1"),
            "rag_quality_score": row.get("rag_quality_score"),
            "document_recall": row.get("document_recall"),
            "document_recall_at_5": row.get("document_recall_at_5"),
            "document_mrr_at_10": row.get("document_mrr_at_10"),
            "document_map_at_10": row.get("document_map_at_10"),
            "document_ndcg_at_10": row.get("document_ndcg_at_10"),
            "document_complete_recall_at_10": row.get("document_complete_recall_at_10"),
            "section_recall_at_5": row.get("section_recall_at_5"),
            "section_mrr_at_10": row.get("section_mrr_at_10"),
            "retrieval_task_recall_at_5": row.get("retrieval_task_recall_at_5"),
            "retrieval_task_mrr_at_10": row.get("retrieval_task_mrr_at_10"),
            "retrieval_task_coverage": row.get("task_coverage"),
            "answer_task_coverage": row.get("answer_task_coverage"),
            "citation_valid": row.get("citation_valid"),
            "retrieval_quality_status": row.get("retrieval_quality_status"),
            "retrieval_warnings": row.get("retrieval_warnings") or [],
            "latency_ms": row.get("latency_ms"),
            "error": row.get("error") or "",
        }
        sections.append("\n".join([
            "=" * 88,
            f"[{row.get('id', '')}] subset={row.get('subset', '')} task={row.get('task_type', '')}",
            "\n[Question]\n" + str(row.get("question") or ""),
            "\n[Expected answer]\n" + str(row.get("expected_answer") or ""),
            "\n[System answer]\n" + str(row.get("predicted_answer") or ""),
            "\n[Metrics]\n" + json.dumps(metrics, ensure_ascii=False, indent=2),
            "\n[Retrieved sources]\n" + json.dumps(
                row.get("retrieved_sources") or [], ensure_ascii=False, indent=2),
            "\n[Retrieval tasks]\n" + json.dumps(
                row.get("task_trace") or [], ensure_ascii=False, indent=2),
            "\n[Evidence trace]\n" + json.dumps(
                row.get("evidence_trace") or [], ensure_ascii=False, indent=2),
            "\n[Fact trace]\n" + json.dumps(
                row.get("fact_trace") or [], ensure_ascii=False, indent=2),
            "\n[Calculation trace]\n" + json.dumps(
                row.get("calculation_trace") or [], ensure_ascii=False, indent=2),
        ]))
    return portable_console_text("\n\n".join(sections))


def format_summary_line(summary: dict[str, Any]) -> str:
    overall = summary.get("overall") or {}

    def score(name: str) -> str:
        value = overall.get(name)
        return "n/a" if value is None else f"{float(value):.3f}"

    return (
        f"[summary] success={overall.get('successful', 0)}/{overall.get('count', 0)} "
        f"f1={score('token_f1')} quality={score('rag_quality_score')} "
        f"numeric_f1={score('numeric_f1')} doc_recall={score('document_recall')} "
        f"recall@5={score('document_recall_at_5')} mrr@10={score('document_mrr_at_10')} "
        f"ndcg@10={score('document_ndcg_at_10')} all_docs@10={score('document_complete_recall_at_10')} "
        f"section_recall@5={score('section_recall_at_5')} "
        f"task_recall@5={score('retrieval_task_recall_at_5')} "
        f"task_coverage={score('retrieval_task_coverage')} "
        f"citation_valid={score('citation_valid_rate')} refusal={score('refusal_rate')}"
    )


def portable_console_text(value: str) -> str:
    """Avoid mojibake in legacy Windows PowerShell while preserving Unicode in saved JSON reports."""
    return (value or "").translate(str.maketrans({
        "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"',
        "\u2013": "-", "\u2014": "-", "\u00a0": " ",
    }))


def load_report(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload.get("results"), list):
        raise ValueError("report does not contain a results list")
    return payload


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    """Persist a checkpoint without exposing a half-written JSON file after interruption."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def evaluation_payload(args: argparse.Namespace, questions: list[dict[str, Any]],
                       results: list[dict[str, Any]], status: str) -> dict[str, Any]:
    return {
        "dataset": "Multi-Doc-2025",
        "metric_version": "deterministic-v6",
        "status": status,
        "split": args.split,
        "subsets": args.subsets,
        "run_id": args.run_id,
        "execution_id": args.execution_id,
        "dataset_scope_enabled": args.use_dataset_scope,
        "selected_question_ids": [str(row.get("id") or "") for row in questions],
        "completed_count": len(results),
        "total_count": len(questions),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": report(results),
        "results": results,
    }


def evaluation_conversation_id(run_id: str, execution_id: str, question_id: str) -> str:
    return f"multidoc-eval-{run_id}-{execution_id}-{question_id}"


def request_analysis(endpoint: str, row: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    attempts = max(1, int(args.retries) + 1)
    for attempt in range(attempts):
        params = {
            "prompt": retrieval_prompt(row, args.use_dataset_scope),
            "conv_id": evaluation_conversation_id(
                args.run_id, args.execution_id, f"{row['id']}-attempt-{attempt + 1}"),
            "retrievalStrategy": args.retrieval_strategy,
        }
        if args.model_id:
            params["modelId"] = args.model_id
        url = endpoint + ("&" if "?" in endpoint else "?") + urllib.parse.urlencode(params)
        request = urllib.request.Request(
            url, headers={"Accept": "application/json", "User-Agent": "Kecore-AI-Eval/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.loads(response.read().decode("utf-8", errors="replace"))
        except urllib.error.HTTPError as exc:
            if attempt + 1 >= attempts or exc.code not in {408, 425, 429, 500, 502, 503, 504}:
                raise
            time.sleep(args.retry_backoff * (2 ** attempt))
            continue
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
            if attempt + 1 >= attempts:
                raise
            time.sleep(args.retry_backoff * (2 ** attempt))
            continue
        if not transient_analysis_error(str(payload.get("error") or "")) or attempt + 1 >= attempts:
            return payload
        time.sleep(args.retry_backoff * (2 ** attempt))
    raise RuntimeError("evaluation request exhausted retry attempts")


def transient_analysis_error(error: str) -> bool:
    normalized = (error or "").lower()
    # A tokens-per-day quota cannot recover during this process. Retrying the whole
    # RAG request only adds several minutes of latency and may consume more quota.
    if "tpd rate limit" in normalized or "tokens per day" in normalized:
        return False
    return any(marker in normalized for marker in (
        "429", "overloaded", "rate limit", "too many requests", "temporarily unavailable",
        "internal server error", "bad gateway", "service unavailable", "gateway timeout",
        "connection reset", "connection refused", "unexpected end of file", "premature eof",
        "timed out", "timeout", "i/o error", "connection closed", "empty response",
    ))


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
    parser.add_argument("--inspect-report", type=Path,
                        help="display saved questions, expected answers, system answers, and traces without API calls")
    parser.add_argument("--resume-report", type=Path,
                        help="resume an interrupted .partial.json report; successful questions are not called again")
    parser.add_argument("--show-details", action="store_true",
                        help="print full per-question answers and diagnostic traces after evaluation")
    parser.add_argument("--show-summary", action="store_true",
                        help="print complete aggregate metrics instead of the compact one-line summary")
    parser.add_argument("--split", choices=("train", "val", "test"), default="test")
    parser.add_argument("--subsets", type=lambda value: parse_values(value, set(SUBSET_LABELS)), default=["S3", "S4", "S5"])
    parser.add_argument("--question-ids", type=lambda value: [item.strip() for item in value.split(",") if item.strip()],
                        default=[], help="comma-separated question IDs for inexpensive targeted reruns")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--model-id", default="")
    parser.add_argument("--retrieval-strategy", choices=("default", "parent-child"), default="parent-child")
    parser.add_argument("--timeout", type=int, default=600,
                        help="per-question HTTP timeout in seconds; complex S5 requests can exceed five minutes")
    parser.add_argument("--retries", type=int, default=3,
                        help="retries for transient 429/5xx/overloaded model errors")
    parser.add_argument("--retry-backoff", type=float, default=2.0,
                        help="initial retry delay in seconds; subsequent delays use exponential backoff")
    parser.add_argument("--workers", type=int, default=2, help="parallel evaluation requests")
    parser.add_argument("--run-id", default="", help="human-readable experiment label; defaults to a timestamp")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-dataset-scope", dest="use_dataset_scope", action="store_false",
                        help="omit company/year scope supplied by Multi-Doc-2025 metadata (ablation only)")
    parser.set_defaults(use_dataset_scope=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.inspect_report:
        try:
            payload = load_report(args.inspect_report)
            results = select_question_ids(payload["results"], args.question_ids)
        except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
            print(f"error: {exc}")
            return 1
        print(format_result_details(results))
        print(f"[input] {args.inspect_report.resolve()}")
        return 0
    if args.rescore_report:
        try:
            output = rescore_report(args.rescore_report, args.output_dir, args.known_issues)
            payload = json.loads(output.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
            print(f"error: {exc}")
            return 1
        print(json.dumps(payload["summary"], ensure_ascii=False, indent=2)
              if args.show_summary else format_summary_line(payload["summary"]))
        if args.show_details:
            print(format_result_details(payload["results"]))
        print(f"[output] {output}")
        return 0
    resume_payload: dict[str, Any] | None = None
    if args.resume_report:
        try:
            resume_payload = load_report(args.resume_report)
        except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
            print(f"error: {exc}")
            return 1
        args.split = str(resume_payload.get("split") or args.split)
        args.subsets = [str(value).upper() for value in resume_payload.get("subsets") or args.subsets]
        if not args.run_id:
            args.run_id = str(resume_payload.get("run_id") or "")
    if not args.run_id:
        args.run_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    args.execution_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f") + "-" + uuid.uuid4().hex[:8]
    try:
        questions = load_questions(args.data_dir, args.split, args.subsets)
        resumed_question_ids = list(resume_payload.get("selected_question_ids") or []) \
            if resume_payload else []
        questions = select_question_ids(
            questions, resumed_question_ids or args.question_ids)
        known_issues = load_known_issues(args.known_issues)
    except (FileNotFoundError, json.JSONDecodeError, ValueError) as exc:
        print(f"error: {exc}")
        return 1
    if args.limit and not resumed_question_ids:
        questions = balanced_limit(questions, args.limit, args.subsets)
    counts: dict[str, int] = defaultdict(int)
    for row in questions:
        counts[str(row["subset"]).upper()] += 1
    print(f"[selection] split={args.split}, questions={len(questions)}, by_subset={dict(sorted(counts.items()))}")
    if args.dry_run:
        return 0

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    safe_run_id = re.sub(r"[^A-Za-z0-9._-]+", "-", args.run_id).strip("-.") or "run"
    if args.resume_report and args.resume_report.name.endswith(".partial.json"):
        checkpoint_path = args.resume_report
        output = args.resume_report.with_name(args.resume_report.name.removesuffix(".partial.json") + ".json")
    else:
        output = args.output_dir / f"{args.split}-{'-'.join(args.subsets).lower()}-{safe_run_id}-{stamp}.json"
        checkpoint_path = output.with_name(output.stem + ".partial.json")

    question_indexes = {str(row.get("id") or ""): index for index, row in enumerate(questions, start=1)}
    indexed_results: list[tuple[int, dict[str, Any]]] = []
    if resume_payload:
        for saved in resume_payload.get("results") or []:
            question_id = str(saved.get("id") or "")
            if question_id in question_indexes and not saved.get("error"):
                indexed_results.append((question_indexes[question_id], saved))
    completed_ids = {str(result.get("id") or "") for _, result in indexed_results}
    pending_questions = [
        (index, row) for index, row in enumerate(questions, start=1)
        if str(row.get("id") or "") not in completed_ids
    ]
    if indexed_results:
        print(f"[resume] kept={len(indexed_results)}, pending={len(pending_questions)}, "
              f"checkpoint={checkpoint_path}")
    write_json_atomic(checkpoint_path, evaluation_payload(
        args, questions, [result for _, result in sorted(indexed_results)], "in_progress"))

    def run_one(index: int, row: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        started = time.perf_counter()
        prediction = error = ""
        analysis: dict[str, Any] = {}
        try:
            analysis = request_analysis(args.endpoint, row, args)
            prediction = str(analysis.get("answer") or "")
            error = str(analysis.get("error") or "")
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, RuntimeError) as exc:
            error = str(exc)
        latency_ms = int((time.perf_counter() - started) * 1000)
        result = evaluate_row(row, prediction, latency_ms, error, analysis)
        result["known_issue"] = known_issues.get(str(row.get("id") or ""))
        return index, result

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = [executor.submit(run_one, index, row) for index, row in pending_questions]
        for completed, future in enumerate(concurrent.futures.as_completed(futures),
                                            start=len(indexed_results) + 1):
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
            checkpoint_results = [item for _, item in sorted(indexed_results, key=lambda pair: pair[0])]
            write_json_atomic(checkpoint_path, evaluation_payload(
                args, questions, checkpoint_results, "in_progress"))
    results = [result for _, result in sorted(indexed_results, key=lambda item: item[0])]
    payload = evaluation_payload(args, questions, results, "complete")
    write_json_atomic(checkpoint_path, payload)
    write_json_atomic(output, payload)
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2)
          if args.show_summary else format_summary_line(payload["summary"]))
    if args.show_details:
        print(format_result_details(results))
    print(f"[output] {output}")
    return 0 if payload["summary"]["overall"]["successful"] == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
