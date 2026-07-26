"""Multi-agent orchestration for Pixelify Infinity PR/issue review."""

from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
from fnmatch import fnmatch
from pathlib import Path
from typing import Any, Literal

from llm_client import LLMClient, LLMClientError
from media_ocr import build_media_context, discover_media_items


ROOT = Path(__file__).resolve().parents[1]

DEFAULT_REQUIRED_TRIAGE_SECTIONS = [
    "CLASSIFICATION",
    "ACTIONABILITY",
    "SUMMARY",
    "EVIDENCE_USED",
    "ROOT_CAUSE_HYPOTHESES",
    "REPORTER_NEXT_STEPS",
    "MAINTAINER_NEXT_STEPS",
    "SUGGESTED_LABELS",
    "ISSUE_QUALITY_SCORE",
    "QUALITY_BREAKDOWN",
    "MISSING_INFO",
    "RISK",
    "SECURITY_ROUTING",
]

FieldStatus = Literal["strong", "weak", "missing"]


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


@dataclass
class FieldQuality:
    field: str
    status: FieldStatus
    detail: str
    points: int


class AgentOrchestrator:
    def __init__(self) -> None:
        self.config = json.loads((ROOT / "config" / "bot_config.json").read_text(encoding="utf-8"))
        self.soul = (ROOT / "prompts" / "SOUL.md").read_text(encoding="utf-8")
        fallback_models = list(self.config.get("fallbackModels") or [])
        if self.config.get("fallbackModel"):
            fallback_models.append(self.config["fallbackModel"])
        gate = self.config.get("llmResponseGate") or {}
        self.llm = LLMClient(
            fallback_models=fallback_models,
            fallback_model=self.config.get("fallbackModel", "deepseek-v4-flash-free"),
            min_response_chars=int(gate.get("minResponseChars", 0)),
            reject_finish_reasons=list(gate.get("rejectFinishReasons") or ["length", "content_filter"]),
            same_model_retry_on_length=int(gate.get("sameModelRetryOnLength", 1)),
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

    def run_triage(
        self,
        title: str,
        body: str,
        media_context: str = "",
        thread_comments: list[dict[str, Any]] | None = None,
        repo_knowledge: str = "",
    ) -> str:
        """Investigate an issue: quality score, missing info, root-cause hypotheses."""
        fields = self.assess_issue_field_quality(title, body)
        hints = self.issue_quality_hints(title, body, fields=fields)
        local_score = self.issue_quality_score_estimate(fields)
        prior_questions = extract_prior_questions(
            thread_comments or [],
            bot_markers=[
                self.config.get("triageMarker", "<!-- PIXELIFY_AI_TRIAGE_REPORT -->"),
                self.config.get("commentMarker", ""),
                self.config.get("commandMarker", ""),
            ],
        )
        knowledge = repo_knowledge or self.build_issue_repo_knowledge()
        user_prompt = self._issue_user_prompt(
            title,
            body,
            media_context,
            quality_hints=hints,
            thread_comments=thread_comments,
            prior_questions=prior_questions,
            repo_knowledge=knowledge,
            local_score=local_score,
        )
        triage_cfg = self.config.get("triage") or {}
        required = list(triage_cfg.get("requiredSections") or DEFAULT_REQUIRED_TRIAGE_SECTIONS)
        min_chars = int(triage_cfg.get("minResponseChars", 400))
        result = self._run_role(
            "triage_agent",
            user_prompt,
            min_chars=min_chars,
            required_markers=required[:4],  # early fail on missing decision-first headers
        )
        findings = result.findings.strip()
        problems = validate_triage_report(findings, required_sections=required)
        if problems and not findings.lower().startswith("role `triage_agent` failed"):
            # One repair attempt with explicit missing sections.
            repair_prompt = (
                user_prompt
                + "\n\n### Repair request\n"
                + "Your previous draft was incomplete. Rewrite the full investigation now.\n"
                + "Missing/invalid items:\n"
                + "\n".join(f"- {item}" for item in problems)
                + "\nUse the required section headings exactly. Do not invent private data.\n"
            )
            repaired = self._run_role(
                "triage_agent",
                repair_prompt,
                min_chars=min_chars,
                required_markers=required[:4],
            )
            repaired_findings = repaired.findings.strip()
            repaired_problems = validate_triage_report(repaired_findings, required_sections=required)
            if not repaired_problems:
                findings = repaired_findings
                problems = []
            else:
                problems = repaired_problems
                findings = repaired_findings

        if problems or findings.lower().startswith("role `triage_agent` failed"):
            findings = build_fail_closed_triage_stub(
                title=title,
                problems=problems or ["role failure or empty investigation"],
                fields=fields,
                local_score=local_score,
                prior_questions=prior_questions,
            )

        return self._format_triage_report(
            findings=findings,
            hints=hints,
            fields=fields,
            local_score=local_score,
            media_context=media_context,
            thread_comments=thread_comments or [],
            prior_questions=prior_questions,
        )

    def run_explainer(self, ctx: ReviewContext) -> str:
        result = self._run_role("explainer_agent", self._review_user_prompt(ctx))
        return (
            "# Pixelify Infinity PR Explanation\n\n"
            f"- Generated: `{_utc_now()}`\n"
            "- Mode: **pull request explanation**\n\n"
            f"{result.findings}\n"
        )

    def assess_issue_field_quality(self, title: str, body: str) -> list[FieldQuality]:
        """Deterministic field-quality assessment (strong/weak/missing)."""
        text = f"{title}\n{body}".strip()
        blob = text.lower()
        fields: list[FieldQuality] = []

        # Problem clarity
        vague = bool(re.search(r"\b(broken|not work|doesn't work|does not work|no effect|没用|沒有效|壞掉)\b", blob))
        concrete = len(re.findall(r"[.!?。！？]", text)) >= 1 and len(text) >= 40
        if not text or len(text) < 20:
            fields.append(FieldQuality("problem clarity", "missing", "issue text too short/vague", 0))
        elif vague and len(text) < 120:
            fields.append(FieldQuality("problem clarity", "weak", "mostly vague failure language", 8))
        elif concrete:
            fields.append(FieldQuality("problem clarity", "strong", "concrete failure statement present", 20))
        else:
            fields.append(FieldQuality("problem clarity", "weak", "problem stated but low detail", 10))

        # Module version
        if re.search(r"\bv?\d+\.\d+(\.\d+)?\b", text) and re.search(r"(?i)(pixelify|module|apk|version)", text):
            fields.append(FieldQuality("module version", "strong", "explicit version-like token with module context", 15))
        elif re.search(r"(?i)\b(latest|newest|最新)\b", blob) or "pixelify" in blob:
            fields.append(FieldQuality("module version", "weak", "module mentioned without exact version", 6))
        else:
            fields.append(FieldQuality("module version", "missing", "no module/app version", 0))

        # Android version (horizontal whitespace only so "android\n1. step" is not a version)
        if re.search(
            r"(?i)android[ \t]*[:\-]?[ \t]*\d{1,2}\b|\bapi[ \t]*[:\-]?[ \t]*\d{2}\b|\bsdk[ \t]*[:\-]?[ \t]*\d{2}\b",
            text,
        ):
            fields.append(FieldQuality("android version", "strong", "Android/API version present", 12))
        elif "android" in blob:
            fields.append(FieldQuality("android version", "weak", "Android mentioned without version number", 4))
        else:
            fields.append(FieldQuality("android version", "missing", "no Android version", 0))

        # Xposed env
        if re.search(r"(?i)(lsposed|xposed|zygisk|magisk|jingmatrix)", text):
            detail = "framework/manager named"
            status: FieldStatus = "strong"
            points = 12
            if not re.search(r"(?i)(lsposed|xposed)", text):
                status, points, detail = "weak", 5, "root stack mentioned without LSPosed/Xposed"
            fields.append(FieldQuality("xposed/lsposed environment", status, detail, points))
        else:
            fields.append(FieldQuality("xposed/lsposed environment", "missing", "no Xposed/LSPosed environment", 0))

        # Photos context (avoid "Photos\n3. Open app" false-strong; allow "Photos: 7.83")
        if "com.google.android.apps.photos" in blob or re.search(
            r"(?i)(?:google[ \t]+)?photos[ \t]*[:\-]?[ \t]*v?\d+(?:\.\d+)+",
            text,
        ):
            fields.append(FieldQuality("google photos context", "strong", "package id or Photos version present", 12))
        elif "photos" in blob or "相簿" in text or "相册" in text:
            fields.append(FieldQuality("google photos context", "weak", "Photos mentioned without version/package", 5))
        else:
            fields.append(FieldQuality("google photos context", "missing", "no Google Photos context", 0))

        # Reproduction
        numbered = re.findall(r"(?m)^\s*(?:\d+[\.\)]\s+|[-*]\s+\S+)", text)
        if len(numbered) >= 2 or re.search(r"(?i)steps?\s+to\s+reproduce", text):
            if len(numbered) >= 2:
                fields.append(FieldQuality("reproduction steps", "strong", "ordered steps present", 15))
            else:
                fields.append(FieldQuality("reproduction steps", "weak", "repro mentioned without clear ordered steps", 6))
        elif re.search(r"(?i)(reproduce|repro|steps|重現|复现)", text):
            fields.append(FieldQuality("reproduction steps", "weak", "repro keyword without usable steps", 4))
        else:
            fields.append(FieldQuality("reproduction steps", "missing", "no reproduction steps", 0))

        # Expected vs actual
        has_expected = bool(re.search(r"(?i)expected|期望|預期", text))
        has_actual = bool(re.search(r"(?i)actual|觀察|实际|實際|instead|still|no effect|沒有效|无效", text))
        if has_expected and has_actual:
            fields.append(FieldQuality("expected vs actual", "strong", "both expected and actual present", 10))
        elif has_expected or has_actual:
            fields.append(FieldQuality("expected vs actual", "weak", "only one of expected/actual present", 4))
        else:
            fields.append(FieldQuality("expected vs actual", "missing", "no expected vs actual", 0))

        # Evidence
        if re.search(r"https?://|\.png|\.jpe?g|\.webp|!\[[^\]]*\]\(|<img|logcat|user-attachments", blob):
            fields.append(FieldQuality("evidence attachment", "strong", "URL/image/log evidence referenced", 14))
        elif re.search(r"(?i)(screenshot|screen shot|log|截圖|截图)", text):
            fields.append(FieldQuality("evidence attachment", "weak", "evidence mentioned but not attached", 5))
        else:
            fields.append(FieldQuality("evidence attachment", "missing", "no evidence attachment", 0))

        # Diagnostics for module load path
        if re.search(r"(?i)(toast|notification|verify|module loaded|scope|作用域|作用範圍)", text):
            fields.append(FieldQuality("load/VERIFY diagnostics", "strong", "toast/VERIFY/scope signal present", 10))
        elif re.search(r"(?i)(enabled|enable|啟用|启用)", text):
            fields.append(FieldQuality("load/VERIFY diagnostics", "weak", "enablement mentioned without VERIFY/toast", 4))
        else:
            fields.append(FieldQuality("load/VERIFY diagnostics", "missing", "no load/VERIFY diagnostics", 0))

        return fields

    def issue_quality_hints(
        self,
        title: str,
        body: str,
        fields: list[FieldQuality] | None = None,
    ) -> list[str]:
        """Deterministic issue completeness signals with field quality, not mere keyword presence."""
        assessed = fields if fields is not None else self.assess_issue_field_quality(title, body)
        return [f"{item.status}: {item.field} — {item.detail}" for item in assessed]

    def issue_quality_score_estimate(self, fields: list[FieldQuality]) -> int:
        total = sum(item.points for item in fields)
        # Max points roughly 120; normalize to 0-100.
        return max(0, min(100, int(round(total * 100 / 120))))

    def build_issue_repo_knowledge(self, *, max_chars: int = 3500) -> str:
        """Small grounded snippets so issue investigation can reference real project behavior."""
        chunks: list[str] = []
        candidates = [
            (ROOT.parent / "README.md", 1800),
            (ROOT.parent / "CHANGELOG.md", 1200),
            (ROOT.parent / "app/src/main/res/values/strings.xml", 900),
        ]
        for path, limit in candidates:
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            # Prefer VERIFY/toast/scope-related paragraphs when present.
            lowered = text.lower()
            focus_idx = -1
            for token in ("verify", "toast", "android 17", "scope", "lsposed"):
                focus_idx = lowered.find(token)
                if focus_idx >= 0:
                    break
            if focus_idx >= 0:
                start = max(0, focus_idx - 200)
                snippet = text[start : start + limit]
            else:
                snippet = text[:limit]
            chunks.append(f"### {path.name}\n{snippet.strip()}")
        joined = "\n\n".join(chunks).strip()
        if len(joined) > max_chars:
            return joined[:max_chars] + "\n\n[repo knowledge truncated]\n"
        return joined

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

    def _run_role(
        self,
        role_id: str,
        user_prompt: str,
        *,
        min_chars: int | None = None,
        required_markers: list[str] | None = None,
    ) -> RoleResult:
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
                min_chars=min_chars,
                required_markers=required_markers,
            )
            verdict = _extract_verdict(raw)
            return RoleResult(role=role_id, model=model, verdict=verdict, findings=raw)
        except LLMClientError as exc:
            # Never surface model ids / provider routing in published findings.
            detail = sanitize_public_error_text(str(exc))
            return RoleResult(
                role=role_id,
                model=model,
                verdict="COMMENT",
                findings=f"Role `{role_id}` failed: {detail}",
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
        thread_comments: list[dict[str, Any]] | None = None,
        prior_questions: list[str] | None = None,
        repo_knowledge: str = "",
        local_score: int | None = None,
    ) -> str:
        body = body or "(empty)"
        media_block = f"### Multimodal evidence\n{media_context}\n\n" if media_context else ""
        hints = quality_hints or []
        hints_block = ""
        if hints:
            hints_block = (
                "### Local field-quality hints (deterministic)\n"
                + "\n".join(f"- {item}" for item in hints)
                + "\n\n"
            )
        score_block = ""
        if local_score is not None:
            score_block = f"### Local quality estimate\n{local_score}/100\n\n"
        thread_block = format_thread_comments_for_prompt(thread_comments or [])
        if thread_block:
            thread_block = f"### Issue thread comments (redacted)\n{thread_block}\n\n"
        prior = prior_questions or []
        prior_block = ""
        if prior:
            prior_block = (
                "### Questions already asked (do not repeat)\n"
                + "\n".join(f"- {item}" for item in prior)
                + "\n\n"
                + "Only request NEW missing information. Mark already-answered items as resolved in MISSING_INFO.\n\n"
            )
        knowledge_block = ""
        if repo_knowledge.strip():
            knowledge_block = (
                "### Repository knowledge pack (grounded excerpts)\n"
                f"{repo_knowledge.strip()}\n\n"
            )
        return (
            "Investigate this GitHub issue for Pixelify Infinity. "
            "This is not a pull-request code review.\n\n"
            "Lead with CLASSIFICATION and ACTIONABILITY before narrative sections. "
            "Use the Pixelify load/VERIFY playbook when module-not-loading or feature-not-applying is plausible. "
            "Score issue quality from observed evidence only. Prefer NOT_ENOUGH_INFO over speculation.\n\n"
            f"### Issue Title\n{title}\n\n"
            f"### Issue Body\n{body}\n\n"
            f"{score_block}"
            f"{hints_block}"
            f"{thread_block}"
            f"{prior_block}"
            f"{media_block}"
            f"{knowledge_block}"
            "Produce the issue investigation sections required by your role prompt. "
            "Do not include model names. Do not emit PR merge verdicts."
        )

    def _format_triage_report(
        self,
        *,
        findings: str,
        hints: list[str],
        fields: list[FieldQuality],
        local_score: int,
        media_context: str,
        thread_comments: list[dict[str, Any]],
        prior_questions: list[str],
    ) -> str:
        decision = _infer_local_decision(local_score, findings)
        lines = [
            "# Pixelify Infinity Issue Investigation",
            "",
            f"- Generated: `{_utc_now()}`",
            "- Mode: **issue investigation** (not a PR code review)",
            f"- Local quality estimate: **{local_score}/100**",
            f"- Local decision: **{decision}**",
            f"- Thread comments considered: **{len(thread_comments)}**",
            "",
        ]
        if hints:
            lines.extend(
                [
                    "## Local field-quality hints",
                    "",
                    "These are deterministic checks used to ground the quality score (strong/weak/missing):",
                    "",
                ]
            )
            lines.extend(f"- {item}" for item in hints)
            lines.append("")
        if prior_questions:
            lines.extend(
                [
                    "## Already-asked questions suppressed from re-asking",
                    "",
                ]
            )
            lines.extend(f"- {item}" for item in prior_questions[:12])
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
        lines.append(findings.strip())
        lines.append("")
        # Keep fields referenced to avoid unused lint-style warnings in some checkers.
        _ = fields
        return "\n".join(lines)

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


