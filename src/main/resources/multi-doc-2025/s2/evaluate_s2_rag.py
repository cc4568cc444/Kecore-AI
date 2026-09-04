#!/usr/bin/env python3
"""
Evaluate a medium-sized RAG subset for Multi-Doc-2025 S2.

The script is intentionally self-contained and reads the current project
configuration from src/main/resources/application.yaml:

- Embedding: app.embedding.ollama.* using Ollama /api/embeddings
- LLM: spring.ai.openai.chat.* using an OpenAI-compatible chat endpoint
- Database: spring.datasource.* storing vectors in isolated pgvector tables
- Evaluation: the configured chat model judges answer correctness, context recall, and grounding

Example:
  python evaluate_s2_rag.py --create-medium
  python evaluate_s2_rag.py --subset medium --rebuild --build-index
  python evaluate_s2_rag.py --subset medium --eval --limit 5
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlparse


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[4]
APPLICATION_YAML = PROJECT_ROOT / "src" / "main" / "resources" / "application.yaml"
ORIGINAL_DOC_DIR = SCRIPT_DIR / "original_doc"
DEFAULT_MEDIUM_DIR = SCRIPT_DIR / "medium"
DEFAULT_RESULTS_DIR = SCRIPT_DIR / "eval-results"

DEFAULT_MEDIUM_SIZE = 30
DEFAULT_SEED = 20250602
DEFAULT_TOP_K = 8
DEFAULT_HYBRID_TOP_K = 15
DEFAULT_RERANK_TOP_K = 8
DEFAULT_EMBEDDING_MAX_TOKENS = 384
DEFAULT_CHUNK_CHARS = 1400
DEFAULT_CHUNK_OVERLAP = 180
DEFAULT_TABLE_CONTEXT_TOKENS = 200
DEFAULT_PARENT_CONTEXT_CHARS = 6000

STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "be", "by", "calculate", "does", "for",
    "from", "how", "in", "include", "is", "of", "on", "or", "the", "to", "using",
    "was", "were", "what", "which", "with", "year", "ended", "fiscal",
}

ITEM_PATTERN = re.compile(
    r"\bItem\s+"
    r"(?P<item>1A|1B|1C|1|2|3|4|5|6|7A|7|8|9A|9B|9C|9|10|11|12|13|14|15|16)"
    r"(?:\.\s+|[ \t]+[-–—]\s+|[ \t]{2,})"
    r"(?P<title>[A-Z][A-Za-z0-9 &,.'’:/()%-]{3,120})",
    flags=re.IGNORECASE,
)


class HtmlTextParser(HTMLParser):
    BLOCK_TAGS = {
        "address", "article", "aside", "blockquote", "br", "caption", "div",
        "figcaption", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header",
        "li", "p", "section", "td", "th", "tr",
    }

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        tag = tag.lower()
        if tag in {"script", "style"} or tag.endswith(":hidden"):
            self.skip_depth += 1
            return
        if self.skip_depth == 0 and tag in self.BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if self.skip_depth:
            self.skip_depth -= 1
            return
        if tag in self.BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self.skip_depth:
            return
        text = data.strip()
        if text:
            self.parts.append(text)
            self.parts.append(" ")

    def text(self) -> str:
        return normalize_multiline_text("".join(self.parts))


class HtmlTableParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self.current_row: Optional[list[str]] = None
        self.current_cell: Optional[list[str]] = None
        self.skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        tag = tag.lower()
        if tag in {"script", "style"} or tag.endswith(":hidden"):
            self.skip_depth += 1
            return
        if self.skip_depth:
            return
        if tag == "tr":
            self.current_row = []
        elif tag in {"td", "th"}:
            self.current_cell = []

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if self.skip_depth:
            self.skip_depth -= 1
            return
        if tag in {"td", "th"} and self.current_cell is not None:
            cell = normalize_text(" ".join(self.current_cell))
            if self.current_row is not None:
                self.current_row.append(cell)
            self.current_cell = None
        elif tag == "tr" and self.current_row is not None:
            if any(cell for cell in self.current_row):
                self.rows.append(self.current_row)
            self.current_row = None

    def handle_data(self, data: str) -> None:
        if self.skip_depth or self.current_cell is None:
            return
        text = data.strip()
        if text:
            self.current_cell.append(text)

    def normalized_rows(self) -> list[list[str]]:
        rows = [row for row in self.rows if any(row)]
        if not rows:
            return []
        width = max(len(row) for row in rows)
        return [row + [""] * (width - len(row)) for row in rows]

    def markdown(self) -> str:
        normalized_rows = self.normalized_rows()
        if not normalized_rows:
            return ""

        lines = []
        width = max(len(row) for row in normalized_rows)
        header = normalized_rows[0]
        lines.append("| " + " | ".join(clean_table_cell(cell) for cell in header) + " |")
        lines.append("| " + " | ".join(["---"] * width) + " |")
        for row in normalized_rows[1:]:
            lines.append("| " + " | ".join(clean_table_cell(cell) for cell in row) + " |")
        return "\n".join(lines)


@dataclass
class ProjectConfig:
    jdbc_url: str
    db_username: str
    db_password: str
    embedding_base_url: str
    embedding_model: str
    embedding_dimensions: int
    llm_base_url: str
    llm_api_key: str
    llm_completions_path: str
    llm_model: str
    llm_temperature: float
    judge_model: str


@dataclass
class Chunk:
    chunk_id: str
    subset: str
    split: str
    company: str
    year: str
    source_file: str
    chunk_type: str
    chunk_index: int
    content: str
    metadata: dict[str, Any]


def main() -> int:
    load_dotenv(SCRIPT_DIR / ".env")
    args = parse_args()
    if args.create_medium:
        create_medium_subset(args.medium_size, args.seed)

    if not args.build_index and not args.eval:
        if args.create_medium:
            return 0
        print("No action selected. Use --create-medium, --build-index, or --eval.", file=sys.stderr)
        return 2

    config = load_project_config(APPLICATION_YAML)
    subset_dir = resolve_subset_dir(args.subset)
    questions = load_json(subset_dir / "test.json")
    required_docs = load_required_docs(subset_dir / "required-docs.txt", questions)
    tables = table_names(args.subset)

    with connect_postgres(config) as conn:
        ensure_schema(conn, tables, config.embedding_dimensions, args.rebuild)
        if args.build_index:
            build_index(conn, tables, config, required_docs, questions, args)
        if args.eval:
            evaluate(conn, tables, config, questions, args)

    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Multi-Doc-2025 S2 medium RAG evaluator")
    parser.add_argument("--create-medium", action="store_true", help="create medium/test.json and required-docs.txt")
    parser.add_argument("--medium-size", type=int, default=DEFAULT_MEDIUM_SIZE)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--subset", default="medium", choices=["medium", "full"], help="evaluation subset")
    parser.add_argument("--rebuild", action="store_true", help="drop isolated S2 tables before running")
    parser.add_argument("--build-index", action="store_true", help="parse HTML, embed chunks, and save to pgvector")
    parser.add_argument("--eval", action="store_true", help="run retrieval + LLM answering on subset test.json")
    parser.add_argument("--limit", type=int, default=0, help="limit questions/chunks for a smoke test")
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K)
    parser.add_argument("--hybrid-top-k", type=int, default=DEFAULT_HYBRID_TOP_K, help="vector/BM25 hybrid candidate count before final top-k")
    parser.add_argument(
        "--chunk-strategy",
        default="default",
        choices=["default", "parent-child"],
        help="chunking strategy; parent-child embeds child chunks and expands hits with parent context",
    )
    parser.add_argument("--chunk-chars", type=int, default=DEFAULT_CHUNK_CHARS)
    parser.add_argument("--chunk-overlap", type=int, default=DEFAULT_CHUNK_OVERLAP)
    parser.add_argument(
        "--parent-context-chars",
        type=int,
        default=DEFAULT_PARENT_CONTEXT_CHARS,
        help="max parent context characters attached to each child chunk when --chunk-strategy parent-child is enabled",
    )
    parser.add_argument("--embedding-max-tokens", type=int, default=DEFAULT_EMBEDDING_MAX_TOKENS)
    parser.add_argument("--max-chunks-per-doc", type=int, default=150, help="0 means unlimited; table chunks are kept first")
    parser.add_argument("--table-period-metadata", action="store_true", help="extract period/year metadata from table chunks during indexing")
    parser.add_argument("--whole-table-markdown", action="store_true", help="embed each HTML table as one complete markdown table chunk")
    parser.add_argument(
        "--table-context-tokens",
        type=int,
        default=DEFAULT_TABLE_CONTEXT_TOKENS,
        help="when --whole-table-markdown is enabled, include this many surrounding tokens before and after each table",
    )
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--no-metadata-filter", action="store_true", help="disable company/year/sector metadata filtering during retrieval")
    parser.add_argument("--rerank", action="store_true", help="rerank hybrid candidates with a local reranker")
    parser.add_argument("--rerank-url", default="http://127.0.0.1:8010", help="local reranker base URL")
    parser.add_argument("--rerank-model", default="Qwen3-Reranker-0.6B", help="local reranker model name")
    parser.add_argument("--rerank-top-k", type=int, default=DEFAULT_RERANK_TOP_K, help="reranker output count")
    parser.add_argument("--rerank-doc-chars", type=int, default=3000, help="max characters per candidate sent to reranker")
    parser.add_argument("--strict-rerank", action="store_true", help="fail the question when reranker returns an error")
    parser.add_argument("--no-llm", action="store_true", help="write retrieval-only eval rows without calling the LLM")
    parser.add_argument("--no-judge", action="store_true", help="skip LLM-as-judge metrics")
    return parser.parse_args()


def create_medium_subset(size: int, seed: int) -> None:
    all_test = load_json(SCRIPT_DIR / "test.json")
    selected = select_medium_questions(all_test, size, seed)
    docs = sorted({source_file_for(row) for row in selected if doc_exists(source_file_for(row))})

    DEFAULT_MEDIUM_DIR.mkdir(parents=True, exist_ok=True)
    write_json(DEFAULT_MEDIUM_DIR / "test.json", selected)
    (DEFAULT_MEDIUM_DIR / "required-docs.txt").write_text("\n".join(docs) + "\n", encoding="utf-8")
    (DEFAULT_MEDIUM_DIR / "README.md").write_text(
        "\n".join([
            "# Multi-Doc-2025 S2 Medium 子集",
            "",
            f"- 来源：`../test.json` 中固定抽样的 {len(selected)} 条测试问题",
            f"- 随机种子：`{seed}`",
            f"- 关联 HTML 文档数：`{len(docs)}`",
            "- 用途：先验证 S2 RAG 的向量化、检索、LLM 回答和评估结果落库链路",
            "",
            "HTML 文档不在本目录重复复制，脚本会从 `../original_doc/` 按 `required-docs.txt` 读取。",
        ]),
        encoding="utf-8",
    )
    print(f"Created medium subset: {len(selected)} questions, {len(docs)} docs")


def select_medium_questions(rows: list[dict[str, Any]], size: int, seed: int) -> list[dict[str, Any]]:
    rng = random.Random(seed)
    shuffled = list(rows)
    rng.shuffle(shuffled)

    selected: list[dict[str, Any]] = []
    seen_company: set[str] = set()
    seen_year: set[str] = set()

    for row in shuffled:
        company = str(row.get("company", ""))
        year = str(row.get("year", ""))
        if doc_exists(source_file_for(row)) and (company not in seen_company or year not in seen_year):
            selected.append(row)
            seen_company.add(company)
            seen_year.add(year)
        if len(selected) >= size:
            break

    if len(selected) < size:
        selected_ids = {row["id"] for row in selected}
        for row in shuffled:
            if row["id"] not in selected_ids and doc_exists(source_file_for(row)):
                selected.append(row)
                selected_ids.add(row["id"])
            if len(selected) >= size:
                break

    return sorted(selected, key=lambda row: str(row.get("id", "")))


def resolve_subset_dir(subset: str) -> Path:
    if subset == "full":
        return SCRIPT_DIR
    return SCRIPT_DIR / subset


def table_names(subset: str) -> dict[str, str]:
    safe_subset = re.sub(r"[^a-z0-9_]", "_", subset.lower())
    return {
        "chunks": f"multidoc_s2_{safe_subset}_chunks",
        "results": f"multidoc_s2_{safe_subset}_eval_results",
    }


def build_index(
    conn: Any,
    tables: dict[str, str],
    config: ProjectConfig,
    required_docs: list[str],
    questions: list[dict[str, Any]],
    args: argparse.Namespace,
) -> None:
    question_by_source = {normalize_source_file(source_file_for(row)): row for row in questions}
    docs = required_docs[: args.limit] if args.limit else required_docs
    inserted_chunks = 0
    existing_chunks = 0
    indexed_docs = 0
    missing_docs = 0
    total_parsed_chunks = 0
    total_selected_chunks = 0
    selected_type_counts: dict[str, int] = {}

    for doc_index, source_file in enumerate(docs, start=1):
        source_file = normalize_source_file(source_file)
        path = ORIGINAL_DOC_DIR / source_file
        if not path.exists():
            missing_docs += 1
            print(f"[skip] missing doc: {source_file}")
            continue

        row = question_by_source.get(source_file, {})
        parsed_chunks = parse_html_to_chunks(
            path,
            row,
            args.chunk_chars,
            args.chunk_overlap,
            args.table_period_metadata,
            args.whole_table_markdown,
            args.table_context_tokens,
        )
        if args.chunk_strategy == "parent-child":
            apply_parent_child_context(parsed_chunks, args.parent_context_chars)
        chunks = limit_chunks_per_doc(parsed_chunks, args.max_chunks_per_doc)
        indexed_docs += 1
        total_parsed_chunks += len(parsed_chunks)
        total_selected_chunks += len(chunks)
        for chunk in chunks:
            selected_type_counts[chunk.chunk_type] = selected_type_counts.get(chunk.chunk_type, 0) + 1
        if len(chunks) == len(parsed_chunks):
            print(f"[index] {doc_index}/{len(docs)} {source_file}: {len(chunks)} chunks")
        else:
            print(f"[index] {doc_index}/{len(docs)} {source_file}: {len(chunks)}/{len(parsed_chunks)} chunks")
        for chunk in chunks:
            if chunk_exists(conn, tables["chunks"], chunk.chunk_id):
                existing_chunks += 1
                continue
            embedding_max_tokens = 0 if args.whole_table_markdown and chunk.chunk_type == "table" else args.embedding_max_tokens
            embedding = embed_text(config, chunk.content, embedding_max_tokens)
            insert_chunk(conn, tables["chunks"], chunk, embedding)
            inserted_chunks += 1
            if inserted_chunks % args.batch_size == 0:
                conn.commit()
        conn.commit()

    print("[index-summary]")
    print(f"  docs: indexed={indexed_docs}, missing={missing_docs}, requested={len(docs)}")
    print(f"  chunks parsed: {total_parsed_chunks}")
    print(f"  chunks selected for indexing: {total_selected_chunks}")
    print(f"  chunks by type: {format_count_map(selected_type_counts)}")
    print(f"  chunks inserted: {inserted_chunks}")
    print(f"  chunks already existed: {existing_chunks}")


def evaluate(
    conn: Any,
    tables: dict[str, str],
    config: ProjectConfig,
    questions: list[dict[str, Any]],
    args: argparse.Namespace,
) -> None:
    DEFAULT_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    rows = questions[: args.limit] if args.limit else questions
    output_rows: list[dict[str, Any]] = []

    for index, row in enumerate(rows, start=1):
        started_at = time.time()
        print(f"[eval] {index}/{len(rows)} {row.get('id')} {row.get('company')}_{row.get('year')}")

        error = ""
        judge_error = ""
        prediction = ""
        retrieved: list[dict[str, Any]] = []
        judge = default_judge_result()
        try:
            query_embedding = embed_text(config, str(row["question"]), args.embedding_max_tokens)
            retrieved = search_chunks(conn, tables["chunks"], row, query_embedding, args)
            if args.no_llm:
                prediction = ""
            else:
                prediction = answer_with_llm(config, row, retrieved)
            if not args.no_judge:
                try:
                    judge = judge_with_llm(config, row, retrieved, prediction)
                except Exception as exc:
                    judge_error = str(exc)
        except Exception as exc:
            error = str(exc)

        latency_ms = int((time.time() - started_at) * 1000)
        retrieved_sources = [item["source_file"] for item in retrieved]
        retrieved_scores = [item["score"] for item in retrieved]
        retrieved_context_chars = sum(len(item["content"]) for item in retrieved)
        expected_source = source_file_for(row)
        result = {
            "question_id": row.get("id"),
            "company": row.get("company"),
            "year": row.get("year"),
            "question": row.get("question"),
            "expected_answer": row.get("answer"),
            "predicted_answer": prediction,
            "exact_match": normalize_answer(prediction) == normalize_answer(str(row.get("answer", ""))) if prediction else False,
            "contains_expected": normalize_answer(str(row.get("answer", ""))) in normalize_answer(prediction) if prediction else False,
            "retrieved_chunk_ids": [item["chunk_id"] for item in retrieved],
            "retrieved_sources": retrieved_sources,
            "top_scores": retrieved_scores,
            "retrieved_context_chars": retrieved_context_chars,
            "retrieval_source_hit": expected_source in retrieved_sources,
            "llm_answer_correct": judge["answer_correct"],
            "llm_context_recall": judge["context_has_answer"],
            "llm_answer_grounded": judge["answer_grounded"],
            "llm_calculation_correct": judge["calculation_correct"],
            "llm_score": judge["score"],
            "llm_judge_reason": judge["reason"],
            "llm_judged": not args.no_judge and not error and not judge_error,
            "latency_ms": latency_ms,
            "error": error,
            "judge_error": judge_error,
        }
        insert_eval_result(conn, tables["results"], result)
        conn.commit()
        output_rows.append(result)
        print_eval_errors(result)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    json_path = DEFAULT_RESULTS_DIR / f"s2-{args.subset}-results-{stamp}.json"
    csv_path = DEFAULT_RESULTS_DIR / f"s2-{args.subset}-results-{stamp}.csv"
    summary = summarize_results(output_rows)
    write_json(json_path, {"summary": summary, "results": output_rows})
    write_csv(csv_path, output_rows)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print_error_summary(output_rows)
    print(f"Wrote results: {json_path}")
    print(f"Wrote results: {csv_path}")


def parse_html_to_chunks(
    path: Path,
    qa_row: dict[str, Any],
    chunk_chars: int,
    overlap: int,
    include_period_metadata: bool = False,
    whole_table_markdown: bool = False,
    table_context_tokens: int = DEFAULT_TABLE_CONTEXT_TOKENS,
) -> list[Chunk]:
    html_text = path.read_text(encoding="utf-8", errors="ignore")
    source_file = path.name
    company, year = parse_company_year(source_file)
    company = str(qa_row.get("company") or company)
    year = str(qa_row.get("year") or year)
    sector = str(qa_row.get("sector") or qa_row.get("gics_sector") or "")
    industry = str(qa_row.get("industry") or qa_row.get("gics_industry") or sector)
    subset = str(qa_row.get("subset") or "s2")
    split = str(qa_row.get("split") or "medium")
    base_metadata = {
        "dataset": "multi-doc-2025",
        "subset": subset,
        "subsets": qa_row.get("subsets") or [subset],
        "split": split,
        "splits": qa_row.get("splits") or [split],
        "company": company,
        "year": year,
        "sector": sector,
        "gics_sector": sector,
        "gics_industry": industry,
        "source_file": source_file,
        "evidence_section": qa_row.get("evidence_section"),
        "evidence_sections": qa_row.get("evidence_sections") or [],
        "is_cross_doc": bool(qa_row.get("is_cross_doc", False)),
        "is_cross_year": bool(qa_row.get("is_cross_year", False)),
        "is_hybrid_modal": bool(qa_row.get("is_hybrid_modal", False)),
        "requires_calculation": bool(qa_row.get("requires_calculation", False)),
    }

    chunks: list[Chunk] = []
    table_matches = list(re.finditer(r"<table\b[\s\S]*?</table>", html_text, flags=re.IGNORECASE))
    for table_index, match in enumerate(table_matches):
        table_html = match.group(0)
        table_rows = table_to_rows(table_html)
        table_header = table_header_text(table_rows)
        period_metadata = extract_table_period_metadata(table_rows) if include_period_metadata else {}
        item = item_before_html_offset(html_text, match.start())
        table_before_context = table_context_excerpt(
            html_text[max(0, match.start() - 80_000):match.start()],
            table_context_tokens,
            keep_tail=True,
        )
        table_after_context = table_context_excerpt(
            html_text[match.end():min(len(html_text), match.end() + 80_000)],
            table_context_tokens,
            keep_tail=False,
        )
        if whole_table_markdown:
            markdown_table = table_to_markdown_with_context(
                table_html,
                table_before_context,
                table_after_context,
            )
            table_parts = [markdown_table] if markdown_table else []
        else:
            table_parts = [
                table_chunk_with_context(part, table_before_context, table_after_context)
                for part in split_table_rows(table_rows, chunk_chars, overlap)
            ]
        if not table_parts:
            continue
        for part_index, part in enumerate(table_parts):
            chunks.append(make_chunk(
                source_file,
                company,
                year,
                "table",
                len(chunks),
                part,
                {
                    **base_metadata,
                    "table_index": table_index,
                    "table_part_index": part_index,
                    "table_header": table_header,
                    "item": item["item"],
                    "section_title": item["section_title"],
                    "table_context_before": table_before_context,
                    "table_context_after": table_after_context,
                    **period_metadata,
                    "search_keywords": extract_search_keywords(part, table_header),
                },
            ))

    plain_text = html_to_text_with_markdown_tables(html_text)
    sections = extract_item_sections(plain_text)
    for section_index, section in enumerate(sections):
        section_prefix = section_context_prefix(section)
        for text_index, part in enumerate(split_text(section["content"], chunk_chars, overlap)):
            if len(part) < 120:
                continue
            content = f"{section_prefix}\n{part}" if section_prefix else part
            chunks.append(make_chunk(
                source_file,
                company,
                year,
                "text",
                len(chunks),
                content,
                {
                    **base_metadata,
                    "section_index": section_index,
                    "text_part_index": text_index,
                    "item": section["item"],
                    "section_title": section["section_title"],
                    "search_keywords": extract_search_keywords(content, section["section_title"]),
                },
            ))

    return chunks


def limit_chunks_per_doc(chunks: list[Chunk], max_chunks: int) -> list[Chunk]:
    if max_chunks <= 0 or len(chunks) <= max_chunks:
        return chunks

    table_chunks = [chunk for chunk in chunks if chunk.chunk_type == "table"]
    text_chunks = [chunk for chunk in chunks if chunk.chunk_type == "text"]
    selected = table_chunks[:max_chunks]
    if len(selected) < max_chunks:
        selected.extend(text_chunks[:max_chunks - len(selected)])
    return selected


def make_chunk(
    source_file: str,
    company: str,
    year: str,
    chunk_type: str,
    chunk_index: int,
    content: str,
    metadata: dict[str, Any],
) -> Chunk:
    digest = hashlib.sha256(f"{source_file}:{chunk_type}:{chunk_index}:{content[:500]}".encode("utf-8")).hexdigest()[:24]
    subset = str(metadata.get("subset") or "s2")
    split = str(metadata.get("split") or "medium")
    chunk_prefix = "md2025" if subset == "multi" else "s2"
    return Chunk(
        chunk_id=f"{chunk_prefix}-{digest}",
        subset=subset,
        split=split,
        company=company,
        year=year,
        source_file=source_file,
        chunk_type=chunk_type,
        chunk_index=chunk_index,
        content=content,
        metadata=metadata,
    )


def apply_parent_child_context(chunks: list[Chunk], max_chars: int) -> None:
    if not chunks:
        return
    max_chars = max(1, max_chars)
    original_chunk_ids = {id(chunk): chunk.chunk_id for chunk in chunks}
    grouped: dict[tuple[str, str, str], list[Chunk]] = {}
    for chunk in chunks:
        grouped.setdefault(parent_group_key(chunk), []).append(chunk)

    for group_key, group_chunks in grouped.items():
        group_chunks.sort(key=lambda item: item.chunk_index)
        for position, chunk in enumerate(group_chunks):
            start, end = parent_window_bounds(group_chunks, position, max_chars)
            parent_chunks = group_chunks[start:end]
            parent_context = "\n\n".join(item.content for item in parent_chunks).strip()
            if len(parent_context) > max_chars:
                parent_context = parent_context[:max_chars].rstrip()
            child_chunk_id = chunk.chunk_id
            parent_id = parent_context_id(group_key, parent_chunks)
            chunk.metadata.update({
                "chunk_strategy": "parent-child",
                "child_chunk_id": child_chunk_id,
                "parent_id": parent_id,
                "parent_context": parent_context,
                "parent_context_chars": len(parent_context),
                "parent_window_chunk_ids": [original_chunk_ids[id(item)] for item in parent_chunks],
                "parent_window_chunk_indexes": [item.chunk_index for item in parent_chunks],
            })
            chunk.chunk_id = parent_child_chunk_id(chunk, child_chunk_id, parent_id)


def parent_group_key(chunk: Chunk) -> tuple[str, str, str]:
    metadata = chunk.metadata or {}
    if chunk.chunk_type == "table" and metadata.get("table_index") is not None:
        group = f"table:{metadata.get('table_index')}"
    elif chunk.chunk_type == "text" and metadata.get("section_index") is not None:
        group = f"section:{metadata.get('section_index')}"
    else:
        group = f"chunk:{chunk.chunk_index}"
    return (chunk.source_file, chunk.chunk_type, group)


def parent_window_bounds(chunks: list[Chunk], position: int, max_chars: int) -> tuple[int, int]:
    start = position
    end = position + 1
    total = len(chunks[position].content)
    left = position - 1
    right = position + 1

    while left >= 0 or right < len(chunks):
        expanded = False
        if left >= 0:
            candidate_len = len(chunks[left].content) + 2
            if total + candidate_len <= max_chars:
                start = left
                total += candidate_len
                expanded = True
            left -= 1
        if right < len(chunks):
            candidate_len = len(chunks[right].content) + 2
            if total + candidate_len <= max_chars:
                end = right + 1
                total += candidate_len
                expanded = True
            right += 1
        if not expanded:
            break

    return start, end


def parent_context_id(group_key: tuple[str, str, str], parent_chunks: list[Chunk]) -> str:
    indexes = ",".join(str(item.chunk_index) for item in parent_chunks)
    digest = hashlib.sha256(f"{group_key}:{indexes}".encode("utf-8")).hexdigest()[:24]
    return f"s2-parent-{digest}"


def parent_child_chunk_id(chunk: Chunk, child_chunk_id: str, parent_id: str) -> str:
    digest = hashlib.sha256(f"{child_chunk_id}:{parent_id}:{chunk.chunk_index}".encode("utf-8")).hexdigest()[:24]
    return f"s2-pc-{digest}"


def html_to_text(value: str) -> str:
    parser = HtmlTextParser()
    parser.feed(value)
    return parser.text()


def html_to_text_with_markdown_tables(value: str) -> str:
    visible_html = re.sub(r"<ix:hidden\b[\s\S]*?</ix:hidden>", " ", value, flags=re.IGNORECASE)
    parts: list[str] = []
    last_end = 0
    for match in re.finditer(r"<table\b[\s\S]*?</table>", visible_html, flags=re.IGNORECASE):
        before_text = html_to_text(visible_html[last_end:match.start()])
        if before_text:
            parts.append(before_text)
        markdown_table = table_to_markdown(match.group(0))
        if markdown_table:
            parts.append("Markdown table:\n" + markdown_table)
        last_end = match.end()

    after_text = html_to_text(visible_html[last_end:])
    if after_text:
        parts.append(after_text)
    return normalize_multiline_text("\n\n".join(parts))


def table_to_markdown(value: str) -> str:
    parser = HtmlTableParser()
    parser.feed(value)
    return parser.markdown()


def table_to_markdown_with_context(table_html: str, before_context: str, after_context: str) -> str:
    return table_chunk_with_context(table_to_markdown(table_html), before_context, after_context)


def table_chunk_with_context(table_text: str, before_context: str, after_context: str) -> str:
    parts = []
    if before_context:
        parts.append("Table context before:\n" + before_context)

    if table_text:
        parts.append("Table:\n" + table_text)

    if after_context:
        parts.append("Table context after:\n" + after_context)

    return "\n\n".join(parts)


def table_context_excerpt(value: str, token_limit: int, keep_tail: bool) -> str:
    if token_limit <= 0:
        return ""
    text = normalize_multiline_text(html_to_text(value))
    if not text:
        return ""
    tokens = tokenize_for_context(text)
    if not tokens:
        return ""
    selected = tokens[-token_limit:] if keep_tail else tokens[:token_limit]
    return normalize_multiline_text(" ".join(selected))


def table_to_rows(value: str) -> list[list[str]]:
    parser = HtmlTableParser()
    parser.feed(value)
    return parser.normalized_rows()


def split_table_rows(rows: list[list[str]], chunk_chars: int, overlap: int) -> list[str]:
    if not rows:
        return []
    width = max(len(row) for row in rows)
    normalized_rows = [row + [""] * (width - len(row)) for row in rows if any(row)]
    if not normalized_rows:
        return []

    header_count = detect_table_header_count(normalized_rows)
    header_rows = normalized_rows[:header_count]
    body_rows = normalized_rows[header_count:] or normalized_rows[header_count - 1:]
    header_text = table_header_text(normalized_rows)
    prefix_lines = [f"Table header: {header_text}"] if header_text else []
    prefix_lines.extend(markdown_lines(header_rows))

    chunks: list[str] = []
    current_rows: list[list[str]] = []
    current_size = len("\n".join(prefix_lines))
    row_budget = max(240, chunk_chars - current_size)

    for row in body_rows:
        row_line = markdown_lines([row])[0]
        if current_rows and current_size + len(row_line) + 1 > chunk_chars:
            chunks.append("\n".join(prefix_lines + markdown_lines(current_rows)).strip())
            overlap_rows = current_rows[-1:] if overlap > 0 else []
            current_rows = list(overlap_rows)
            current_size = len("\n".join(prefix_lines + markdown_lines(current_rows)))
        current_rows.append(row)
        current_size += len(row_line) + 1
        if not current_rows and len(row_line) > row_budget:
            chunks.append("\n".join(prefix_lines + [row_line]).strip())

    if current_rows:
        chunks.append("\n".join(prefix_lines + markdown_lines(current_rows)).strip())
    return [chunk for chunk in chunks if len(chunk) >= 80]


def markdown_lines(rows: list[list[str]]) -> list[str]:
    return ["| " + " | ".join(clean_table_cell(cell) for cell in row) + " |" for row in rows]


def detect_table_header_count(rows: list[list[str]]) -> int:
    header_count = 1
    for index, row in enumerate(rows[:4]):
        text = " ".join(row)
        non_empty = [cell for cell in row if cell.strip()]
        numeric_cells = sum(1 for cell in non_empty if re.search(r"\d", cell))
        if index == 0:
            header_count = 1
            continue
        if non_empty and numeric_cells <= max(1, len(non_empty) // 3):
            header_count = index + 1
            continue
        if re.search(r"\b(Year|Fiscal|Month|Three Months|Nine Months|December|September|202[0-9])\b", text, re.IGNORECASE):
            header_count = index + 1
            continue
        break
    return min(header_count, max(1, len(rows)))


def table_header_text(rows: list[list[str]]) -> str:
    if not rows:
        return ""
    header_count = detect_table_header_count(rows)
    cells: list[str] = []
    for row in rows[:header_count]:
        cells.extend(cell for cell in row if cell.strip())
    return normalize_text(" | ".join(cells))[:800]


def extract_table_period_metadata(rows: list[list[str]]) -> dict[str, Any]:
    if not rows:
        return {}

    candidate_text = table_period_candidate_text(rows)
    periods = unique_preserve_order(extract_period_phrases(candidate_text))
    years = unique_preserve_order(re.findall(r"\b20\d{2}\b", " ".join(periods) if periods else candidate_text))

    if not periods and years:
        periods = years[:]
    if not periods and not years:
        return {}

    return {
        "periods": periods[:12],
        "period_years": years[:8],
        "primary_period": periods[0] if periods else "",
    }


def table_period_candidate_text(rows: list[list[str]]) -> str:
    selected_rows = rows[: min(len(rows), 8)]
    first_column_cells = [row[0] for row in rows[:20] if row and row[0].strip()]
    values: list[str] = []
    for row in selected_rows:
        values.extend(cell for cell in row if cell.strip())
    values.extend(first_column_cells)
    return normalize_text(" | ".join(values))


def extract_period_phrases(text: str) -> list[str]:
    month = (
        "January|February|March|April|May|June|July|August|September|October|November|December"
    )
    patterns = [
        rf"\b(?:As of|At|As at)?\s*(?:{month})\s+\d{{1,2}},\s+20\d{{2}}\b",
        rf"\b(?:Year|Years|Fiscal year|Fiscal years|Three months|Six months|Nine months|Twelve months)"
        rf"(?:\s+ended)?\s+(?:{month})\s+\d{{1,2}},\s+20\d{{2}}\b",
        r"\bFY\s*20\d{2}\b",
        r"\bFiscal\s+20\d{2}\b",
    ]
    periods: list[str] = []
    for pattern in patterns:
        for match in re.finditer(pattern, text, flags=re.IGNORECASE):
            periods.append(normalize_text(match.group(0)))
    return periods


def unique_preserve_order(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        normalized = normalize_text(str(value))
        key = normalized.lower()
        if not normalized or key in seen:
            continue
        seen.add(key)
        result.append(normalized)
    return result


def extract_item_sections(text: str) -> list[dict[str, str]]:
    matches = list(ITEM_PATTERN.finditer(text))
    if not matches:
        return [{"item": "", "section_title": "", "content": text}]

    sections: list[dict[str, str]] = []
    if matches[0].start() > 0:
        prefix = text[:matches[0].start()].strip()
        if prefix:
            sections.append({"item": "", "section_title": "", "content": prefix})

    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        title = normalize_text(match.group("title"))
        sections.append({
            "item": normalize_item(match.group("item")),
            "section_title": title,
            "content": text[start:end].strip(),
        })
    return [section for section in sections if section["content"]]


def item_before_html_offset(html_text: str, offset: int) -> dict[str, str]:
    context_html = html_text[max(0, offset - 80000):offset]
    context_text = html_to_text(context_html)
    matches = list(ITEM_PATTERN.finditer(context_text))
    if not matches:
        return {"item": "", "section_title": ""}
    match = matches[-1]
    return {
        "item": normalize_item(match.group("item")),
        "section_title": normalize_text(match.group("title")),
    }


def normalize_item(value: str) -> str:
    return f"Item {value.upper()}"


def section_context_prefix(section: dict[str, str]) -> str:
    if not section.get("item"):
        return ""
    title = section.get("section_title") or ""
    return normalize_text(f"Section: {section['item']}. {title}")


def extract_search_keywords(content: str, extra: str = "") -> list[str]:
    values = []
    for token in tokenize_for_search(f"{extra} {content}"):
        if token not in values:
            values.append(token)
        if len(values) >= 80:
            break
    return values


def split_text(value: str, chunk_chars: int, overlap: int) -> list[str]:
    text = normalize_multiline_text(value)
    if not text:
        return []
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + chunk_chars, len(text))
        if end < len(text):
            boundary = max(text.rfind("\n", start, end), text.rfind(". ", start, end), text.rfind(" ", start, end))
            if boundary > start + chunk_chars // 2:
                end = boundary + 1
        chunks.append(text[start:end].strip())
        if end >= len(text):
            break
        start = max(0, end - overlap)
    return [chunk for chunk in chunks if chunk]


def search_chunks(
    conn: Any,
    table: str,
    qa_row: dict[str, Any],
    query_embedding: list[float],
    args: argparse.Namespace,
) -> list[dict[str, Any]]:
    vector = vector_literal(query_embedding)
    top_k = args.top_k
    hybrid_top_k = max(args.hybrid_top_k, top_k)
    vector_limit = max(hybrid_top_k * 3, top_k)
    filters = retrieval_filters(qa_row, not args.no_metadata_filter)

    vector_rows = fetch_vector_candidates(conn, table, vector, filters, vector_limit)
    candidate_rows = fetch_metadata_candidates(conn, table, filters)
    if not candidate_rows:
        fallback_filters = fallback_retrieval_filters(qa_row)
        vector_rows = fetch_vector_candidates(conn, table, vector, fallback_filters, vector_limit)
        candidate_rows = fetch_metadata_candidates(conn, table, fallback_filters)

    vector_ranked = rank_vector_rows(vector_rows, hybrid_top_k)
    bm25_ranked = rank_bm25_rows(candidate_rows, qa_row, hybrid_top_k)
    ranked = hybrid_rrf(vector_ranked, bm25_ranked, hybrid_top_k)
    if args.chunk_strategy == "parent-child":
        ranked = collapse_parent_child_rows(ranked, hybrid_top_k)

    if args.rerank:
        try:
            ranked = rerank_with_local_model(ranked, qa_row, args)
        except Exception as exc:
            if args.strict_rerank:
                raise
            print(f"[rerank] fallback to hybrid for {qa_row.get('id')}: {exc}")

    return [format_retrieved_row(row) for row in ranked[:top_k]]


def fetch_vector_candidates(
    conn: Any,
    table: str,
    vector: str,
    filters: list[tuple[str, Any]],
    limit: int,
) -> list[dict[str, Any]]:
    where_sql, params = where_clause(filters)
    sql = f"""
        SELECT chunk_id, source_file, chunk_type, content, metadata,
               1 - (embedding <=> %s::vector) AS score
        FROM {table}
        {where_sql}
        ORDER BY embedding <=> %s::vector
        LIMIT %s
    """
    return fetch_all(conn, sql, (vector, *params, vector, limit))


def fetch_metadata_candidates(conn: Any, table: str, filters: list[tuple[str, Any]]) -> list[dict[str, Any]]:
    where_sql, params = where_clause(filters)
    sql = f"""
        SELECT chunk_id, source_file, chunk_type, content, metadata, 0.0 AS score
        FROM {table}
        {where_sql}
        ORDER BY source_file, chunk_index
    """
    return fetch_all(conn, sql, tuple(params))


def retrieval_filters(qa_row: dict[str, Any], enabled: bool) -> list[tuple[str, Any]]:
    if not enabled:
        return []
    source_file = source_file_for(qa_row)
    filters: list[tuple[str, Any]] = []
    if doc_exists(source_file):
        filters.append(("source_file", source_file))
    if qa_row.get("company"):
        filters.append(("company", str(qa_row.get("company"))))
    if qa_row.get("year"):
        filters.append(("year", str(qa_row.get("year"))))
    if qa_row.get("sector"):
        filters.append(("sector", str(qa_row.get("sector"))))
    return filters


def fallback_retrieval_filters(qa_row: dict[str, Any]) -> list[tuple[str, Any]]:
    filters: list[tuple[str, Any]] = []
    if qa_row.get("company"):
        filters.append(("company", str(qa_row.get("company"))))
    if qa_row.get("year"):
        filters.append(("year", str(qa_row.get("year"))))
    return filters


def where_clause(filters: list[tuple[str, Any]]) -> tuple[str, list[Any]]:
    if not filters:
        return "", []
    conditions = []
    params: list[Any] = []
    for key, value in filters:
        if key in {"source_file", "company", "year"}:
            conditions.append(f"{key} = %s")
            params.append(value)
        elif key == "sector":
            conditions.append("(metadata->>'sector' = %s OR metadata->>'gics_sector' = %s OR metadata->>'gics_industry' = %s)")
            params.extend([value, value, value])
    return "WHERE " + " AND ".join(conditions), params


def rank_vector_rows(rows: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    def key(item: dict[str, Any]) -> tuple[float, float]:
        table_bonus = 0.04 if item["chunk_type"] == "table" else 0.0
        item_bonus = 0.02 if item.get("metadata", {}).get("item") == "Item 8" else 0.0
        return (float(item["score"]) + table_bonus + item_bonus, float(item["score"]))

    ranked = sorted(rows, key=key, reverse=True)[:limit]
    for index, row in enumerate(ranked, start=1):
        row["vector_rank"] = index
        row["vector_score"] = round(float(row.get("score", 0.0)), 6)
    return ranked


def rank_bm25_rows(rows: list[dict[str, Any]], qa_row: dict[str, Any], limit: int) -> list[dict[str, Any]]:
    if not rows:
        return []
    query_terms = query_terms_for_row(qa_row)
    if not query_terms:
        return []

    doc_terms = [tokenize_for_search(searchable_text_for_row(row)) for row in rows]
    doc_freq: dict[str, int] = {}
    for terms in doc_terms:
        for term in set(terms):
            doc_freq[term] = doc_freq.get(term, 0) + 1

    avg_len = sum(len(terms) for terms in doc_terms) / len(doc_terms) if doc_terms else 1.0
    ranked: list[dict[str, Any]] = []
    for row, terms in zip(rows, doc_terms):
        score = bm25_score(query_terms, terms, doc_freq, len(rows), avg_len)
        score += keyword_boost(row, qa_row)
        if score <= 0:
            continue
        item = dict(row)
        item["bm25_score"] = round(score, 6)
        ranked.append(item)

    ranked.sort(key=lambda item: float(item["bm25_score"]), reverse=True)
    for index, row in enumerate(ranked[:limit], start=1):
        row["bm25_rank"] = index
    return ranked[:limit]


def hybrid_rrf(vector_rows: list[dict[str, Any]], bm25_rows: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    by_id: dict[str, dict[str, Any]] = {}
    scores: dict[str, float] = {}
    k = 60.0

    for index, row in enumerate(vector_rows, start=1):
        chunk_id = row["chunk_id"]
        by_id.setdefault(chunk_id, dict(row))
        scores[chunk_id] = scores.get(chunk_id, 0.0) + 1.0 / (k + index)
    for index, row in enumerate(bm25_rows, start=1):
        chunk_id = row["chunk_id"]
        by_id.setdefault(chunk_id, dict(row))
        by_id[chunk_id].update({key: value for key, value in row.items() if key in {"bm25_score", "bm25_rank"}})
        scores[chunk_id] = scores.get(chunk_id, 0.0) + 1.0 / (k + index)

    ranked = []
    for chunk_id, row in by_id.items():
        row["hybrid_score"] = round(scores[chunk_id], 6)
        row["score"] = row["hybrid_score"]
        ranked.append(row)

    ranked.sort(
        key=lambda item: (
            float(item.get("hybrid_score", 0.0)),
            float(item.get("vector_score", item.get("score", 0.0))),
            1 if item.get("chunk_type") == "table" else 0,
        ),
        reverse=True,
    )
    return ranked[:limit]


def collapse_parent_child_rows(rows: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    collapsed: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in rows:
        metadata = row.get("metadata") or {}
        parent_id = str(metadata.get("parent_id") or row.get("chunk_id"))
        if parent_id in seen:
            continue
        seen.add(parent_id)
        collapsed.append(row)
        if len(collapsed) >= limit:
            break
    return collapsed


def rerank_with_local_model(rows: list[dict[str, Any]], qa_row: dict[str, Any], args: argparse.Namespace) -> list[dict[str, Any]]:
    if not rows:
        return rows

    documents = [rerank_document_text(row, args.rerank_doc_chars) for row in rows]
    body: dict[str, Any] = {
        "query": str(qa_row.get("question") or ""),
        "documents": documents,
        "top_n": max(args.rerank_top_k, args.top_k),
        "return_documents": False,
    }
    if args.rerank_model:
        body["model"] = args.rerank_model

    response = http_json(join_url(args.rerank_url, "/v1/rerank"), body, timeout=120)
    results = response.get("results")
    if not isinstance(results, list):
        raise RuntimeError(f"Reranker response missing results: {response}")

    ranked: list[dict[str, Any]] = []
    seen_indexes: set[int] = set()
    for result in results:
        if not isinstance(result, dict):
            continue
        try:
            index = int(result["index"])
            score = float(result["relevance_score"])
        except (KeyError, TypeError, ValueError):
            continue
        if index < 0 or index >= len(rows):
            continue
        item = dict(rows[index])
        item["rerank_score"] = round(score, 6)
        item["rerank_rank"] = len(ranked) + 1
        item["score"] = item["rerank_score"]
        ranked.append(item)
        seen_indexes.add(index)
        if len(ranked) >= max(args.rerank_top_k, args.top_k):
            break

    for index, row in enumerate(rows):
        if index in seen_indexes:
            continue
        item = dict(row)
        item["rerank_score"] = 0.0
        item["score"] = item.get("hybrid_score", item.get("score", 0.0))
        ranked.append(item)
        if len(ranked) >= max(args.rerank_top_k, args.top_k):
            break

    return ranked


def rerank_document_text(row: dict[str, Any], max_chars: int) -> str:
    metadata = row.get("metadata") or {}
    prefix_parts = [
        f"source={row.get('source_file')}",
        f"type={row.get('chunk_type')}",
    ]
    if metadata.get("company"):
        prefix_parts.append(f"company={metadata.get('company')}")
    if metadata.get("year"):
        prefix_parts.append(f"year={metadata.get('year')}")
    if metadata.get("item"):
        prefix_parts.append(f"item={metadata.get('item')}")
    if metadata.get("section_title"):
        prefix_parts.append(f"section={metadata.get('section_title')}")
    if metadata.get("table_header"):
        prefix_parts.append(f"table_header={metadata.get('table_header')}")
    if metadata.get("periods"):
        prefix_parts.append(f"periods={'; '.join(str(value) for value in metadata.get('periods', []))}")
    elif metadata.get("primary_period"):
        prefix_parts.append(f"period={metadata.get('primary_period')}")

    text = " | ".join(str(part) for part in prefix_parts if part)
    text = f"{text}\n{context_content_for_row(row)}"
    if max_chars > 0 and len(text) > max_chars:
        return text[:max_chars].rstrip()
    return text


def context_content_for_row(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    parent_context = metadata.get("parent_context")
    if isinstance(parent_context, str) and parent_context.strip():
        return parent_context
    return str(row.get("content") or "")


def format_retrieved_row(row: dict[str, Any]) -> dict[str, Any]:
    metadata = row.get("metadata") or {}
    return {
        "chunk_id": row["chunk_id"],
        "source_file": row["source_file"],
        "chunk_type": row["chunk_type"],
        "content": context_content_for_row(row),
        "child_content": row["content"],
        "metadata": metadata,
        "score": round(float(row.get("score", 0.0)), 6),
        "vector_score": row.get("vector_score"),
        "bm25_score": row.get("bm25_score"),
        "hybrid_score": row.get("hybrid_score"),
        "rerank_score": row.get("rerank_score"),
        "item": metadata.get("item"),
        "section_title": metadata.get("section_title"),
        "periods": metadata.get("periods"),
        "primary_period": metadata.get("primary_period"),
        "parent_id": metadata.get("parent_id"),
        "child_chunk_id": metadata.get("child_chunk_id"),
    }


def retrieved_context_block(index: int, item: dict[str, Any]) -> str:
    metadata = item.get("metadata") or {}
    parts = [
        f"[{index}]",
        f"source={item['source_file']}",
        f"type={item['chunk_type']}",
        f"score={item['score']}",
    ]
    if item.get("parent_id"):
        parts.append(f"parent_id={item.get('parent_id')}")
    if item.get("child_chunk_id"):
        parts.append(f"child_chunk_id={item.get('child_chunk_id')}")
    if metadata.get("item"):
        parts.append(f"item={metadata.get('item')}")
    if metadata.get("section_title"):
        parts.append(f"section={metadata.get('section_title')}")
    if metadata.get("primary_period"):
        parts.append(f"primary_period={metadata.get('primary_period')}")
    if metadata.get("periods"):
        parts.append(f"periods={'; '.join(str(value) for value in metadata.get('periods', []))}")
    return f"{' '.join(parts)}\n{item['content']}"


def query_terms_for_row(qa_row: dict[str, Any]) -> list[str]:
    parts = [
        str(qa_row.get("question") or ""),
        str(qa_row.get("evidence_section") or ""),
        str(qa_row.get("company") or ""),
        str(qa_row.get("year") or ""),
    ]
    for value in qa_row.get("years_required") or []:
        parts.append(str(value))
    terms = tokenize_for_search(" ".join(parts))
    return [term for term in terms if term not in STOPWORDS]


def searchable_text_for_row(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    values = [
        row.get("content") or "",
        metadata.get("table_header") or "",
        metadata.get("section_title") or "",
        metadata.get("evidence_section") or "",
        metadata.get("company") or "",
        metadata.get("year") or "",
        metadata.get("sector") or "",
        metadata.get("primary_period") or "",
    ]
    periods = metadata.get("periods")
    if isinstance(periods, list):
        values.extend(str(item) for item in periods)
    period_years = metadata.get("period_years")
    if isinstance(period_years, list):
        values.extend(str(item) for item in period_years)
    keywords = metadata.get("search_keywords")
    if isinstance(keywords, list):
        values.extend(str(item) for item in keywords)
    return " ".join(str(value) for value in values if value)


def tokenize_for_search(value: str) -> list[str]:
    tokens = re.findall(r"[A-Za-z][A-Za-z0-9&.-]*|\d+(?:,\d{3})*(?:\.\d+)?%?", value.lower())
    normalized = []
    for token in tokens:
        token = token.strip(".,;:()[]{}")
        if not token or token in STOPWORDS:
            continue
        normalized.append(token.replace(",", ""))
    return normalized


def tokenize_for_context(value: str) -> list[str]:
    return re.findall(r"[A-Za-z0-9]+(?:[-'][A-Za-z0-9]+)?|[^\w\s]", value)


def bm25_score(
    query_terms: list[str],
    doc_terms: list[str],
    doc_freq: dict[str, int],
    doc_count: int,
    avg_len: float,
) -> float:
    if not doc_terms:
        return 0.0
    term_counts: dict[str, int] = {}
    for term in doc_terms:
        term_counts[term] = term_counts.get(term, 0) + 1

    k1 = 1.4
    b = 0.72
    doc_len = len(doc_terms)
    score = 0.0
    for term in set(query_terms):
        tf = term_counts.get(term, 0)
        if tf == 0:
            continue
        df = doc_freq.get(term, 0)
        idf = math.log(1.0 + max(0.0, (doc_count - df + 0.5) / (df + 0.5)))
        denom = tf + k1 * (1.0 - b + b * doc_len / max(avg_len, 1.0))
        score += idf * (tf * (k1 + 1.0)) / denom
    return score


def keyword_boost(row: dict[str, Any], qa_row: dict[str, Any]) -> float:
    metadata = row.get("metadata") or {}
    question = str(qa_row.get("question") or "").lower()
    content = str(row.get("content") or "").lower()
    header = str(metadata.get("table_header") or "").lower()
    boost = 0.0

    if row.get("chunk_type") == "table":
        boost += 0.4
    if metadata.get("item") and str(metadata.get("item")).lower() in str(qa_row.get("evidence_section") or "").lower():
        boost += 0.25

    numeric_terms = re.findall(r"\d+(?:,\d{3})*(?:\.\d+)?%?", question)
    boost += 0.08 * sum(1 for term in numeric_terms if term.replace(",", "") in content.replace(",", ""))

    question_terms = set(query_terms_for_row(qa_row))
    header_hits = sum(1 for term in question_terms if term in header)
    boost += min(0.6, header_hits * 0.12)

    for phrase in ["current assets", "current liabilities", "net income", "income taxes", "total assets", "revenue"]:
        if phrase in question and phrase in content:
            boost += 0.18
    return boost


def answer_with_llm(config: ProjectConfig, qa_row: dict[str, Any], retrieved: list[dict[str, Any]]) -> str:
    if not config.llm_api_key:
        raise RuntimeError("LLM api-key is empty. Set the environment variable referenced by application.yaml, for example MIMO_KEY.")

    context = "\n\n".join(
        retrieved_context_block(index, item)
        for index, item in enumerate(retrieved, start=1)
    )
    messages = [
        {
            "role": "system",
            "content": (
                "You are a financial document QA assistant. Answer only from the supplied context. "
                "Return a very short answer. "
                "For direct lookup questions, output only the value or short phrase. "
                "For calculation questions, output the final value plus at most three compact formula line. "
                "When multiple periods are present, use the period requested by the question; if unspecified, use the most relevant period in the context. "
                "Do not include markdown headings, bullet points, background, caveats, or explanatory paragraphs. "
                "If the context is insufficient, say: Cannot be determined from the provided context."
            ),
        },
        {
            "role": "user",
            "content": (
                f"Company: {qa_row.get('company')}\n"
                f"Year: {qa_row.get('year')}\n"
                f"Question: {qa_row.get('question')}\n\n"
                f"Context:\n{context}\n\n"
                "Answer in 1 sentence. If calculation is needed, use 2 short lines at most:"
            ),
        },
    ]
    body = {
        "model": config.llm_model,
        "temperature": config.llm_temperature,
        "reasoning_effort": "high",
        "messages": messages,
    }
    response = http_json(
        join_url(config.llm_base_url, config.llm_completions_path),
        body,
        headers={"Authorization": f"Bearer {config.llm_api_key}"},
        timeout=120,
    )
    return response["choices"][0]["message"]["content"].strip()


def judge_with_llm(
    config: ProjectConfig,
    qa_row: dict[str, Any],
    retrieved: list[dict[str, Any]],
    prediction: str,
) -> dict[str, Any]:
    if not config.llm_api_key:
        raise RuntimeError("LLM api-key is empty. Set the environment variable referenced by application.yaml, for example MIMO_KEY.")

    context = "\n\n".join(
        retrieved_context_block(index, item)
        for index, item in enumerate(retrieved, start=1)
    )
    messages = [
        {
            "role": "system",
            "content": (
                "You are a strict RAG evaluator for financial QA. "
                "Return only valid JSON. Do not include markdown fences. "
                "Use semantic equivalence for financial values, percentages, units, and short calculations."
            ),
        },
        {
            "role": "user",
            "content": (
                "Evaluate this RAG result.\n\n"
                f"Question: {qa_row.get('question')}\n"
                f"Gold answer: {qa_row.get('answer')}\n"
                f"Model answer: {prediction or '[NO ANSWER]'}\n"
                f"Requires calculation: {qa_row.get('requires_calculation')}\n\n"
                f"Retrieved context:\n{context}\n\n"
                "Return JSON with these keys:\n"
                "- answer_correct: boolean, whether Model answer matches Gold answer semantically.\n"
                "- context_has_answer: boolean, whether Retrieved context contains enough evidence to answer the question. "
                "This is the context recall proxy because gold chunk labels are unavailable.\n"
                "- answer_grounded: boolean, whether Model answer is supported by Retrieved context.\n"
                "- calculation_correct: boolean or null, null when no calculation is needed.\n"
                "- score: number from 0 to 1, where 1 is fully correct and grounded.\n"
                "- reason: short explanation under 40 words."
            ),
        },
    ]
    body = {
        "model": config.judge_model,
        "temperature": 0,
        "messages": messages,
    }
    response = http_json(
        join_url(config.llm_base_url, config.llm_completions_path),
        body,
        headers={"Authorization": f"Bearer {config.llm_api_key}"},
        timeout=120,
    )
    content = response["choices"][0]["message"]["content"].strip()
    parsed = parse_json_object(content)
    return normalize_judge_result(parsed)


def default_judge_result() -> dict[str, Any]:
    return {
        "answer_correct": False,
        "context_has_answer": False,
        "answer_grounded": False,
        "calculation_correct": None,
        "score": 0.0,
        "reason": "",
    }


def normalize_judge_result(value: dict[str, Any]) -> dict[str, Any]:
    result = default_judge_result()
    result["answer_correct"] = bool(value.get("answer_correct", False))
    result["context_has_answer"] = bool(value.get("context_has_answer", False))
    result["answer_grounded"] = bool(value.get("answer_grounded", False))
    calculation_correct = value.get("calculation_correct")
    result["calculation_correct"] = None if calculation_correct is None else bool(calculation_correct)
    try:
        result["score"] = max(0.0, min(1.0, float(value.get("score", 0.0))))
    except (TypeError, ValueError):
        result["score"] = 0.0
    result["reason"] = str(value.get("reason", ""))[:500]
    return result


def parse_json_object(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{[\s\S]*}", value)
    if not match:
        raise ValueError(f"LLM judge did not return a JSON object: {value[:300]}")
    parsed = json.loads(match.group(0))
    if not isinstance(parsed, dict):
        raise ValueError(f"LLM judge JSON is not an object: {value[:300]}")
    return parsed


def summarize_results(rows: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(rows)
    successful = [row for row in rows if not row.get("error")]
    judged = [row for row in successful if row.get("llm_judged")]
    top_scores = [row["top_scores"][0] for row in rows if row.get("top_scores")]

    return {
        "total_questions": total,
        "successful_questions": len(successful),
        "failed_questions": total - len(successful),
        "llm_judged_questions": len(judged),
        "llm_accuracy": rate(judged, "llm_answer_correct"),
        "context_recall": rate(judged, "llm_context_recall"),
        "grounded_rate": rate(judged, "llm_answer_grounded"),
        "calculation_accuracy": nullable_rate([
            row for row in judged
            if row.get("llm_calculation_correct") is not None
        ], "llm_calculation_correct"),
        "average_llm_score": average([row.get("llm_score", 0.0) for row in judged]),
        "exact_match_rate": rate(successful, "exact_match"),
        "contains_expected_rate": rate(successful, "contains_expected"),
        "retrieval_coverage": len([row for row in rows if row.get("retrieved_chunk_ids")]) / total if total else 0.0,
        "retrieval_source_hit_rate": rate(successful, "retrieval_source_hit"),
        "average_top_score": average(top_scores),
        "average_latency_ms": average([row.get("latency_ms", 0) for row in rows]),
    }


def print_eval_errors(result: dict[str, Any]) -> None:
    question_id = result.get("question_id") or "unknown"
    company = result.get("company") or ""
    year = result.get("year") or ""
    label = f"{question_id} {company}_{year}".strip()
    if result.get("error"):
        print(f"[error] {label}: {compact_error(result.get('error'))}", file=sys.stderr)
    if result.get("judge_error"):
        print(f"[judge-error] {label}: {compact_error(result.get('judge_error'))}", file=sys.stderr)


def print_error_summary(rows: list[dict[str, Any]]) -> None:
    errored = [row for row in rows if row.get("error")]
    judge_errored = [row for row in rows if row.get("judge_error")]
    if not errored and not judge_errored:
        return
    print("[error-summary]", file=sys.stderr)
    print(f"  main errors: {len(errored)}", file=sys.stderr)
    for row in errored:
        print(
            f"  - {row.get('question_id')} {row.get('company')}_{row.get('year')}: "
            f"{compact_error(row.get('error'))}",
            file=sys.stderr,
        )
    print(f"  judge errors: {len(judge_errored)}", file=sys.stderr)
    for row in judge_errored:
        print(
            f"  - {row.get('question_id')} {row.get('company')}_{row.get('year')}: "
            f"{compact_error(row.get('judge_error'))}",
            file=sys.stderr,
        )


def compact_error(value: Any, max_chars: int = 800) -> str:
    text = normalize_multiline_text(str(value or ""))
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip() + "..."


def rate(rows: list[dict[str, Any]], key: str) -> float:
    if not rows:
        return 0.0
    return round(sum(1 for row in rows if row.get(key)) / len(rows), 4)


def nullable_rate(rows: list[dict[str, Any]], key: str) -> Optional[float]:
    if not rows:
        return None
    return rate(rows, key)


def average(values: list[Any]) -> float:
    numeric_values = [float(value) for value in values if value is not None]
    if not numeric_values:
        return 0.0
    return round(sum(numeric_values) / len(numeric_values), 4)


def format_count_map(values: dict[str, int]) -> str:
    if not values:
        return "none"
    return ", ".join(f"{key}={values[key]}" for key in sorted(values))


def embed_text(config: ProjectConfig, text: str, max_tokens: int = DEFAULT_EMBEDDING_MAX_TOKENS) -> list[float]:
    if max_tokens <= 0:
        response = http_json(
            join_url(config.embedding_base_url, "/api/embeddings"),
            {"model": config.embedding_model, "prompt": normalize_text(text)},
            timeout=120,
        )
        embedding = response.get("embedding")
        if not isinstance(embedding, list):
            raise RuntimeError(f"Ollama embedding response missing embedding: {response}")
        if len(embedding) != config.embedding_dimensions:
            raise RuntimeError(f"Embedding dimension mismatch: expected {config.embedding_dimensions}, got {len(embedding)}")
        return [float(value) for value in embedding]

    actual_max_tokens = max_tokens
    while actual_max_tokens >= 64:
        prompt = trim_for_embedding(text, actual_max_tokens)
        try:
            response = http_json(
                join_url(config.embedding_base_url, "/api/embeddings"),
                {"model": config.embedding_model, "prompt": prompt},
                timeout=120,
            )
            break
        except RuntimeError as exc:
            if "input length exceeds the context length" not in str(exc):
                raise
            actual_max_tokens = int(actual_max_tokens * 0.75)
    else:
        raise RuntimeError("Embedding input still exceeds context length after repeated truncation.")

    embedding = response.get("embedding")
    if not isinstance(embedding, list):
        raise RuntimeError(f"Ollama embedding response missing embedding: {response}")
    if len(embedding) != config.embedding_dimensions:
        raise RuntimeError(f"Embedding dimension mismatch: expected {config.embedding_dimensions}, got {len(embedding)}")
    return [float(value) for value in embedding]


def trim_for_embedding(text: str, max_tokens: int) -> str:
    text = normalize_text(text)
    if max_tokens <= 0:
        return text

    # Nomic's tokenizer is stricter than this lightweight estimator, especially
    # for markdown tables and filings punctuation. Keep a character budget too.
    char_budget = max_tokens * 3
    if len(text) > char_budget:
        text = text[:char_budget].rstrip()

    if estimate_embedding_tokens(text) <= max_tokens:
        return text

    low = 0
    high = len(text)
    best = ""
    while low <= high:
        mid = (low + high) // 2
        candidate = text[:mid].rstrip()
        if estimate_embedding_tokens(candidate) <= max_tokens:
            best = candidate
            low = mid + 1
        else:
            high = mid - 1

    boundary = max(best.rfind("\n"), best.rfind(". "), best.rfind("; "), best.rfind(" "))
    if boundary > max(0, len(best) - 220):
        best = best[:boundary + 1].rstrip()
    return best


def estimate_embedding_tokens(text: str) -> int:
    # Conservative approximation for English financial filings and markdown tables.
    return len(re.findall(r"[A-Za-z0-9]+(?:[-'][A-Za-z0-9]+)?|[^\w\s]", text))


def http_json(url: str, body: dict[str, Any], headers: Optional[dict[str, str]] = None, timeout: int = 60) -> dict[str, Any]:
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=request_headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"HTTP {exc.code} calling {url}: {error_body}") from exc


def ensure_schema(conn: Any, tables: dict[str, str], dimensions: int, rebuild: bool) -> None:
    if rebuild:
        execute(conn, f"DROP TABLE IF EXISTS {tables['results']}")
        execute(conn, f"DROP TABLE IF EXISTS {tables['chunks']}")
    execute(conn, "CREATE EXTENSION IF NOT EXISTS vector")
    execute(conn, f"""
        CREATE TABLE IF NOT EXISTS {tables['chunks']} (
            chunk_id TEXT PRIMARY KEY,
            subset TEXT NOT NULL,
            split TEXT NOT NULL,
            company TEXT NOT NULL,
            year TEXT NOT NULL,
            source_file TEXT NOT NULL,
            chunk_type TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            content TEXT NOT NULL,
            metadata JSONB NOT NULL,
            embedding vector({dimensions}) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    """)
    execute(conn, f"""
        CREATE TABLE IF NOT EXISTS {tables['results']} (
            question_id TEXT PRIMARY KEY,
            company TEXT,
            year TEXT,
            question TEXT NOT NULL,
            expected_answer TEXT,
            predicted_answer TEXT,
            exact_match BOOLEAN NOT NULL DEFAULT FALSE,
            contains_expected BOOLEAN NOT NULL DEFAULT FALSE,
            retrieved_chunk_ids JSONB NOT NULL,
            retrieved_sources JSONB NOT NULL,
            top_scores JSONB NOT NULL,
            retrieved_context_chars INTEGER NOT NULL DEFAULT 0,
            retrieval_source_hit BOOLEAN NOT NULL DEFAULT FALSE,
            llm_answer_correct BOOLEAN NOT NULL DEFAULT FALSE,
            llm_context_recall BOOLEAN NOT NULL DEFAULT FALSE,
            llm_answer_grounded BOOLEAN NOT NULL DEFAULT FALSE,
            llm_calculation_correct BOOLEAN,
            llm_score DOUBLE PRECISION NOT NULL DEFAULT 0,
            llm_judge_reason TEXT,
            llm_judged BOOLEAN NOT NULL DEFAULT FALSE,
            latency_ms INTEGER NOT NULL,
            error TEXT,
            judge_error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    """)
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS retrieved_context_chars INTEGER NOT NULL DEFAULT 0")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS retrieval_source_hit BOOLEAN NOT NULL DEFAULT FALSE")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_answer_correct BOOLEAN NOT NULL DEFAULT FALSE")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_context_recall BOOLEAN NOT NULL DEFAULT FALSE")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_answer_grounded BOOLEAN NOT NULL DEFAULT FALSE")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_calculation_correct BOOLEAN")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_score DOUBLE PRECISION NOT NULL DEFAULT 0")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_judge_reason TEXT")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS llm_judged BOOLEAN NOT NULL DEFAULT FALSE")
    execute(conn, f"ALTER TABLE {tables['results']} ADD COLUMN IF NOT EXISTS judge_error TEXT")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_source ON {tables['chunks']} (source_file)")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_company_year ON {tables['chunks']} (company, year)")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_type ON {tables['chunks']} (chunk_type)")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_metadata_gin ON {tables['chunks']} USING gin (metadata)")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_sector ON {tables['chunks']} ((metadata->>'sector'))")
    execute(conn, f"CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_item ON {tables['chunks']} ((metadata->>'item'))")
    execute(conn, f"""
        CREATE INDEX IF NOT EXISTS idx_{tables['chunks']}_embedding
        ON {tables['chunks']} USING hnsw (embedding vector_cosine_ops)
    """)
    conn.commit()


def chunk_exists(conn: Any, table: str, chunk_id: str) -> bool:
    rows = fetch_all(conn, f"SELECT 1 AS exists FROM {table} WHERE chunk_id = %s LIMIT 1", (chunk_id,))
    return bool(rows)


def insert_chunk(conn: Any, table: str, chunk: Chunk, embedding: list[float]) -> None:
    execute(conn, f"""
        INSERT INTO {table}
            (chunk_id, subset, split, company, year, source_file, chunk_type, chunk_index, content, metadata, embedding)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::vector)
        ON CONFLICT (chunk_id) DO NOTHING
    """, (
        chunk.chunk_id,
        chunk.subset,
        chunk.split,
        chunk.company,
        chunk.year,
        chunk.source_file,
        chunk.chunk_type,
        chunk.chunk_index,
        chunk.content,
        json.dumps(chunk.metadata, ensure_ascii=False),
        vector_literal(embedding),
    ))


def insert_eval_result(conn: Any, table: str, result: dict[str, Any]) -> None:
    execute(conn, f"""
        INSERT INTO {table}
            (question_id, company, year, question, expected_answer, predicted_answer,
             exact_match, contains_expected, retrieved_chunk_ids, retrieved_sources,
             top_scores, retrieved_context_chars, retrieval_source_hit,
             llm_answer_correct, llm_context_recall, llm_answer_grounded,
             llm_calculation_correct, llm_score, llm_judge_reason, llm_judged,
             latency_ms, error, judge_error)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb,
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (question_id) DO UPDATE SET
            predicted_answer = EXCLUDED.predicted_answer,
            exact_match = EXCLUDED.exact_match,
            contains_expected = EXCLUDED.contains_expected,
            retrieved_chunk_ids = EXCLUDED.retrieved_chunk_ids,
            retrieved_sources = EXCLUDED.retrieved_sources,
            top_scores = EXCLUDED.top_scores,
            retrieved_context_chars = EXCLUDED.retrieved_context_chars,
            retrieval_source_hit = EXCLUDED.retrieval_source_hit,
            llm_answer_correct = EXCLUDED.llm_answer_correct,
            llm_context_recall = EXCLUDED.llm_context_recall,
            llm_answer_grounded = EXCLUDED.llm_answer_grounded,
            llm_calculation_correct = EXCLUDED.llm_calculation_correct,
            llm_score = EXCLUDED.llm_score,
            llm_judge_reason = EXCLUDED.llm_judge_reason,
            llm_judged = EXCLUDED.llm_judged,
            latency_ms = EXCLUDED.latency_ms,
            error = EXCLUDED.error,
            judge_error = EXCLUDED.judge_error,
            created_at = now()
    """, (
        result["question_id"],
        result["company"],
        result["year"],
        result["question"],
        result["expected_answer"],
        result["predicted_answer"],
        result["exact_match"],
        result["contains_expected"],
        json.dumps(result["retrieved_chunk_ids"], ensure_ascii=False),
        json.dumps(result["retrieved_sources"], ensure_ascii=False),
        json.dumps(result["top_scores"], ensure_ascii=False),
        result["retrieved_context_chars"],
        result["retrieval_source_hit"],
        result["llm_answer_correct"],
        result["llm_context_recall"],
        result["llm_answer_grounded"],
        result["llm_calculation_correct"],
        result["llm_score"],
        result["llm_judge_reason"],
        result["llm_judged"],
        result["latency_ms"],
        result["error"],
        result["judge_error"],
    ))


def connect_postgres(config: ProjectConfig) -> Any:
    parsed = parse_jdbc_url(config.jdbc_url)
    try:
        import psycopg

        return psycopg.connect(
            host=parsed["host"],
            port=parsed["port"],
            dbname=parsed["database"],
            user=config.db_username,
            password=config.db_password,
            row_factory=psycopg.rows.dict_row,
        )
    except ImportError:
        try:
            import psycopg2
            import psycopg2.extras

            return psycopg2.connect(
                host=parsed["host"],
                port=parsed["port"],
                dbname=parsed["database"],
                user=config.db_username,
                password=config.db_password,
                cursor_factory=psycopg2.extras.RealDictCursor,
            )
        except ImportError as exc:
            raise RuntimeError("Install psycopg or psycopg2 to write vectors into PostgreSQL/pgvector.") from exc


def execute(conn: Any, sql: str, params: Optional[tuple[Any, ...]] = None) -> None:
    with conn.cursor() as cur:
        cur.execute(sql, params or ())


def fetch_all(conn: Any, sql: str, params: Optional[tuple[Any, ...]] = None) -> list[dict[str, Any]]:
    with conn.cursor() as cur:
        cur.execute(sql, params or ())
        rows = cur.fetchall()
    return [dict(row) for row in rows]


def parse_jdbc_url(jdbc_url: str) -> dict[str, Any]:
    match = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", jdbc_url)
    if not match:
        raise ValueError(f"Unsupported JDBC URL: {jdbc_url}")
    return {
        "host": match.group(1),
        "port": int(match.group(2) or 5432),
        "database": match.group(3),
    }


def load_project_config(path: Path) -> ProjectConfig:
    text = path.read_text(encoding="utf-8")
    return ProjectConfig(
        jdbc_url=read_yaml_scalar(text, "spring.datasource.url"),
        db_username=read_yaml_scalar(text, "spring.datasource.username"),
        db_password=read_yaml_scalar(text, "spring.datasource.password"),
        embedding_base_url=read_yaml_scalar(text, "app.embedding.ollama.base-url"),
        embedding_model=read_yaml_scalar(text, "app.embedding.ollama.model"),
        embedding_dimensions=int(read_yaml_scalar(text, "app.embedding.ollama.dimensions")),
        llm_base_url=read_yaml_scalar(text, "spring.ai.openai.chat.base-url"),
        llm_api_key=resolve_env_value(read_yaml_scalar(text, "spring.ai.openai.chat.api-key")),
        llm_completions_path=read_yaml_scalar(text, "spring.ai.openai.chat.completions-path"),
        llm_model=read_yaml_scalar(text, "spring.ai.openai.chat.options.model"),
        llm_temperature=float(read_yaml_scalar(text, "spring.ai.openai.chat.options.temperature")),
        judge_model=read_yaml_scalar(text, "spring.ai.openai.chat.options.model"),
    )


def read_yaml_scalar(text: str, dotted_path: str) -> str:
    try:
        import yaml

        value: Any = yaml.safe_load(text)
        for key in dotted_path.split("."):
            value = value[key]
        return str(value)
    except Exception:
        return read_yaml_scalar_fallback(text, dotted_path)


def read_yaml_scalar_fallback(text: str, dotted_path: str) -> str:
    keys = dotted_path.split(".")
    stack: list[tuple[int, str]] = []
    for raw_line in text.splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        line = raw_line.strip()
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip().strip('"')
        value = value.strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        current_path = ".".join(item[1] for item in stack)
        if current_path == dotted_path:
            return value.strip().strip('"').strip("'")
    raise KeyError(f"Cannot read YAML path: {dotted_path}")


def resolve_env_value(value: str) -> str:
    match = re.fullmatch(r"\$\{([^}:]+)(?::([^}]*))?}", value)
    if not match:
        return value
    return os.environ.get(match.group(1), match.group(2) or "")


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        if key in os.environ:
            continue
        os.environ[key] = parse_dotenv_value(value)


def parse_dotenv_value(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        quote = value[0]
        value = value[1:-1]
        if quote == '"':
            return bytes(value, "utf-8").decode("unicode_escape")
        return value
    return strip_dotenv_comment(value).strip()


def strip_dotenv_comment(value: str) -> str:
    in_single_quote = False
    in_double_quote = False
    escaped = False
    for index, char in enumerate(value):
        if escaped:
            escaped = False
            continue
        if char == "\\" and in_double_quote:
            escaped = True
            continue
        if char == "'" and not in_double_quote:
            in_single_quote = not in_single_quote
        elif char == '"' and not in_single_quote:
            in_double_quote = not in_double_quote
        elif char == "#" and not in_single_quote and not in_double_quote:
            if index == 0 or value[index - 1].isspace():
                return value[:index]
    return value


def source_file_for(row: dict[str, Any]) -> str:
    return f"{row.get('company')}_{row.get('year')}.html"


def normalize_source_file(value: str) -> str:
    normalized = str(value).replace("\\", "/").strip()
    if not normalized:
        return normalized
    return Path(normalized).name


def doc_exists(source_file: str) -> bool:
    source_file = normalize_source_file(source_file)
    return (ORIGINAL_DOC_DIR / source_file).exists()


def parse_company_year(source_file: str) -> tuple[str, str]:
    stem = Path(source_file).stem
    if "_" not in stem:
        return stem, ""
    company, year = stem.rsplit("_", 1)
    return company, year


def load_required_docs(path: Path, questions: list[dict[str, Any]]) -> list[str]:
    if path.exists():
        return [normalize_source_file(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return sorted({source_file_for(row) for row in questions if doc_exists(source_file_for(row))})


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def normalize_text(value: str) -> str:
    value = html.unescape(value)
    value = re.sub(r"\s+", " ", value)
    value = re.sub(r"\s+([,.;:%)])", r"\1", value)
    value = re.sub(r"([(])\s+", r"\1", value)
    return value.strip()


def normalize_multiline_text(value: str) -> str:
    value = html.unescape(value)
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    value = re.sub(r"[ \t\f\v]+", " ", value)
    value = re.sub(r" *\n+ *", "\n", value)
    value = re.sub(r"\n{3,}", "\n\n", value)
    value = re.sub(r"\s+([,.;:%)])", r"\1", value)
    value = re.sub(r"([(])\s+", r"\1", value)
    return value.strip()


def clean_table_cell(value: str) -> str:
    return normalize_text(value).replace("|", "/")


def normalize_answer(value: str) -> str:
    return re.sub(r"[^a-z0-9.%$-]+", "", value.lower())


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in values) + "]"


def join_url(base_url: str, path: str) -> str:
    if not path.startswith("/"):
        path = "/" + path
    return base_url.rstrip("/") + path


if __name__ == "__main__":
    raise SystemExit(main())
