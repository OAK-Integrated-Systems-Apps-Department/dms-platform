"""Local LLM runtimes behind one small interface.

- OllamaClient: Ollama's native /api/chat (JSON-Schema `format`, num_ctx,
  keep_alive so the model stays loaded between documents).
- OpenAICompatibleClient: /v1/chat/completions as served by llama.cpp's
  llama-server, vLLM, LM Studio or LocalAI (`response_format` json_schema).

stdlib urllib only (no new dependency), with an opener that ignores
HTTP(S)_PROXY environment variables -- a proxy would be an external hop for
confidential document text. `ensure_local_endpoint` refuses a base URL that
isn't loopback/private/an internal service name unless explicitly allowed.

Transport failures are mapped onto structuring error codes; messages carry
status codes and the server's short error string, never prompt content.
"""
from __future__ import annotations

import ipaddress
import json
import socket
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Protocol
from urllib.parse import urlparse

from .errors import (
    CONTEXT_OVERFLOW,
    LLM_ENDPOINT_NOT_LOCAL,
    LLM_MISCONFIGURED,
    LLM_REQUEST_REJECTED,
    LLM_SERVER_ERROR,
    LLM_TIMEOUT,
    MODEL_UNAVAILABLE,
    StructuringError,
)

_INTERNAL_SUFFIXES = (".local", ".internal", ".localhost", ".svc", ".cluster.local", ".lan", ".home.arpa")


@dataclass
class LlmResponse:
    content: str
    finish_reason: str | None
    prompt_tokens: int | None
    completion_tokens: int | None
    duration_ms: int


class LlmClient(Protocol):
    provider: str
    model: str

    def complete_json(self, messages: list[dict], *, schema: dict | None, max_tokens: int) -> LlmResponse: ...

    def health(self) -> dict: ...

    def warmup(self) -> None: ...


def ensure_local_endpoint(base_url: str, *, allowed_hosts: str = "", allow_non_local: bool = False) -> None:
    parsed = urlparse(base_url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme not in ("http", "https") or not host:
        raise StructuringError(LLM_MISCONFIGURED, f"LLM_BASE_URL must be an http(s) URL, got scheme {parsed.scheme!r}")
    if allow_non_local:
        return
    allowed = {h.strip().lower() for h in allowed_hosts.split(",") if h.strip()}
    if host in allowed or host == "localhost" or host.endswith(_INTERNAL_SUFFIXES):
        return
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        # A single-label name ("ollama", "llm-server") is a docker-compose /
        # kubernetes service on the same private network.
        if "." not in host:
            return
        raise StructuringError(
            LLM_ENDPOINT_NOT_LOCAL,
            f"LLM host {host!r} is not a local/private address; documents are never sent to external APIs "
            "(add it to LLM_ALLOWED_HOSTS if it is an internal host)",
        ) from None
    if ip.is_loopback or ip.is_private or ip.is_link_local:
        return
    raise StructuringError(LLM_ENDPOINT_NOT_LOCAL, f"LLM host {host} is a public address; documents are never sent to external APIs")


class _JsonHttp:
    def __init__(self, base_url: str, timeout_seconds: float, headers: dict[str, str] | None = None):
        self._base = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._headers = {"Content-Type": "application/json", "Accept": "application/json", **(headers or {})}
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, method: str, path: str, payload: dict | None = None, timeout: float | None = None) -> dict:
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(self._base + path, data=body, method=method, headers=self._headers)
        try:
            with self._opener.open(request, timeout=timeout or self._timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise _map_http_error(exc.code, _server_message(exc)) from None
        except (TimeoutError, socket.timeout):
            raise StructuringError(LLM_TIMEOUT, f"no reply from the model server within {timeout or self._timeout:.0f}s") from None
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, (TimeoutError, socket.timeout)):
                raise StructuringError(LLM_TIMEOUT, f"no reply from the model server within {timeout or self._timeout:.0f}s") from None
            raise StructuringError(MODEL_UNAVAILABLE, f"model server unreachable at {self._base} ({type(exc.reason).__name__})") from None
        except (ConnectionError, OSError) as exc:
            raise StructuringError(MODEL_UNAVAILABLE, f"model server connection failed ({type(exc).__name__})") from None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            raise StructuringError(LLM_SERVER_ERROR, "model server returned a non-JSON HTTP body") from None


def _server_message(exc: urllib.error.HTTPError) -> str:
    try:
        body = json.loads(exc.read()[:4096] or b"{}")
    except (ValueError, OSError):
        return ""
    error = body.get("error") if isinstance(body, dict) else None
    if isinstance(error, dict):
        error = error.get("message")
    return str(error or "")[:200]


def _map_http_error(status: int, message: str) -> StructuringError:
    lowered = message.lower()
    detail = f"HTTP {status}" + (f": {message}" if message else "")
    if status == 404 or "not found" in lowered:
        return StructuringError(MODEL_UNAVAILABLE, f"model not available on the server ({detail})")
    if "memory" in lowered or status == 503:
        return StructuringError(MODEL_UNAVAILABLE, f"model server cannot load the model ({detail})")
    if "context" in lowered and ("length" in lowered or "exceed" in lowered or "window" in lowered):
        return StructuringError(CONTEXT_OVERFLOW, f"prompt exceeds the model context ({detail})")
    if status == 408 or status == 504:
        return StructuringError(LLM_TIMEOUT, f"model server timed out ({detail})")
    if 400 <= status < 500:
        return StructuringError(LLM_REQUEST_REJECTED, f"model server rejected the request ({detail})")
    return StructuringError(LLM_SERVER_ERROR, f"model server error ({detail})")


