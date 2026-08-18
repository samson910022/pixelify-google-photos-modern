"""Unified LLM Client supporting OpenCode and CPA providers with retry, streaming, and response gating."""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable

ChatMessage = dict[str, Any]
ChatContent = str | list[dict[str, Any]]

DEFAULT_TIMEOUT_SECONDS = 300
DEFAULT_MIN_RESPONSE_CHARS = 0
DEFAULT_REJECT_FINISH_REASONS = ("length", "max_tokens", "content_filter")


class LLMClientError(RuntimeError):
    """Raised when an LLM call fails or returns an unusable completion."""


def load_dotenv(dotenv_path: str | Path | None = None) -> dict[str, str]:
    """Simple .env parser using standard library. Bypassed in CI environments."""
    if os.environ.get("GITHUB_ACTIONS") or os.environ.get("CI"):
        return {}
    loaded: dict[str, str] = {}
    paths_to_check: list[Path] = []
    if dotenv_path:
        paths_to_check.append(Path(dotenv_path))
    else:
        paths_to_check.extend([
            Path.cwd() / ".env",
            Path(__file__).resolve().parents[2] / ".env",
            Path(__file__).resolve().parents[1] / ".env",
        ])

    for path in paths_to_check:
        if path.is_file():
            try:
                for line in path.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if not line or line.startswith("#"):
                        continue
                    if "=" in line:
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip("'\"")
                        if k and k not in os.environ:
                            os.environ[k] = v
                            loaded[k] = v
                break
            except Exception:
                pass
    return loaded


def interpolate_env_vars(text: str) -> str:
    """Interpolate ${VAR_NAME} or $VAR_NAME placeholders from environment variables."""
    load_dotenv()

    def replacer(match: re.Match) -> str:
        var_name = match.group(1) or match.group(2)
        return os.environ.get(var_name, "")

    return re.sub(r"\$\{([A-Za-z0-9_]+)\}|\$([A-Za-z0-9_]+)", replacer, text)


def fetch_models_dev_free_ids(timeout_seconds: int = 8) -> set[str]:
    """Fetch https://models.dev/api.json and extract all free opencode model IDs (cost.input == 0)."""
    free_ids: set[str] = set()
    url = "https://models.dev/api.json"
    headers = {"User-Agent": "pixelify-infinity-ai-review-bot/1.0"}
    try:
        req = urllib.request.Request(url, headers=headers, method="GET")
        with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            opencode_models = (data.get("opencode") or {}).get("models", {})
            for mid, info in opencode_models.items():
                cost = info.get("cost", {})
                if cost.get("input") == 0 or "free" in mid.lower():
                    free_ids.add(mid)
    except Exception:
        # Fallback to known free suffixes if models.dev is unreachable
        pass
    return free_ids


def sanitize_model_name_for_display(model_id: str) -> str:
    """Remove internal model tier suffixes like -high, -free, -extra-low, -low for clean display."""
    if not model_id:
        return ""
    cleaned = re.sub(r"-(high|free|extra-low|low)$", "", model_id, flags=re.IGNORECASE)
    return cleaned