def validate_triage_report(
    text: str,
    *,
    required_sections: list[str] | None = None,
) -> list[str]:
    """Return missing/invalid section problems; empty list means publishable."""
    body = (text or "").strip()
    problems: list[str] = []
    if not body:
        return ["empty investigation body"]
    if body.lower().startswith("role `triage_agent` failed"):
        return [f"role failure: {body[:200]}"]
    if len(body) < 200:
        problems.append(f"body too short ({len(body)} chars)")

    required = required_sections or DEFAULT_REQUIRED_TRIAGE_SECTIONS
    lowered = body.lower()
    for section in required:
        # Accept section heading style variants.
        token = section.lower().replace("_", " ")
        compact = section.lower()
        if compact not in lowered and token not in lowered:
            problems.append(f"missing section: {section}")

    score_match = re.search(
        r"ISSUE_QUALITY_SCORE\s*[:\-]?\s*(\d{1,3})\s*(?:\(([^)]+)\))?",
        body,
        flags=re.IGNORECASE,
    )
    if not score_match:
        problems.append("missing or unparseable ISSUE_QUALITY_SCORE")
    else:
        score = int(score_match.group(1))
        if score < 0 or score > 100:
            problems.append(f"ISSUE_QUALITY_SCORE out of range: {score}")

    # Reject obvious mid-sentence truncation on final non-empty line.
    last_line = next((ln.strip() for ln in reversed(body.splitlines()) if ln.strip()), "")
    if last_line and not re.search(r"[.!?。！？:`*\)]$|score|routing|labels|info|steps", last_line, re.I):
        if len(last_line) > 40 and not last_line.endswith((".", ":", "。")):
            # Soft signal only if overall short.
            if len(body) < 900:
                problems.append("possible truncation near end of report")
    return problems


