"""Discover multimedia attachments and OCR/summarize them for review context."""

from __future__ import annotations

import base64
import mimetypes
import os
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from llm_client import LLMClient, LLMClientError


MARKDOWN_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HTML_IMAGE_RE = re.compile(r"<img[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)
BARE_MEDIA_URL_RE = re.compile(
    r"https?://[^\s)>\"]+\.(?:png|jpe?g|gif|webp|bmp|mp4|webm|mov|mp3|wav|m4a|ogg)(?:\?[^\s)>\"]*)?",
    re.IGNORECASE,
)
GITHUB_ATTACHMENT_RE = re.compile(
    r"https?://(?:github\.com/user-attachments/assets/[^\s)>\"]+|private-user-images\.githubusercontent\.com/[^\s)>\"]+)",
    re.IGNORECASE,
)


@dataclass
class MediaItem:
    label: str
    source: str
    kind: str  # path | url
    media_type: str  # image | video | audio | unknown


def discover_media_items(
    *,
    body: str,
    changed_files: list[str],
    extensions: list[str],
    max_items: int,
    workspace: Path | None = None,
) -> list[MediaItem]:
    workspace = workspace or Path.cwd()
    ext_set = {e.lower() if e.startswith(".") else f".{e.lower()}" for e in extensions}
    found: list[MediaItem] = []
    seen: set[str] = set()

    def add(item: MediaItem) -> None:
        key = item.source.strip()
        if not key or key in seen:
            return
        seen.add(key)
        found.append(item)

    for match in MARKDOWN_IMAGE_RE.finditer(body or ""):
        url = match.group(1).strip()
        add(MediaItem(label=url, source=url, kind="url", media_type=_media_type_from_name(url)))
    for match in HTML_IMAGE_RE.finditer(body or ""):
        url = match.group(1).strip()
        add(MediaItem(label=url, source=url, kind="url", media_type=_media_type_from_name(url)))
    for match in GITHUB_ATTACHMENT_RE.finditer(body or ""):
        url = match.group(0).strip().rstrip(".,;")
        add(MediaItem(label=url, source=url, kind="url", media_type=_media_type_from_name(url) or "image"))
    for match in BARE_MEDIA_URL_RE.finditer(body or ""):
        url = match.group(0).strip().rstrip(".,;")
        add(MediaItem(label=url, source=url, kind="url", media_type=_media_type_from_name(url)))

    for path in changed_files:
        suffix = Path(path).suffix.lower()
        if suffix not in ext_set:
            continue
        candidate = workspace / path
        if candidate.is_file():
            add(
                MediaItem(
                    label=path,
                    source=str(candidate),
                    kind="path",
                    media_type=_media_type_from_name(path),
                )
            )
        else:
            # Still note missing binary path for reviewers.
            add(
                MediaItem(
                    label=path,
                    source=path,
                    kind="path",
                    media_type=_media_type_from_name(path),
                )
            )

    return found[: max(0, max_items)]


def build_media_context(
    llm: LLMClient,
    *,
    items: list[MediaItem],
    ocr_config: dict[str, Any],
    soul_prompt: str,
    role_prompt: str,
) -> str:
    if not items:
        return ""

    primary_model = str(ocr_config.get("model", "gemini-3.7-flash-high"))
    fallback_models = [m for m in (ocr_config.get("fallbackModels") or []) if m != primary_model]
    max_bytes = int(ocr_config.get("maxBytesPerItem", 5_000_000))
    max_summary_chars = int(ocr_config.get("maxSummaryChars", 12_000))
    timeout_seconds = int(ocr_config.get("timeoutSeconds", 300))

    sections: list[str] = ["### Multimodal OCR context", ""]
    for index, item in enumerate(items, start=1):
        content_parts = _build_user_content(item, max_bytes=max_bytes)
        try:
            raw = llm.chat_completion(
                primary_model,
                [
                    {"role": "system", "content": f"{soul_prompt}\n\n{role_prompt}"},
                    {"role": "user", "content": content_parts},
                ],
                temperature=0.1,
                max_tokens=2048,
                allow_fallback=True,
                fallback_models=fallback_models,
                timeout_seconds=timeout_seconds,
            )
            sections.append(f"#### Media {index}: `{item.label}`")
            sections.append("")
            sections.append(raw.strip())
            sections.append("")
        except Exception as exc:  # noqa: BLE001 - record failure and move on
            sections.append(f"#### Media {index}: `{item.label}`")
            sections.append("")
            sections.append(f"OCR failed: {exc}")
            sections.append("")

    text = "\n".join(sections).strip()
    if len(text) > max_summary_chars:
        text = text[:max_summary_chars] + "\n\n[media OCR context truncated]\n"
    return text


def _build_user_content(item: MediaItem, *, max_bytes: int) -> list[dict[str, Any]]:
    instruction = (
        "Interpret this media for Pixelify Infinity issue/PR review. "
        "Perform OCR on visible text and summarize UI/state relevant to Android/Xposed/Google Photos. "
        f"SOURCE_LABEL={item.label}"
    )
    parts: list[dict[str, Any]] = [{"type": "text", "text": instruction}]

    if item.kind == "url":
        url = item.source
        media_type = item.media_type
        if media_type == "video":
            parts.append({"type": "image_url", "image_url": {"url": url}})
            # Some gateways accept video_url; include as extra text reference.
            parts[0]["text"] += f"\nVIDEO_URL={url}"
        elif media_type == "audio":
            parts[0]["text"] += f"\nAUDIO_URL={url}\nIf audio cannot be fetched, note that limitation."
            parts.append({"type": "image_url", "image_url": {"url": url}})
        else:
            parts.append({"type": "image_url", "image_url": {"url": url}})
        return parts

    path = Path(item.source)
    if not path.is_file():
        parts[0]["text"] += (
            f"\nLocal media path `{item.label}` is listed in the change set but the binary is not available "
            "in the review workspace (common for Git LFS/skipped binaries)."
        )
        return parts

    data = path.read_bytes()
    if len(data) > max_bytes:
        raise LLMClientError(f"media exceeds maxBytesPerItem ({len(data)} > {max_bytes})")

    mime, _ = mimetypes.guess_type(str(path))
    mime = mime or "application/octet-stream"
    b64 = base64.b64encode(data).decode("ascii")
    data_url = f"data:{mime};base64,{b64}"
    parts.append({"type": "image_url", "image_url": {"url": data_url}})
    return parts


def download_url_as_data_url(url: str, *, max_bytes: int, token: str | None = None) -> str:
    headers = {"User-Agent": "pixelify-infinity-ai-review-bot/1.0"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise LLMClientError("downloaded media exceeds maxBytesPerItem")
        content_type = response.headers.get("Content-Type") or mimetypes.guess_type(url)[0] or "application/octet-stream"
        content_type = content_type.split(";")[0].strip()
        b64 = base64.b64encode(data).decode("ascii")
        return f"data:{content_type};base64,{b64}"


def _media_type_from_name(name: str) -> str:
    lower = name.lower().split("?", 1)[0]
    if lower.endswith((".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")):
        return "image"
    if lower.endswith((".mp4", ".webm", ".mov")):
        return "video"
    if lower.endswith((".mp3", ".wav", ".m4a", ".ogg")):
        return "audio"
    if "user-attachments/assets" in lower:
        return "image"
    return "unknown"
