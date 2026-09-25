"""LLM transport against a real local HTTP server (stdlib http.server on a
background thread) standing in for Ollama / an OpenAI-compatible runtime:
request payloads, error-code mapping, timeouts, proxy bypass, and the
local-endpoint guard."""
from __future__ import annotations

import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.structuring.errors import StructuringError  # noqa: E402
from ocr_worker.structuring.llm_client import (  # noqa: E402
    OllamaClient,
    OpenAICompatibleClient,
    build_llm_client,
    ensure_local_endpoint,
)
from ocr_worker.structuring.schema import EXTRACTION_JSON_SCHEMA  # noqa: E402


class FakeServer:
    """Records each request; `respond` returns (status, body_dict, delay_s)."""

    def __init__(self, respond):
        self.requests: list[dict] = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                server.requests.append({"method": self.command, "path": self.path, "body": body, "headers": dict(self.headers)})
                status, payload, delay = respond(self.command, self.path, body)
                time.sleep(delay)
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            do_GET = do_POST = _handle

            def log_message(self, *args):
                pass

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._httpd.server_address[1]}"
        threading.Thread(target=self._httpd.serve_forever, daemon=True).start()

    def close(self):
        self._httpd.shutdown()
        self._httpd.server_close()


@pytest.fixture()
def server_factory():
    servers = []

    def make(respond):
        server = FakeServer(respond)
        servers.append(server)
        return server

    yield make
    for server in servers:
        server.close()


def _ollama(url, timeout=5.0):
    return OllamaClient(base_url=url, model="qwen2.5:7b-instruct", timeout_seconds=timeout, context_tokens=8192, temperature=0.0, seed=42, keep_alive="30m")


MESSAGES = [{"role": "system", "content": "sys"}, {"role": "user", "content": "doc"}]


def test_ollama_request_carries_schema_context_and_keep_alive(server_factory):
    reply = {"message": {"role": "assistant", "content": '{"fields": {}}'}, "done_reason": "stop", "prompt_eval_count": 812, "eval_count": 40}
    server = server_factory(lambda m, p, b: (200, reply, 0))
    response = _ollama(server.url).complete_json(MESSAGES, schema=EXTRACTION_JSON_SCHEMA, max_tokens=2048)

    sent = server.requests[0]
    assert sent["path"] == "/api/chat" and sent["method"] == "POST"
    body = sent["body"]
    assert body["model"] == "qwen2.5:7b-instruct" and body["stream"] is False
    assert body["format"] == EXTRACTION_JSON_SCHEMA
    assert body["keep_alive"] == "30m"
    assert body["options"] == {"temperature": 0.0, "seed": 42, "num_ctx": 8192, "num_predict": 2048}
    assert response.content == '{"fields": {}}' and response.finish_reason == "stop"
    assert (response.prompt_tokens, response.completion_tokens) == (812, 40)


def test_ollama_without_schema_falls_back_to_plain_json_mode(server_factory):
    server = server_factory(lambda m, p, b: (200, {"message": {"content": "{}"}, "done_reason": "stop"}, 0))
    _ollama(server.url).complete_json(MESSAGES, schema=None, max_tokens=10)
    assert server.requests[0]["body"]["format"] == "json"


@pytest.mark.parametrize(
    "status, error, code",
    [
        (404, "model 'qwen2.5:7b-instruct' not found, try pulling it first", "MODEL_UNAVAILABLE"),
        (500, "model requires more system memory (9.1 GiB) than is available (4.0 GiB)", "MODEL_UNAVAILABLE"),
        (400, "input length exceeds the context window", "CONTEXT_OVERFLOW"),
        (400, "invalid format", "LLM_REQUEST_REJECTED"),
        (500, "unexpected server error", "LLM_SERVER_ERROR"),
    ],
)
def test_http_errors_map_to_structuring_codes(server_factory, status, error, code):
    server = server_factory(lambda m, p, b: (status, {"error": error}, 0))
    with pytest.raises(StructuringError) as info:
        _ollama(server.url).complete_json(MESSAGES, schema=None, max_tokens=10)
    assert info.value.code == code
    assert str(status) in info.value.message


def test_slow_model_raises_llm_timeout(server_factory):
    server = server_factory(lambda m, p, b: (200, {"message": {"content": "{}"}}, 2.0))
    with pytest.raises(StructuringError) as info:
        _ollama(server.url, timeout=0.5).complete_json(MESSAGES, schema=None, max_tokens=10)
    assert info.value.code == "LLM_TIMEOUT"