def is_triage_report_publishable(text: str, *, required_sections: list[str] | None = None) -> bool:
    return not validate_triage_report(text, required_sections=required_sections)


def format_thread_comments_for_prompt(
    comments: list[dict[str, Any]],
    *,
    max_chars: int = 12000,
) -> str:
    if not comments:
        return ""
    parts: list[str] = []
    used = 0
    for index, comment in enumerate(comments, start=1):
        login = str(comment.get("login") or comment.get("author") or "unknown")
        role = "bot" if comment.get("is_bot") else str(comment.get("role") or "user")
        body = str(comment.get("body") or "").strip()
        if not body:
            continue
        # Collapse prior sticky bot reports to save tokens while keeping asks.
        if comment.get("is_bot") and "PIXELIFY_AI_" in body:
            excerpt = _extract_section_lines(body, ("REPORTER_NEXT_STEPS", "REPORTER_ASKS", "MISSING_INFO", "ACTIONABILITY"))
            body = excerpt or "[prior bot investigation present; full sticky report omitted]"
        chunk = f"{index}. @{login} ({role})\n{body}"
        if used + len(chunk) > max_chars:
            parts.append(f"{index}. @{login} ({role})\n[comment truncated for context budget]")
            break
        parts.append(chunk)
        used += len(chunk) + 2
    return "\n\n".join(parts)


