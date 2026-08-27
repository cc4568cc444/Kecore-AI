#!/usr/bin/env python3
"""OpenAI/Cohere-style rerank endpoint backed by Qwen3-Reranker."""

from __future__ import annotations

import argparse
import threading
from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field


DEFAULT_MODEL = "Qwen/Qwen3-Reranker-0.6B"
DEFAULT_INSTRUCTION = (
    "Given a scientific research question, retrieve passages that contain evidence "
    "needed to answer the question."
)


class RerankRequest(BaseModel):
    query: str = Field(min_length=1)
    documents: list[str] = Field(min_length=1, max_length=128)
    top_n: int | None = Field(default=None, ge=1)
    return_documents: bool = False
    model: str | None = None
    instruction: str | None = None


@dataclass(frozen=True)
class ServerSettings:
    model_path: str
    device: str
    max_length: int
    batch_size: int
    instruction: str


class Qwen3Reranker:
    def __init__(self, settings: ServerSettings) -> None:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        if settings.device == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        else:
            device = settings.device
        if device.startswith("cuda") and not torch.cuda.is_available():
            raise RuntimeError("CUDA was requested, but torch.cuda.is_available() is false")

        dtype = torch.float16 if device.startswith("cuda") else torch.float32
        self.torch = torch
        self.device = torch.device(device)
        self.settings = settings
        self.lock = threading.Lock()
        self.tokenizer = AutoTokenizer.from_pretrained(
            settings.model_path,
            trust_remote_code=True,
            padding_side="left",
        )
        self.model = AutoModelForCausalLM.from_pretrained(
            settings.model_path,
            trust_remote_code=True,
            torch_dtype=dtype,
        ).to(self.device).eval()
        self.false_token_id = self.tokenizer.convert_tokens_to_ids("no")
        self.true_token_id = self.tokenizer.convert_tokens_to_ids("yes")
        self.prefix = (
            '<|im_start|>system\nJudge whether the Document meets the requirements based on '
            'the Query and the Instruct provided. Note that the answer can only be "yes" or '
            '"no".<|im_end|>\n<|im_start|>user\n'
        )
        self.suffix = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        self.prefix_tokens = self.tokenizer.encode(self.prefix, add_special_tokens=False)
        self.suffix_tokens = self.tokenizer.encode(self.suffix, add_special_tokens=False)

    def score(self, query: str, documents: list[str], instruction: str | None) -> list[float]:
        task = instruction or self.settings.instruction
        pairs = [
            f"<Instruct>: {task}\n<Query>: {query}\n<Document>: {document}"
            for document in documents
        ]
        scores: list[float] = []
        with self.lock, self.torch.inference_mode():
            for start in range(0, len(pairs), self.settings.batch_size):
                scores.extend(self._score_batch(pairs[start:start + self.settings.batch_size]))
        return scores

    def _score_batch(self, pairs: list[str]) -> list[float]:
        usable_length = (
            self.settings.max_length - len(self.prefix_tokens) - len(self.suffix_tokens)
        )
        encoded = self.tokenizer(
            pairs,
            padding=False,
            truncation="longest_first",
            return_attention_mask=False,
            max_length=usable_length,
        )
        for index, token_ids in enumerate(encoded["input_ids"]):
            encoded["input_ids"][index] = (
                self.prefix_tokens + token_ids + self.suffix_tokens
            )
        inputs = self.tokenizer.pad(
            encoded,
            padding=True,
            return_tensors="pt",
        )
        inputs = {key: value.to(self.device) for key, value in inputs.items()}
        logits = self.model(**inputs).logits[:, -1, :]
        yes_logits = logits[:, self.true_token_id]
        no_logits = logits[:, self.false_token_id]
        probabilities = self.torch.softmax(
            self.torch.stack([no_logits, yes_logits], dim=1), dim=1
        )[:, 1]
        return probabilities.float().cpu().tolist()


def create_app(engine: Qwen3Reranker) -> FastAPI:
    app = FastAPI(title="Qwen3 Reranker", version="1.0.0")

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Any, exception: RequestValidationError) -> JSONResponse:
        # Keep document contents out of logs while exposing which request fields failed.
        errors = exception.errors()
        print(f"Invalid /v1/rerank request: {errors}")
        return JSONResponse(status_code=422, content={"detail": jsonable_encoder(errors)})

    @app.get("/health")
    def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "model": engine.settings.model_path,
            "device": str(engine.device),
            "max_length": engine.settings.max_length,
            "batch_size": engine.settings.batch_size,
        }

    @app.post("/v1/rerank")
    def rerank(request: RerankRequest) -> dict[str, Any]:
        requested_model = (request.model or "").strip()
        allowed_names = {
            engine.settings.model_path,
            engine.settings.model_path.rsplit("/", 1)[-1],
        }
        if requested_model and requested_model not in allowed_names:
            raise HTTPException(
                status_code=400,
                detail=f"Model {requested_model!r} is not loaded; loaded model is "
                       f"{engine.settings.model_path!r}",
            )
        scores = engine.score(request.query, request.documents, request.instruction)
        top_n = min(request.top_n or len(scores), len(scores))
        ranked_indices = sorted(range(len(scores)), key=scores.__getitem__, reverse=True)[:top_n]
        results = []
        for index in ranked_indices:
            item: dict[str, Any] = {
                "index": index,
                "relevance_score": scores[index],
            }
            if request.return_documents:
                item["document"] = {"text": request.documents[index]}
            results.append(item)
        return {"id": "qwen3-rerank", "results": results}

    return app


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Serve Qwen3-Reranker on /v1/rerank")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8010)
    parser.add_argument("--model-path", default=DEFAULT_MODEL)
    parser.add_argument("--device", default="auto", help="auto, cpu, cuda, or cuda:0")
    parser.add_argument("--max-length", type=int, default=2048)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--instruction", default=DEFAULT_INSTRUCTION)
    return parser.parse_args()


def main() -> None:
    import uvicorn

    args = parse_args()
    if args.max_length < 256:
        raise SystemExit("--max-length must be at least 256")
    if args.batch_size < 1:
        raise SystemExit("--batch-size must be at least 1")
    settings = ServerSettings(
        model_path=args.model_path,
        device=args.device,
        max_length=args.max_length,
        batch_size=args.batch_size,
        instruction=args.instruction,
    )
    print(f"Loading {settings.model_path}; the first run may download model weights...")
    engine = Qwen3Reranker(settings)
    print(f"Model loaded on {engine.device}; starting http://{args.host}:{args.port}")
    uvicorn.run(create_app(engine), host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
