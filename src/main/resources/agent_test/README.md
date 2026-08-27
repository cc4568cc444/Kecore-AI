# Agent Evaluation

This directory contains an agent-mode evaluation set for the project.
The current dataset focuses on cross-file reasoning, conflict resolution,
search-heavy discovery, calculation, and multi-artifact outputs. All task
workspaces are created under this directory, so tasks do not share files or
interfere with each other.

## Contents

- `dataset.json`: 20 advanced agent tasks. Each task has fixture files, a prompt,
  and expected output file paths.
- `evaluate_agent.py`: runner that calls the local Spring Boot agent API and
  uses a MIMO OpenAI-compatible model as the LLM judge.
- `runs/`: generated per-run workspaces.
- `reports/`: generated JSON and Markdown reports.

## Prerequisites

Start the Spring Boot app with the MIMO chat configuration enabled. The default
project configuration already points Spring AI OpenAI chat to:

```text
https://token-plan-cn.xiaomimimo.com/v1/chat/completions
model: mimo-v2.5-pro
```

Set `MIMO_KEY` before starting the app and before running the judge:

```powershell
$env:MIMO_KEY="your-mimo-key"
mvn spring-boot:run
```

The runner defaults to `http://127.0.0.1:8088/agent`.

## Run

Run all tasks with LLM judging:

```powershell
python src/main/resources/agent_test/evaluate_agent.py
```

Run a quick smoke test without LLM judging:

```powershell
python src/main/resources/agent_test/evaluate_agent.py --limit 1 --no-judge
```

Run selected cases:

```powershell
python src/main/resources/agent_test/evaluate_agent.py --case t01_invoice_total --case t10_error_code_lookup
```

If the app runs on another port:

```powershell
python src/main/resources/agent_test/evaluate_agent.py --agent-url http://127.0.0.1:8090/agent
```

## Metrics

- `token_usage.total_tokens`: total tokens for one task. Provider usage is used
  when the agent stream returns token usage. Otherwise the runner uses a
  character-based estimate and marks `token_source` as `estimated_chars`.
- `average_tokens_per_step`: total tokens divided by model steps plus executed
  tool steps.
- `latency_ms`: end-to-end time from user request submission until final agent
  response, including auto-approved tool calls.
- `judge.scores.relevance`: whether the answer addresses the task.
- `judge.scores.correctness`: whether the answer and artifacts are factually and
  computationally correct.
- `judge.scores.logical_consistency`: whether reasoning and conclusions are
  coherent.
- `judge.scores.bias_safety`: whether the answer avoids unsafe or biased output.
- `judge.scores.intent_alignment`: whether the result matches the user's intent.
- `completed`: true only when the agent completes, expected output files exist,
  and the LLM judge marks the task complete with an average score of at least 7.
  The hard rule layer does not inspect artifact content; correctness and
  relevance are left to the LLM judge.

## Isolation

For run id `20260612_120000`, task `t01_invoice_total` uses:

```text
src/main/resources/agent_test/runs/20260612_120000/workspaces/t01_invoice_total
```

The runner recreates each selected task workspace before executing it. Generated
reports are written to `src/main/resources/agent_test/reports`.