def extract_prior_questions(
    comments: list[dict[str, Any]],
    *,
    bot_markers: list[str] | None = None,
) -> list[str]:
    markers = [m for m in (bot_markers or []) if m]
    found: list[str] = []
    seen: set[str] = set()

    def _add(item: str) -> None:
        cleaned = re.sub(r"\s+", " ", item).strip(" -*\t")
        cleaned = cleaned.lstrip("0123456789.) ").strip()
        if len(cleaned) < 8:
            return
        key = cleaned.lower()
        if key in seen:
            return
        seen.add(key)
        found.append(cleaned)

    for comment in comments:
        body = str(comment.get("body") or "")
        if not body.strip():
            continue
        is_bot = bool(comment.get("is_bot")) or any(marker in body for marker in markers)
        if is_bot:
            section = _extract_section_text(body, ("REPORTER_NEXT_STEPS", "REPORTER_ASKS", "MISSING_INFO"))
            for line in section.splitlines():
                line = line.strip()
                if line.startswith(("-", "*", "1", "2", "3", "4", "5", "6", "7", "8", "9")):
                    _add(line)
            continue
        for line in body.splitlines():
            stripped = line.strip()
            if "?" in stripped or "？" in stripped:
                _add(stripped)
            elif re.search(r"(?i)(please provide|need|missing|請提供|需要|缺少)", stripped):
                _add(stripped)
    return found[:20]



