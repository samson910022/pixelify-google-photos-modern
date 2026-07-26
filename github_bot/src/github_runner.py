#!/usr/bin/env python3
"""GitHub Actions entrypoint for Pixelify Infinity AI review bot."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from agent_orchestrator import (
    AgentOrchestrator,
    ReviewContext,
    is_triage_report_publishable,
    validate_triage_report,
)


BOT_DIR = Path(__file__).resolve().parents[1]
CONFIG = json.loads((BOT_DIR / "config" / "bot_config.json").read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Pixelify Infinity GitHub AI bot")
    parser.add_argument(
        "--mode",
        choices=["review", "triage", "comment", "explain"],
        default="review",
        help="Execution mode",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print output instead of posting to GitHub",
    )
    args = parser.parse_args(argv)

    if not os.environ.get("OPENCODE_API_KEY") and not args.dry_run:
        # dry-run without key can still validate local scaffolding with mocked path.
        print("OPENCODE_API_KEY is required", file=sys.stderr)
        return 2

    orchestrator = AgentOrchestrator()
    mode = args.mode

    if mode == "comment":
        mode = _resolve_comment_mode(os.environ.get("COMMENT_BODY", ""))
        if mode is None:
            print("No supported slash command found; skipping")
            return 0

    # Issue threads must never receive PR-style code review output.
    if mode in {"review", "explain"} and not _is_pull_request_context():
        print(f"Non-PR context detected; routing `{mode}` to issue investigation")
        mode = "triage"

    if mode == "triage":
        title = os.environ.get("ISSUE_TITLE") or _event_field("issue", "title") or "Issue"
        body = os.environ.get("ISSUE_BODY") or _event_field("issue", "body") or ""
        body = _redact_secrets(body)
        max_body = int(CONFIG.get("maxIssueBodyChars", 40000))
        if len(body) > max_body:
            body = body[:max_body] + "\n\n[issue body truncated for model context]\n"

        thread_comments = _fetch_issue_comments()
        # OCR may also see media linked from comments.
        ocr_body = body
        if thread_comments:
            ocr_body = body + "\n" + "\n".join(str(c.get("body") or "") for c in thread_comments)
        media_context, _media_labels = orchestrator.enrich_with_media_ocr(
            title=title,
            body=ocr_body,
            changed_files=[],
        )
        report = orchestrator.run_triage(
            title,
            body,
            media_context=media_context,
            thread_comments=thread_comments,
        )
        return _publish_triage(report, dry_run=args.dry_run)

    ctx = _build_review_context(orchestrator)
    if mode == "explain":
        report = orchestrator.run_explainer(ctx)
        return _publish(report, marker=CONFIG["commandMarker"], dry_run=args.dry_run)

    report = orchestrator.run_multi_agent_review(ctx)
    marker = CONFIG["commentMarker"] if mode == "review" else CONFIG["commandMarker"]
    return _publish(report, marker=marker, dry_run=args.dry_run)


def _is_pull_request_context() -> bool:
    """True when the GitHub event is a pull request (including PR issue comments)."""
    if os.environ.get("PR_BASE_SHA") or os.environ.get("PR_HEAD_SHA"):
        return True
    if _event_field("pull_request", "number") is not None:
        return True
    # issue_comment events on PRs expose issue.pull_request
    if _event_field("issue", "pull_request") is not None:
        return True
    event_name = (os.environ.get("GITHUB_EVENT_NAME") or "").lower()
    return event_name in {"pull_request", "pull_request_target"}


def _resolve_comment_mode(comment_body: str) -> str | None:
    text = comment_body.lower()
    if "/review" in text:
        return "review"
    if "/explain" in text:
        return "explain"
    if "/triage" in text:
        return "triage"
    return None


def _build_review_context(orchestrator: AgentOrchestrator) -> ReviewContext:
    title = _event_field("pull_request", "title") or _event_field("issue", "title") or "PR Review"
    body = _event_field("pull_request", "body") or _event_field("issue", "body") or ""
    base_ref, head_ref, git_diff, changed_files = _extract_git_context()
    max_diff = int(CONFIG.get("maxDiffChars", 180000))
    if len(git_diff) > max_diff:
        git_diff = git_diff[:max_diff] + "\n\n[diff truncated for model context]\n"
    git_diff = _redact_secrets(git_diff)
    body = _redact_secrets(body)
    sensitive = orchestrator.classify_sensitive_files(changed_files)
    media_context, media_labels = orchestrator.enrich_with_media_ocr(
        title=title,
        body=body,
        changed_files=changed_files,
    )
    return ReviewContext(
        title=title,
        body=body,
        git_diff=git_diff,
        changed_files=changed_files,
        base_ref=base_ref,
        head_ref=head_ref,
        sensitive_files=sensitive,
        media_context=media_context,
        media_labels=media_labels,
    )


def _extract_git_context() -> tuple[str, str, str, list[str]]:
    base_ref = (
        os.environ.get("PR_BASE_SHA")
        or _event_field("pull_request", "base", "sha")
        or _detect_default_base()
    )
    head_ref = os.environ.get("PR_HEAD_SHA") or _event_field("pull_request", "head", "sha") or "HEAD"

    # For issue_comment on a PR, prefer event SHAs when available.
    if base_ref == "HEAD":
        base_ref = _detect_default_base()

    git_diff = _run_git(["diff", "--no-color", f"{base_ref}...{head_ref}"])
    if not git_diff.strip():
        git_diff = _run_git(["diff", "--no-color", f"{base_ref}", f"{head_ref}"])
    name_only = _run_git(["diff", "--name-only", f"{base_ref}...{head_ref}"])
    if not name_only.strip():
        name_only = _run_git(["diff", "--name-only", f"{base_ref}", f"{head_ref}"])
    changed_files = [line.strip() for line in name_only.splitlines() if line.strip()]
    return str(base_ref), str(head_ref), git_diff, changed_files


def _detect_default_base() -> str:
    for candidate in ("origin/master", "origin/main", "master", "main"):
        probe = _run_git(["rev-parse", "--verify", candidate])
        if probe.strip():
            return candidate
    return "HEAD~1"


def _run_git(args: list[str]) -> str:
    try:
        completed = subprocess.run(
            ["git", *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            return ""
        return completed.stdout
    except Exception:  # noqa: BLE001
        return ""


def _publish_triage(report: str, *, dry_run: bool) -> int:
    triage_cfg = CONFIG.get("triage") or {}
    required = list(triage_cfg.get("requiredSections") or [])
    problems = validate_triage_report(report, required_sections=required or None)
    is_stub = (
        "fail-closed stub" in report.lower()
        or "could not publish a complete analysis" in report.lower()
    )
    if problems and triage_cfg.get("failClosedOnIncomplete", True) and not is_stub:
        print(
            "Triage completeness gate problems: " + "; ".join(problems),
            file=sys.stderr,
        )
        if triage_cfg.get("publishIncompleteStub", True):
            # Last-resort local stub so public comments never carry truncated drafts.
            from agent_orchestrator import (
                AgentOrchestrator,
                build_fail_closed_triage_stub,
            )

            orch = AgentOrchestrator()
            title = os.environ.get("ISSUE_TITLE") or _event_field("issue", "title") or "Issue"
            body = os.environ.get("ISSUE_BODY") or _event_field("issue", "body") or ""
            fields = orch.assess_issue_field_quality(str(title), str(body))
            score = orch.issue_quality_score_estimate(fields)
            stub_body = build_fail_closed_triage_stub(
                title=str(title),
                problems=problems,
                fields=fields,
                local_score=score,
                prior_questions=[],
            )
            report = (
                "# Pixelify Infinity Issue Investigation\n\n"
                "- Generated: local fail-closed gate\n"
                "- Mode: **issue investigation** (not a PR code review)\n\n"
                f"{stub_body}\n"
            )
            print("Replaced incomplete triage draft with fail-closed stub", file=sys.stderr)
        else:
            print("Refusing to publish incomplete triage report", file=sys.stderr)
            return 3
    if "ISSUE_QUALITY_SCORE" not in report:
        print("Triage report missing score after fail-closed handling", file=sys.stderr)
        return 3
    return _publish(report, marker=CONFIG["triageMarker"], dry_run=dry_run)


def _publish(body: str, *, marker: str, dry_run: bool) -> int:
    full_body = f"{marker}\n{body.strip()}\n"
    if dry_run or not os.environ.get("GITHUB_TOKEN") or not os.environ.get("GITHUB_REPOSITORY"):
        print(full_body)
        return 0

    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path or not Path(event_path).exists():
        print(full_body)
        return 0

    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    number = (
        (event.get("pull_request") or {}).get("number")
        or (event.get("issue") or {}).get("number")
    )
    if not number:
        print("Could not determine issue/PR number; printing report only", file=sys.stderr)
        print(full_body)
        return 0

    owner, repo = os.environ["GITHUB_REPOSITORY"].split("/", 1)
    token = os.environ["GITHUB_TOKEN"]
    comments = _github_request(
        "GET",
        f"/repos/{owner}/{repo}/issues/{number}/comments?per_page=100",
        token=token,
    )
    existing = None
    for comment in comments:
        text = comment.get("body") or ""
        if marker in text:
            existing = comment
            break

    payload = {"body": full_body}
    if existing:
        _github_request(
            "PATCH",
            f"/repos/{owner}/{repo}/issues/comments/{existing['id']}",
            token=token,
            payload=payload,
        )
        print(f"Updated existing bot comment {existing['id']}")
    else:
        _github_request(
            "POST",
            f"/repos/{owner}/{repo}/issues/{number}/comments",
            token=token,
            payload=payload,
        )
        print(f"Created bot comment on #{number}")
    return 0


def _fetch_issue_comments(*, max_pages: int = 2) -> list[dict[str, Any]]:
    """Fetch redacted issue comments for thread-aware triage."""
    fixture = os.environ.get("ISSUE_COMMENTS_JSON")
    if fixture:
        try:
            raw_items = json.loads(fixture)
        except json.JSONDecodeError:
            raw_items = []
        return [_normalize_comment(item) for item in raw_items if isinstance(item, dict)]

    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    number = _event_field("issue", "number") or _event_field("pull_request", "number")
    if not token or not repo or not number:
        return []

    owner, name = repo.split("/", 1)
    triage_cfg = CONFIG.get("triage") or {}
    thread_cfg = triage_cfg.get("thread") or {}
    max_comments = int(thread_cfg.get("maxComments", 30))
    max_chars = int(thread_cfg.get("maxChars", 20000))
    markers = [
        CONFIG.get("triageMarker", ""),
        CONFIG.get("commentMarker", ""),
        CONFIG.get("commandMarker", ""),
    ]
    markers = [m for m in markers if m]

    collected: list[dict[str, Any]] = []
    used_chars = 0
    for page in range(1, max_pages + 1):
        path = f"/repos/{owner}/{name}/issues/{number}/comments?per_page=100&page={page}"
        try:
            batch = _github_request("GET", path, token=token)
        except Exception as exc:  # noqa: BLE001
            print(f"Warning: failed to fetch issue comments: {exc}", file=sys.stderr)
            break
        if not isinstance(batch, list) or not batch:
            break
        for item in batch:
            body = _redact_secrets(str(item.get("body") or ""))
            if not body.strip():
                continue
            # Skip pure sticky reports to avoid self-echo; keep a compact signal via is_bot.
            is_sticky = any(marker in body for marker in markers)
            user = item.get("user") or {}
            login = str(user.get("login") or "unknown")
            user_type = str(user.get("type") or "")
            is_bot = (
                is_sticky
                or user_type.lower() == "bot"
                or login.endswith("[bot]")
                or login.endswith("-bot")
            )
            normalized = {
                "login": login,
                "author": login,
                "body": body,
                "is_bot": is_bot,
                "role": "bot" if is_bot else "user",
                "created_at": item.get("created_at"),
            }
            if used_chars + len(body) > max_chars:
                normalized["body"] = body[: max(0, max_chars - used_chars)] + "\n[truncated]"
                collected.append(normalized)
                return collected[:max_comments]
            collected.append(normalized)
            used_chars += len(body)
            if len(collected) >= max_comments:
                return collected
    return collected


def _normalize_comment(item: dict[str, Any]) -> dict[str, Any]:
    body = _redact_secrets(str(item.get("body") or ""))
    login = str(item.get("login") or item.get("author") or "unknown")
    is_bot = bool(item.get("is_bot"))
    return {
        "login": login,
        "author": login,
        "body": body,
        "is_bot": is_bot,
        "role": "bot" if is_bot else str(item.get("role") or "user"),
        "created_at": item.get("created_at"),
    }


def _github_request(method: str, path: str, *, token: str, payload: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.github.com{path}",
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "pixelify-infinity-ai-review-bot/1.0",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API {method} {path} failed: HTTP {exc.code}: {detail[:500]}") from exc


def _event_field(*keys: str):
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path or not Path(event_path).exists():
        return None
    data = json.loads(Path(event_path).read_text(encoding="utf-8"))
    cursor = data
    for key in keys:
        if not isinstance(cursor, dict) or key not in cursor:
            return None
        cursor = cursor[key]
    return cursor


def _redact_secrets(text: str) -> str:
    patterns = [
        r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        r"(?i)(api[_-]?key|token|password|secret|storePassword|keyPassword)\s*[:=]\s*['\"][^'\"]{6,}['\"]",
        r"(?i)(authorization:\s*bearer\s+)[a-z0-9._\-]+",
    ]
    redacted = text
    for pattern in patterns:
        redacted = re.sub(pattern, "[REDACTED]", redacted, flags=re.DOTALL)
    return redacted


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Bot failed: {exc}", file=sys.stderr)
        raise
