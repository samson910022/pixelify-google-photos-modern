"""OpenCode Zen OpenAI-compatible client for free review models."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ChatContent = str | list[dict[str, Any]]
ChatMessage = dict[str, Any]


class LLMClientError(RuntimeError):
    """Raised when an LLM request fails."""


class LLMClient:
    def __init__(
        self,
        config_path: Path | None = None,
        fallback_models: list[str] | None = None,
        fallback_model: str = "deepseek-v4-flash-free",
    ) -> None:
        root = Path(__file__).resolve().parents[1]
        example = root / "config" / "LLM_config.example.json"
        target = config_path or example
        if not target.exists():
            raise LLMClientError(f"LLM configuration file not found: {target}")

        data = json.loads(target.read_text(encoding="utf-8"))
        provider = data["opencode"]
        api_key = os.environ.get("OPENCODE_API_KEY") or provider.get("apikey", "")
        if api_key == "${OPENCODE_API_KEY}":
            api_key = ""

        self.base_url = str(provider["baseUrl"]).rstrip("/") + "/"
        self.timeout_seconds = int(provider.get("timeoutSeconds", 180))
        self.api_key = api_key
        self.models = {m["id"]: m for m in provider.get("models", [])}

        chain: list[str] = []
        if fallback_models:
            chain.extend(fallback_models)
        if fallback_model:
            chain.append(fallback_model)
        # Preserve order, unique.
        seen: set[str] = set()
        self.fallback_models = []
        for model_id in chain:
            if model_id and model_id not in seen:
                self.fallback_models.append(model_id)
                seen.add(model_id)

    def supports_input(self, model_id: str, modality: str) -> bool:
        model = self.models.get(model_id)
        if not model:
            return False
        inputs = model.get("input") or ["text"]
        return modality in inputs

    def chat_completion(
        self,
        model_id: str,
        messages: list[ChatMessage],
        *,
        temperature: float = 0.2,
        max_tokens: int = 4096,
        allow_fallback: bool = True,
        timeout_seconds: int | None = None,
    ) -> str:
        candidates = [model_id]
        if allow_fallback:
            candidates.extend(m for m in self.fallback_models if m != model_id)

        errors: list[str] = []
        for candidate in candidates:
            # Multimodal messages should not fall back to text-only models unless content is pure text.
            if _messages_have_media(messages) and not self.supports_input(candidate, "image"):
                # Still allow if only text remains after coercion failure path.
                if candidate != model_id:
                    errors.append(f"{candidate}: skipped (no multimodal input support)")
                    continue
            try:
                return self._single_call(
                    candidate,
                    messages,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    timeout_seconds=timeout_seconds,
                )
            except LLMClientError as exc:
                errors.append(f"{candidate}: {exc}")
                continue
        raise LLMClientError("; ".join(errors) if errors else f"No model candidates for {model_id}")

    def _single_call(
        self,
        model_id: str,
        messages: list[ChatMessage],
        *,
        temperature: float,
        max_tokens: int,
        timeout_seconds: int | None,
    ) -> str:
        if model_id not in self.models:
            raise LLMClientError(f"Model '{model_id}' is not listed in LLM config")
        if not self.api_key:
            raise LLMClientError("OPENCODE_API_KEY is not configured")

        model = self.models[model_id]
        prepared = _prepare_messages_for_model(messages, model)

        endpoint = f"{self.base_url}chat/completions"
        body: dict[str, Any] = {
            "model": model_id,
            "messages": prepared,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
                "User-Agent": "pixelify-infinity-ai-review-bot/1.0",
            },
            method="POST",
        )
        timeout = timeout_seconds if timeout_seconds is not None else self.timeout_seconds
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise LLMClientError(f"HTTP {exc.code}: {detail[:500]}") from exc
        except Exception as exc:  # noqa: BLE001 - surface transport failures to callers
            raise LLMClientError(str(exc)) from exc

        content = (
            payload.get("choices", [{}])[0]
            .get("message", {})
            .get("content")
        )
        if not content:
            raise LLMClientError("LLM returned empty content")
        if isinstance(content, list):
            text_parts = []
            for part in content:
                if isinstance(part, dict) and part.get("type") == "text":
                    text_parts.append(str(part.get("text", "")))
                else:
                    text_parts.append(str(part))
            content = "\n".join(text_parts)
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


def _prepare_messages_for_model(messages: list[ChatMessage], model: dict[str, Any]) -> list[ChatMessage]:
    requires_string = bool((model.get("compat") or {}).get("requiresStringContent"))
    supports_image = "image" in (model.get("input") or ["text"])

    prepared: list[ChatMessage] = []
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, list):
            if supports_image and not requires_string:
                prepared.append({"role": message["role"], "content": content})
                continue
            if supports_image and requires_string:
                # Keep multimodal array for vision models even when string content is preferred for text models.
                # Many OpenCode vision endpoints still accept OpenAI-style content parts.
                prepared.append({"role": message["role"], "content": content})
                continue
            # Text-only model: flatten to string and drop binary payloads.
            prepared.append({"role": message["role"], "content": _flatten_content(content)})
        else:
            prepared.append({"role": message["role"], "content": str(content)})
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