class LLMClient:
    """Multi-provider LLM client for OpenCode Zen and CPA endpoints."""

    def __init__(
        self,
        config_path: str | Path | None = None,
        fallback_models: list[str] | None = None,
        fallback_model: str = "gemini-3.7-flash-high",
        *,
        default_provider: str = "opencode",
        min_response_chars: int = DEFAULT_MIN_RESPONSE_CHARS,
        reject_finish_reasons: list[str] | set[str] | None = None,
        same_model_retry_on_length: int = 1,
        enable_streaming: bool = True,
    ) -> None:
        load_dotenv()
        self.default_provider = default_provider
        self.min_response_chars = int(min_response_chars)
        self.reject_finish_reasons = {
            str(item).lower()
            for item in (reject_finish_reasons or list(DEFAULT_REJECT_FINISH_REASONS))
        }
        self.same_model_retry_on_length = max(0, int(same_model_retry_on_length))
        self.enable_streaming = enable_streaming
        self.providers: dict[str, dict[str, Any]] = {}
        self.models: dict[str, dict[str, Any]] = {}
        self._discovery_cache: dict[str, list[str]] | None = None
        self._discovery_cache_time: float = 0.0
        self._discovery_ttl_seconds: float = 600.0

        root = Path(__file__).resolve().parents[1]
        example = root / "config" / "LLM_config.example.json"
        target = Path(config_path) if config_path else example
        if target.exists():
            self.load_config(target)
        else:
            raise LLMClientError(f"LLM configuration file not found: {target}")

        # Build fallback chain
        chain: list[str] = []
        if fallback_models:
            chain.extend(fallback_models)
        if fallback_model:
            chain.append(fallback_model)
        seen: set[str] = set()
        self.fallback_models: list[str] = []
        for mid in chain:
            if mid and mid not in seen:
                self.fallback_models.append(mid)
                seen.add(mid)

        # Backwards compatibility attributes
        default_pdata = self.providers.get(self.default_provider) or {}
        self.base_url = default_pdata.get("baseUrl", "")
        self.api_key = default_pdata.get("apikey", "")
        self.timeout_seconds = default_pdata.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)

    def load_config(self, config_path: str | Path) -> None:
        """Load and parse LLM providers and model catalog."""
        raw_text = Path(config_path).read_text(encoding="utf-8")
        interpolated = interpolate_env_vars(raw_text)
        data = json.loads(interpolated)

        self.providers = {}
        self.models = {}

        for provider_name, pdata in data.items():
            if not isinstance(pdata, dict):
                continue
            base_url = pdata.get("baseUrl") or ""
            if not base_url and provider_name == "cpa":
                base_url = os.environ.get("CPA_BASE_URL", "")
            if base_url and not base_url.endswith("/"):
                base_url += "/"

            api_key = pdata.get("apikey") or ""
            if not api_key:
                if provider_name == "cpa":
                    api_key = os.environ.get("CPA_API_KEY", "")
                elif provider_name == "opencode":
                    api_key = os.environ.get("OPENCODE_API_KEY", "")

            api_type = pdata.get("api", "openai-completions")
            timeout_seconds = int(pdata.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS))

            self.providers[provider_name] = {
                "name": provider_name,
                "baseUrl": base_url,
                "apikey": api_key,
                "api": api_type,
                "timeoutSeconds": timeout_seconds,
                "models": pdata.get("models", []),
            }

            for m in pdata.get("models", []):
                mid = m.get("id")
                if mid:
                    self.models[mid] = {**m, "_provider": provider_name}

        # Resolve default provider dynamically based on available credentials
        if getattr(self, "default_provider", "opencode") == "opencode":
            cpa_pdata = self.providers.get("cpa", {})
            opencode_pdata = self.providers.get("opencode", {})
            if cpa_pdata.get("apikey") and cpa_pdata.get("baseUrl") and not opencode_pdata.get("apikey"):
                self.default_provider = "cpa"

        default_pdata = self.providers.get(self.default_provider) or {}
        self.base_url = default_pdata.get("baseUrl", "")
        self.api_key = default_pdata.get("apikey", "")
        self.timeout_seconds = default_pdata.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)

    def supports_input(self, model_id: str, modality: str) -> bool:
        """Check if a model supports a specific input modality (e.g. image, text)."""
        model = self.models.get(model_id)
        if not model:
            return False
        inputs = model.get("input") or ["text"]
        return modality in inputs

    def discover_models(self, timeout_seconds: int = 8, *, force_refresh: bool = False) -> dict[str, list[str]]:
        """Dynamically query providers' /models endpoints, cross-filter with models.dev, and register active models."""
        now = time.monotonic()
        if not force_refresh and self._discovery_cache is not None and now - self._discovery_cache_time < self._discovery_ttl_seconds:
            return dict(self._discovery_cache)

        discovered: dict[str, list[str]] = {}
        models_dev_free = fetch_models_dev_free_ids(timeout_seconds=timeout_seconds)

        for pname, pdata in self.providers.items():
            base_url = pdata.get("baseUrl") or ""
            api_key = pdata.get("apikey") or ""
            if not base_url or not api_key:
                continue

            models_url = f"{base_url.rstrip('/')}/models"
            headers = {
                "Authorization": f"Bearer {api_key}",
                "User-Agent": "pixelify-infinity-ai-review-bot/1.0",
            }
            try:
                req = urllib.request.Request(models_url, headers=headers, method="GET")
                with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    model_list: list[str] = []
                    items = data.get("data", []) if isinstance(data, dict) else (data if isinstance(data, list) else [])
                    for item in items:
                        if not isinstance(item, dict):
                            continue
                        mid = item.get("id")
                        if not mid:
                            continue

                        # If provider is opencode, only keep active free models
                        if pname == "opencode":
                            is_free = (mid in models_dev_free) or ("free" in mid.lower())
                            if not is_free:
                                continue

                        model_list.append(mid)
                        if mid not in self.models:
                            self.models[mid] = {
                                "id": mid,
                                "name": item.get("name", mid),
                                "description": f"Dynamically discovered model from {pname}",
                                "_provider": pname,
                                "input": ["text"],
                                "output": ["text"],
                            }
                    discovered[pname] = model_list
            except Exception:
                pass

        self._discovery_cache = discovered
        self._discovery_cache_time = time.monotonic()
        return dict(self._discovery_cache)

    def get_dynamic_fallback_chain(self, configured_fallbacks: list[str] | None = None) -> list[str]:
        """Build prioritized fallback chain: CPA priority -> Configured -> Dynamic OpenCode Free -> Dynamic CPA."""
        chain: list[str] = []

        # 1. Configured fallbacks in prioritized order
        for m in (configured_fallbacks or []):
            if m and m not in chain:
                chain.append(m)

        # 2. Discover live active models from providers
        try:
            discovered = self.discover_models()
        except Exception:
            discovered = {}

        # 3. Append verified active OpenCode free models
        opencode_free = discovered.get("opencode", [])
        for m in opencode_free:
            if m not in chain:
                chain.append(m)

        # 4. Append remaining live CPA models (excluding image generation)
        for m in discovered.get("cpa", []):
            if m not in chain and not m.startswith("vertex/imagen") and not m.startswith("imagen-"):
                chain.append(m)

        return chain

    def get_provider_for_model(self, model_id: str) -> dict[str, Any]:
        """Resolve the provider definition for a specific model ID."""
        default_pname = getattr(self, "default_provider", "opencode")
        providers = getattr(self, "providers", {})
        models = getattr(self, "models", {})
        if model_id in models:
            pname = models[model_id].get("_provider", default_pname)
            return providers.get(pname, {})
        return providers.get(default_pname, {})

    def chat_completion(
        self,
        model_id: str,
        messages: list[ChatMessage],
        *,
        temperature: float = 0.2,
        max_tokens: int = 4096,
        allow_fallback: bool = True,
        fallback_models: list[str] | None = None,
        timeout_seconds: int | None = None,
        min_chars: int | None = None,
        required_markers: list[str] | None = None,
    ) -> str:
        """Call an LLM model with automatic retry on truncation and fallback candidate chain."""
        candidates = [model_id]
        fallbacks_to_use = fallback_models if fallback_models is not None else getattr(self, "fallback_models", [])
        if allow_fallback:
            candidates.extend(m for m in fallbacks_to_use if m != model_id)

        errors: list[str] = []
        if min_chars is None:
            effective_min_chars = int(getattr(self, "min_response_chars", DEFAULT_MIN_RESPONSE_CHARS))
        else:
            effective_min_chars = int(min_chars)

        models = getattr(self, "models", {})

        for candidate in candidates:
            # Multimodal messages should not fall back to text-only models unless content is pure text
            if _messages_have_media(messages) and not self.supports_input(candidate, "image"):
                if candidate != model_id:
                    errors.append(f"{candidate}: skipped (no multimodal input support)")
                    continue

            model_info = models.get(candidate, {"id": candidate})
            model_max = int(model_info.get("maxTokens") or model_info.get("maxOutputTokens") or 65536)

            attempts = 1 + getattr(self, "same_model_retry_on_length", 1)
            current_max = min(max_tokens, model_max)

            for attempt in range(attempts):
                try:
                    return self._single_call(
                        candidate,
                        messages,
                        temperature=temperature,
                        max_tokens=current_max,
                        timeout_seconds=timeout_seconds,
                        min_chars=effective_min_chars,
                        required_markers=required_markers,
                    )
                except LLMClientError as exc:
                    reason = str(exc).lower()
                    if attempt + 1 < attempts and ("finish_reason=length" in reason or "truncated" in reason or "too short" in reason):
                        current_max = min(max(current_max * 2, current_max + 2048), model_max)
                        continue
                    errors.append(f"{candidate}: {exc}")
                    break

        raise LLMClientError("; ".join(errors) if errors else f"No model candidates for {model_id}")

    def call_model(
        self,
        model_id: str,
        messages: list[ChatMessage],
        *,
        temperature: float = 0.2,
        max_tokens: int = 4096,
        timeout_seconds: int | None = None,
        min_chars: int = DEFAULT_MIN_RESPONSE_CHARS,
        required_markers: list[str] | None = None,
        fallback_models: list[str] | None = None,
    ) -> str:
        """Alternative entry point to chat_completion."""
        fallbacks = fallback_models if fallback_models is not None else getattr(self, "fallback_models", [])
        return self.chat_completion(
            model_id,
            messages,
            temperature=temperature,
            max_tokens=max_tokens,
            allow_fallback=bool(fallbacks),
            fallback_models=fallbacks,
            timeout_seconds=timeout_seconds,
            min_chars=min_chars,
            required_markers=required_markers,
        )

    def _single_call(
        self,
        model_id: str,
        messages: list[ChatMessage],
        *,
        temperature: float,
        max_tokens: int,
        timeout_seconds: int | None,
        min_chars: int,
        required_markers: list[str] | None,
    ) -> str:
        provider = self.get_provider_for_model(model_id)
        base_url = provider.get("baseUrl") or getattr(self, "base_url", "")
        api_key = provider.get("apikey") or getattr(self, "api_key", "")
        api_type = provider.get("api", "openai-completions")
        timeout = timeout_seconds if timeout_seconds is not None else provider.get("timeoutSeconds", getattr(self, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS))

        if not base_url:
            raise LLMClientError(f"Base URL is not configured for provider '{provider.get('name')}'")
        if not api_key:
            raise LLMClientError(f"API key is missing for provider '{provider.get('name')}' (set OPENCODE_API_KEY or CPA_API_KEY)")

        models = getattr(self, "models", {})
        model_info = models.get(model_id, {"id": model_id})
        prepared_messages = _prepare_messages_for_model(messages, model_info)
        reasoning_effort = model_info.get("reasoningEffort")
        thinking_cfg = model_info.get("thinking")

        # Check API endpoints (CPA responses vs OpenAI chat/completions)
        if api_type == "responses" or base_url.rstrip("/").endswith("/responses"):
            endpoint = f"{base_url}responses" if not base_url.rstrip("/").endswith("/responses") else base_url
            body: dict[str, Any] = {
                "model": model_id,
                "input": prepared_messages,
                "temperature": temperature,
                "max_output_tokens": max_tokens,
            }
            if reasoning_effort:
                body["reasoning_effort"] = reasoning_effort
        else:
            endpoint = f"{base_url}chat/completions" if not base_url.rstrip("/").endswith("/chat/completions") else base_url
            body = {
                "model": model_id,
                "messages": prepared_messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
            }
            if reasoning_effort:
                body["reasoning_effort"] = reasoning_effort
            if thinking_cfg:
                body["thinking"] = thinking_cfg

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "User-Agent": "pixelify-infinity-ai-review-bot/1.0",
        }
        if getattr(self, "enable_streaming", True):
            body["stream"] = True
            headers["Accept"] = "text/event-stream"

        req_data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(endpoint, data=req_data, headers=headers, method="POST")

        content = ""
        finish_reason = None
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                headers_obj = getattr(resp, "headers", None)
                content_type = headers_obj.get("Content-Type", "") if headers_obj is not None else ""
                if "text/event-stream" in content_type:
                    content, finish_reason = _parse_sse_stream(resp, api_type)
                else:
                    resp_bytes = resp.read()
                    payload = json.loads(resp_bytes.decode("utf-8"))
                    content, finish_reason = _extract_response_content_and_reason(payload, api_type)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            # If responses endpoint failed with 404, attempt fallback to chat/completions
            if exc.code == 404 and "responses" in endpoint:
                return self._fallback_openai_call(
                    base_url, api_key, model_id, prepared_messages, temperature, max_tokens, timeout, min_chars, required_markers
                )
            raise LLMClientError(f"HTTP {exc.code} from {endpoint}: {detail[:500]}") from exc
        except Exception as exc:
            raise LLMClientError(f"LLM request to {endpoint} failed: {exc}") from exc

        reject_finish_reasons = getattr(self, "reject_finish_reasons", DEFAULT_REJECT_FINISH_REASONS)
        rejection = unusable_completion_reason(
            content,
            finish_reason,
            min_chars=min_chars,
            required_markers=required_markers,
            reject_finish_reasons=reject_finish_reasons,
        )
        if rejection:
            raise LLMClientError(rejection)
        return content

    def _fallback_openai_call(
        self,
        base_url: str,
        api_key: str,
        model_id: str,
        messages: list[ChatMessage],
        temperature: float,
        max_tokens: int,
        timeout: int,
        min_chars: int,
        required_markers: list[str] | None,
    ) -> str:
        endpoint = f"{base_url}chat/completions"
        body = {
            "model": model_id,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": getattr(self, "enable_streaming", True),
        }
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "User-Agent": "pixelify-infinity-ai-review-bot/1.0",
        }
        if getattr(self, "enable_streaming", True):
            headers["Accept"] = "text/event-stream"

        req = urllib.request.Request(endpoint, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
        content = ""
        finish_reason = None
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                headers_obj = getattr(resp, "headers", None)
                content_type = headers_obj.get("Content-Type", "") if headers_obj is not None else ""
                if "text/event-stream" in content_type:
                    content, finish_reason = _parse_sse_stream(resp, "openai-completions")
                else:
                    payload = json.loads(resp.read().decode("utf-8"))
                    content, finish_reason = _extract_response_content_and_reason(payload, "openai-completions")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise LLMClientError(f"HTTP {exc.code} from fallback {endpoint}: {detail[:500]}") from exc
        except Exception as exc:
            raise LLMClientError(f"LLM fallback request to {endpoint} failed: {exc}") from exc

        reject_finish_reasons = getattr(self, "reject_finish_reasons", DEFAULT_REJECT_FINISH_REASONS)
        rejection = unusable_completion_reason(
            content, finish_reason, min_chars=min_chars, required_markers=required_markers, reject_finish_reasons=reject_finish_reasons
        )
        if rejection:
            raise LLMClientError(rejection)
        return content


def _parse_sse_stream(
    resp: Any,
    api_type: str,
    on_chunk: Callable[[str], None] | None = None,
    print_progress: bool = False,
) -> tuple[str, str | None]:
    """Parse Server-Sent Events (SSE) stream chunks."""
    chunks: list[str] = []
    finish_reason: str | None = None

    for raw_line in resp:
        line_str = raw_line.decode("utf-8", errors="replace").strip()
        if not line_str or line_str.startswith(":"):
            continue
        if line_str.startswith("data:"):
            data_body = line_str[5:].strip()
            if data_body == "[DONE]":
                break
            try:
                chunk = json.loads(data_body)
                piece = ""
                # 1. CPA responses format
                if "delta" in chunk and isinstance(chunk["delta"], str):
                    piece = chunk["delta"]
                elif chunk.get("type") == "response.output_text.delta" and "delta" in chunk:
                    piece = str(chunk["delta"])
                # 2. OpenAI chat/completions format
                elif "choices" in chunk and isinstance(chunk["choices"], list) and chunk["choices"]:
                    c = chunk["choices"][0]
                    delta = c.get("delta") or {}
                    if "content" in delta and isinstance(delta["content"], str):
                        piece = delta["content"]
                    if c.get("finish_reason"):
                        finish_reason = str(c["finish_reason"]).lower()
                elif "response" in chunk and isinstance(chunk["response"], dict):
                    if chunk["response"].get("status"):
                        finish_reason = str(chunk["response"]["status"]).lower()

                if piece:
                    chunks.append(piece)
                    if on_chunk:
                        on_chunk(piece)
                    elif print_progress:
                        sys.stdout.write(piece)
                        sys.stdout.flush()
            except Exception:
                pass

    if print_progress and chunks:
        sys.stdout.write("\n")
        sys.stdout.flush()

    return "".join(chunks).strip(), finish_reason


def _extract_response_content_and_reason(payload: dict[str, Any], api_type: str = "openai-completions") -> tuple[str, str | None]:
    """Extract output text and finish_reason from varied JSON payload formats."""
    # 1. Standard OpenAI format
    if "choices" in payload and isinstance(payload["choices"], list) and payload["choices"]:
        choice = payload["choices"][0]
        finish_reason = choice.get("finish_reason") or choice.get("native_finish_reason")
        content = (choice.get("message") or {}).get("content", "")
        return _normalize_content(content), str(finish_reason).lower() if finish_reason else None

    # 2. CPA responses API format
    if "output" in payload:
        texts: list[str] = []
        finish_reason = payload.get("finish_reason") or payload.get("status")
        for item in payload.get("output", []):
            if isinstance(item, dict):
                if item.get("type") == "message" and isinstance(item.get("content"), list):
                    for part in item["content"]:
                        if isinstance(part, dict) and part.get("type") == "output_text":
                            texts.append(str(part.get("text", "")))
                        elif isinstance(part, str):
                            texts.append(part)
                elif item.get("type") == "output_text" and "text" in item:
                    texts.append(str(item["text"]))
                elif "text" in item:
                    texts.append(str(item["text"]))
        full_text = "\n".join(texts).strip()
        return full_text, str(finish_reason).lower() if finish_reason else None

    # 3. Direct content or text
    if "content" in payload and isinstance(payload["content"], str):
        return payload["content"].strip(), None

    return "", "empty_payload"


def _normalize_content(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                parts.append(str(part.get("text", "")))
            else:
                parts.append(str(part))
        return "\n".join(parts).strip()
    return str(content).strip()


def _messages_have_media(messages: list[ChatMessage]) -> bool:
    for message in messages:
        content = message.get("content")
        if isinstance(content, list):
            for part in content:
                if not isinstance(part, dict):
                    continue
                if part.get("type") in {"image_url", "input_image", "video_url", "audio_url", "input_audio"}:
                    return True
                if "image_url" in part or "video_url" in part or "audio_url" in part:
                    return True
    return False


def _prepare_messages_for_model(messages: list[ChatMessage], model_info: dict[str, Any]) -> list[ChatMessage]:
    supports_multimodal = any(t in (model_info.get("input") or ["text"]) for t in ["image", "video", "audio"])
    requires_string = bool((model_info.get("compat") or {}).get("requiresStringContent"))

    prepared: list[ChatMessage] = []
    for msg in messages:
        role = msg.get("role", "user")
        content = msg.get("content", "")
        if isinstance(content, list):
            if supports_multimodal and not requires_string:
                prepared.append({"role": role, "content": content})
            else:
                prepared.append({"role": role, "content": _flatten_content(content)})
        else:
            prepared.append({"role": role, "content": str(content)})
    return prepared


def _flatten_content(content: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for part in content:
        if not isinstance(part, dict):
            parts.append(str(part))
            continue
        ptype = part.get("type")
        if ptype == "text":
            parts.append(str(part.get("text", "")))
        elif ptype in {"image_url", "input_image"}:
            url = ((part.get("image_url") or {}).get("url")) or part.get("url") or ""
            parts.append(f"[image omitted for text-only model: {url[:120]}]")
        elif ptype in {"video_url", "input_video"}:
            url = ((part.get("video_url") or {}).get("url")) or part.get("url") or ""
            parts.append(f"[video omitted for text-only model: {url[:120]}]")
        elif ptype in {"audio_url", "input_audio"}:
            parts.append("[audio omitted for text-only model]")
        else:
            parts.append(str(part))
    return "\n".join(p for p in parts if p).strip()


def unusable_completion_reason(
    text: str,
    finish_reason: str | None,
    *,
    min_chars: int = DEFAULT_MIN_RESPONSE_CHARS,
    required_markers: list[str] | None = None,
    reject_finish_reasons: set[str] | None = None,
) -> str | None:
    """Validate that the completion is complete, not truncated, and meets markers."""
    reject_reasons = reject_finish_reasons or set(DEFAULT_REJECT_FINISH_REASONS)
    reason = (finish_reason or "").lower()
    if reason in reject_reasons:
        return f"unusable completion (finish_reason={reason})"

    content = (text or "").strip()
    if not content:
        return "LLM returned empty content"
    if len(content) < int(min_chars):
        return f"unusable completion (too short: {len(content)} < {min_chars} chars)"
    if content.endswith(("...", "…")) and len(content) < max(min_chars * 2, 600):
        return "unusable completion (appears truncated)"

    for marker in required_markers or []:
        if marker and marker.lower() not in content.lower():
            return f"unusable completion (missing required marker: {marker})"
    return None
