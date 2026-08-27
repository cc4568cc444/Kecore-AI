#!/usr/bin/env python3
"""Download QASPER v0.3, build an isolated PGVector index, and export evaluation cases."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tarfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse

TRAIN_DEV_URL = "https://qasper-dataset.s3.us-west-2.amazonaws.com/qasper-train-dev-v0.3.tgz"
TEST_URL = "https://qasper-dataset.s3.us-west-2.amazonaws.com/qasper-test-and-evaluator-v0.3.tgz"
ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parents[3]
APPLICATION_YAML = PROJECT_ROOT / "src" / "main" / "resources" / "application.yaml"
DATA_DIR = Path(os.getenv("QASPER_DATA_DIR", PROJECT_ROOT / "datasets" / "qasper"))
EVAL_DIR = PROJECT_ROOT / "src" / "test" / "resources" / "qasper"


@dataclass(frozen=True)
class ProjectConfig:
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    table: str
    embedding_base_url: str
    embedding_model: str
    embedding_dimensions: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare the QASPER scientific-paper RAG corpus")
    parser.add_argument("--download", action="store_true", help="download and extract official QASPER v0.3 data")
    parser.add_argument("--build-index", action="store_true", help="build the isolated qasper_paper_chunks index")
    parser.add_argument("--export-eval", action="store_true", help="export normalized dev/test JSONL cases")
    parser.add_argument("--split", choices=["train", "dev", "test", "all"], default="all")
    parser.add_argument("--limit-papers", type=int, default=0, help="limit papers for a smoke test")
    parser.add_argument("--rebuild", action="store_true", help="drop the paper index before building")
    parser.add_argument("--chunk-chars", type=int, default=1400)
    parser.add_argument("--chunk-overlap", type=int, default=180)
    parser.add_argument("--parent-chars", type=int, default=6000)
    parser.add_argument("--batch-size", type=int, default=25, help="commit and report progress every N chunks")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not any((args.download, args.build_index, args.export_eval)):
        raise SystemExit("Choose --download, --build-index, or --export-eval")
    if args.download:
        download_data()
    files = split_files(args.split)
    if args.build_index:
        config = load_project_config(APPLICATION_YAML)
        build_index(files, args, config)
    if args.export_eval:
        export_eval(files)


def download_data() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    for url in (TRAIN_DEV_URL, TEST_URL):
        archive = DATA_DIR / url.rsplit("/", 1)[-1]
        if not archive.exists():
            print(f"Downloading {archive.name} ...")
            urllib.request.urlretrieve(url, archive)
        with tarfile.open(archive, "r:gz") as handle:
            safe_extract(handle, DATA_DIR)
    print(f"QASPER data is ready in {DATA_DIR}")


def safe_extract(archive: tarfile.TarFile, target: Path) -> None:
    target_resolved = target.resolve()
    for member in archive.getmembers():
        destination = (target / member.name).resolve()
        if target_resolved not in destination.parents and destination != target_resolved:
            raise RuntimeError(f"Unsafe archive member: {member.name}")
    archive.extractall(target)


def split_files(split: str) -> list[tuple[str, Path]]:
    patterns = {
        "train": "qasper-train-v0.3.json",
        "dev": "qasper-dev-v0.3.json",
        "test": "qasper-test-v0.3.json",
    }
    selected = patterns if split == "all" else {split: patterns[split]}
    result: list[tuple[str, Path]] = []
    for name, filename in selected.items():
        matches = list(DATA_DIR.rglob(filename))
        if not matches:
            raise FileNotFoundError(f"Missing {filename}; run with --download first")
        result.append((name, matches[0]))
    return result


def build_index(files: list[tuple[str, Path]], args: argparse.Namespace, config: ProjectConfig) -> None:
    try:
        import psycopg
    except ImportError as exc:
        raise RuntimeError("Install the index dependency first: pip install 'psycopg[binary]'") from exc

    table = validate_identifier(config.table, "paper RAG table")
    print(
        f"Configuration: db={config.db_host}:{config.db_port}/{config.db_name}, "
        f"table={table}, embedding={config.embedding_base_url} ({config.embedding_model}, "
        f"{config.embedding_dimensions} dimensions)",
        flush=True,
    )
    try:
        connection = psycopg.connect(
            host=config.db_host,
            port=config.db_port,
            dbname=config.db_name,
            user=config.db_user,
            password=config.db_password,
        )
    except psycopg.OperationalError as exc:
        raise RuntimeError(
            f"Cannot connect to PostgreSQL using spring.datasource from {APPLICATION_YAML}. "
            "Check that PostgreSQL is running and the YAML credentials are correct. "
            "QASPER_DB_* environment variables may be used to override them."
        ) from exc
    try:
        with connection.cursor() as cursor:
            cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
            if args.rebuild:
                cursor.execute(f"DROP TABLE IF EXISTS {table}")
            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS {table} (
                    chunk_id TEXT PRIMARY KEY,
                    paper_id TEXT NOT NULL,
                    split TEXT NOT NULL,
                    title TEXT NOT NULL,
                    section_name TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    metadata JSONB NOT NULL,
                    embedding VECTOR({config.embedding_dimensions}) NOT NULL
                )
            """)
            cursor.execute(f"CREATE INDEX IF NOT EXISTS {table}_paper_idx ON {table}(paper_id)")
            cursor.execute(f"CREATE INDEX IF NOT EXISTS {table}_split_idx ON {table}(split)")
        connection.commit()

        indexed = 0
        batch_size = max(1, args.batch_size)
        for split, path in files:
            papers = list(iter_papers(load_json(path)))
            if args.limit_papers:
                papers = papers[: args.limit_papers]
            print(f"Split {split}: preparing {len(papers)} papers from {path.name}", flush=True)
            for paper_number, (paper_id, paper) in enumerate(papers, start=1):
                chunks = paper_chunks(paper_id, paper, split, args.chunk_chars, args.chunk_overlap, args.parent_chars)
                title = clean_text(paper.get("title")) or paper_id
                print(
                    f"[{split} {paper_number}/{len(papers)}] {title[:90]}: {len(chunks)} chunks",
                    flush=True,
                )
                for chunk in chunks:
                    embedding = embed(chunk["content"], config)
                    with connection.cursor() as cursor:
                        cursor.execute(f"""
                            INSERT INTO {table}
                                (chunk_id, paper_id, split, title, section_name, chunk_index, content, metadata, embedding)
                            VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::vector)
                            ON CONFLICT (chunk_id) DO UPDATE SET
                                split = EXCLUDED.split,
                                title = EXCLUDED.title,
                                section_name = EXCLUDED.section_name,
                                chunk_index = EXCLUDED.chunk_index,
                                content = EXCLUDED.content,
                                metadata = EXCLUDED.metadata,
                                embedding = EXCLUDED.embedding
                        """, (
                            chunk["chunk_id"], paper_id, split, chunk["title"], chunk["section_name"],
                            chunk["chunk_index"], chunk["content"], json.dumps(chunk["metadata"]),
                            vector_literal(embedding),
                    ))
                    indexed += 1
                    if indexed % batch_size == 0:
                        connection.commit()
                        print(f"Indexed {indexed} chunks", flush=True)
                connection.commit()
        connection.commit()
        print(f"Finished: {indexed} chunks in {table}", flush=True)
    finally:
        connection.close()


