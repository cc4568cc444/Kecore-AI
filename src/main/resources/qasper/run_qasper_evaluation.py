#!/usr/bin/env python3
"""Run reproducible QASPER retrieval/generation evaluations against the Spring API."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
import time
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from evaluate_qasper_predictions import is_abstention, normalize, numeric_match, token_f1


ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parents[3]
DEFAULT_CASES = PROJECT_ROOT / "src" / "test" / "resources" / "qasper" / "qasper-dev.jsonl"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "evaluation-results" / "qasper"
SUPPORTED_MODES = ("vector", "bm25", "hybrid", "hybrid_rerank")


@dataclass(frozen=True)
class RunConfig:
    endpoint: str
    model_id: str
    judge_model_id: str
    top_k: int
    context_mode: str
    generate_answer: bool
    judge: bool
    timeout: float
    retries: int
    delay: float
    cases_sha256: str
    server_config: dict[str, Any]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run QASPER RAG evaluation and generate JSON/CSV/Markdown reports")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--modes", default="hybrid_rerank",
                        help="comma-separated: vector,bm25,hybrid,hybrid_rerank")
    parser.add_argument("--model-id", default="deepseek")
    parser.add_argument("--judge-model-id", default="gpt",
                        help="independent model config id used for semantic and claim-level judging")
    parser.add_argument("--top-k", type=int, default=8)
    parser.add_argument("--context-mode", choices=("child", "parent-child"), default="parent-child")
    parser.add_argument("--limit", type=int, default=0, help="0 evaluates all selected cases")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--retrieval-only", action="store_true", help="skip answer generation and answer metrics")
    parser.add_argument("--judge", action="store_true", help="run an additional LLM faithfulness judge")
    parser.add_argument("--resume", action="store_true", help="reuse successful rows already in mode result files")
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--delay", type=float, default=0.0, help="seconds between requests")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    modes = parse_modes(args.modes)
    cases = read_jsonl(args.cases)
    start = max(0, args.offset)
    end = None if args.limit <= 0 else start + args.limit
    selected = cases[start:end]
    if not selected:
        raise SystemExit("No evaluation cases selected")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    base_url = args.base_url.rstrip("/")
    server_config = get_json(base_url + "/paper/evaluation/config", max(1.0, args.timeout))
    config = RunConfig(
        endpoint=base_url + "/paper/evaluation/query",
        model_id=args.model_id,
        judge_model_id=args.judge_model_id,
        top_k=max(1, args.top_k),
        context_mode=args.context_mode,
        generate_answer=not args.retrieval_only,
        judge=bool(args.judge and not args.retrieval_only),
        timeout=max(1.0, args.timeout),
        retries=max(0, args.retries),
        delay=max(0.0, args.delay),
        cases_sha256=sha256_file(args.cases),
        server_config=server_config,
    )

    reports: dict[str, dict[str, Any]] = {}
    for mode in modes:
        print(f"\n=== {mode}: {len(selected)} cases ===", flush=True)
        rows = run_mode(mode, selected, config, output_dir, args.resume)
        report = summarize(mode, selected, rows, config)
        reports[mode] = report
        write_mode_artifacts(mode, selected, rows, report, output_dir)
        print(json.dumps(report["metrics"], ensure_ascii=False, indent=2), flush=True)

    combined = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cases": str(args.cases.resolve()),
        "selected_cases": len(selected),
        "offset": start,
        "config": {
            "endpoint": config.endpoint,
            "model_id": config.model_id,
            "judge_model_id": config.judge_model_id,
            "top_k": config.top_k,
            "context_mode": config.context_mode,
            "generate_answer": config.generate_answer,
            "judge": config.judge,
            "cases_sha256": config.cases_sha256,
            "server": config.server_config,
        },
        "modes": reports,
    }
    write_json(output_dir / "comparison.json", combined)
    (output_dir / "comparison.md").write_text(markdown_report(combined), encoding="utf-8")
    print(f"\nReports written to {output_dir}", flush=True)


def parse_modes(value: str) -> list[str]:
    modes: list[str] = []
    for raw in str(value or "").split(","):
        mode = raw.strip().lower().replace("-", "_")
        if not mode:
            continue
        if mode not in SUPPORTED_MODES:
            raise SystemExit(f"Unsupported mode {mode}; choose from {', '.join(SUPPORTED_MODES)}")
        if mode not in modes:
            modes.append(mode)
    if not modes:
        raise SystemExit("At least one retrieval mode is required")
    return modes


def run_mode(mode: str,
             cases: list[dict[str, Any]],
             config: RunConfig,
             output_dir: Path,
             resume: bool) -> list[dict[str, Any]]:
    result_path = output_dir / f"{mode}.results.jsonl"
    signature = run_signature(mode, config)
    completed = successful_rows(result_path, signature) if resume else {}
    if not resume:
        result_path.write_text("", encoding="utf-8")

    rows: list[dict[str, Any]] = []
    for index, case in enumerate(cases, start=1):
        question_id = str(case.get("question_id", ""))
        if question_id in completed:
            row = completed[question_id]
            rows.append(row)
            print(f"[{index}/{len(cases)}] resume {question_id}", flush=True)
            continue

        payload = {
            "questionId": question_id,
            "paperId": str(case.get("paper_id", "")),
            "question": str(case.get("question", "")),
            "retrievalMode": mode,
            "contextMode": config.context_mode,
            "topK": config.top_k,
            "modelId": config.model_id,
            "judgeModelId": config.judge_model_id,
            "goldAnswers": [str(item) for item in (case.get("gold_answers") or [])],
            "generateAnswer": config.generate_answer,
            "judgeFaithfulness": config.judge,
        }
        started = time.perf_counter()
        try:
            response = post_json(config.endpoint, payload, config.timeout, config.retries)
            if config.judge and not valid_judgement(response):
                faithfulness = response.get("faithfulness") or {}
                answer_evaluation = response.get("answerEvaluation") or {}
                reason = faithfulness.get("reason") or answer_evaluation.get("reason") or "missing judge output"
                raise RuntimeError(f"judge did not return a score: {reason}")
            row = {
                **response,
                "question_id": question_id,
                "paper_id": str(case.get("paper_id", "")),
                "mode": mode,
                "client_latency_ms": round((time.perf_counter() - started) * 1000),
                "error": "",
                "_run_config": signature,
            }
            status = f"chunks={len(response.get('retrievedChunks') or [])} latency={response.get('latencyMs')}ms"
        except Exception as exc:  # keep the run resumable and make failure rate explicit
            row = {
                "question_id": question_id,
                "paper_id": str(case.get("paper_id", "")),
                "question": str(case.get("question", "")),
                "answer": "",
                "retrievedChunks": [],
                "citations": [],
                "retrievalMode": mode,
                "rerankerApplied": False,
                "client_latency_ms": round((time.perf_counter() - started) * 1000),
                "error": str(exc),
                "_run_config": signature,
            }
            status = f"ERROR {exc}"
        rows.append(row)
        append_jsonl(result_path, row)
        print(f"[{index}/{len(cases)}] {question_id} {status}", flush=True)
        if index == 1 and config.judge and row.get("error"):
            raise SystemExit(
                "Judge smoke check failed on the first case; stopping to avoid an expensive invalid run. "
                f"Details: {row['error']}"
            )
        if config.delay:
            time.sleep(config.delay)
    return rows


def post_json(url: str, payload: dict[str, Any], timeout: float, retries: int) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        request = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            last_error = RuntimeError(f"HTTP {exc.code}: {detail[:1000]}")
            if 400 <= exc.code < 500:
                break
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
        if attempt < retries:
            time.sleep(min(8.0, 2.0 ** attempt))
    raise RuntimeError(str(last_error or "request failed"))


def get_json(url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            value = json.loads(response.read().decode("utf-8"))
            if not isinstance(value, dict):
                raise RuntimeError("evaluation config response is not a JSON object")
            return value
    except Exception as exc:
        raise SystemExit(
            f"Cannot read {url}: {exc}. Restart Spring Boot so the optimized evaluation API is active."
        ) from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def summarize(mode: str,
              cases: list[dict[str, Any]],
              rows: list[dict[str, Any]],
              config: RunConfig) -> dict[str, Any]:
    case_by_id = {str(case.get("question_id", "")): case for case in cases}
    scored: list[dict[str, Any]] = []
    for row in rows:
        case = case_by_id.get(str(row.get("question_id", "")))
        if case is None:
            continue
        error = str(row.get("error", ""))
        answer = str(row.get("answer", ""))
        evidence = [str(item) for item in (row.get("citations") or [])]
        gold_answers = [str(item) for item in (case.get("gold_answers") or [])]
        gold_evidence = [str(item) for item in (case.get("evidence") or [])]
        faithfulness_result = row.get("faithfulness") or {}
        faithfulness = faithfulness_result.get("score")
        supported_claims = int(faithfulness_result.get("supportedClaims") or 0)
        total_claims = int(faithfulness_result.get("totalClaims") or 0)
        answer_evaluation = row.get("answerEvaluation") or {}
        semantic_correct = answer_evaluation.get("correct")
        semantic_score = answer_evaluation.get("score")
        retrieval_gate = row.get("retrievalGate") or {}
        gate_status = str(retrieval_gate.get("status") or "UNKNOWN").upper()
        numeric = numeric_match(answer, gold_answers) if case.get("has_numeric_answer") and answer else None
        generated = bool(answer.strip()) and (
            (number(row.get("generationMs")) or 0.0) > 0.0 or gate_status == "REJECT"
        )
        scored.append({
            "question_id": str(case.get("question_id", "")),
            "paper_id": str(case.get("paper_id", "")),
            "question": str(case.get("question", "")),
            "answer": answer,
            "answer_f1": max((token_f1(answer, gold) for gold in gold_answers), default=0.0) if generated else None,
            "evidence_f1": evidence_f1(evidence, gold_evidence),
            "context_recall_at_k": context_recall(evidence, gold_evidence),
            "numeric_accuracy": numeric,
            "abstention_correct": (is_abstention(answer) == (not case.get("answerable", True))) if generated else None,
            "faithfulness": float(faithfulness) if isinstance(faithfulness, (int, float)) else None,
            "supported_claims": supported_claims,
            "total_claims": total_claims,
            "semantic_correct": semantic_correct if isinstance(semantic_correct, bool) else None,
            "semantic_score": float(semantic_score) if isinstance(semantic_score, (int, float)) else None,
            "gate_status": gate_status,
            "gate_query_coverage": number(retrieval_gate.get("queryCoverage")),
            "gate_reason": str(retrieval_gate.get("reason") or ""),
            "retrieval_ms": number(row.get("retrievalMs")),
            "generation_ms": number(row.get("generationMs")),
            "judge_ms": number(row.get("judgeMs")),
            "answer_latency_ms": add_numbers(row.get("retrievalMs"), row.get("generationMs"))
            if generated else None,
            "total_latency_ms": number(row.get("latencyMs")),
            "client_latency_ms": number(row.get("client_latency_ms")),
            "retrieved_count": len(row.get("retrievedChunks") or []),
            "reranker_applied": bool(row.get("rerankerApplied")),
            "error": error,
        })

    successful = [item for item in scored if not item["error"]]
    answer_scored = [item for item in successful if item["answer_f1"] is not None]
    numeric_scored = [item for item in successful if item["numeric_accuracy"] is not None]
    abstention_scored = [item for item in successful if item["abstention_correct"] is not None]
    faithfulness_scored = [item for item in successful if item["faithfulness"] is not None]
    semantic_scored = [item for item in successful if item["semantic_correct"] is not None]
    total_supported_claims = sum(item["supported_claims"] for item in faithfulness_scored)
    total_judged_claims = sum(item["total_claims"] for item in faithfulness_scored)
    gate_pass = sum(item["gate_status"] == "PASS" for item in successful)
    gate_caution = sum(item["gate_status"] == "CAUTION" for item in successful)
    gate_reject = sum(item["gate_status"] == "REJECT" for item in successful)
    metrics = {
        "total": len(scored),
        "successful": len(successful),
        "failed": len(scored) - len(successful),
        "failure_rate": ratio(len(scored) - len(successful), len(scored)),
        "context_scored": sum(1 for item in successful if item["context_recall_at_k"] is not None),
        "answer_scored": len(answer_scored),
        "numeric_scored": len(numeric_scored),
        "abstention_scored": len(abstention_scored),
        "faithfulness_scored": len(faithfulness_scored),
        "semantic_scored": len(semantic_scored),
        "gate_pass": gate_pass,
        "gate_caution": gate_caution,
        "gate_reject": gate_reject,
        "gate_rejection_rate": ratio(gate_reject, len(successful)),
        "supported_claims": total_supported_claims,
        "total_claims": total_judged_claims,
        "context_recall_at_k": mean(item["context_recall_at_k"] for item in successful),
        "evidence_f1": mean(item["evidence_f1"] for item in successful),
        "answer_f1": mean(item["answer_f1"] for item in answer_scored),
        "numeric_accuracy": mean(item["numeric_accuracy"] for item in numeric_scored),
        "abstention_accuracy": mean_bool(item["abstention_correct"] for item in abstention_scored),
        "faithfulness": ratio(total_supported_claims, total_judged_claims),
        "faithfulness_macro": mean(item["faithfulness"] for item in faithfulness_scored),
        "semantic_accuracy": mean_bool(item["semantic_correct"] for item in semantic_scored),
        "semantic_score": mean(item["semantic_score"] for item in semantic_scored),
        "retrieval_latency_p50_ms": percentile((item["retrieval_ms"] for item in successful), 50),
        "retrieval_latency_p95_ms": percentile((item["retrieval_ms"] for item in successful), 95),
        "answer_latency_p50_ms": percentile((item["answer_latency_ms"] for item in answer_scored), 50),
        "answer_latency_p95_ms": percentile((item["answer_latency_ms"] for item in answer_scored), 95),
        "judge_latency_p50_ms": percentile((item["judge_ms"] for item in faithfulness_scored), 50),
        "judge_latency_p95_ms": percentile((item["judge_ms"] for item in faithfulness_scored), 95),
        "reranker_applied_rate": mean_bool(item["reranker_applied"] for item in successful)
        if mode == "hybrid_rerank" else None,
    }
    return {"mode": mode, "run_config": run_signature(mode, config), "metrics": metrics, "questions": scored}


def context_recall(predicted: list[str], expected: list[str]) -> float | None:
    expected_tokens = Counter(normalize(" ".join(expected)))
    if not expected_tokens:
        return None
    predicted_tokens = Counter(normalize(" ".join(predicted)))
    covered = sum((expected_tokens & predicted_tokens).values())
    return covered / sum(expected_tokens.values())


def evidence_f1(predicted: list[str], expected: list[str]) -> float | None:
    if not expected:
        return None
    return token_f1(" ".join(predicted), " ".join(expected))


def write_mode_artifacts(mode: str,
                         cases: list[dict[str, Any]],
                         rows: list[dict[str, Any]],
                         report: dict[str, Any],
                         output_dir: Path) -> None:
    write_json(output_dir / f"{mode}.report.json", report)
    case_by_id = {str(case.get("question_id", "")): case for case in cases}
    prediction_path = output_dir / f"{mode}.predictions.jsonl"
    with prediction_path.open("w", encoding="utf-8") as handle:
        for row in rows:
            question_id = str(row.get("question_id", ""))
            if question_id not in case_by_id:
                continue
            handle.write(json.dumps({
                "question_id": question_id,
                "answer": str(row.get("answer", "")),
                "evidence": row.get("citations") or [],
            }, ensure_ascii=False) + "\n")

    question_rows = report["questions"]
    fieldnames = list(question_rows[0].keys()) if question_rows else ["question_id"]
    with (output_dir / f"{mode}.questions.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(question_rows)


def markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# QASPER RAG Evaluation",
        "",
        f"- Generated: {report['generated_at']}",
        f"- Cases: {report['selected_cases']}",
        f"- Generator: `{report['config']['model_id']}`",
        f"- Judge: `{report['config']['judge_model_id']}`",
        f"- Top-K: {report['config']['top_k']}",
        f"- Context: `{report['config']['context_mode']}`",
        f"- Cases SHA-256: `{report['config']['cases_sha256']}`",
        f"- Server config: `{json.dumps(report['config']['server'], ensure_ascii=False, sort_keys=True)}`",
        "",
        "| Mode | Success | Gold Evidence Recall@K (N) | Evidence F1 | Answer F1 (N) | Semantic Acc. (N) | Numeric Acc. (N) | Abstention Acc. (N) | Gate P/C/R | Claim Faithfulness (claims) | Retrieval P50/P95 ms | Answer P50/P95 ms | Reranker applied |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for mode, mode_report in report["modes"].items():
        metric = mode_report["metrics"]
        lines.append("| " + " | ".join([
            mode,
            f"{metric['successful']}/{metric['total']}",
            f"{fmt(metric['context_recall_at_k'])} ({metric['context_scored']})",
            fmt(metric["evidence_f1"]),
            f"{fmt(metric['answer_f1'])} ({metric['answer_scored']})",
            f"{fmt(metric['semantic_accuracy'])} ({metric['semantic_scored']})",
            f"{fmt(metric['numeric_accuracy'])} ({metric['numeric_scored']})",
            f"{fmt(metric['abstention_accuracy'])} ({metric['abstention_scored']})",
            f"{metric['gate_pass']}/{metric['gate_caution']}/{metric['gate_reject']}",
            f"{fmt(metric['faithfulness'])} ({metric['supported_claims']}/{metric['total_claims']})",
            f"{fmt(metric['retrieval_latency_p50_ms'], percent=False)}/{fmt(metric['retrieval_latency_p95_ms'], percent=False)}",
            f"{fmt(metric['answer_latency_p50_ms'], percent=False)}/{fmt(metric['answer_latency_p95_ms'], percent=False)}",
            fmt(metric["reranker_applied_rate"]),
        ]) + " |")
    lines.extend([
        "",
        "> Gold Evidence Recall@K is token coverage of QASPER human evidence by returned Top-K citation text.",
        "> Claim Faithfulness is supported atomic claims / all judged atomic claims. Always report judge model and claim count.",
        "> Semantic Accuracy is judged against QASPER gold answers and is reported separately from token Answer F1.",
        "> Gate P/C/R reports deterministic retrieval-quality PASS/CAUTION/REJECT counts.",
        "> A hybrid_rerank result is valid only when `reranker_applied_rate` is 100% or the fallback rate is disclosed.",
        "",
    ])
    return "\n".join(lines)


def run_signature(mode: str, config: RunConfig) -> dict[str, Any]:
    return {
        "schema": 4,
        "endpoint": config.endpoint,
        "mode": mode,
        "model_id": config.model_id,
        "judge_model_id": config.judge_model_id,
        "top_k": config.top_k,
        "context_mode": config.context_mode,
        "generate_answer": config.generate_answer,
        "judge": config.judge,
        "cases_sha256": config.cases_sha256,
        "server_config": config.server_config,
    }


def successful_rows(path: Path, signature: dict[str, Any]) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    result: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(path):
        question_id = str(row.get("question_id", ""))
        judge_required = bool(signature.get("judge"))
        judgement_ok = not judge_required or valid_judgement(row)
        if question_id and not row.get("error") and judgement_ok and row.get("_run_config") == signature:
            result[question_id] = row
    return result


def valid_judgement(row: dict[str, Any]) -> bool:
    faithfulness = row.get("faithfulness") or {}
    answer_evaluation = row.get("answerEvaluation") or {}
    return (
        isinstance(faithfulness.get("score"), (int, float))
        and int(faithfulness.get("totalClaims") or 0) > 0
        and isinstance(answer_evaluation.get("score"), (int, float))
        and isinstance(answer_evaluation.get("correct"), bool)
    )


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        handle.flush()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def number(value: Any) -> float | None:
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def add_numbers(*values: Any) -> float | None:
    normalized = [number(value) for value in values]
    present = [value for value in normalized if value is not None]
    return sum(present) if present else None


def mean(values) -> float | None:
    present = [float(value) for value in values if value is not None and math.isfinite(float(value))]
    return round(statistics.fmean(present), 6) if present else None


def mean_bool(values) -> float | None:
    present = [value for value in values if value is not None]
    return round(sum(1.0 if value else 0.0 for value in present) / len(present), 6) if present else None


def ratio(left: int, right: int) -> float | None:
    return round(left / right, 6) if right else None


def percentile(values, percentile_value: float) -> float | None:
    ordered = sorted(float(value) for value in values if value is not None and math.isfinite(float(value)))
    if not ordered:
        return None
    if len(ordered) == 1:
        return round(ordered[0], 3)
    position = (len(ordered) - 1) * percentile_value / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return round(ordered[lower], 3)
    weight = position - lower
    return round(ordered[lower] * (1.0 - weight) + ordered[upper] * weight, 3)


def fmt(value: Any, percent: bool = True) -> str:
    numeric = number(value)
    if numeric is None:
        return "-"
    return f"{numeric * 100:.2f}%" if percent else f"{numeric:.1f}"


if __name__ == "__main__":
    main()