def sanitize_public_error_text(text: str) -> str:
    """Strip model ids / provider routing crumbs from public-facing error text.

    Validation messages like ``missing section: CLASSIFICATION`` are preserved.
    Multi-candidate chains such as ``model-a: reason; model-b: reason`` are scrubbed.
    """
    cleaned = str(text or "").strip()
    if not cleaned:
        return "investigation backend error"

    known_model = re.compile(
        r"(?i)\b(?:deepseek-[a-z0-9._-]+|ling-[a-z0-9._-]+|laguna-[a-z0-9._-]+|"
        r"north-[a-z0-9._-]+|mimo-[a-z0-9._-]+|big-pickle|"
        r"[a-z0-9][a-z0-9._-]{2,}-free)\b"
    )
    # Only treat as model-id prefix when the token looks like a model id.
    model_prefix = re.compile(
        r"(?i)\b((?:deepseek|ling|laguna|north|mimo)[a-z0-9._-]*|big-pickle|[a-z0-9][a-z0-9._-]{2,}-free)\s*:\s*"
    )

    parts = re.split(r"\s*;\s*", cleaned)
    scrubbed: list[str] = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        # Strip leading model-id prefixes repeatedly.
        while True:
            updated = model_prefix.sub("", part, count=1)
            if updated == part:
                break
            part = updated.strip()
        part = known_model.sub("[model]", part)
        part = re.sub(r"\s+", " ", part).strip(" ;,")
        if part:
            scrubbed.append(part)
    out = "; ".join(scrubbed) if scrubbed else "investigation backend error"
    return out[:500]


