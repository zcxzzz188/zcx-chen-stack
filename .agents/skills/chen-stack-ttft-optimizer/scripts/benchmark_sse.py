#!/usr/bin/env python3
"""Sequential, dependency-free SSE benchmark for an isolated Chen Stack test account."""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


def repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / ".git").exists():
            return parent
    raise RuntimeError("Cannot locate repository root")


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def replace_placeholders(value: Any, message: str, run_id: str) -> Any:
    if isinstance(value, dict):
        return {key: replace_placeholders(item, message, run_id) for key, item in value.items()}
    if isinstance(value, list):
        return [replace_placeholders(item, message, run_id) for item in value]
    if isinstance(value, str):
        return value.replace("{{message}}", message).replace("{{runId}}", run_id)
    return value


def sse_events(lines: Iterable[bytes]):
    event_name = "message"
    data: list[str] = []
    for raw in lines:
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        if not line:
            if data:
                yield event_name, "\n".join(data)
            event_name, data = "message", []
        elif line.startswith(":"):
            continue
        elif line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
    if data:
        yield event_name, "\n".join(data)


def body_text(event_name: str, payload: Any, content_types: set[str]) -> str | None:
    if isinstance(payload, str):
        return payload if event_name.upper() in content_types and payload.strip() else None
    if not isinstance(payload, dict):
        return None
    kind = str(payload.get("type", payload.get("event", event_name))).upper()
    if kind not in content_types:
        return None
    for key in ("text", "content", "delta"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return None


def request_once(args, payload_template: dict[str, Any], message: str, run_number: int) -> dict[str, Any]:
    bearer = os.environ.get(args.token_env)
    if not bearer:
        raise RuntimeError(f"Required auth token environment variable is unset: {args.token_env}")

    run_id = f"ttft-{args.scenario[:20]}-{run_number}-{uuid.uuid4().hex[:16]}"
    payload = replace_placeholders(payload_template, message, run_id)
    payload["chatId"] = run_id
    payload["requestId"] = run_id
    request = urllib.request.Request(
        args.url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Accept": "text/event-stream",
            "Authorization": f"Bearer {bearer.removeprefix('Bearer ').strip()}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    started_at_utc = datetime.now(timezone.utc).isoformat()
    started = time.perf_counter()
    first_event_ms = first_body_ms = None
    event_count = response_bytes = 0
    model_calls = input_tokens = output_tokens = None
    done_event_seen = False
    status = None
    error = None
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            status = response.status
            for event_name, data in sse_events(response):
                event_count += 1
                response_bytes += len(data.encode("utf-8"))
                elapsed = (time.perf_counter() - started) * 1000
                if first_event_ms is None and data:
                    first_event_ms = elapsed
                try:
                    parsed = json.loads(data)
                except json.JSONDecodeError:
                    parsed = data
                if first_body_ms is None and body_text(event_name, parsed, args.content_types):
                    first_body_ms = elapsed
                if isinstance(parsed, dict):
                    done_event_seen |= parsed.get("type") == "DONE"
                    model_calls = parsed.get("modelCalls", parsed.get("modelCallCount", model_calls))
                    input_tokens = parsed.get("inputTokens", parsed.get("promptTokens", input_tokens))
                    output_tokens = parsed.get("outputTokens", parsed.get("completionTokens", output_tokens))
    except urllib.error.HTTPError as exc:
        status = exc.code
        error = "http_error"
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        error = type(exc).__name__
    if status == 200 and error is None and not done_event_seen:
        error = "incomplete_sse"
    full_ms = (time.perf_counter() - started) * 1000
    return {
        "scenario": args.scenario,
        "run": run_number,
        "chat_id": run_id,
        "request_id": run_id,
        "started_at_utc": started_at_utc,
        "http_status": status,
        "first_sse_event_ms": round(first_event_ms, 2) if first_event_ms is not None else None,
        "sse_first_body_ms": round(first_body_ms, 2) if first_body_ms is not None else None,
        "full_completion_ms": round(full_ms, 2),
        "event_count": event_count,
        "done_event_seen": done_event_seen,
        "response_bytes": response_bytes,
        "model_calls": model_calls,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "error": error,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Customer-service SSE endpoint for an isolated test instance")
    parser.add_argument("--payload-file", required=True, type=Path, help="Non-sensitive JSON request template under .work/")
    parser.add_argument("--message", required=True, help="Fixed, non-sensitive scenario text")
    parser.add_argument("--scenario", required=True, help="Anonymous label such as faq, hybrid, or navigation")
    parser.add_argument("--token-env", default="CHEN_STACK_BENCHMARK_TOKEN", help="Environment variable holding a test-account bearer token")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--content-types", default="CONTENT,ANSWER,ANSWER_CHUNK,TEXT,DELTA")
    args = parser.parse_args()
    if not 1 <= args.runs <= 100:
        parser.error("--runs must be between 1 and 100")
    if not args.scenario.replace("-", "").replace("_", "").isalnum():
        parser.error("--scenario may contain only letters, numbers, hyphens, and underscores")
    args.content_types = {item.strip().upper() for item in args.content_types.split(",") if item.strip()}
    root = repo_root()
    payload_path = args.payload_file.resolve()
    work_root = (root / ".work").resolve()
    if work_root not in payload_path.parents:
        parser.error("payload file must be inside repository .work/")
    payload_template = json.loads(payload_path.read_text(encoding="utf-8"))
    if not isinstance(payload_template, dict):
        parser.error("payload template must be a JSON object")
    if not os.environ.get(args.token_env):
        parser.error(f"set {args.token_env} to an isolated test-account token; its value is never printed")

    samples = []
    for run_number in range(1, args.runs + 1):
        sample = request_once(args, payload_template, args.message, run_number)
        samples.append(sample)
        if run_number < args.runs:
            time.sleep(1)

    out_dir = root / ".work" / "ttft"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{args.scenario}.jsonl"
    out_file.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in samples), encoding="utf-8")
    latencies = [row["sse_first_body_ms"] for row in samples if row["sse_first_body_ms"] is not None]
    complete = [row["full_completion_ms"] for row in samples]
    print(json.dumps({
        "scenario": args.scenario,
        "runs": len(samples),
        "successful_http": sum(row["http_status"] == 200 and row["error"] is None and row["done_event_seen"] for row in samples),
        "sse_first_body_p50_ms": percentile(latencies, 0.50),
        "sse_first_body_p95_ms": percentile(latencies, 0.95),
        "full_completion_p50_ms": percentile(complete, 0.50),
        "full_completion_p95_ms": percentile(complete, 0.95),
        "model_calls": "read from per-request trace; not inferred from latency",
        "provider_tokens": "read from provider usage; not inferred from SSE timing",
        "samples_file": str(out_file),
    }, ensure_ascii=False))
    return 0 if all(row["http_status"] == 200 and row["error"] is None and row["done_event_seen"] for row in samples) else 1


if __name__ == "__main__":
    raise SystemExit(main())
