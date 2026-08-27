#!/usr/bin/env python3
"""
Agent-mode evaluation runner for the Spring AI demo project.

The runner calls the local Spring Boot agent API and uses a MIMO OpenAI-compatible
chat model as the LLM judge. It keeps every task in an isolated workspace under
agent_test/runs/<run_id>/workspaces/<task_id>.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DATASET = BASE_DIR / "dataset.json"
DEFAULT_AGENT_URL = "http://127.0.0.1:8088/agent"
DEFAULT_MIMO_BASE_URL = "https://token-plan-cn.xiaomimimo.com"
DEFAULT_MIMO_MODEL = "mimo-v2.5-pro"


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate Spring AI agent-mode tasks.")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET), help="Path to dataset JSON.")
    parser.add_argument("--agent-url", default=os.getenv("AGENT_EVAL_AGENT_URL", DEFAULT_AGENT_URL))
    parser.add_argument("--run-id", default=dt.datetime.now().strftime("%Y%m%d_%H%M%S"))
    parser.add_argument("--case", action="append", dest="case_ids", help="Run only selected task id. Repeatable.")
    parser.add_argument("--limit", type=int, default=0, help="Run at most N tasks after filtering.")
    parser.add_argument("--timeout", type=int, default=300, help="HTTP timeout seconds per agent phase.")
    parser.add_argument("--max-approvals", type=int, default=12, help="Maximum auto approvals per task.")
    parser.add_argument("--no-judge", action="store_true", help="Skip LLM quality judging.")
    parser.add_argument("--judge-base-url", default=os.getenv("MIMO_BASE_URL", DEFAULT_MIMO_BASE_URL))
    parser.add_argument("--judge-model", default=os.getenv("MIMO_MODEL", DEFAULT_MIMO_MODEL))
    parser.add_argument("--judge-api-key", default=os.getenv("MIMO_KEY", ""))
    parser.add_argument("--keep-workspaces", action="store_true", help="Do not delete prior run directory if it exists.")
    args = parser.parse_args()

    dataset = read_json(Path(args.dataset))
    tasks = list(dataset.get("tasks", []))
    if args.case_ids:
        selected = set(args.case_ids)
        tasks = [task for task in tasks if task.get("id") in selected]
    if args.limit > 0:
      tasks = tasks[:args.limit]
    if not tasks:
        print("No tasks selected.", file=sys.stderr)
        return 2
    if not args.no_judge and not args.judge_api_key:
        print("MIMO_KEY is required unless --no-judge is used.", file=sys.stderr)
        return 2

    run_dir = BASE_DIR / "runs" / args.run_id
    workspace_root = run_dir / "workspaces"
    report_dir = BASE_DIR / "reports"
    if run_dir.exists() and not args.keep_workspaces:
        shutil.rmtree(run_dir)
    workspace_root.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, Any]] = []
    for index, task in enumerate(tasks, start=1):
        task_id = task["id"]
        print(f"[{index}/{len(tasks)}] {task_id} {task.get('title', '')}".strip())
        workspace = workspace_root / task_id
        prepare_workspace(workspace, task.get("setup_files", {}))
        result = run_task(task, workspace, args)
        results.append(result)
        print(format_task_line(result))

    summary = build_summary(dataset, results, args)
    report = {
        "summary": summary,
        "results": results,
    }
    json_path = report_dir / f"{args.run_id}_agent_eval.json"
    md_path = report_dir / f"{args.run_id}_agent_eval.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown_report(report), encoding="utf-8")

    print()
    print(f"JSON report: {json_path}")
    print(f"Markdown report: {md_path}")
    print(
        "Completion rate: "
        f"{summary['completion_rate']:.2%} "
        f"({summary['completed_tasks']}/{summary['total_tasks']})"
    )
    return 0


def run_task(task: dict[str, Any], workspace: Path, args: argparse.Namespace) -> dict[str, Any]:
    conversation_id = f"agent-eval-{args.run_id}-{task['id']}"
    prompt = build_agent_prompt(task)
    started = time.perf_counter()
    phases: list[dict[str, Any]] = []
    status = "error"
    final_content = ""
    final_payload: dict[str, Any] = {}
    error = ""
    approvals = 0

    try:
        phase = post_agent_stream(
            f"{args.agent_url.rstrip('/')}/chat/stream",
            form={
                "prompt": prompt,
                "conversationId": conversation_id,
                "workingDirectory": str(workspace),
            },
            timeout=args.timeout,
        )
        phases.append(phase)
        terminal = phase.get("terminal") or {}
        status = terminal.get("status", "")

        while status == "approval_required":
            approvals += 1
            if approvals > args.max_approvals:
                raise RuntimeError(f"approval limit exceeded ({args.max_approvals})")
            run_id = terminal.get("runId")
            if not run_id:
                raise RuntimeError("approval_required response did not include runId")
            phase = post_agent_stream(
                f"{args.agent_url.rstrip('/')}/approve/stream",
                json_body={"runId": run_id},
                timeout=args.timeout,
            )
            phases.append(phase)
            terminal = phase.get("terminal") or {}
            status = terminal.get("status", "")

        if status == "completed":
            final_payload = terminal
            final_content = terminal.get("content") or "".join(phase.get("tokens", []))
        elif status in {"interaction_required", "loop_limit_required"}:
            final_payload = terminal
            final_content = terminal.get("content", "")
        else:
            final_payload = terminal
            error = terminal.get("message") or f"unexpected status: {status}"
    except Exception as exc:
        error = str(exc)

    latency_ms = int((time.perf_counter() - started) * 1000)
    executions = collect_executions(phases, final_payload)
    tool_steps = len(executions)
    model_steps = count_model_steps(phases)
    agent_steps = max(1, model_steps + tool_steps)
    token_usage, token_source = collect_token_usage(phases, final_payload, prompt, final_content)
    expected_checks = run_expected_checks(task, workspace, final_content)
    judge = None
    if not args.no_judge:
        judge = judge_response(task, workspace, final_content, executions, expected_checks, args)
    completion = task_completed(status, expected_checks, judge)

    return {
        "id": task["id"],
        "title": task.get("title", ""),
        "status": status,
        "completed": completion,
        "error": error,
        "workspace": str(workspace),
        "latency_ms": latency_ms,
        "latency_seconds": round(latency_ms / 1000, 3),
        "token_usage": token_usage,
        "token_source": token_source,
        "agent_steps": agent_steps,
        "model_steps": model_steps,
        "tool_steps": tool_steps,
        "average_tokens_per_step": round(token_usage["total_tokens"] / agent_steps, 2),
        "approval_count": approvals,
        "tools": executions,
        "expected_checks": expected_checks,
        "judge": judge,
        "final_content": final_content,
    }


def build_agent_prompt(task: dict[str, Any]) -> str:
    return (
        f"Task: {task['prompt']}\n\n"
        "Rules:\n"
        "1. Work only inside the selected working directory.\n"
        "2. Inspect files before deciding what to write.\n"
        "3. Use tools when calculation, search, reading, or writing is needed.\n"
        "4. Keep the final answer concise and mention created or changed files.\n"
    )


def post_agent_stream(
    url: str,
    *,
    form: dict[str, str] | None = None,
    json_body: dict[str, Any] | None = None,
    timeout: int,
) -> dict[str, Any]:
    headers: dict[str, str] = {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
    elif json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    else:
        data = b""

    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    events: list[dict[str, Any]] = []
    tokens: list[str] = []
    terminal: dict[str, Any] = {}
    with urllib.request.urlopen(request, timeout=timeout) as response:
        current_event = "message"
        current_data: list[str] = []
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
            if line == "":
                event = dispatch_sse_event(current_event, current_data)
                if event:
                    events.append(event)
                    if event["event"] == "token":
                        content = event["data"].get("content")
                        if content:
                            tokens.append(str(content))
                    if event["event"] in {
                        "complete",
                        "approval_required",
                        "interaction_required",
                        "error",
                    }:
                        terminal = event["data"]
                        if event["event"] == "error":
                            terminal.setdefault("status", "error")
                        else:
                            terminal.setdefault("status", terminal.get("status") or event["event"])
                current_event = "message"
                current_data = []
                continue
            if line.startswith("event:"):
                current_event = line[len("event:"):].strip()
            elif line.startswith("data:"):
                current_data.append(line[len("data:"):].strip())
        event = dispatch_sse_event(current_event, current_data)
        if event:
            events.append(event)
    return {
        "events": events,
        "tokens": tokens,
        "terminal": terminal,
    }


def dispatch_sse_event(event_name: str, data_lines: list[str]) -> dict[str, Any] | None:
    if not data_lines:
        return None
    raw = "\n".join(data_lines)
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        data = {"raw": raw}
    return {"event": event_name, "data": data}


def collect_executions(phases: list[dict[str, Any]], final_payload: dict[str, Any]) -> list[dict[str, Any]]:
    batches = final_payload.get("executions") or []
    if not batches:
        for phase in phases:
            for event in phase.get("events", []):
                data = event.get("data") or {}
                batches.extend(data.get("executions") or [])
    tools: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for batch in batches:
        for tool in batch.get("tools") or []:
            key = (
                str(tool.get("name", "")),
                str(tool.get("arguments", "")),
                str(tool.get("output", ""))[:500],
            )
            if key not in seen:
                seen.add(key)
                tools.append({
                    "name": tool.get("name", ""),
                    "arguments": tool.get("arguments", ""),
                    "output_preview": str(tool.get("output", ""))[:1000],
                })
    return tools


def count_model_steps(phases: list[dict[str, Any]]) -> int:
    count = 0
    for phase in phases:
        terminal = phase.get("terminal") or {}
        if terminal.get("tokenUsage"):
            count += 1
        elif phase.get("tokens") or terminal:
            count += 1
    return max(1, count)


def collect_token_usage(
    phases: list[dict[str, Any]],
    final_payload: dict[str, Any],
    prompt: str,
    final_content: str,
) -> tuple[dict[str, int], str]:
    total = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    found_real = False
    for phase in phases:
        terminal = phase.get("terminal") or {}
        usage = normalize_usage(terminal.get("tokenUsage"))
        if usage and usage["total_tokens"] > 0:
            found_real = True
            for key in total:
                total[key] += usage[key]
    if found_real:
        return total, "provider"

    estimated_prompt = estimate_tokens(prompt)
    estimated_completion = estimate_tokens(final_content)
    estimated_total = estimated_prompt + estimated_completion
    return {
        "prompt_tokens": estimated_prompt,
        "completion_tokens": estimated_completion,
        "total_tokens": estimated_total,
    }, "estimated_chars"


def normalize_usage(value: Any) -> dict[str, int] | None:
    if not isinstance(value, dict):
        return None
    prompt = int_or_zero(value.get("promptTokens"))
    completion = int_or_zero(value.get("completionTokens"))
    total = int_or_zero(value.get("totalTokens"))
    if total == 0:
        total = prompt + completion
    return {
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": total,
    }


def int_or_zero(value: Any) -> int:
    try:
        number = int(value)
        return number if number > 0 else 0
    except (TypeError, ValueError):
        return 0


def estimate_tokens(text: str) -> int:
    if not text:
        return 0
    cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
    non_cjk = len(text) - cjk
    return max(1, cjk + round(non_cjk / 4))


def run_expected_checks(task: dict[str, Any], workspace: Path, final_content: str) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    for expected in task.get("expected_files", []):
        path = workspace / expected["path"]
        checks.append({
            "type": "file_exists",
            "path": expected["path"],
            "passed": path.is_file(),
        })
    return {
        "passed": all(check["passed"] for check in checks) if checks else True,
        "checks": checks,
    }


def judge_response(
    task: dict[str, Any],
    workspace: Path,
    final_content: str,
    executions: list[dict[str, Any]],
    expected_checks: dict[str, Any],
    args: argparse.Namespace,
) -> dict[str, Any]:
    snapshot = workspace_snapshot(workspace)
    prompt = {
        "task_id": task["id"],
        "task_prompt": task["prompt"],
        "expected_checks": expected_checks,
        "final_answer": final_content,
        "tool_names": [tool["name"] for tool in executions],
        "workspace_snapshot": snapshot,
    }
    messages = [
        {
            "role": "system",
            "content": (
                "You are an impartial evaluator for coding-agent task results. "
                "Return strict JSON only. Score each dimension from 0 to 10: "
                "relevance, correctness, logical_consistency, bias_safety, intent_alignment. "
                "Set task_completed true only when the user's requested outcome is actually achieved. "
                "Do not reward unsupported claims."
            ),
        },
        {
            "role": "user",
            "content": (
                "Evaluate this agent result and return JSON with keys: "
                "scores, average_score, task_completed, rationale, issues.\n\n"
                + json.dumps(prompt, ensure_ascii=False)
            ),
        },
    ]
    data = call_mimo_chat(args.judge_base_url, args.judge_model, args.judge_api_key, messages)
    text = data["choices"][0]["message"]["content"]
    parsed = parse_json_object(text)
    scores = parsed.get("scores", {})
    score_values = [clamp_score(scores.get(key)) for key in [
        "relevance",
        "correctness",
        "logical_consistency",
        "bias_safety",
        "intent_alignment",
    ]]
    average = round(sum(score_values) / len(score_values), 2)
    parsed["average_score"] = float(parsed.get("average_score", average) or average)
    parsed["scores"] = {
        "relevance": score_values[0],
        "correctness": score_values[1],
        "logical_consistency": score_values[2],
        "bias_safety": score_values[3],
        "intent_alignment": score_values[4],
    }
    parsed["task_completed"] = bool(parsed.get("task_completed", False))
    parsed["raw"] = text
    return parsed


def call_mimo_chat(base_url: str, model: str, api_key: str, messages: list[dict[str, str]]) -> dict[str, Any]:
    url = base_url.rstrip("/") + "/v1/chat/completions"
    body = {
        "model": model,
        "messages": messages,
        "temperature": 0,
        "response_format": {"type": "json_object"},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"MIMO judge HTTP {exc.code}: {detail}") from exc


def parse_json_object(text: str) -> dict[str, Any]:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.S)
        if match:
            return json.loads(match.group(0))
        raise


def workspace_snapshot(workspace: Path, max_files: int = 20, max_chars_per_file: int = 2000) -> dict[str, str]:
    snapshot: dict[str, str] = {}
    files = [path for path in workspace.rglob("*") if path.is_file()]
    for path in sorted(files)[:max_files]:
        rel = path.relative_to(workspace).as_posix()
        content = path.read_text(encoding="utf-8", errors="replace")
        snapshot[rel] = content[:max_chars_per_file]
    return snapshot


def task_completed(status: str, expected_checks: dict[str, Any], judge: dict[str, Any] | None) -> bool:
    if status != "completed":
        return False
    if not expected_checks.get("passed", False):
        return False
    if judge is None:
        return True
    return bool(judge.get("task_completed")) and float(judge.get("average_score", 0)) >= 7.0


def build_summary(dataset: dict[str, Any], results: list[dict[str, Any]], args: argparse.Namespace) -> dict[str, Any]:
    total = len(results)
    completed = sum(1 for item in results if item["completed"])
    latencies = [item["latency_ms"] for item in results]
    total_tokens = [item["token_usage"]["total_tokens"] for item in results]
    avg_step_tokens = [item["average_tokens_per_step"] for item in results]
    judge_scores = [
        float(item["judge"]["average_score"])
        for item in results
        if item.get("judge") and "average_score" in item["judge"]
    ]
    return {
        "dataset": dataset.get("name"),
        "version": dataset.get("version"),
        "run_id": args.run_id,
        "agent_url": args.agent_url,
        "judge_model": None if args.no_judge else args.judge_model,
        "total_tasks": total,
        "completed_tasks": completed,
        "completion_rate": completed / total if total else 0,
        "avg_latency_ms": round(sum(latencies) / total, 2) if total else 0,
        "avg_total_tokens": round(sum(total_tokens) / total, 2) if total else 0,
        "avg_tokens_per_step": round(sum(avg_step_tokens) / total, 2) if total else 0,
        "avg_quality_score": round(sum(judge_scores) / len(judge_scores), 2) if judge_scores else None,
    }


def render_markdown_report(report: dict[str, Any]) -> str:
    summary = report["summary"]
    avg_quality = summary["avg_quality_score"]
    lines = [
        "# Agent Evaluation Report",
        "",
        f"- Dataset: {summary['dataset']} {summary['version']}",
        f"- Run id: {summary['run_id']}",
        f"- Agent URL: {summary['agent_url']}",
        f"- Judge model: {summary['judge_model'] or 'disabled'}",
        f"- Completion rate: {summary['completion_rate']:.2%} ({summary['completed_tasks']}/{summary['total_tasks']})",
        f"- Avg latency: {summary['avg_latency_ms']} ms",
        f"- Avg total tokens: {summary['avg_total_tokens']}",
        f"- Avg tokens per step: {summary['avg_tokens_per_step']}",
        f"- Avg quality score: {avg_quality if avg_quality is not None else 'n/a'}",
        "",
        "| Task | Done | Latency | Tokens | Step avg | Tools | Quality |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in report["results"]:
        quality = item["judge"]["average_score"] if item.get("judge") else ""
        lines.append(
            f"| {item['id']} | {'yes' if item['completed'] else 'no'} | "
            f"{item['latency_ms']} | {item['token_usage']['total_tokens']} "
            f"({item['token_source']}) | {item['average_tokens_per_step']} | "
            f"{item['tool_steps']} | {quality} |"
        )
    return "\n".join(lines) + "\n"


def format_task_line(result: dict[str, Any]) -> str:
    return (
        f"  status={result['status']} completed={result['completed']} "
        f"latency={result['latency_ms']}ms "
        f"tokens={result['token_usage']['total_tokens']}({result['token_source']}) "
        f"tools={result['tool_steps']}"
    )


def prepare_workspace(workspace: Path, setup_files: dict[str, str]) -> None:
    if workspace.exists():
        shutil.rmtree(workspace)
    workspace.mkdir(parents=True, exist_ok=True)
    for relative, content in setup_files.items():
        path = safe_child(workspace, relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def safe_child(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    root_resolved = root.resolve()
    if root_resolved != path and root_resolved not in path.parents:
        raise ValueError(f"Path escapes workspace: {relative}")
    return path


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def clamp_score(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(10.0, number))


if __name__ == "__main__":
    raise SystemExit(main())
