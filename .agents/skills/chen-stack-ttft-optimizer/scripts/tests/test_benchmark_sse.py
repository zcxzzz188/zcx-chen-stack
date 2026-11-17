import importlib.util
import io
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).resolve().parents[1] / "benchmark_sse.py"
SPEC = importlib.util.spec_from_file_location("benchmark_sse", SCRIPT)
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class BenchmarkHelpersTest(unittest.TestCase):
    def test_nearest_rank_percentile(self):
        self.assertEqual(benchmark.percentile([5, 1, 4, 2, 3], 0.5), 3)
        self.assertEqual(benchmark.percentile([5, 1, 4, 2, 3], 0.95), 5)
        self.assertIsNone(benchmark.percentile([], 0.95))

    def test_sse_multiline_and_comment_frames(self):
        raw = b": keepalive\r\nevent: ANSWER\r\ndata: {\r\ndata: \"type\":\"ANSWER\",\r\ndata: \"text\":\"Hi\"}\r\n\r\n"
        frames = list(benchmark.sse_events(io.BytesIO(raw).readlines()))
        self.assertEqual(frames, [("ANSWER", '{\n"type":"ANSWER",\n"text":"Hi"}')])
        self.assertEqual(benchmark.body_text("ANSWER", json.loads(frames[0][1]), {"ANSWER"}), "Hi")

    def test_non_answer_events_are_not_first_body(self):
        self.assertIsNone(benchmark.body_text("KNOWLEDGE_SOURCES", {"type": "KNOWLEDGE_SOURCES", "text": "source"}, {"ANSWER"}))

    def test_request_once_measures_local_sse_without_logging_payload(self):
        received = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received["authorization"] = self.headers.get("Authorization")
                received["payload"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                self.wfile.write(b'event: customer-service\ndata: {"type":"TEXT","text":"ok"}\n\n')
                self.wfile.write(b'event: customer-service\ndata: {"type":"DONE"}\n\n')

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        token_name = "CHEN_STACK_TEST_TOKEN"
        previous = os.environ.get(token_name)
        os.environ[token_name] = "test-only"
        try:
            args = SimpleNamespace(
                url=f"http://127.0.0.1:{server.server_port}/chat",
                token_env=token_name,
                scenario="faq",
                timeout=2,
                content_types={"TEXT"},
            )
            row = benchmark.request_once(args, {"message": "{{message}}", "chatId": "{{runId}}"}, "fixed", 1)
        finally:
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()
            if previous is None:
                os.environ.pop(token_name, None)
            else:
                os.environ[token_name] = previous
        self.assertEqual(received["authorization"], "Bearer test-only")
        self.assertEqual(received["payload"]["message"], "fixed")
        self.assertTrue(received["payload"]["chatId"].startswith("ttft-faq-1-"))
        self.assertEqual(received["payload"]["requestId"], received["payload"]["chatId"])
        self.assertEqual(row["chat_id"], received["payload"]["chatId"])
        self.assertEqual(row["request_id"], received["payload"]["requestId"])
        self.assertEqual(row["http_status"], 200)
        self.assertIsNotNone(row["sse_first_body_ms"])
        self.assertTrue(row["done_event_seen"])
        self.assertIsNone(row["model_calls"])
        self.assertIsNone(row["error"])


if __name__ == "__main__":
    unittest.main()
