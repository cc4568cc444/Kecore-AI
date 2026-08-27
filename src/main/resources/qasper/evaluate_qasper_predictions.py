#!/usr/bin/env python3
"""Evaluate JSONL paper-RAG predictions against exported QASPER cases."""

from __future__ import annotations

import argparse
import json
import math
import re
import string
from collections import Counter
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cases", type=Path, help="qasper-dev.jsonl or qasper-test.jsonl")
    parser.add_argument("predictions", type=Path, help="JSONL with question_id, answer, evidence")
    args = parser.parse_args()

    cases = {row["question_id"]: row for row in read_jsonl(args.cases)}
    predictions = {row["question_id"]: row for row in read_jsonl(args.predictions)}
    scores = []
    for question_id, case in cases.items():
        prediction = predictions.get(question_id, {})
        answer = str(prediction.get("answer", ""))
        evidence = prediction.get("evidence", [])
        if isinstance(evidence, str):
            evidence = [evidence]
        answer_f1 = max((token_f1(answer, gold) for gold in case.get("gold_answers", [])), default=0.0)
        evidence_f1 = evidence_token_f1(evidence, case.get("evidence", []))
        numeric = numeric_match(answer, case.get("gold_answers", [])) if case.get("has_numeric_answer") else None
        abstention = is_abstention(answer) == (not case.get("answerable", True))
        scores.append((answer_f1, evidence_f1, numeric, abstention))

    numeric_scores = [row[2] for row in scores if row[2] is not None]
    report = {
        "total": len(cases),
        "predicted": len(predictions),
        "answer_f1": average(row[0] for row in scores),
        "evidence_f1": average(row[1] for row in scores),
        "numeric_accuracy": average(numeric_scores),
        "abstention_accuracy": average(1.0 if row[3] else 0.0 for row in scores),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def normalize(value: str) -> list[str]:
    value = value.lower().translate(str.maketrans("", "", string.punctuation))
    return re.sub(r"\b(a|an|the)\b", " ", value).split()


def token_f1(prediction: str, gold: str) -> float:
    predicted = normalize(prediction)
    expected = normalize(gold)
    common = Counter(predicted) & Counter(expected)
    overlap = sum(common.values())
    if not predicted or not expected:
        return float(predicted == expected)
    if overlap == 0:
        return 0.0
    precision = overlap / len(predicted)
    recall = overlap / len(expected)
    return 2 * precision * recall / (precision + recall)


def evidence_token_f1(predicted: list[str], expected: list[str]) -> float:
    predicted_text = " ".join(str(item) for item in predicted if item)
    expected_text = " ".join(str(item) for item in expected if item)
    return token_f1(predicted_text, expected_text)


def numeric_match(prediction: str, gold_answers: list[str]) -> float:
    predicted = numbers(prediction)
    expected = {number for answer in gold_answers for number in numbers(answer)}
    if not expected:
        return math.nan
    return float(any(any(close(left, right) for right in expected) for left in predicted))


def numbers(value: str) -> set[float]:
    result: set[float] = set()
    for match in re.finditer(r"(-?\d+(?:,\d{3})*(?:\.\d+)?)\s*(%)?", value or ""):
        number = float(match.group(1).replace(",", ""))
        result.add(number / 100.0 if match.group(2) else number)
    return result


def close(left: float, right: float) -> bool:
    scale = max(1.0, abs(right))
    return abs(left - right) <= max(1e-6, scale * 1e-3)


def is_abstention(value: str) -> bool:
    lower = value.lower()
    return any(term in lower for term in ("unanswerable", "cannot determine", "insufficient evidence", "无法", "证据不足"))


def average(values) -> float | None:
    values = [value for value in values if value is not None and not math.isnan(value)]
    return round(sum(values) / len(values), 6) if values else None


if __name__ == "__main__":
    main()
