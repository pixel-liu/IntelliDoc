#!/usr/bin/env python3
"""Labelled retrieval evaluation, offline replay and authenticated live capture (stdlib only)."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def read_jsonl(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if not rows or any(not isinstance(row, dict) for row in rows):
        raise ValueError("JSONL must contain non-empty object rows")
    ids = [row.get("id") for row in rows]
    if any(not isinstance(value, str) or not value.strip() for value in ids) or len(set(ids)) != len(ids):
        raise ValueError("Every row needs a unique non-empty string id")
    return rows


def chunk_key(item):
    if not isinstance(item, dict) or not isinstance(item.get("fileMd5"), str) or not item["fileMd5"].strip():
        raise ValueError("Chunk needs fileMd5")
    if type(item.get("chunkId")) is not int or item["chunkId"] < 0:
        raise ValueError("Chunk needs non-negative integer chunkId")
    return item["fileMd5"], item["chunkId"]


def validate_cases(cases):
    for case in cases:
        if not isinstance(case.get("query"), str) or not case["query"].strip():
            raise ValueError("Case needs query")
        if not isinstance(case.get("relevant"), list):
            raise ValueError("Case needs relevant list (empty means no answer)")
        seen = set()
        for item in case["relevant"]:
            key = chunk_key(item)
            grade = item.get("grade", 1)
            if type(grade) is not int or not 1 <= grade <= 3 or key in seen:
                raise ValueError("Relevant chunks must be unique, with integer grade 1..3")
            seen.add(key)
        forbidden = case.get("forbidden", [])
        if not isinstance(forbidden, list):
            raise ValueError("forbidden must be a list")
        for item in forbidden:
            if not isinstance(item, dict) or not isinstance(item.get("fileMd5"), str) or not item["fileMd5"]:
                raise ValueError("Forbidden reference needs fileMd5")
            if "chunkId" in item:
                chunk_key(item)
            if any(key[0] == item["fileMd5"] and ("chunkId" not in item or key[1] == item["chunkId"]) for key in seen):
                raise ValueError("A chunk cannot be both relevant and forbidden")


def unique_chunks(items):
    if not isinstance(items, list):
        raise ValueError("results/citations must be a list")
    seen, result = set(), []
    for item in items:
        key = chunk_key(item)
        if key not in seen:
            result.append(item)
            seen.add(key)
    return result


def evaluate(cases, rows, k):
    validate_cases(cases)
    if type(k) is not int or k < 1:
        raise ValueError("k must be positive")
    indexed = {row["id"]: row for row in rows}
    if len(indexed) != len(rows) or set(indexed) != {case["id"] for case in cases}:
        raise ValueError("Captured results must match every case id exactly once")
    details, latencies, modes = [], [], {}
    for case in cases:
        row = indexed[case["id"]]
        ranked = unique_chunks(row.get("results", []))
        error = row.get("error")
        if error and ranked:
            raise ValueError("Failed capture cannot contain successful ranked results")
        expected = {chunk_key(item): item.get("grade", 1) for item in case["relevant"]}
        top = [chunk_key(item) for item in ranked[:k]]
        relevant_ranks = [index + 1 for index, key in enumerate(top) if key in expected]
        dcg = sum((2 ** expected.get(key, 0) - 1) / math.log2(index + 2) for index, key in enumerate(top))
        ideal = sum((2 ** grade - 1) / math.log2(index + 2)
                    for index, grade in enumerate(sorted(expected.values(), reverse=True)[:k]))
        leaked = [item for item in ranked if any(item["fileMd5"] == rule["fileMd5"] and
                  ("chunkId" not in rule or item["chunkId"] == rule["chunkId"]) for rule in case.get("forbidden", []))]
        detail = {"id": case["id"], "answerable": bool(expected), "error": error,
                  "recall_at_k": len(relevant_ranks) / len(expected) if expected else None,
                  "precision_at_k": len(relevant_ranks) / k if expected else None,
                  "mrr_at_k": 1 / relevant_ranks[0] if relevant_ranks else (0.0 if expected else None),
                  "ndcg_at_k": dcg / ideal if ideal else None,
                  "no_result_correct": (not error and not ranked) if not expected else None,
                  "permission_leaks": len(leaked)}
        if "citations" in row:
            cited = [chunk_key(item) for item in unique_chunks(row["citations"])]
            correct = len(set(cited) & set(expected))
            detail["citation_precision"] = correct / len(cited) if cited else (0.0 if expected else 1.0)
            detail["citation_recall"] = correct / len(expected) if expected else (1.0 if not cited else 0.0)
        latency = row.get("latencyMs")
        if latency is not None and (type(latency) not in (int, float) or not math.isfinite(latency) or latency < 0):
            raise ValueError("latencyMs must be finite and non-negative")
        if latency is not None and not error:
            latencies.append(latency)
        for item in ranked[:k]:
            mode = item.get("retrievalMode", "UNKNOWN")
            modes[mode] = modes.get(mode, 0) + 1
        details.append(detail)
    def mean(metric):
        values = [row[metric] for row in details if row.get(metric) is not None]
        return sum(values) / len(values) if values else None
    metrics = {metric: mean(metric) for metric in ["recall_at_k", "precision_at_k", "mrr_at_k", "ndcg_at_k",
               "no_result_correct", "citation_precision", "citation_recall"]}
    metrics.update({"error_count": sum(bool(row["error"]) for row in details),
                    "permission_leaks": sum(row["permission_leaks"] for row in details),
                    "latency_p95_ms": sorted(latencies)[math.ceil(len(latencies) * .95) - 1] if latencies else None})
    fingerprint = hashlib.sha256(json.dumps(cases, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return {"schemaVersion": 1, "datasetSha256": fingerprint, "k": k, "caseCount": len(cases),
            "answerableCount": sum(row["answerable"] for row in details), "metrics": metrics,
            "retrievalModes": modes, "cases": details}


def compare(report, baseline, max_regression):
    if (report["datasetSha256"], report["k"], report["schemaVersion"]) != (baseline.get("datasetSha256"), baseline.get("k"), baseline.get("schemaVersion")):
        raise ValueError("Baseline must use the same dataset, k and schema version")
    deltas = {}
    for metric in ["recall_at_k", "precision_at_k", "mrr_at_k", "ndcg_at_k", "no_result_correct", "citation_precision", "citation_recall"]:
        current, previous = report["metrics"].get(metric), baseline["metrics"].get(metric)
        if previous is not None and current is None:
            raise ValueError("Current run is missing baseline metric: " + metric)
        if current is not None and previous is not None:
            deltas[metric] = current - previous
    report["deltas"] = deltas
    return all(delta >= -max_regression - 1e-12 for delta in deltas.values())


def capture(cases, base_url, k, timeout):
    if any(case.get("example") for case in cases):
        raise ValueError("Replace example labels with real corpus judgements before live evaluation")
    rows = []
    for case in cases:
        start = time.perf_counter()
        row = {"id": case["id"], "results": []}
        try:
            token_env = case.get("tokenEnv", "RAG_EVAL_TOKEN")
            token = os.environ.get(token_env, "").strip()
            if not token:
                raise ValueError("Missing token environment variable: " + token_env)
            url = base_url.rstrip("/") + "/search/hybrid?" + urlencode({"query": case["query"], "topK": k})
            request = Request(url, headers={"Authorization": "Bearer " + token})
            with urlopen(request, timeout=timeout) as response:
                payload = json.load(response)
            if not isinstance(payload, dict) or payload.get("code") != 200 or not isinstance(payload.get("data"), list):
                raise ValueError("Search returned unsuccessful business status")
            row["results"] = [{key: item[key] for key in ("fileMd5", "chunkId", "score", "pageNumber", "retrievalMode") if key in item}
                              for item in payload["data"]]
            unique_chunks(row["results"])
        except HTTPError as error:
            row["results"] = []
            row["error"] = "HTTP " + str(error.code)
        except (URLError, TimeoutError, ValueError, OSError, TypeError, KeyError) as error:
            row["results"] = []
            row["error"] = str(error)
        row["latencyMs"] = round((time.perf_counter() - start) * 1000, 3)
        rows.append(row)
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--results", help="Offline JSONL capture")
    mode.add_argument("--api-base", help="Live API root, e.g. http://localhost:8081/api/v1")
    parser.add_argument("--capture", help="Save live JSONL without tokens or document text")
    parser.add_argument("--out", required=True)
    parser.add_argument("--baseline")
    parser.add_argument("--label", default="unlabelled")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--max-regression", type=float, default=0)
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.k <= 100 or not math.isfinite(args.timeout) or args.timeout <= 0 or not 0 <= args.max_regression <= 1:
            raise ValueError("k must be 1..100, timeout positive, max-regression 0..1")
        if args.capture and not args.api_base:
            raise ValueError("--capture requires --api-base")
        cases = read_jsonl(args.dataset)
        validate_cases(cases)
        rows = read_jsonl(args.results) if args.results else capture(cases, args.api_base, args.k, args.timeout)
        report = evaluate(cases, rows, args.k)
        report["label"] = args.label
        passed = report["metrics"]["error_count"] == 0 and report["metrics"]["permission_leaks"] == 0
        if args.baseline:
            passed = compare(report, json.loads(Path(args.baseline).read_text(encoding="utf-8")), args.max_regression) and passed
        report["gatePassed"] = passed
        output = Path(args.out)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if args.capture:
            capture_path = Path(args.capture)
            capture_path.parent.mkdir(parents=True, exist_ok=True)
            capture_path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        print(json.dumps({"label": args.label, "metrics": report["metrics"], "gatePassed": passed}, ensure_ascii=False))
        return 0 if passed else 1
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