def build_fail_closed_triage_stub(
    *,
    title: str,
    problems: list[str],
    fields: list[FieldQuality],
    local_score: int,
    prior_questions: list[str],
) -> str:
    missing = [f.field for f in fields if f.status == "missing"]
    weak = [f.field for f in fields if f.status == "weak"]
    if local_score >= 80:
        band = "actionable"
    elif local_score >= 50:
        band = "needs-info"
    else:
        band = "insufficient"
    # Fail-closed stub always means the automated investigation needs a re-run once
    # evidence/model output is complete; keep header/body decision single-sourced.
    decision = "needs-rerun" if local_score >= 50 else "needs-info"
    asks = []
    for field_name in missing[:5]:
        asks.append(f"Provide {field_name}.")
    for field_name in weak[:3]:
        asks.append(f"Strengthen {field_name} with concrete values/evidence.")
    # Avoid re-asking.
    prior_l = {q.lower() for q in prior_questions}
    asks = [a for a in asks if a.lower() not in prior_l] or [
        "Reply with the still-missing diagnostics listed under MISSING_INFO."
    ]
    labels = ["needs-info"] if band != "actionable" else ["needs-triage"]
    security_hit = bool(re.search(
        r"(?i)security|vulnerab|exploit|\b(?:data|token|secret|credential|key|account)\s*leak\b|\bleak(?:ed|ing)?\s+(?:data|token|secret|credential|key|account)s?\b",
        title,
    ))
    if security_hit:
        labels = ["security", "needs-info"]
        decision = "security-private"
        band = "needs-info" if band == "actionable" else band
    security_routing = (
        "move-to-private — potential security-sensitive title/content; follow SECURITY.md and do not expand exploit detail publicly"
        if security_hit
        else "public — unless reporter later includes exploit details; then move to SECURITY.md private reporting"
    )
    return f"""CLASSIFICATION
support / incomplete automated investigation

ACTIONABILITY
BLOCKING_MISSING: automated investigation output failed completeness validation.
Local decision: {decision}

SUMMARY
The automated investigation could not publish a complete analysis for `{title}`.
Validation problems: {'; '.join(sanitize_public_error_text(p) for p in problems)}.
A fail-closed stub is shown instead of a truncated or low-value draft.

EVIDENCE_USED
- Local field-quality estimate only ({local_score}/100)
- No complete model investigation body accepted

ROOT_CAUSE_HYPOTHESES
1. NOT_ENOUGH_INFO — automated draft incomplete/truncated; do not treat as root-cause conclusion.
   Confidence: low
   Validate: re-run `/triage` after providing missing reporter evidence.

REPORTER_NEXT_STEPS
{chr(10).join(f'- {item}' for item in asks)}

MAINTAINER_NEXT_STEPS
- Wait for reporter diagnostics before deep code speculation.
- Re-run issue investigation after new evidence arrives.
- If this keeps failing, inspect Actions logs for completion-gate rejects.

SUGGESTED_LABELS
{', '.join(labels)}

ISSUE_QUALITY_SCORE: {local_score} ({band})

QUALITY_BREAKDOWN
- problem clarity: local={next((f.status for f in fields if f.field=='problem clarity'), 'missing')}
- environment: local aggregate
- reproduction: local={next((f.status for f in fields if f.field=='reproduction steps'), 'missing')}
- expected vs actual: local={next((f.status for f in fields if f.field=='expected vs actual'), 'missing')}
- evidence: local={next((f.status for f in fields if f.field=='evidence attachment'), 'missing')}

MISSING_INFO
{chr(10).join(f'- {item}' for item in (missing + weak)[:10]) or '- none computed'}

RISK
low (incomplete triage; no code change recommended from this stub)

SECURITY_ROUTING
{security_routing}
"""