def test_unreachable_server_raises_model_unavailable():
    # Nothing listens on port 9 (discard) on the loopback interface.
    with pytest.raises(StructuringError) as info:
        _ollama("http://127.0.0.1:9", timeout=2).complete_json(MESSAGES, schema=None, max_tokens=10)
    assert info.value.code == "MODEL_UNAVAILABLE"


def test_proxy_environment_variables_are_ignored(server_factory, monkeypatch):
    # A proxy would be an extra hop for confidential text; the client must
    # talk to the model server directly even when a proxy is configured.
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:9")
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:9")
    server = server_factory(lambda m, p, b: (200, {"message": {"content": "{}"}, "done_reason": "stop"}, 0))
    assert _ollama(server.url).complete_json(MESSAGES, schema=None, max_tokens=10).content == "{}"


def test_ollama_health_checks_that_the_model_is_pulled(server_factory):
    tags = {"models": [{"name": "qwen2.5:7b-instruct", "model": "qwen2.5:7b-instruct"}]}
    server = server_factory(lambda m, p, b: (200, tags, 0))
    assert _ollama(server.url).health() == {"reachable": True, "model_available": True}
    other = OllamaClient(base_url=server.url, model="llama3.1:8b", timeout_seconds=5, context_tokens=8192, temperature=0, seed=1, keep_alive="5m")
    assert other.health()["model_available"] is False


def test_openai_compatible_request_and_response(server_factory):
    reply = {
        "choices": [{"message": {"role": "assistant", "content": '{"fields": {}}'}, "finish_reason": "length"}],
        "usage": {"prompt_tokens": 100, "completion_tokens": 2048},
    }
    server = server_factory(lambda m, p, b: (200, reply, 0))
    client = OpenAICompatibleClient(base_url=server.url + "/v1", model="local-model", timeout_seconds=5, temperature=0.0, seed=7, api_key="k")
    response = client.complete_json(MESSAGES, schema=EXTRACTION_JSON_SCHEMA, max_tokens=2048)

    sent = server.requests[0]
    assert sent["path"] == "/v1/chat/completions"
    assert sent["headers"]["Authorization"] == "Bearer k"
    assert sent["body"]["response_format"]["type"] == "json_schema"
    assert sent["body"]["response_format"]["json_schema"]["schema"] == EXTRACTION_JSON_SCHEMA
    assert sent["body"]["max_tokens"] == 2048
    assert response.finish_reason == "length" and response.completion_tokens == 2048


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost:11434",
        "http://127.0.0.1:11434",
        "http://ollama:11434",
        "http://10.0.0.5:8080",
        "http://192.168.1.20:11434",
        "http://host.docker.internal:11434",
        "http://ollama.dms.svc.cluster.local:11434",
        "http://[::1]:11434",
    ],
)
def test_local_and_private_endpoints_are_allowed(url):
    ensure_local_endpoint(url)


@pytest.mark.parametrize("url", ["https://api.openai.com/v1", "http://8.8.8.8:11434", "https://llm.example.com"])
def test_external_endpoints_are_refused(url):
    with pytest.raises(StructuringError) as info:
        ensure_local_endpoint(url)
    assert info.value.code == "LLM_ENDPOINT_NOT_LOCAL"


def test_external_endpoint_needs_an_explicit_allow_listing():
    ensure_local_endpoint("https://llm.example.com", allowed_hosts="llm.example.com")
    ensure_local_endpoint("https://llm.example.com", allow_non_local=True)
    with pytest.raises(StructuringError):
        ensure_local_endpoint("ftp://localhost")


def test_build_llm_client_uses_configuration_only():
    ollama = build_llm_client(Settings(llm_provider="ollama", llm_model="mistral:7b-instruct", llm_base_url="http://ollama:11434"))
    assert isinstance(ollama, OllamaClient) and ollama.model == "mistral:7b-instruct"
    compat = build_llm_client(Settings(llm_provider="openai_compatible", llm_model="qwen", llm_base_url="http://llama-server:8080"))
    assert isinstance(compat, OpenAICompatibleClient)
    with pytest.raises(StructuringError) as info:
        build_llm_client(Settings(llm_provider="hosted-api"))
    assert info.value.code == "LLM_MISCONFIGURED"
    with pytest.raises(StructuringError) as info:
        build_llm_client(Settings(llm_base_url="https://api.openai.com"))
    assert info.value.code == "LLM_ENDPOINT_NOT_LOCAL"