def export_eval(files: list[tuple[str, Path]]) -> None:
    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    for split, path in files:
        if split == "train":
            continue
        rows: list[dict[str, Any]] = []
        for paper_id, paper in iter_papers(load_json(path)):
            title = text_value(paper.get("title"))
            for qa in normalize_records(paper.get("qas", [])):
                answers = [normalize_answer_record(item.get("answer", item)) for item in normalize_records(qa.get("answers", []))]
                rows.append({
                    "question_id": text_value(qa.get("question_id")),
                    "paper_id": paper_id,
                    "title": title,
                    "question": text_value(qa.get("question")),
                    "gold_answers": [answer["answer"] for answer in answers],
                    "evidence": sorted({evidence for answer in answers for evidence in answer["evidence"]}),
                    "answerable": any(not answer["unanswerable"] for answer in answers),
                    "has_numeric_answer": any(has_number(answer["answer"]) for answer in answers),
                })
        output = EVAL_DIR / f"qasper-{split}.jsonl"
        with output.open("w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(f"Exported {len(rows)} evaluation cases to {output}")


def paper_chunks(paper_id: str, paper: dict[str, Any], split: str,
                 chunk_chars: int, overlap: int, parent_chars: int) -> list[dict[str, Any]]:
    title = text_value(paper.get("title")) or paper_id
    sections: list[tuple[str, list[str]]] = []
    abstract = flatten_text(paper.get("abstract", ""))
    if abstract:
        sections.append(("Abstract", [abstract]))
    sections.extend(iter_sections(paper.get("full_text", [])))

    rows: list[dict[str, Any]] = []
    index = 0
    for section_name, paragraphs in sections:
        parent = "\n\n".join(filter(None, map(clean_text, paragraphs)))[:parent_chars]
        if not parent:
            continue
        for child in split_text(parent, chunk_chars, overlap):
            digest = hashlib.sha1(f"{paper_id}:{section_name}:{index}:{child}".encode()).hexdigest()[:20]
            rows.append({
                "chunk_id": f"qasper-{digest}",
                "title": title,
                "section_name": section_name or "Untitled section",
                "chunk_index": index,
                "content": f"Paper: {title}\nSection: {section_name}\n{child}",
                "metadata": {
                    "paper_id": paper_id,
                    "title": title,
                    "section_name": section_name,
                    "split": split,
                    "parent_context": parent,
                },
            })
            index += 1
    return rows


def iter_sections(value: Any) -> list[tuple[str, list[str]]]:
    if isinstance(value, list):
        return [(text_value(item.get("section_name")), list_text(item.get("paragraphs")))
                for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        names = value.get("section_name", [])
        paragraphs = value.get("paragraphs", [])
        if isinstance(names, list) and isinstance(paragraphs, list):
            return [(text_value(name), list_text(paragraphs[i]) if i < len(paragraphs) else [])
                    for i, name in enumerate(names)]
    return []


def iter_papers(data: Any) -> Iterable[tuple[str, dict[str, Any]]]:
    if isinstance(data, dict):
        for paper_id, paper in data.items():
            if isinstance(paper, dict):
                yield str(paper_id), paper
    elif isinstance(data, list):
        for index, paper in enumerate(data):
            if isinstance(paper, dict):
                paper_id = text_value(paper.get("paper_id") or paper.get("id")) or str(index)
                yield paper_id, paper


def normalize_answer_record(answer: Any) -> dict[str, Any]:
    if not isinstance(answer, dict):
        return {"answer": text_value(answer), "evidence": [], "unanswerable": False}
    unanswerable = bool(answer.get("unanswerable", False))
    free_form = text_value(answer.get("free_form_answer"))
    extractive = list_text(answer.get("extractive_spans"))
    yes_no = answer.get("yes_no")
    if unanswerable:
        value = "unanswerable"
    elif free_form:
        value = free_form
    elif extractive:
        value = "; ".join(extractive)
    elif isinstance(yes_no, bool):
        value = "yes" if yes_no else "no"
    else:
        value = ""
    return {"answer": value, "evidence": list_text(answer.get("evidence")), "unanswerable": unanswerable}


def normalize_records(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        keys = list(value)
        length = max((len(value[key]) for key in keys if isinstance(value[key], list)), default=0)
        return [{key: value[key][i] if isinstance(value[key], list) and i < len(value[key]) else value[key]
                 for key in keys} for i in range(length)]
    return []


def split_text(text: str, size: int, overlap: int) -> list[str]:
    size = max(300, size)
    overlap = max(0, min(overlap, size // 2))
    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = min(len(text), start + size)
        if end < len(text):
            boundary = max(text.rfind(". ", start, end), text.rfind("\n", start, end))
            if boundary > start + size // 2:
                end = boundary + 1
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = max(start + 1, end - overlap)
    return chunks


def embed(text: str, config: ProjectConfig) -> list[float]:
    request = urllib.request.Request(
        config.embedding_base_url.rstrip("/") + "/api/embed",
        data=json.dumps({"model": config.embedding_model, "input": text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            payload = json.loads(response.read().decode())
    except HTTPError as exc:
        details = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Ollama embedding request failed with HTTP {exc.code}: {details}") from exc
    except URLError as exc:
        raise RuntimeError(f"Cannot reach Ollama embedding service at {config.embedding_base_url}: {exc.reason}") from exc
    values = payload.get("embeddings", [[]])[0]
    if len(values) != config.embedding_dimensions:
        raise RuntimeError(
            f"Expected a {config.embedding_dimensions}-dimensional embedding, got {len(values)}"
        )
    return values


def load_project_config(path: Path) -> ProjectConfig:
    if not path.exists():
        raise FileNotFoundError(f"Missing Spring configuration: {path}")
    yaml_text = path.read_text(encoding="utf-8")
    jdbc_url = resolve_env_value(read_yaml_scalar(yaml_text, "spring.datasource.url"))
    database = parse_jdbc_url(jdbc_url)
    table = env_value("QASPER_TABLE", read_yaml_scalar(yaml_text, "app.paper-rag.table"))
    return ProjectConfig(
        db_host=env_value("QASPER_DB_HOST", database["host"]),
        db_port=int(env_value("QASPER_DB_PORT", str(database["port"]))),
        db_name=env_value("QASPER_DB_NAME", database["database"]),
        db_user=env_value(
            "QASPER_DB_USER",
            resolve_env_value(read_yaml_scalar(yaml_text, "spring.datasource.username")),
        ),
        db_password=env_value(
            "QASPER_DB_PASSWORD",
            resolve_env_value(read_yaml_scalar(yaml_text, "spring.datasource.password")),
        ),
        table=table,
        embedding_base_url=env_value(
            "QASPER_EMBEDDING_URL",
            resolve_env_value(read_yaml_scalar(yaml_text, "app.embedding.ollama.base-url")),
        ).rstrip("/"),
        embedding_model=env_value(
            "QASPER_EMBEDDING_MODEL",
            resolve_env_value(read_yaml_scalar(yaml_text, "app.embedding.ollama.model")),
        ),
        embedding_dimensions=int(
            env_value(
                "QASPER_EMBEDDING_DIMENSIONS",
                resolve_env_value(read_yaml_scalar(yaml_text, "app.embedding.ollama.dimensions")),
            )
        ),
    )


def parse_jdbc_url(jdbc_url: str) -> dict[str, Any]:
    parsed = urlparse(jdbc_url.removeprefix("jdbc:"))
    if parsed.scheme != "postgresql" or not parsed.hostname or not parsed.path.strip("/"):
        raise ValueError(f"Unsupported PostgreSQL JDBC URL: {jdbc_url}")
    return {
        "host": parsed.hostname,
        "port": parsed.port or 5432,
        "database": parsed.path.strip("/"),
    }


def env_value(name: str, default: str) -> str:
    value = os.getenv(name)
    return value if value is not None else default


def resolve_env_value(value: str) -> str:
    match = re.fullmatch(r"\$\{([^}:]+)(?::([^}]*))?}", value)
    if not match:
        return value
    return os.environ.get(match.group(1), match.group(2) or "")


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
    stack: list[tuple[int, str]] = []
    for raw_line in text.splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        line = raw_line.strip()
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key.strip().strip('"')))
        if ".".join(item[1] for item in stack) == dotted_path:
            return value.strip().strip('"').strip("'")
    raise KeyError(f"Cannot read YAML path: {dotted_path}")


def validate_identifier(value: str, label: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value):
        raise ValueError(f"Invalid {label}: {value!r}")
    return value


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(str(value) for value in values) + "]"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def clean_text(value: Any) -> str:
    return re.sub(r"\s+", " ", text_value(value)).strip()


def text_value(value: Any) -> str:
    return value if isinstance(value, str) else "" if value is None else str(value)


def list_text(value: Any) -> list[str]:
    if isinstance(value, list):
        return [text_value(item) for item in value if text_value(item).strip()]
    text = text_value(value).strip()
    return [text] if text else []


def flatten_text(value: Any) -> str:
    return " ".join(list_text(value))


def has_number(value: str) -> bool:
    cleaned = re.sub(r"(?:BIBREF|TABREF|FIGREF)\d+", "", value or "", flags=re.IGNORECASE)
    return bool(re.search(r"\d+(?:,\d{3})*(?:\.\d+)?%?", cleaned))


if __name__ == "__main__":
    main()