def _extract_section_text(body: str, headers: tuple[str, ...]) -> str:
    lines = body.splitlines()
    capturing = False
    captured: list[str] = []
    header_re = re.compile(r"^\s{0,3}#{0,3}\s*([A-Z0-9_ /-]+)\s*$")
    wanted = {h.lower() for h in headers}
    for line in lines:
        match = header_re.match(line.strip().strip("*").strip())
        if match:
            name = match.group(1).strip().lower().replace(" ", "_")
            capturing = any(w.replace(" ", "_") in name or name in w.replace(" ", "_") for w in wanted)
            continue
        if capturing:
            if line.strip().startswith("#") and len(line.strip()) < 40:
                capturing = False
                continue
            captured.append(line)
    return "\n".join(captured).strip()


def _extract_section_lines(body: str, headers: tuple[str, ...]) -> str:
    text = _extract_section_text(body, headers)
    if not text:
        return ""
    keep = [ln for ln in text.splitlines() if ln.strip()][:20]
    return "\n".join(keep)


def _infer_local_decision(local_score: int, findings: str) -> str:
    # Only treat explicit private routing as security-private. Do not match the
    # common public phrasing "... private reporting".
    if re.search(
        r"(?i)SECURITY_ROUTING\s*(?:\r?\n|\s|:|-)*move-to-private|\bsecurity-private\b",
        findings,
    ):
        return "security-private"
    if "fail-closed stub" in findings.lower() or "could not publish a complete analysis" in findings.lower():
        return "needs-rerun" if local_score >= 50 else "needs-info"
    if local_score < 45:
        return "insufficient"
    if local_score < 70:
        return "needs-info"
    return "investigate"


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