class OllamaClient:
    provider = "ollama"

    def __init__(self, *, base_url: str, model: str, timeout_seconds: float, context_tokens: int, temperature: float, seed: int, keep_alive: str):
        self.model = model
        self._http = _JsonHttp(base_url, timeout_seconds)
        self._context_tokens = context_tokens
        self._temperature = temperature
        self._seed = seed
        self._keep_alive = keep_alive

    def complete_json(self, messages: list[dict], *, schema: dict | None, max_tokens: int) -> LlmResponse:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "format": schema if schema is not None else "json",
            "keep_alive": self._keep_alive,
            "options": {
                "temperature": self._temperature,
                "seed": self._seed,
                "num_ctx": self._context_tokens,
                "num_predict": max_tokens,
            },
        }
        started = time.monotonic()
        body = self._http.request("POST", "/api/chat", payload)
        message = body.get("message") or {}
        return LlmResponse(
            content=message.get("content") or "",
            finish_reason=body.get("done_reason"),
            prompt_tokens=body.get("prompt_eval_count"),
            completion_tokens=body.get("eval_count"),
            duration_ms=int((time.monotonic() - started) * 1000),
        )

    def health(self) -> dict:
        body = self._http.request("GET", "/api/tags", timeout=10)
        names = {m.get("name") for m in body.get("models", [])} | {m.get("model") for m in body.get("models", [])}
        wanted = {self.model, f"{self.model}:latest"} if ":" not in self.model else {self.model}
        return {"reachable": True, "model_available": bool(names & wanted)}

    def warmup(self) -> None:
        # An empty prompt just loads the model and pins it for keep_alive.
        self._http.request("POST", "/api/generate", {"model": self.model, "prompt": "", "keep_alive": self._keep_alive, "stream": False})


class OpenAICompatibleClient:
    provider = "openai_compatible"

    def __init__(self, *, base_url: str, model: str, timeout_seconds: float, temperature: float, seed: int, api_key: str = ""):
        self.model = model
        base = base_url.rstrip("/")
        self._prefix = "" if base.endswith("/v1") else "/v1"
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._http = _JsonHttp(base, timeout_seconds, headers)
        self._temperature = temperature
        self._seed = seed

    def complete_json(self, messages: list[dict], *, schema: dict | None, max_tokens: int) -> LlmResponse:
        response_format = (
            {"type": "json_schema", "json_schema": {"name": "document_extraction", "schema": schema}}
            if schema is not None
            else {"type": "json_object"}
        )
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "temperature": self._temperature,
            "seed": self._seed,
            "max_tokens": max_tokens,
            "response_format": response_format,
        }
        started = time.monotonic()
        body = self._http.request("POST", f"{self._prefix}/chat/completions", payload)
        choices = body.get("choices") or [{}]
        usage = body.get("usage") or {}
        return LlmResponse(
            content=(choices[0].get("message") or {}).get("content") or "",
            finish_reason=choices[0].get("finish_reason"),
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            duration_ms=int((time.monotonic() - started) * 1000),
        )

    def health(self) -> dict:
        body = self._http.request("GET", f"{self._prefix}/models", timeout=10)
        ids = {m.get("id") for m in body.get("data", [])}
        # llama-server reports whatever file it loaded; a single served model
        # is "the" model regardless of its id.
        return {"reachable": True, "model_available": self.model in ids or len(ids) == 1}

    def warmup(self) -> None:
        # These servers load their model at process start; nothing to do.
        return None


def build_llm_client(settings) -> LlmClient:
    """Raises StructuringError(LLM_ENDPOINT_NOT_LOCAL / LLM_MISCONFIGURED)
    for a configuration that must not be used."""
    ensure_local_endpoint(settings.llm_base_url, allowed_hosts=settings.llm_allowed_hosts, allow_non_local=settings.llm_allow_non_local)
    if not settings.llm_model.strip():
        raise StructuringError(LLM_MISCONFIGURED, "LLM_MODEL is empty")
    if settings.llm_provider == "ollama":
        return OllamaClient(
            base_url=settings.llm_base_url,
            model=settings.llm_model,
            timeout_seconds=settings.llm_timeout_seconds,
            context_tokens=settings.llm_context_tokens,
            temperature=settings.llm_temperature,
            seed=settings.llm_seed,
            keep_alive=settings.llm_keep_alive,
        )
    if settings.llm_provider == "openai_compatible":
        return OpenAICompatibleClient(
            base_url=settings.llm_base_url,
            model=settings.llm_model,
            timeout_seconds=settings.llm_timeout_seconds,
            temperature=settings.llm_temperature,
            seed=settings.llm_seed,
            api_key=settings.llm_api_key,
        )
    raise StructuringError(LLM_MISCONFIGURED, f"unknown LLM_PROVIDER {settings.llm_provider!r} (expected 'ollama' or 'openai_compatible')")
