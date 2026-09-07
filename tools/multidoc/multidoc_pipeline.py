#!/usr/bin/env python3
"""Prepare and index the complete Multi-Doc-2025 dataset.

The dataset itself is written to ``datasets/multi-doc-2025`` and remains outside
Git.  Parsing/chunking reuses the proven S2 implementation, while this entrypoint
adds S1-S5 document discovery, metadata aggregation, and the unified
``multidoc_full_chunks`` pgvector table.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from types import ModuleType
from typing import Any, Iterable
from urllib.parse import quote


REPO_ID = "Anonymous-Team-HC-RAG/Multi-Doc-2025"
HF_BASE = f"https://huggingface.co/datasets/{REPO_ID}"
ALL_SPLITS = ("train", "val", "test")
ALL_SUBSETS = ("S1", "S2", "S3", "S4", "S5")
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = PROJECT_ROOT / "datasets" / "multi-doc-2025"
S2_SCRIPT = PROJECT_ROOT / "src" / "main" / "resources" / "multi-doc-2025" / "s2" / "evaluate_s2_rag.py"


@dataclass(frozen=True)
class RemoteDocument:
    path: str
    size: int

    @property
    def name(self) -> str:
        return Path(self.path).name


def parse_csv(value: str, allowed: tuple[str, ...], label: str) -> list[str]:
    values = [part.strip() for part in value.split(",") if part.strip()]
    normalized = [part.lower() if label == "split" else part.upper() for part in values]
    invalid = sorted(set(normalized) - set(allowed))
    if invalid:
        raise ValueError(f"Unknown {label}(s): {', '.join(invalid)}")
    return normalized


def fetch_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={"User-Agent": "Kecore-AI-MultiDoc/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"HTTP {exc.code} while reading {url}: {body[:300]}") from exc


def download_file(url: str, target: Path, expected_size: int = 0) -> str:
    if target.exists() and (not expected_size or target.stat().st_size == expected_size):
        return "cached"
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, 5):
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"User-Agent": "Kecore-AI-MultiDoc/1.0", "Accept-Encoding": "identity"}
        if expected_size and 0 < offset < expected_size:
            headers["Range"] = f"bytes={offset}-"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                append = offset > 0 and getattr(response, "status", 200) == 206
                with partial.open("ab" if append else "wb") as output:
                    while block := response.read(1024 * 1024):
                        output.write(block)
        except Exception as exc:
            last_error = exc
        actual = partial.stat().st_size if partial.exists() else 0
        if (not expected_size and partial.exists()) or (expected_size and actual == expected_size):
            break
        if actual > expected_size:
            partial.unlink(missing_ok=True)
        last_error = RuntimeError(f"Size mismatch for {target.name}: expected {expected_size}, got {actual}")
        if attempt < 4:
            print(f"[retry] {target.name} attempt={attempt + 1}, downloaded={actual}/{expected_size}")
            time.sleep(attempt)
    else:
        raise RuntimeError(str(last_error or f"Unable to download {target.name}"))
    partial.replace(target)
    return "downloaded"


def resolve_url(repo_path: str) -> str:
    return f"{HF_BASE}/resolve/main/{quote(repo_path, safe='/')}?download=true"


def fetch_document_manifest() -> list[RemoteDocument]:
    url = f"https://huggingface.co/api/datasets/{REPO_ID}/tree/main/original_doc?recursive=false&expand=false&limit=1000"
    payload = fetch_json(url)
    documents = [
        RemoteDocument(path=str(item["path"]), size=int(item.get("size") or 0))
        for item in payload
        if item.get("type") == "file" and str(item.get("path", "")).lower().endswith(".html")
    ]
    if not documents:
        raise RuntimeError("The official repository returned no original HTML documents.")
    return sorted(documents, key=lambda item: item.name)


def load_rows(data_dir: Path, splits: Iterable[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for split in splits:
        path = data_dir / f"{split}.json"
        if not path.exists():
            raise FileNotFoundError(f"Missing {path}. Run the prepare command first.")
        payload = json.loads(path.read_text(encoding="utf-8"))
        for row in payload:
            copied = dict(row)
            copied["_split"] = split
            rows.append(copied)
    return rows


def filter_rows(rows: Iterable[dict[str, Any]], subsets: set[str]) -> list[dict[str, Any]]:
    return [row for row in rows if str(row.get("subset", "")).upper() in subsets]


def candidate_document_names(row: dict[str, Any]) -> set[str]:
    companies = [str(value).strip() for value in row.get("companies") or [] if str(value).strip()]
    if not companies:
        companies = [part for part in str(row.get("company") or "").split("+") if part]
    years = [str(value).strip() for value in row.get("years_required") or [] if str(value).strip()]
    if not years and row.get("year"):
        years = [str(row["year"])]
    names = {f"{company}_{year}.html" for company in companies for year in years}
    primary_company = str(row.get("company") or "")
    primary_year = str(row.get("year") or "")
    if "+" not in primary_company and primary_company and primary_year:
        names.add(f"{primary_company}_{primary_year}.html")
    return names


def required_documents(rows: Iterable[dict[str, Any]], manifest: Iterable[RemoteDocument]) -> list[RemoteDocument]:
    by_name = {document.name: document for document in manifest}
    requested: set[str] = set()
    for row in rows:
        requested.update(candidate_document_names(row))
    return [by_name[name] for name in sorted(requested) if name in by_name]


def selection_key(splits: Iterable[str], subsets: Iterable[str]) -> str:
    return f"{'-'.join(splits)}__{'-'.join(subsets)}".lower()


def write_selection(data_dir: Path, splits: list[str], subsets: list[str], rows: list[dict[str, Any]], docs: list[RemoteDocument]) -> Path:
    output_dir = data_dir / "selections" / selection_key(splits, subsets)
    output_dir.mkdir(parents=True, exist_ok=True)
    clean_rows = [{key: value for key, value in row.items() if key != "_split"} | {"split": row["_split"]} for row in rows]
    (output_dir / "questions.json").write_text(json.dumps(clean_rows, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "required-docs.txt").write_text("".join(f"{doc.name}\n" for doc in docs), encoding="utf-8")
    return output_dir


def command_prepare(args: argparse.Namespace) -> int:
    data_dir = args.data_dir.resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    for split in ALL_SPLITS:
        status = download_file(resolve_url(f"{split}.json"), data_dir / f"{split}.json")
        print(f"[{status}] {split}.json")

    manifest = fetch_document_manifest()
    manifest_payload = {
        "repository": REPO_ID,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "document_count": len(manifest),
        "total_bytes": sum(item.size for item in manifest),
        "documents": [{"path": item.path, "name": item.name, "size": item.size} for item in manifest],
    }
    (data_dir / "document-manifest.json").write_text(
        json.dumps(manifest_payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    rows = filter_rows(load_rows(data_dir, args.splits), set(args.subsets))
    docs = manifest if args.all_docs else required_documents(rows, manifest)
    output_dir = write_selection(data_dir, args.splits, args.subsets, rows, docs)
    print(f"[selection] questions={len(rows)}, documents={len(docs)}, path={output_dir}")
    print(f"[manifest] official_documents={len(manifest)}, total={sum(item.size for item in manifest) / 1024**3:.2f} GiB")

    if args.download_docs:
        selected = docs[: args.limit_docs] if args.limit_docs else docs
        def fetch(document: RemoteDocument) -> tuple[RemoteDocument, str]:
            target = data_dir / document.path
            return document, download_file(resolve_url(document.path), target, document.size)

        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.download_workers)) as executor:
            futures = [executor.submit(fetch, document) for document in selected]
            for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
                document, status = future.result()
                print(f"[{status}] {index}/{len(selected)} {document.name}", flush=True)
    else:
        print("[next] Add --download-docs to fetch the selected 10-K HTML files.")
    return 0


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while block := handle.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def command_verify(args: argparse.Namespace) -> int:
    manifest_path = args.data_dir / "document-manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError("Manifest not found. Run the prepare command first.")
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    checked = missing = invalid = 0
    for item in payload["documents"]:
        path = args.data_dir / item["path"]
        if not path.exists():
            missing += 1
            continue
        checked += 1
        expected_size = int(item.get("size") or 0)
        if expected_size and path.stat().st_size != expected_size:
            invalid += 1
            print(f"[invalid] {item['name']}: size={path.stat().st_size}, expected={expected_size}")
        elif args.sha256:
            print(f"[ok] {item['name']} sha256={sha256(path)}")
    print(f"[verify] present={checked}, missing={missing}, invalid={invalid}")
    return 1 if invalid else 0


def command_status(args: argparse.Namespace) -> int:
    manifest_path = args.data_dir / "document-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"documents": []}
    documents = manifest.get("documents") or []
    present = sum(1 for item in documents if (args.data_dir / item["path"]).exists())
    print(f"[data] documents={present}/{len(documents)}")
    if args.skip_database:
        return 0
    module = load_s2_module()
    module.load_dotenv(PROJECT_ROOT / ".env")
    config = module.load_project_config(module.APPLICATION_YAML)
    with module.connect_postgres(config) as connection:
        table_rows = module.fetch_all(connection, "SELECT to_regclass('public.multidoc_full_chunks') AS table_name")
        if not table_rows or table_rows[0].get("table_name") is None:
            print("[database] multidoc_full_chunks=missing")
            return 0
        counts = module.fetch_all(connection, """
            SELECT COUNT(*) AS chunks, COUNT(DISTINCT source_file) AS documents,
                   COUNT(*) FILTER (WHERE chunk_type = 'text') AS text_chunks,
                   COUNT(*) FILTER (WHERE chunk_type = 'table') AS table_chunks
            FROM multidoc_full_chunks
        """)[0]
        index_rows = module.fetch_all(connection,
                                      "SELECT to_regclass('public.idx_multidoc_full_chunks_embedding') AS index_name")
        has_index = bool(index_rows and index_rows[0].get("index_name") is not None)
        print("[database] " + ", ".join([
            f"documents={counts['documents']}", f"chunks={counts['chunks']}",
            f"text={counts['text_chunks']}", f"table={counts['table_chunks']}",
            f"hnsw={has_index}",
        ]))
    return 0


def load_s2_module() -> ModuleType:
    if not S2_SCRIPT.exists():
        raise FileNotFoundError(f"Reusable S2 pipeline not found: {S2_SCRIPT}")
    spec = importlib.util.spec_from_file_location("multidoc_s2_pipeline", S2_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot import {S2_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def row_mentions_document(row: dict[str, Any], company: str, year: str) -> bool:
    return f"{company}_{year}.html" in candidate_document_names(row)


def document_metadata(rows: list[dict[str, Any]], document_names: list[str]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for name in document_names:
        stem = Path(name).stem
        company, year = stem.rsplit("_", 1)
        related = [row for row in rows if row_mentions_document(row, company, year)]
        subsets = sorted({str(row.get("subset", "")).upper() for row in related if row.get("subset")})
        splits = sorted({str(row.get("_split") or row.get("split") or "") for row in related if row.get("_split") or row.get("split")})
        sectors = [str(row.get("sector")) for row in related if row.get("sector")]
        evidence_sections = sorted({str(row.get("evidence_section")) for row in related if row.get("evidence_section")})
        result.append({
            "company": company,
            "year": year,
            "subset": "multi",
            "subsets": subsets,
            "split": "multi",
            "splits": splits,
            "sector": sectors[0] if sectors else "",
            "evidence_sections": evidence_sections,
            "is_cross_doc": any(bool(row.get("is_cross_doc")) for row in related),
            "is_cross_year": any(bool(row.get("is_cross_year")) for row in related),
            "is_hybrid_modal": any(bool(row.get("is_hybrid_modal")) for row in related),
            "requires_calculation": any(bool(row.get("requires_calculation")) for row in related),
        })
    return result


def command_index(args: argparse.Namespace) -> int:
    module = load_s2_module()
    module.ORIGINAL_DOC_DIR = args.data_dir.resolve() / "original_doc"
    module.load_dotenv(PROJECT_ROOT / ".env")
    module.load_dotenv(S2_SCRIPT.parent / ".env")
    rows = filter_rows(load_rows(args.data_dir, args.splits), set(args.subsets))
    manifest = fetch_document_manifest()
    docs = manifest if args.all_docs else required_documents(rows, manifest)
    existing_names = [doc.name for doc in docs if (module.ORIGINAL_DOC_DIR / doc.name).exists()]
    if not existing_names:
        raise RuntimeError("No selected HTML documents are present. Run prepare --download-docs first.")
    metadata_rows = document_metadata(rows, existing_names)
    build_args = argparse.Namespace(
        limit=args.limit_docs,
        chunk_chars=args.chunk_chars,
        chunk_overlap=args.chunk_overlap,
        table_period_metadata=True,
        whole_table_markdown=args.whole_table_markdown,
        table_context_tokens=args.table_context_tokens,
        chunk_strategy=args.chunk_strategy,
        parent_context_chars=args.parent_context_chars,
        max_chunks_per_doc=args.max_chunks_per_doc,
        embedding_max_tokens=args.embedding_max_tokens,
        batch_size=args.batch_size,
        embedding_workers=args.embedding_workers,
        embedding_batch_size=args.embedding_batch_size,
    )
    if args.dry_run:
        names = existing_names[: args.limit_docs] if args.limit_docs else existing_names
        by_source = {module.source_file_for(row): row for row in metadata_rows}
        total = 0
        for name in names:
            chunks = module.parse_html_to_chunks(
                module.ORIGINAL_DOC_DIR / name,
                by_source[name],
                args.chunk_chars,
                args.chunk_overlap,
                True,
                args.whole_table_markdown,
                args.table_context_tokens,
            )
            if args.chunk_strategy == "parent-child":
                module.apply_parent_child_context(chunks, args.parent_context_chars)
            selected = module.limit_chunks_per_doc(chunks, args.max_chunks_per_doc)
            total += len(selected)
            print(f"[dry-run] {name}: parsed={len(chunks)}, selected={len(selected)}")
        print(f"[dry-run-summary] documents={len(names)}, selected_chunks={total}, table=multidoc_full_chunks")
        return 0

    config = module.load_project_config(module.APPLICATION_YAML)
    tables = {"chunks": "multidoc_full_chunks", "results": "multidoc_full_eval_results"}
    with module.connect_postgres(config) as connection:
        module.ensure_schema(connection, tables, config.embedding_dimensions, args.rebuild,
                             create_vector_index=not args.defer_vector_index)
        if args.defer_vector_index:
            module.drop_vector_index(connection, tables["chunks"])
        module.build_index(connection, tables, config, existing_names, metadata_rows, build_args)
        if args.defer_vector_index:
            print("[index] building HNSW vector index after chunk insertion")
            module.ensure_vector_index(connection, tables["chunks"])
    return 0


def command_finalize_index(args: argparse.Namespace) -> int:
    """Create the lexical/vector indexes after an interrupted or deferred bulk load."""
    module = load_s2_module()
    module.load_dotenv(PROJECT_ROOT / ".env")
    module.load_dotenv(S2_SCRIPT.parent / ".env")
    config = module.load_project_config(module.APPLICATION_YAML)
    tables = {"chunks": "multidoc_full_chunks", "results": "multidoc_full_eval_results"}
    with module.connect_postgres(config) as connection:
        rows = module.fetch_all(
            connection,
            "SELECT to_regclass('public.multidoc_full_chunks') AS table_name",
        )
        if not rows or rows[0].get("table_name") is None:
            raise RuntimeError("multidoc_full_chunks does not exist. Run the index command first.")
        print("[finalize] creating metadata, full-text and HNSW indexes")
        module.ensure_schema(
            connection,
            tables,
            config.embedding_dimensions,
            rebuild=False,
            create_vector_index=True,
        )
    print("[finalize] indexes ready")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Multi-Doc-2025 S1-S5 data and indexing pipeline")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare", help="download QA metadata and optionally selected 10-K documents")
    prepare.add_argument("--splits", type=lambda value: parse_csv(value, ALL_SPLITS, "split"), default=list(ALL_SPLITS))
    prepare.add_argument("--subsets", type=lambda value: parse_csv(value, ALL_SUBSETS, "subset"), default=list(ALL_SUBSETS))
    prepare.add_argument("--download-docs", action="store_true")
    prepare.add_argument("--all-docs", action="store_true", help="select all 179 official 10-K files")
    prepare.add_argument("--limit-docs", type=int, default=0)
    prepare.add_argument("--download-workers", type=int, default=4)
    prepare.set_defaults(handler=command_prepare)

    verify = subparsers.add_parser("verify", help="verify locally downloaded documents against official sizes")
    verify.add_argument("--sha256", action="store_true")
    verify.set_defaults(handler=command_verify)

    status = subparsers.add_parser("status", help="show local document and unified index progress")
    status.add_argument("--skip-database", action="store_true")
    status.set_defaults(handler=command_status)

    index = subparsers.add_parser("index", help="build the unified multidoc_full_chunks pgvector index")
    index.add_argument("--splits", type=lambda value: parse_csv(value, ALL_SPLITS, "split"), default=list(ALL_SPLITS))
    index.add_argument("--subsets", type=lambda value: parse_csv(value, ALL_SUBSETS, "subset"), default=list(ALL_SUBSETS))
    index.add_argument("--all-docs", action="store_true")
    index.add_argument("--limit-docs", type=int, default=0)
    index.add_argument("--rebuild", action="store_true")
    index.add_argument("--dry-run", action="store_true", help="parse and count chunks without database or embedding calls")
    index.add_argument("--chunk-strategy", choices=("default", "parent-child"), default="parent-child")
    index.add_argument("--chunk-chars", type=int, default=1400)
    index.add_argument("--chunk-overlap", type=int, default=180)
    index.add_argument("--parent-context-chars", type=int, default=6000)
    index.add_argument("--embedding-max-tokens", type=int, default=384)
    index.add_argument("--max-chunks-per-doc", type=int, default=0, help="0 keeps every chunk; set a cap only for experiments")
    index.add_argument("--whole-table-markdown", action="store_true")
    index.add_argument("--table-context-tokens", type=int, default=200)
    index.add_argument("--batch-size", type=int, default=10)
    index.add_argument("--embedding-workers", type=int, default=1)
    index.add_argument("--embedding-batch-size", type=int, default=64)
    index.add_argument("--defer-vector-index", action=argparse.BooleanOptionalAction, default=True,
                       help="build HNSW after inserting chunks for faster full indexing")
    index.set_defaults(handler=command_index)

    finalize = subparsers.add_parser(
        "finalize-index",
        help="create metadata, full-text and HNSW indexes after a deferred or interrupted bulk load",
    )
    finalize.set_defaults(handler=command_finalize_index)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        return int(args.handler(args))
    except (FileNotFoundError, RuntimeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
