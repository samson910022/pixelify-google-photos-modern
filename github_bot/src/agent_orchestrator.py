"""Multi-agent orchestration for Pixelify Infinity PR/issue review."""

from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
from fnmatch import fnmatch
from pathlib import Path

from llm_client import LLMClient, LLMClientError
from media_ocr import build_media_context, discover_media_items


ROOT = Path(__file__).resolve().parents[1]


@dataclass
class ReviewContext:
    title: str
    body: str
    git_diff: str
    changed_files: list[str]
    base_ref: str
    head_ref: str
    sensitive_files: list[str]
    media_context: str = ""
    media_labels: list[str] = field(default_factory=list)


@dataclass
class RoleResult:
    role: str
    model: str
    verdict: str
    findings: str


class AgentOrchestrator:
    def __init__(self) -> None:
        self.config = json.loads((ROOT / "config" / "bot_config.json").read_text(encoding="utf-8"))
        self.soul = (ROOT / "prompts" / "SOUL.md").read_text(encoding="utf-8")
        fallback_models = list(self.config.get("fallbackModels") or [])
        if self.config.get("fallbackModel"):
            fallback_models.append(self.config["fallbackModel"])
        self.llm = LLMClient(
            fallback_models=fallback_models,
            fallback_model=self.config.get("fallbackModel", "deepseek-v4-flash-free"),
        )

    def enrich_with_media_ocr(
        self,
        *,
        title: str,
        body: str,
        changed_files: list[str],
        workspace: Path | None = None,
    ) -> tuple[str, list[str]]:
        ocr_config = self.config.get("mediaOcr") or {}
        if not ocr_config.get("enabled", True):
            return "", []

        items = discover_media_items(
            body=body,
            changed_files=changed_files,
            extensions=list(ocr_config.get("extensions") or []),
            max_items=int(ocr_config.get("maxItems", 4)),
            workspace=workspace or Path.cwd(),
        )
        if not items:
            return "", []

        role_prompt = (ROOT / "prompts" / "roles" / "media_ocr.md").read_text(encoding="utf-8")
        context = build_media_context(
            self.llm,
            items=items,
            ocr_config=ocr_config,
            soul_prompt=self.soul,
            role_prompt=role_prompt,
        )
        labels = [item.label for item in items]
        if title:
            # title unused for OCR itself; keep signature stable for callers.
            pass
        return context, labels

    def run_multi_agent_review(self, ctx: ReviewContext) -> str:
        role_ids = list(self.config.get("reviewPipeline", []))
        results: list[RoleResult] = []
        user_prompt = self._review_user_prompt(ctx)

        with ThreadPoolExecutor(max_workers=max(1, len(role_ids))) as pool:
            futures = {
                pool.submit(self._run_role, role_id, user_prompt): role_id
                for role_id in role_ids
            }
            for future in as_completed(futures):
                results.append(future.result())

        order = {role_id: index for index, role_id in enumerate(role_ids)}
        results.sort(key=lambda item: order.get(item.role, 999))
        return self._format_review_report(ctx, results)

    def run_triage(self, title: str, body: str, media_context: str = "") -> str:
        """Investigate an issue: quality score, missing info, root-cause hypotheses."""
        hints = self.issue_quality_hints(title, body)
        result = self._run_role(
            "triage_agent",
            self._issue_user_prompt(title, body, media_context, quality_hints=hints),
        )
        lines = [
            "# Pixelify Infinity Issue Investigation",
            "",
            f"- Generated: `{_utc_now()}`",
            "- Mode: **issue investigation** (not a PR code review)",
            "",
        ]
        if hints:
            lines.extend(
                [
                    "## Local completeness hints",
                    "",
                    "These are deterministic checks used to ground the quality score:",
                    "",
                ]
            )
            lines.extend(f"- {item}" for item in hints)
            lines.append("")
        if media_context:
            lines.extend(
                [
                    "## Multimodal evidence used",
                    "",
                    "Attached media was OCR/summarized and injected into the investigation context.",
                    "",
                ]
            )
        lines.append(result.findings.strip())
        lines.append("")
        return "\n".join(lines)

    def run_explainer(self, ctx: ReviewContext) -> str:
        result = self._run_role("explainer_agent", self._review_user_prompt(ctx))
        return (
            "# Pixelify Infinity PR Explanation\n\n"
            f"- Generated: `{_utc_now()}`\n"
            "- Mode: **pull request explanation**\n\n"
            f"{result.findings}\n"
        )

    def issue_quality_hints(self, title: str, body: str) -> list[str]:
        """Deterministic issue completeness signals (OpenClaw-style field coverage)."""
        blob = f"{title}\n{body}".lower()
        checks = [
            ("module/app version", any(token in blob for token in ("version", "v1.", "pixelify infinity", "apk"))),
            ("android version", any(token in blob for token in ("android ", "aosp", "api ", "sdk "))),
            ("xposed/lsposed environment", any(token in blob for token in ("lsposed", "xposed", "zygisk", "magisk"))),
            ("google photos version/context", any(token in blob for token in ("photos", "com.google.android.apps.photos"))),
            ("reproduction steps", any(token in blob for token in ("steps", "reproduce", "repro", "1.", "1)"))),
            ("expected vs actual", "expected" in blob and "actual" in blob),
            ("evidence attachment", any(token in blob for token in ("http", "screenshot", "logcat", "!.[", "<img", ".png", ".jpg", ".webp"))),
        ]
        hints: list[str] = []
        for label, present in checks:
            hints.append(f"{'present' if present else 'missing'}: {label}")
        return hints

    def deterministic_findings(self, ctx: ReviewContext) -> list[str]:
        """Cheap local checks that do not require an LLM."""
        findings: list[str] = []
        haystacks = [ctx.git_diff, ctx.body, "\n".join(ctx.changed_files)]
        blob = "\n".join(haystacks)
        if re.search(r"-----BEGIN [A-Z ]*PRIVATE KEY-----", blob):
            findings.append("Blocking: private key PEM material appears in the review bundle.")
        if re.search(r"(?i)(storePassword|keyPassword|RELEASE_STORE_PASSWORD|RELEASE_KEY_PASSWORD)\s*[:=]", blob):
            findings.append("Blocking: release signing password material appears in the review bundle.")
        if re.search(r"\b(balti\.xposed\.pixelifygooglephotos)\b", blob) and re.search(
            r"(applicationId|namespace|package=)", blob
        ):
            findings.append(
                "Blocking: legacy package id may be reintroduced as an active package identity."
            )
        for path in ctx.changed_files:
            lowered = path.lower()
            if lowered.endswith((".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pk8")):
                findings.append(f"Blocking: generated or private binary path committed: `{path}`")
            if lowered.endswith("local.properties") or lowered.endswith("signing.properties"):
                findings.append(f"Blocking: local/signing properties path committed: `{path}`")
        return findings

    def classify_sensitive_files(self, changed_files: list[str]) -> list[str]:
        globs = self.config.get("sensitivePathGlobs", [])
        hits: list[str] = []
        for path in changed_files:
            normalized = path.lstrip("./")
            if any(fnmatch(normalized, pattern) or fnmatch(path, pattern) for pattern in globs):
                hits.append(path)
        return hits

    def _run_role(self, role_id: str, user_prompt: str) -> RoleResult:
        role = self.config["roles"][role_id]
        prompt_path = (ROOT / role["promptFile"]).resolve()
        role_prompt = prompt_path.read_text(encoding="utf-8")
        system_prompt = f"{self.soul}\n\n{role_prompt}"
        model = role["model"]
        try:
            raw = self.llm.chat_completion(
                model,
                [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=float(role.get("temperature", 0.2)),
                max_tokens=int(role.get("maxTokens", 4096)),
            )
            verdict = _extract_verdict(raw)
            return RoleResult(role=role_id, model=model, verdict=verdict, findings=raw)
        except LLMClientError as exc:
            return RoleResult(
                role=role_id,
                model=model,
                verdict="COMMENT",
                findings=f"Role `{role_id}` failed: {exc}",
            )

    def _review_user_prompt(self, ctx: ReviewContext) -> str:
        files = "\n".join(f"- {path}" for path in ctx.changed_files) or "- (none)"
        sensitive = "\n".join(f"- {path}" for path in ctx.sensitive_files) or "- (none)"
        body = ctx.body.strip() or "(empty)"
        media = ctx.media_context.strip() or "(none)"
        return (
            f"### PR Title\n{ctx.title}\n\n"
            f"### PR Body\n{body}\n\n"
            f"### Base...Head\n`{ctx.base_ref}`...`{ctx.head_ref}`\n\n"
            f"### Changed Files\n{files}\n\n"
            f"### Sensitive Path Hits\n{sensitive}\n\n"
            f"### Multimodal OCR Context\n{media}\n\n"
            f"### Git Diff\n```diff\n{ctx.git_diff}\n```\n"
        )

    def _issue_user_prompt(
        self,
        title: str,
        body: str,
        media_context: str = "",
        quality_hints: list[str] | None = None,
    ) -> str:
        body = body or "(empty)"
        media_block = f"### Multimodal evidence\n{media_context}\n\n" if media_context else ""
        hints = quality_hints or []
        hints_block = ""
        if hints:
            hints_block = (
                "### Local completeness hints\n"
                + "\n".join(f"- {item}" for item in hints)
                + "\n\n"
            )
        return (
            "Investigate this GitHub issue for Pixelify Infinity. "
            "This is not a pull-request code review.\n\n"
            f"### Issue Title\n{title}\n\n"
            f"### Issue Body\n{body}\n\n"
            f"{hints_block}"
            f"{media_block}"
            "Produce the issue investigation sections required by your role prompt. "
            "Score issue quality, propose root-cause hypotheses, and list missing info."
        )


    def _format_review_report(self, ctx: ReviewContext, results: list[RoleResult]) -> str:
        final_verdict = _aggregate_verdict([item.verdict for item in results])
        deterministic = self.deterministic_findings(ctx)
        lines = [
            "# Pixelify Infinity PR Code Review",
            "",
            f"- Generated: `{_utc_now()}`",
            "- Mode: **pull request code review**",
            f"- PR: **{ctx.title}**",
            f"- Range: `{ctx.base_ref[:12]}...{ctx.head_ref[:12]}`",
            f"- Changed files: **{len(ctx.changed_files)}**",
            "",
            "> Advisory only. Human maintainer review remains required for merge and release decisions.",
            "",
            "## Role verdicts",
            "",
            "| Role | Verdict |",
            "| --- | --- |",
        ]
        for item in results:
            lines.append(f"| `{item.role}` | `{item.verdict}` |")
        lines.append("")

        if ctx.sensitive_files:
            lines.append("## Sensitive path hits")
            lines.append("")
            lines.extend(f"- `{path}`" for path in ctx.sensitive_files)
            lines.append("")

        if ctx.media_labels:
            lines.append("## Multimodal evidence used")
            lines.append("")
            lines.append("Attached/changed media was OCR/summarized and injected into reviewer context.")
            lines.extend(f"- `{label}`" for label in ctx.media_labels)
            lines.append("")

        if deterministic:
            lines.append("## Deterministic prechecks")
            lines.append("")
            lines.extend(f"- {item}" for item in deterministic)
            lines.append("")

        lines.append("## Role findings")
        lines.append("")
        for item in results:
            lines.append(f"### `{item.role}`")
            lines.append("")
            lines.append(item.findings.strip() or "_No findings returned._")
            lines.append("")

        lines.append("## Aggregate verdict")
        lines.append("")
        lines.append(f"**`FINAL_VERDICT={final_verdict}`**")
        if final_verdict != "APPROVE":
            lines.append("")
            lines.append("At least one reviewer requested changes or comments before merge.")
        lines.append("")
        return "\n".join(lines).strip() + "\n"



def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def _extract_verdict(text: str) -> str:
    match = re.search(r"VERDICT\s*:\s*(APPROVE|NEEDS_CHANGES|COMMENT)", text, flags=re.IGNORECASE)
    if not match:
        if re.search(r"\bNEEDS[_\s-]?CHANGES\b", text, flags=re.IGNORECASE):
            return "NEEDS_CHANGES"
        if re.search(r"\bAPPROVE\b", text, flags=re.IGNORECASE):
            return "APPROVE"
        return "COMMENT"
    return match.group(1).upper()


def _aggregate_verdict(verdicts: list[str]) -> str:
    normalized = [v.upper() for v in verdicts]
    if any(v == "NEEDS_CHANGES" for v in normalized):
        return "NEEDS_CHANGES"
    if normalized and all(v == "APPROVE" for v in normalized):
        return "APPROVE"
    return "COMMENT"
