import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread

spec = importlib.util.spec_from_file_location("rag_eval", Path(__file__).parents[1] / "rag_eval.py")
rag = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rag)

class EvaluationTest(unittest.TestCase):
    def case(self, relevant=True):
        return {"id": "q1", "query": "question", "relevant": [{"fileMd5": "a", "chunkId": 0}] if relevant else [],
                "forbidden": [{"fileMd5": "private"}]}
    def row(self, *docs, **extra):
        return {"id": "q1", "results": [{"fileMd5": doc, "chunkId": 0} for doc in docs], **extra}

    def test_perfect_retrieval(self):
        metrics = rag.evaluate([self.case()], [self.row("a", latencyMs=10)], 1)["metrics"]
        for metric in ["recall_at_k", "precision_at_k", "mrr_at_k", "ndcg_at_k"]:
            self.assertEqual(1, metrics[metric])
        self.assertEqual(10, metrics["latency_p95_ms"])
        self.assertIsNone(metrics["no_result_correct"])

    def test_wrong_rank_and_duplicate_results(self):
        report = rag.evaluate([self.case()], [self.row("b", "b", "a")], 2)
        self.assertEqual(.5, report["metrics"]["mrr_at_k"])
        self.assertAlmostEqual(1 / math.log2(3), report["metrics"]["ndcg_at_k"])
        self.assertEqual(.5, report["metrics"]["precision_at_k"])

    def test_failed_request_is_not_a_correct_no_answer(self):
        metrics = rag.evaluate([self.case(False)], [self.row(error="HTTP 503")], 5)["metrics"]
        self.assertEqual(1, metrics["error_count"])
        self.assertEqual(0, metrics["no_result_correct"])
        self.assertIsNone(metrics["recall_at_k"])

    def test_no_answer_and_false_positive(self):
        self.assertEqual(1, rag.evaluate([self.case(False)], [self.row()], 5)["metrics"]["no_result_correct"])
        self.assertEqual(0, rag.evaluate([self.case(False)], [self.row("b")], 5)["metrics"]["no_result_correct"])

    def test_permission_leaks_checked_beyond_top_k(self):
        self.assertEqual(1, rag.evaluate([self.case()], [self.row("a", "private")], 1)["metrics"]["permission_leaks"])

    def test_citation_metrics_only_exist_when_supplied(self):
        row = self.row("a", citations=[{"fileMd5": "a", "chunkId": 0}, {"fileMd5": "b", "chunkId": 0}])
        self.assertEqual(.5, rag.evaluate([self.case()], [row], 1)["metrics"]["citation_precision"])
        self.assertIsNone(rag.evaluate([self.case()], [self.row("a")], 1)["metrics"]["citation_precision"])

    def test_missing_extra_and_duplicate_cases_are_rejected(self):
        for rows in [[], [self.row(), self.row()], [{"id": "wrong", "results": []}]]:
            with self.assertRaises(ValueError): rag.evaluate([self.case()], rows, 5)

    def test_invalid_labels_are_rejected(self):
        case = self.case(); case["forbidden"] = [{"fileMd5": "a"}]
        with self.assertRaises(ValueError): rag.validate_cases([case])
        case = self.case(); case["relevant"][0]["grade"] = 0
        with self.assertRaises(ValueError): rag.validate_cases([case])

    def test_regression_gate_and_dataset_identity(self):
        baseline = rag.evaluate([self.case()], [self.row("a")], 1)
        current = rag.evaluate([self.case()], [self.row("b")], 1)
        self.assertFalse(rag.compare(current, baseline, 0))
        baseline["k"] = 2
        with self.assertRaises(ValueError): rag.compare(current, baseline, 0)

    def test_missing_token_produces_failed_capture_without_network(self):
        with patch.dict("os.environ", {}, clear=True), patch.object(rag, "urlopen") as network:
            rows = rag.capture([self.case()], "http://localhost:8081/api/v1", 5, 1)
            self.assertIn("Missing token", rows[0]["error"])
            network.assert_not_called()

    def test_demo_labels_cannot_be_used_for_live_scoring(self):
        case = self.case(); case["example"] = True
        with self.assertRaises(ValueError): rag.capture([case], "http://localhost", 5, 1)

    def test_live_capture_uses_business_status_and_does_not_store_token_or_text(self):
        observed = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                observed.append(self.headers.get("Authorization"))
                payload = {"code": 200, "data": [{"fileMd5": "a", "chunkId": 0, "textContent": "private text"}]}
                body = json.dumps(payload).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def log_message(self, *args): pass
        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with patch.dict("os.environ", {"RAG_EVAL_TOKEN": "test-secret"}):
                rows = rag.capture([self.case()], "http://127.0.0.1:" + str(server.server_port), 5, 1)
            self.assertEqual(["Bearer test-secret"], observed)
            self.assertNotIn("test-secret", json.dumps(rows))
            self.assertNotIn("private text", json.dumps(rows))
            self.assertEqual(1, rag.evaluate([self.case()], rows, 1)["metrics"]["recall_at_k"])
        finally:
            server.shutdown(); thread.join(); server.server_close()

    def test_cli_emits_report_and_nonzero_on_permission_leak(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "cases.jsonl").write_text(json.dumps(self.case()), encoding="utf-8")
            (folder / "rows.jsonl").write_text(json.dumps(self.row("private")), encoding="utf-8")
            code = rag.main(["--dataset", str(folder / "cases.jsonl"), "--results", str(folder / "rows.jsonl"), "--out", str(folder / "report.json")])
            self.assertEqual(1, code)
            self.assertFalse(json.loads((folder / "report.json").read_text())["gatePassed"])

if __name__ == "__main__": unittest.main()
