#!/usr/bin/env python3
"""Unit tests for Pixelify Infinity AI review bot helpers."""

from __future__ import annotations

import importlib.util
import io
import json
import os
import sys
import tempfile
import time
import unittest
import urllib.error
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "github_bot" / "src"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


llm_client_mod = _load("llm_client", SRC / "llm_client.py")
media_ocr_mod = _load("media_ocr", SRC / "media_ocr.py")
orchestrator_mod = _load("agent_orchestrator", SRC / "agent_orchestrator.py")
runner_mod = _load("github_runner", SRC / "github_runner.py")


class AiReviewBotTests(unittest.TestCase):
    def _fake_pem_block(self) -> str:
        # Assemble at runtime so static scanners do not treat the fixture as a real key.
        header = "-----BEGIN " + "PRIVATE KEY-----"
        footer = "-----END " + "PRIVATE KEY-----"
        body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC"
        return f"{header}\n{body}\n{footer}\n"

    def test_redact_secrets(self) -> None:
        sample = (
            "token: 'super-secret-value'\n"
            "Authorization: Bearer abcdefghijklmnop\n"
            + self._fake_pem_block()
        )
        redacted = runner_mod._redact_secrets(sample)
        self.assertNotIn("super-secret-value", redacted)
        self.assertNotIn("abcdefghijklmnop", redacted)
        self.assertIn("[REDACTED]", redacted)
        self.assertNotIn("PRIVATE KEY", redacted)

    def test_comment_mode_resolution(self) -> None:
        self.assertEqual(runner_mod._resolve_comment_mode("please /review this"), "review")
        self.assertEqual(runner_mod._resolve_comment_mode("/explain for product"), "explain")
        self.assertEqual(runner_mod._resolve_comment_mode("needs /triage"), "triage")
        self.assertIsNone(runner_mod._resolve_comment_mode("just a normal comment"))

    def test_deterministic_findings_flags_apk_and_private_key(self) -> None:
        ctx = orchestrator_mod.ReviewContext(
            title="t",
            body="",
            git_diff=self._fake_pem_block(),
            changed_files=["app/build/outputs/apk/release/app-release.apk"],
            base_ref="a",
            head_ref="b",
            sensitive_files=[],
        )
        dummy = SimpleNamespace(config={})
        findings = orchestrator_mod.AgentOrchestrator.deterministic_findings(dummy, ctx)
        self.assertTrue(any("private key" in item.lower() for item in findings))
        self.assertTrue(any("app-release.apk" in item for item in findings))

    def test_discover_media_from_markdown_and_files(self) -> None:
        body = (
            "See screenshot ![scope](https://github.com/user-attachments/assets/abcd-1234) "
            "and https://example.com/log.png"
        )
        items = media_ocr_mod.discover_media_items(
            body=body,
            changed_files=["docs/screenshot.webp", "README.md"],
            extensions=[".png", ".webp", ".jpg"],
            max_items=5,
            workspace=ROOT,
        )
        labels = [item.label for item in items]
        self.assertTrue(any("user-attachments" in label or "abcd-1234" in label for label in labels))
        self.assertTrue(any(label.endswith("log.png") or "log.png" in label for label in labels))
        self.assertIn("docs/screenshot.webp", labels)

    def test_messages_have_media_detection(self) -> None:
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "ocr"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}},
                ],
            }
        ]
        self.assertTrue(llm_client_mod._messages_have_media(messages))
        self.assertFalse(
            llm_client_mod._messages_have_media([{"role": "user", "content": "plain text"}])
        )

    def test_unusable_completion_rejects_length_and_short(self) -> None:
        self.assertIsNotNone(
            llm_client_mod.unusable_completion_reason("hello world " * 50, "length", min_chars=200)
        )
        self.assertIsNotNone(
            llm_client_mod.unusable_completion_reason("too short", "stop", min_chars=200)
        )
        long_ok = "x" * 250
        self.assertIsNone(
            llm_client_mod.unusable_completion_reason(long_ok, "stop", min_chars=200)
        )
        self.assertIsNotNone(
            llm_client_mod.unusable_completion_reason(
                long_ok,
                "stop",
                min_chars=200,
                required_markers=["ISSUE_QUALITY_SCORE"],
            )
        )

    def test_llm_fallback_after_truncated_primary(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.models = {
            "primary-free": {"id": "primary-free", "input": ["text"]},
            "fallback-free": {"id": "fallback-free", "input": ["text"]},
        }
        client.fallback_models = ["fallback-free"]
        client.api_key = "test-key"
        client.base_url = "https://example.invalid/v1/"
        client.timeout_seconds = 5
        client.min_response_chars = 50
        client.reject_finish_reasons = {"length", "content_filter"}
        client.same_model_retry_on_length = 0

        payloads = [
            {
                "choices": [
                    {
                        "finish_reason": "length",
                        "message": {"content": "CLASSIFICATION\n" + ("truncated " * 40)},
                    }
                ]
            },
            {
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": (
                                "CLASSIFICATION\nACTIONABILITY\nSUMMARY\n"
                                + ("complete investigation body. " * 20)
                            )
                        },
                    }
                ]
            },
        ]

        class _Resp:
            def __init__(self, payload):
                self._payload = payload

            def read(self):
                return json.dumps(self._payload).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def fake_urlopen(request, timeout=0):  # noqa: ARG001
            return _Resp(payloads.pop(0))

        with mock.patch.object(llm_client_mod.urllib.request, "urlopen", side_effect=fake_urlopen):
            text = client.chat_completion(
                "primary-free",
                [{"role": "user", "content": "triage"}],
                max_tokens=128,
                min_chars=50,
            )
        self.assertIn("complete investigation body", text)
        self.assertEqual(payloads, [])

    def test_issue_quality_hints_field_quality(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        hints = orch.issue_quality_hints(
            "Something broken",
            "It does not work on my phone.",
        )
        self.assertTrue(any(item.startswith("missing:") for item in hints))
        self.assertTrue(any("problem clarity" in item for item in hints))

        rich = orch.issue_quality_hints(
            "Unlock fails on Android 15",
            """
            Pixelify Infinity v1.0.4
            LSPosed JingMatrix
            Google Photos 7.83
            Steps to reproduce:
            1. Enable module scoped to Photos
            2. Force-stop Photos and reopen
            Expected: unlock works
            Actual: still locked, no toast
            screenshot.png https://example.com/a.png
            """,
        )
        self.assertTrue(any(item.startswith("strong: reproduction steps") for item in rich))
        self.assertTrue(any(item.startswith("strong: expected vs actual") for item in rich))
        self.assertTrue(any(item.startswith("strong: module version") for item in rich))
        # bare "photos" alone should not be strong without version/package.
        weak_photos = orch.issue_quality_hints(
            "photos broken",
            "photos not working",
        )
        self.assertTrue(
            any(
                ("google photos context" in item and item.startswith(("weak:", "missing:")))
                for item in weak_photos
            )
        )

    def test_field_quality_version_and_score_bounds(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        fields = orch.assess_issue_field_quality(
            "latest broken",
            "I installed the latest Pixelify and it failed.",
        )
        version = next(f for f in fields if f.field == "module version")
        self.assertEqual(version.status, "weak")
        score = orch.issue_quality_score_estimate(fields)
        self.assertGreaterEqual(score, 0)
        self.assertLessEqual(score, 100)

    def test_validate_triage_report_complete_and_incomplete(self) -> None:
        complete = """
CLASSIFICATION
bug / no-effect

ACTIONABILITY
needs-info
BLOCKING_MISSING: toast/VERIFY result
NEXT_ACTION_REPORTER: provide VERIFY toast outcome
NEXT_ACTION_MAINTAINER: wait for diagnostics

SUMMARY
Reporter says module has no effect on Photos.

EVIDENCE_USED
- issue body mentions Android 16 and LSPosed

ROOT_CAUSE_HYPOTHESES
1. Scope/load issue — confidence: medium — validate with toast/VERIFY

REPORTER_NEXT_STEPS
- Provide toast/VERIFY result

MAINTAINER_NEXT_STEPS
- Wait for reporter diagnostics

SUGGESTED_LABELS
needs-info, bug

ISSUE_QUALITY_SCORE: 58 (needs-info)

QUALITY_BREAKDOWN
- problem clarity: 12/20
- environment: 14/20
- reproduction: 8/20
- expected vs actual: 10/20
- evidence: 14/20

MISSING_INFO
- toast/VERIFY outcome

RISK
low — incomplete diagnostics

SECURITY_ROUTING
public
"""
        self.assertEqual(orchestrator_mod.validate_triage_report(complete), [])
        self.assertTrue(orchestrator_mod.is_triage_report_publishable(complete))
        incomplete = "SUMMARY\nModule does nothing on Resukisu ROM"
        problems = orchestrator_mod.validate_triage_report(incomplete)
        self.assertTrue(problems)
        self.assertFalse(orchestrator_mod.is_triage_report_publishable(incomplete))

    def test_extract_prior_questions_and_thread_prompt(self) -> None:
        comments = [
            {
                "login": "maintainer",
                "is_bot": False,
                "body": "Did you see a VERIFY toast? Please provide logcat tag Pixelify.",
            },
            {
                "login": "bot",
                "is_bot": True,
                "body": "<!-- PIXELIFY_AI_TRIAGE_REPORT -->\nREPORTER_NEXT_STEPS\n- Share Photos version\nMISSING_INFO\n- scope screenshot\n",
            },
        ]
        prior = orchestrator_mod.extract_prior_questions(
            comments,
            bot_markers=["<!-- PIXELIFY_AI_TRIAGE_REPORT -->"],
        )
        self.assertTrue(any("VERIFY toast" in item or "logcat" in item for item in prior))
        self.assertTrue(any("Photos version" in item for item in prior))

        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        prompt = orch._issue_user_prompt(
            "title",
            "body",
            quality_hints=["missing: module version — none"],
            thread_comments=comments,
            prior_questions=prior,
        )
        self.assertIn("Questions already asked", prompt)
        self.assertIn("Issue thread comments", prompt)
        self.assertIn("VERIFY toast", prompt)

    def test_run_triage_fail_closed_stub_and_hides_models(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        orch.config = {
            "roles": {
                "triage_agent": {
                    "model": "hidden-model",
                    "promptFile": "./prompts/roles/triage_agent.md",
                    "temperature": 0.1,
                    "maxTokens": 10,
                }
            },
            "triageMarker": "<!-- PIXELIFY_AI_TRIAGE_REPORT -->",
            "commentMarker": "<!-- PIXELIFY_AI_REVIEW_REPORT -->",
            "commandMarker": "<!-- PIXELIFY_AI_COMMAND_REPORT -->",
            "triage": {
                "minResponseChars": 400,
                "requiredSections": orchestrator_mod.DEFAULT_REQUIRED_TRIAGE_SECTIONS,
            },
        }
        orch.soul = "soul"
        orch.llm = SimpleNamespace()

        def fake_run_role(role_id, user_prompt, min_chars=None, required_markers=None):  # noqa: ARG001
            self.assertIn("not a pull-request code review", user_prompt.lower())
            return orchestrator_mod.RoleResult(
                role=role_id,
                model="should-not-appear",
                verdict="COMMENT",
                findings="SUMMARY\ntruncated mid sentence about Resukisu ROM",
            )

        orch._run_role = fake_run_role  # type: ignore[method-assign]
        orch.build_issue_repo_knowledge = lambda **kwargs: ""  # type: ignore[method-assign]
        issue_report = orch.run_triage(
            "title",
            "body missing details",
            thread_comments=[
                {
                    "login": "maintainer",
                    "is_bot": False,
                    "body": "Do you see a toast?",
                }
            ],
        )
        self.assertIn("Issue Investigation", issue_report)
        self.assertIn("issue investigation", issue_report.lower())
        self.assertIn("fail-closed", issue_report.lower())
        self.assertIn("ISSUE_QUALITY_SCORE", issue_report)
        self.assertNotIn("should-not-appear", issue_report)
        self.assertNotIn("Model:", issue_report)
        self.assertNotIn("PR Code Review", issue_report)
        self.assertNotIn("FINAL_VERDICT", issue_report)
        self.assertIn("Already-asked questions", issue_report)

        ctx = orchestrator_mod.ReviewContext(
            title="Add feature",
            body="desc",
            git_diff="diff",
            changed_files=["app/src/main/java/x.kt"],
            base_ref="aaaaaaaaaaaa",
            head_ref="bbbbbbbbbbbb",
            sensitive_files=[],
        )
        pr_report = orchestrator_mod.AgentOrchestrator._format_review_report(
            SimpleNamespace(
                deterministic_findings=lambda _ctx: [],
            ),
            ctx,
            [
                orchestrator_mod.RoleResult(
                    role="android_xposed",
                    model="hidden-pr-model",
                    verdict="APPROVE",
                    findings="looks good",
                )
            ],
        )
        self.assertIn("PR Code Review", pr_report)
        self.assertIn("FINAL_VERDICT=APPROVE", pr_report)
        self.assertNotIn("hidden-pr-model", pr_report)
        self.assertNotIn("| Model |", pr_report)
        self.assertNotIn("Issue Investigation", pr_report)

    def test_triage_prompt_has_decision_first_and_playbook(self) -> None:
        prompt = (ROOT / "github_bot/prompts/roles/triage_agent.md").read_text(encoding="utf-8")
        self.assertIn("CLASSIFICATION", prompt)
        self.assertIn("ACTIONABILITY", prompt)
        self.assertIn("VERIFY", prompt)
        self.assertIn("com.google.android.apps.photos", prompt)
        self.assertIn("questions already asked", prompt.lower())
        self.assertIn("only **new** asks", prompt.lower())
        self.assertIn("Pixelify load / VERIFY playbook", prompt)

    def test_fetch_issue_comments_from_fixture_redacts(self) -> None:
        fixture = json.dumps(
            [
                {
                    "login": "user1",
                    "body": "password: 'super-secret-value' and please provide logs?",
                    "is_bot": False,
                }
            ]
        )
        old = os.environ.get("ISSUE_COMMENTS_JSON")
        os.environ["ISSUE_COMMENTS_JSON"] = fixture
        try:
            comments = runner_mod._fetch_issue_comments()
        finally:
            if old is None:
                os.environ.pop("ISSUE_COMMENTS_JSON", None)
            else:
                os.environ["ISSUE_COMMENTS_JSON"] = old
        self.assertEqual(len(comments), 1)
        self.assertNotIn("super-secret-value", comments[0]["body"])
        self.assertIn("[REDACTED]", comments[0]["body"])

    def test_non_pr_review_routes_to_issue_investigation(self) -> None:
        self.assertFalse(runner_mod._is_pull_request_context())

    def test_config_models_and_fallbacks(self) -> None:
        bot = json.loads((ROOT / "github_bot/config/bot_config.json").read_text(encoding="utf-8"))
        llm = json.loads(
            (ROOT / "github_bot/config/LLM_config.example.json").read_text(encoding="utf-8")
        )
        opencode_model_ids = {m["id"] for m in llm["opencode"]["models"]}
        self.assertIn("deepseek-v4-flash-free", opencode_model_ids)
        self.assertIn("ling-3.0-flash-free", opencode_model_ids)
        self.assertIn("laguna-s-2.1-free", opencode_model_ids)
        self.assertIn("mimo-v2.5-free", opencode_model_ids)
        self.assertIn("grok-code", opencode_model_ids)
        self.assertIn("glm-5-free", opencode_model_ids)

        cpa_model_ids = {m["id"] for m in llm["cpa"]["models"]}
        self.assertIn("gemini-3.7-flash-high", cpa_model_ids)
        self.assertIn("grok-4.6", cpa_model_ids)
        self.assertIn("claude-opus-4-6-thinking", cpa_model_ids)
        self.assertIn("gemini-3.6-flash-high", cpa_model_ids)

        self.assertEqual(bot["codingModels"], ["gemini-3.7-flash-high", "grok-4.6", "claude-opus-4-6-thinking"])
        self.assertEqual(bot["roles"]["identity_safety"]["model"], "gemini-3.7-flash-high")
        self.assertEqual(bot["roles"]["android_xposed"]["model"], "claude-opus-4-6-thinking")
        self.assertEqual(bot["roles"]["docs_public"]["model"], "gemini-3.7-flash-high")
        self.assertEqual(bot["roles"]["triage_agent"]["model"], "grok-4.6")
        self.assertEqual(bot["roles"]["explainer_agent"]["model"], "grok-4.6")
        self.assertEqual(bot["mediaOcr"]["model"], "gemini-3.7-flash-high")
        self.assertEqual(bot["fallbackModel"], "gemini-3.7-flash-high")
        self.assertTrue(bot.get("dynamicModelDiscovery"))
        self.assertIn("gemini-3.7-flash-high", bot["fallbackModels"])
        self.assertIn("grok-4.6", bot["fallbackModels"])
        self.assertIn("claude-opus-4-6-thinking", bot["fallbackModels"])
        self.assertIn("deepseek-v4-flash-free", bot["fallbackModels"])
        self.assertGreaterEqual(bot["roles"]["triage_agent"]["maxTokens"], 6144)
        self.assertIn("CLASSIFICATION", bot["triage"]["requiredSections"])
        self.assertTrue(bot["triage"]["failClosedOnIncomplete"])
        self.assertTrue(llm["opencode"].get("stream"))
        self.assertTrue(llm["cpa"].get("stream"))
        self.assertEqual(bot["llmResponseGate"]["minResponseChars"], 0)

    def test_cpa_responses_parsing_and_model_resolution(self) -> None:
        cpa_payload = {
            "output": [
                {
                    "type": "message",
                    "content": [
                        {"type": "output_text", "text": "CLASSIFICATION\nbug\n\nACTIONABILITY\nneeds-info\n\nSUMMARY\nTest summary"}
                    ],
                }
            ],
            "finish_reason": "stop",
        }
        content, reason = llm_client_mod._extract_response_content_and_reason(cpa_payload, "responses")
        self.assertIn("CLASSIFICATION", content)
        self.assertIn("Test summary", content)
        self.assertEqual(reason, "stop")

    def test_cpa_env_interpolation(self) -> None:
        raw = '{"cpa_key": "${CPA_API_KEY}", "cpa_url": "${CPA_BASE_URL}"}'
        with mock.patch.dict(os.environ, {"CPA_API_KEY": "secret-cpa-123", "CPA_BASE_URL": "https://cpa.example/v1"}):
            interpolated = llm_client_mod.interpolate_env_vars(raw)
            data = json.loads(interpolated)
            self.assertEqual(data["cpa_key"], "secret-cpa-123")
            self.assertEqual(data["cpa_url"], "https://cpa.example/v1")


    def test_stub_quality_band_and_security_routing(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        rich_fields = orch.assess_issue_field_quality(
            "Unlock fails on Android 15",
            """
            Pixelify Infinity v1.0.4
            LSPosed JingMatrix
            Google Photos 7.83
            Steps:
            1. Enable module
            2. Open Photos
            Expected: unlock works
            Actual: still locked
            https://example.com/a.png
            """,
        )
        score = orch.issue_quality_score_estimate(rich_fields)
        stub = orchestrator_mod.build_fail_closed_triage_stub(
            title="Unlock fails",
            problems=["missing section: SUMMARY"],
            fields=rich_fields,
            local_score=score,
            prior_questions=[],
        )
        self.assertIn(f"ISSUE_QUALITY_SCORE: {score} (", stub)
        if score < 50:
            self.assertIn("(insufficient)", stub)
        else:
            self.assertNotIn("(insufficient)", stub)
        if score >= 80:
            self.assertIn("(actionable)", stub)
        sec = orchestrator_mod.build_fail_closed_triage_stub(
            title="security vulnerability leak report",
            problems=["truncated"],
            fields=rich_fields,
            local_score=score,
            prior_questions=[],
        )
        self.assertIn("move-to-private", sec)
        self.assertNotIn("SECURITY_ROUTING\npublic", sec)

    def test_short_pr_findings_not_rejected_by_default_min_chars(self) -> None:
        reason = llm_client_mod.unusable_completion_reason(
            "No identity/safety issues found.\n\nVERDICT: APPROVE",
            "stop",
            min_chars=0,
        )
        self.assertIsNone(reason)
        # triage still rejects short
        self.assertIsNotNone(
            llm_client_mod.unusable_completion_reason(
                "No identity/safety issues found.\n\nVERDICT: APPROVE",
                "stop",
                min_chars=200,
            )
        )

    def test_publish_triage_replaces_incomplete_non_stub(self) -> None:
        incomplete = "# Pixelify Infinity Issue Investigation\n\nSUMMARY\ncut off mid sentence about Resukisu"
        with mock.patch.object(runner_mod, "_publish", return_value=0) as pub:
            code = runner_mod._publish_triage(incomplete, dry_run=True)
        self.assertEqual(code, 0)
        published = pub.call_args.args[0]
        self.assertIn("fail-closed", published.lower())
        self.assertIn("ISSUE_QUALITY_SCORE", published)



    def test_infer_local_decision_public_vs_private(self) -> None:
        public = (
            "SECURITY_ROUTING\n"
            "public — unless reporter later includes exploit details; then move to SECURITY.md private reporting\n"
        )
        private = "SECURITY_ROUTING\nmove-to-private — potential security-sensitive title\n"
        self.assertNotEqual(orchestrator_mod._infer_local_decision(70, public), "security-private")
        self.assertEqual(orchestrator_mod._infer_local_decision(70, private), "security-private")
        self.assertEqual(
            orchestrator_mod._infer_local_decision(70, "Local decision: security-private"),
            "security-private",
        )

    def test_sanitize_public_error_hides_multi_model_chain(self) -> None:
        raw = (
            "deepseek-v4-flash-free: unusable completion (finish_reason=length); "
            "north-mini-code-free: unusable completion (too short: 12 < 400 chars); "
            "big-pickle: timeout"
        )
        cleaned = orchestrator_mod.sanitize_public_error_text(raw)
        for token in (
            "deepseek-v4-flash-free",
            "north-mini-code-free",
            "big-pickle",
            "deepseek",
            "north-mini",
        ):
            self.assertNotIn(token, cleaned.lower())

        orch = orchestrator_mod.AgentOrchestrator()
        def boom(role_id, user_prompt, **kwargs):  # noqa: ANN001, ARG001
            return orchestrator_mod.RoleResult(
                role=role_id,
                model="should-not-publish",
                verdict="COMMENT",
                findings=f"Role `{role_id}` failed: {orchestrator_mod.sanitize_public_error_text(raw)}",
            )

        orch._run_role = boom  # type: ignore[method-assign]
        report = orch.run_triage(
            "Unlock fails on Android 15",
            "No toast after enable. Module 1.0.4 Photos 7.83",
        )
        low = report.lower()
        self.assertIn("fail-closed stub", low)
        for token in (
            "deepseek-v4-flash-free",
            "north-mini-code-free",
            "big-pickle",
            "should-not-publish",
        ):
            self.assertNotIn(token, low)
        self.assertNotIn("Local decision: **security-private**", report)

    def test_android_colon_version_is_strong(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator()
        fields = orch.assess_issue_field_quality(
            "No effect",
            "Android: 16\nLSPosed: JingMatrix\nPixelify Infinity v1.0.4\nGoogle Photos 7.83",
        )
        android = next(f for f in fields if f.field == "android version")
        self.assertEqual(android.status, "strong")




    def test_memory_leak_title_not_security_private(self) -> None:
        fields = orchestrator_mod.AgentOrchestrator().assess_issue_field_quality(
            "Memory leak when opening album",
            "Android 15 Photos 7.8 Pixelify Infinity 1.0.4",
        )
        stub = orchestrator_mod.build_fail_closed_triage_stub(
            title="Memory leak when opening album",
            problems=["missing section: CLASSIFICATION"],
            fields=fields,
            local_score=88,
            prior_questions=[],
        )
        self.assertNotIn("move-to-private", stub)
        self.assertIn("Local decision: needs-rerun", stub)
        self.assertEqual(
            orchestrator_mod._infer_local_decision(88, stub),
            "needs-rerun",
        )

    def test_sanitize_preserves_validation_messages(self) -> None:
        msg = "missing section: CLASSIFICATION"
        self.assertEqual(orchestrator_mod.sanitize_public_error_text(msg), msg)
        role = "role failure: Role `triage_agent` failed: unusable completion"
        self.assertIn("Role `triage_agent` failed", orchestrator_mod.sanitize_public_error_text(role))
        chain = "deepseek-v4-flash-free: unusable; north-mini-code-free: short; big-pickle: timeout"
        cleaned = orchestrator_mod.sanitize_public_error_text(chain)
        self.assertNotIn("deepseek", cleaned.lower())
        self.assertNotIn("big-pickle", cleaned.lower())




    def test_field_quality_avoids_list_false_strong(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator()
        fields = orch.assess_issue_field_quality(
            "broken",
            "broken on android\n1. install\n2. enable\nForce stop Photos\n3. Open app",
        )
        by = {f.field: f.status for f in fields}
        self.assertEqual(by["android version"], "weak")
        self.assertEqual(by["google photos context"], "weak")

        strong = orch.assess_issue_field_quality(
            "No effect",
            "Android: 16\nGoogle Photos: 7.83\nLSPosed JingMatrix",
        )
        by2 = {f.field: f.status for f in strong}
        self.assertEqual(by2["android version"], "strong")
        self.assertEqual(by2["google photos context"], "strong")

    def test_extract_suggested_labels_inline_and_multiline(self) -> None:
        inline = "SUGGESTED_LABELS\nbug, needs-info, not-a-real-label"
        # inline form with colon on same line
        one_line = runner_mod._extract_suggested_labels(
            "SUMMARY\nok\n\nSUGGESTED_LABELS: bug, needs-info, SECURITY\n\nRISK\nlow\n"
        )
        self.assertEqual(one_line, ["bug", "needs-info", "SECURITY"])

        multi = runner_mod._extract_suggested_labels(
            "SUGGESTED_LABELS\n- bug\n- needs-info\n- device-specific\n\nRISK\nlow\n"
        )
        self.assertEqual(multi, ["bug", "needs-info", "device-specific"])

        none_labels = runner_mod._extract_suggested_labels("SUGGESTED_LABELS: none\n")
        self.assertEqual(none_labels, [])

    def test_maybe_apply_suggested_labels_allowlist_and_dry_run(self) -> None:
        report = (
            "CLASSIFICATION\nbug\n\n"
            "SUGGESTED_LABELS: bug, needs-info, totally-unknown, security\n\n"
            "RISK\nlow\n"
        )
        # Force CONFIG lookup path: function reads runner_mod.CONFIG global
        original = runner_mod.CONFIG
        try:
            runner_mod.CONFIG = {
                **original,
                "triage": {
                    **(original.get("triage") or {}),
                    "applySuggestedLabels": True,
                    "labelAllowlist": ["bug", "needs-info", "security"],
                },
            }
            with mock.patch.dict(os.environ, {}, clear=False):
                # no token => dry-run style print path when dry_run True
                with mock.patch("builtins.print") as mocked_print:
                    runner_mod._maybe_apply_suggested_labels(report, dry_run=True)
                    printed = " ".join(
                        str(call.args[0]) for call in mocked_print.call_args_list if call.args
                    )
                    self.assertIn("bug", printed)
                    self.assertIn("needs-info", printed)
                    self.assertIn("security", printed)
                    self.assertNotIn("totally-unknown", printed)
        finally:
            runner_mod.CONFIG = original

    def test_sanitize_public_error_hides_cpa_models(self) -> None:
        raw = (
            "gemini-3.7-flash-high: unusable completion (finish_reason=length); "
            "grok-4.6: unusable completion (too short: 20 < 400 chars); "
            "claude-opus-4-6-thinking: timeout; "
            "gemini-3.6-flash-high: connection reset"
        )
        cleaned = orchestrator_mod.sanitize_public_error_text(raw)
        for token in (
            "gemini-3.7-flash-high",
            "grok-4.6",
            "claude-opus-4-6-thinking",
            "gemini-3.6-flash-high",
            "gemini",
            "grok",
            "claude",
            "opus",
        ):
            self.assertNotIn(token, cleaned.lower())

    def test_sanitize_model_name_for_display(self) -> None:
        self.assertEqual(llm_client_mod.sanitize_model_name_for_display("gemini-3.7-flash-high"), "gemini-3.7-flash")
        self.assertEqual(llm_client_mod.sanitize_model_name_for_display("deepseek-v4-flash-free"), "deepseek-v4-flash")
        self.assertEqual(llm_client_mod.sanitize_model_name_for_display("grok-4.6"), "grok-4.6")
        self.assertEqual(llm_client_mod.sanitize_model_name_for_display(""), "")

    def test_dynamic_fallback_chain_prioritization(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.providers = {}
        client.models = {}
        client._discovery_cache = {
            "opencode": ["deepseek-v4-flash-free", "mimo-v2.5-free"],
            "cpa": ["gemini-3.7-flash-high", "grok-4.6", "claude-opus-4-6-thinking", "vertex/imagen-3"],
        }
        client._discovery_cache_time = 1e9
        client._discovery_ttl_seconds = 600.0

        chain = client.get_dynamic_fallback_chain(
            configured_fallbacks=["gemini-3.7-flash-high", "grok-4.6", "claude-opus-4-6-thinking"]
        )
        self.assertEqual(chain[0], "gemini-3.7-flash-high")
        self.assertEqual(chain[1], "grok-4.6")
        self.assertEqual(chain[2], "claude-opus-4-6-thinking")
        self.assertIn("deepseek-v4-flash-free", chain)
        self.assertIn("mimo-v2.5-free", chain)
        # vertex/imagen should be excluded from text/code fallbacks
        self.assertNotIn("vertex/imagen-3", chain)

    def test_multimodal_message_flattening_for_text_models(self) -> None:
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Inspect this:"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/screenshot.png"}},
                ],
            }
        ]
        text_only_model = {"id": "text-model", "input": ["text"]}
        multimodal_model = {"id": "multi-model", "input": ["text", "image"]}

        flattened = llm_client_mod._prepare_messages_for_model(messages, text_only_model)
        self.assertIsInstance(flattened[0]["content"], str)
        self.assertIn("[image omitted for text-only model", flattened[0]["content"])

        preserved = llm_client_mod._prepare_messages_for_model(messages, multimodal_model)
        self.assertIsInstance(preserved[0]["content"], list)

    def test_config_apply_labels_defaults(self) -> None:
        cfg_path = ROOT / "github_bot" / "config" / "bot_config.json"
        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        triage = cfg["triage"]
        self.assertTrue(triage.get("applySuggestedLabels"))
        self.assertIn("needs-info", triage.get("labelAllowlist") or [])
        self.assertIn("android-17", triage.get("labelAllowlist") or [])

    def test_sanitize_public_error_hides_providers_and_grok_code(self) -> None:
        raw = (
            "API key is missing for provider 'cpa' (set OPENCODE_API_KEY or CPA_API_KEY); "
            "grok-code: connection timeout; "
            "Base URL is not configured for provider 'opencode'; "
            "provider='cpa': unreachable; cpa_proxy: timeout; cpa_api: bad gateway; "
            "cpa-llm-proxy returned HTTP 524; vertex/imagen-3: no candidates; "
            "No model candidates for vertex/imagen-3; "
            "HTTP 500 from https://vertexai.example/v1/responses: upstream 504; "
            "opencodeai.example refused; mimo.example unreachable; geminiapi.example: 429"
        )
        cleaned = orchestrator_mod.sanitize_public_error_text(raw)
        for token in (
            "cpa",
            "opencode",
            "vertex",
            "imagen",
            "grok-code",
            "grok",
        ):
            self.assertNotIn(token, cleaned.lower())
        self.assertNotIn("[provider]'", cleaned)
        self.assertNotIn("'[provider]", cleaned)

    def test_sanitize_preserves_validation_messages_without_provider(self) -> None:
        msg = "missing section: CLASSIFICATION; role `triage_agent` failed: insufficient evidence"
        self.assertEqual(orchestrator_mod.sanitize_public_error_text(msg), msg)

    def test_fallback_openai_call_success_and_error(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.providers = {}
        client.models = {}
        client.enable_streaming = False
        client.reject_finish_reasons = set()

        # Success case
        resp_payload = {
            "choices": [{"message": {"content": "CLASSIFICATION\nbug\nSUMMARY\nok"}, "finish_reason": "stop"}]
        }
        mock_resp = mock.MagicMock()
        mock_resp.headers = {"Content-Type": "application/json"}
        mock_resp.read.return_value = json.dumps(resp_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch("urllib.request.urlopen", return_value=mock_resp):
            text = client._fallback_openai_call(
                base_url="https://api.example.com/v1/",
                api_key="key-123",
                model_id="grok-4.6",
                messages=[{"role": "user", "content": "hi"}],
                temperature=0.2,
                max_tokens=100,
                timeout=30,
                min_chars=0,
                required_markers=None,
            )
            self.assertIn("CLASSIFICATION", text)

        # HTTP error case wrapping in LLMClientError
        err_fp = io.BytesIO(b"server down")
        http_err = urllib.error.HTTPError("https://api.example.com", 500, "Internal Error", {}, err_fp)
        try:
            with mock.patch("urllib.request.urlopen", side_effect=http_err):
                with self.assertRaises(llm_client_mod.LLMClientError) as ctx:
                    client._fallback_openai_call(
                        base_url="https://api.example.com/v1/",
                        api_key="key-123",
                        model_id="grok-4.6",
                        messages=[{"role": "user", "content": "hi"}],
                        temperature=0.2,
                        max_tokens=100,
                        timeout=30,
                        min_chars=0,
                        required_markers=None,
                    )
                self.assertIn("HTTP 500", str(ctx.exception))
        finally:
            http_err.close()
            err_fp.close()

    def test_parse_sse_stream_chunks_and_done(self) -> None:
        # Multi-chunk CPA format
        lines = [
            b"data: {\"delta\": \"Hello \"}\n",
            b"data: {\"delta\": \"world!\"}\n",
            b"data: [DONE]\n",
        ]
        text, reason = llm_client_mod._parse_sse_stream(iter(lines), "responses")
        self.assertEqual(text, "Hello world!")

        # OpenAI format with finish_reason
        openai_lines = [
            b": keep-alive\n",
            b"data: {\"choices\": [{\"delta\": {\"content\": \"OpenAI \"}}]}\n",
            b"data: {\"choices\": [{\"delta\": {\"content\": \"stream\"}, \"finish_reason\": \"stop\"}]}\n",
            b"data: [DONE]\n",
        ]
        text2, reason2 = llm_client_mod._parse_sse_stream(iter(openai_lines), "openai-completions")
        self.assertEqual(text2, "OpenAI stream")
        self.assertEqual(reason2, "stop")

        # Malformed JSON and empty lines are skipped gracefully
        malformed = [
            b"data: not-json\n",
            b"\n",
            b"data: {\"delta\": \"recovered\"}\n",
        ]
        text3, _ = llm_client_mod._parse_sse_stream(iter(malformed), "responses")
        self.assertEqual(text3, "recovered")

    def test_discover_models_caching_and_models_dev(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "cpa"
        client.providers = {
            "cpa": {
                "baseUrl": "https://cpa.example.com/",
                "apikey": "test-key",
            }
        }
        client.models = {}
        client._discovery_cache = None
        client._discovery_cache_time = 0.0
        client._discovery_ttl_seconds = 600.0

        mock_models_json = {
            "data": [
                {"id": "gemini-3.7-flash-high", "name": "Gemini 3.7 Flash"},
                {"id": "grok-4.6", "name": "Grok 4.6"},
            ]
        }
        mock_resp = mock.MagicMock()
        mock_resp.read.return_value = json.dumps(mock_models_json).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch.object(llm_client_mod, "fetch_models_dev_free_ids", return_value={"mimo-v2.5-free"}):
            with mock.patch("urllib.request.urlopen", return_value=mock_resp):
                discovered = client.discover_models(timeout_seconds=5)
                self.assertIn("gemini-3.7-flash-high", discovered.get("cpa", []))
                self.assertIn("grok-4.6", discovered.get("cpa", []))

                # Second call within TTL should return cached dict without urlopen
                with mock.patch("urllib.request.urlopen", side_effect=AssertionError("Should not call network")):
                    cached = client.discover_models()
                    self.assertEqual(cached, discovered)

    def test_discover_models_cache_expiry_and_force_refresh(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "cpa"
        client.providers = {
            "cpa": {"baseUrl": "https://cpa.example.com/", "apikey": "test-key"}
        }
        client.models = {}
        client._discovery_cache = None
        client._discovery_cache_time = 0.0
        client._discovery_ttl_seconds = 600.0

        mock_models_json = {"data": [{"id": "gemini-3.7-flash-high", "name": "Gemini"}]}
        mock_resp = mock.MagicMock()
        mock_resp.read.return_value = json.dumps(mock_models_json).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch.object(llm_client_mod, "fetch_models_dev_free_ids", return_value=set()):
            with mock.patch("urllib.request.urlopen", return_value=mock_resp) as urlopen:
                first = client.discover_models(timeout_seconds=5)
                self.assertEqual(urlopen.call_count, 1)
                # Expired TTL forces a fresh network fetch
                client._discovery_cache_time = time.monotonic() - 1000
                second = client.discover_models()
                self.assertEqual(second, first)
                self.assertEqual(urlopen.call_count, 2)
                # force_refresh bypasses the cache even inside TTL
                third = client.discover_models(force_refresh=True)
                self.assertEqual(third, first)
                self.assertEqual(urlopen.call_count, 3)

    def test_discover_models_rejects_non_conforming_ids(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "cpa"
        client.providers = {
            "cpa": {"baseUrl": "https://cpa.example.com/", "apikey": "test-key"}
        }
        client.models = {}
        client._discovery_cache = None
        client._discovery_cache_time = 0.0
        client._discovery_ttl_seconds = 600.0

        mock_models_json = {
            "data": [
                {"id": "good-model-2", "name": "Good"},
                {"id": "vertex/imagen-3", "name": "Image"},
                {"id": "UPPER-CASE", "name": "Upper"},
                {"id": "t" * 70, "name": "Too long"},
            ]
        }
        mock_resp = mock.MagicMock()
        mock_resp.read.return_value = json.dumps(mock_models_json).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch.object(llm_client_mod, "fetch_models_dev_free_ids", return_value=set()):
            with mock.patch("urllib.request.urlopen", return_value=mock_resp):
                discovered = client.discover_models(timeout_seconds=5)
        self.assertEqual(discovered.get("cpa", []), ["good-model-2"])
        self.assertNotIn("vertex/imagen-3", client.models)
        self.assertNotIn("UPPER-CASE", client.models)
        self.assertNotIn("t" * 70, client.models)
        self.assertIn("good-model-2", client.models)

    def test_call_model_entry_point(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "opencode"
        client.providers = {
            "opencode": {
                "name": "opencode",
                "baseUrl": "https://opencode.example/v1/",
                "apikey": "k",
                "api": "openai-completions",
                "stream": False,
                "timeoutSeconds": 30,
            }
        }
        client.models = {"primary-free": {"id": "primary-free", "_provider": "opencode"}}
        client.fallback_models = []
        client.enable_streaming = False
        client.reject_finish_reasons = set()
        client.min_response_chars = 0
        client.same_model_retry_on_length = 0
        client.timeout_seconds = 30

        resp_payload = {
            "choices": [{"message": {"content": "CLASSIFICATION\nbug\nSUMMARY\ncall model ok"}, "finish_reason": "stop"}]
        }
        mock_resp = mock.MagicMock()
        mock_resp.headers = {"Content-Type": "application/json"}
        mock_resp.read.return_value = json.dumps(resp_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch("urllib.request.urlopen", return_value=mock_resp):
            text = client.call_model(
                "primary-free",
                [{"role": "user", "content": "hi"}],
                max_tokens=128,
                min_chars=10,
            )
        self.assertIn("call model ok", text)

    def test_interpolate_env_vars_missing_and_bare_form(self) -> None:
        raw = "a=${MISSING_VAR} b=$BARE_VAR c=${CPA_API_KEY}"
        with mock.patch.dict(os.environ, {"CPA_API_KEY": "secret-cpa-123", "GITHUB_ACTIONS": "true"}, clear=True):
            interpolated = llm_client_mod.interpolate_env_vars(raw)
        self.assertEqual(interpolated, "a= b= c=secret-cpa-123")

    def test_prepare_messages_requires_string_content_with_multimodal(self) -> None:
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Look:"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}},
                ],
            }
        ]
        string_model = {
            "id": "string-model",
            "input": ["text", "image"],
            "compat": {"requiresStringContent": True},
        }
        prepared = llm_client_mod._prepare_messages_for_model(messages, string_model)
        self.assertIsInstance(prepared[0]["content"], str)
        self.assertIn("[image omitted for text-only model", prepared[0]["content"])

    def test_reasoning_effort_validation_and_passthrough(self) -> None:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"opencode": {"models": [{"id": "m1", "reasoningEffort": "bogus"}]}}, f)
            bad_path = f.name
        try:
            client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
            client.default_provider = "opencode"
            with self.assertRaises(llm_client_mod.LLMClientError) as ctx:
                client.load_config(Path(bad_path))
            self.assertIn("m1", str(ctx.exception))
            self.assertIn("high", str(ctx.exception))
        finally:
            os.unlink(bad_path)

        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "opencode"
        client.providers = {
            "opencode": {
                "name": "opencode",
                "baseUrl": "https://opencode.example/v1/",
                "apikey": "k",
                "api": "openai-completions",
                "stream": False,
                "timeoutSeconds": 30,
            }
        }
        client.models = {"m1": {"id": "m1", "_provider": "opencode", "reasoningEffort": "high"}}
        client.fallback_models = []
        client.enable_streaming = False
        client.reject_finish_reasons = set()
        client.min_response_chars = 0
        client.same_model_retry_on_length = 0
        client.timeout_seconds = 30

        with self.assertRaises(llm_client_mod.LLMClientError):
            client.chat_completion("m1", [{"role": "user", "content": "hi"}], reasoning_effort="turbo")

        captured = {}
        resp_payload = {
            "choices": [{"message": {"content": "ok body"}, "finish_reason": "stop"}]
        }
        mock_resp = mock.MagicMock()
        mock_resp.headers = {"Content-Type": "application/json"}
        mock_resp.read.return_value = json.dumps(resp_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        def capture(req, *args, **kwargs):
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return mock_resp

        with mock.patch("urllib.request.urlopen", side_effect=capture):
            client.chat_completion("m1", [{"role": "user", "content": "hi"}], reasoning_effort="MAX", min_chars=1)
        self.assertEqual(captured["body"]["reasoning_effort"], "max")

    def test_default_provider_auto_switch_matrix(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            both = Path(tmp) / "both.json"
            both.write_text(
                '{"cpa": {"baseUrl": "https://cpa.example/v1", "apikey": "k", "models": []},'
                ' "opencode": {"baseUrl": "https://opencode.example/v1", "apikey": "k2", "models": []}}',
                encoding="utf-8",
            )
            cpa_only = Path(tmp) / "cpa_only.json"
            cpa_only.write_text(
                '{"cpa": {"baseUrl": "https://cpa.example/v1", "apikey": "k", "models": []},'
                ' "opencode": {"baseUrl": "https://opencode.example/v1", "models": []}}',
                encoding="utf-8",
            )
            opencode_only = Path(tmp) / "opencode_only.json"
            opencode_only.write_text(
                '{"cpa": {"baseUrl": "https://cpa.example/v1", "models": []},'
                ' "opencode": {"baseUrl": "https://opencode.example/v1", "apikey": "k2", "models": []}}',
                encoding="utf-8",
            )
            # Hermetic: CI-gate load_dotenv and clear stray env keys (e.g. a
            # developer's real OPENCODE_API_KEY) so the switch matrix is exact.
            with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}, clear=True):
                for path, expected in ((both, "opencode"), (cpa_only, "cpa"), (opencode_only, "opencode")):
                    client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
                    client.default_provider = "opencode"
                    client.load_config(path)
                    self.assertEqual(client.default_provider, expected, path.name)

    def test_token_window_normalization(self) -> None:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"opencode": {"models": [{"id": "m1", "contextWindow": 1000, "maxTokens": 500}]}}, f)
            path = f.name
        try:
            client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
            client.default_provider = "opencode"
            client.load_config(Path(path))
            self.assertEqual(client.models["m1"]["maxContextTokens"], 1000)
            self.assertEqual(client.models["m1"]["maxOutputTokens"], 500)
        finally:
            os.unlink(path)

    def test_chat_completion_skips_credentialless_candidates(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "opencode"
        client.providers = {
            "cpa": {"name": "cpa", "baseUrl": "https://cpa.example/v1/", "apikey": ""},
            "opencode": {
                "name": "opencode",
                "baseUrl": "https://opencode.example/v1/",
                "apikey": "k",
                "api": "openai-completions",
                "stream": False,
                "timeoutSeconds": 30,
            },
        }
        client.models = {
            "gemini-3.7-flash-high": {"id": "gemini-3.7-flash-high", "_provider": "cpa"},
            "deepseek-v4-flash-free": {"id": "deepseek-v4-flash-free", "_provider": "opencode"},
        }
        client.fallback_models = ["deepseek-v4-flash-free"]
        client.enable_streaming = False
        client.reject_finish_reasons = set()
        client.min_response_chars = 0
        client.same_model_retry_on_length = 0
        client.timeout_seconds = 30

        resp_payload = {
            "choices": [{"message": {"content": "ok from opencode"}, "finish_reason": "stop"}]
        }
        mock_resp = mock.MagicMock()
        mock_resp.headers = {"Content-Type": "application/json"}
        mock_resp.read.return_value = json.dumps(resp_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        captured = {}
        def capture(req, *args, **kwargs):
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return mock_resp

        with mock.patch("urllib.request.urlopen", side_effect=capture):
            text = client.chat_completion(
                "gemini-3.7-flash-high",
                [{"role": "user", "content": "hi"}],
                min_chars=1,
            )
        self.assertIn("ok from opencode", text)
        self.assertEqual(captured["body"]["model"], "deepseek-v4-flash-free")

        # No credentials anywhere: actionable error mentioning the env vars
        client.providers["opencode"]["apikey"] = ""
        with self.assertRaises(llm_client_mod.LLMClientError) as ctx:
            client.chat_completion("gemini-3.7-flash-high", [{"role": "user", "content": "hi"}], min_chars=1)
        self.assertIn("API key is missing for provider 'cpa'", str(ctx.exception))
        self.assertIn("OPENCODE_API_KEY", str(ctx.exception))

    def test_fallback_streaming_parity(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "cpa"
        client.providers = {
            "cpa": {
                "name": "cpa",
                "baseUrl": "https://cpa.example.com/",
                "apikey": "cpa-key",
                "api": "responses",
                "stream": False,
                "timeoutSeconds": 30,
            }
        }
        client.models = {"grok-4.6": {"id": "grok-4.6", "_provider": "cpa"}}
        client.enable_streaming = True
        client.reject_finish_reasons = set()

        err_fp = io.BytesIO(b"Not Found")
        http_404 = urllib.error.HTTPError("https://cpa.example.com/responses", 404, "Not Found", {}, err_fp)
        fallback_resp_payload = {
            "choices": [{"message": {"content": "fallback ok"}, "finish_reason": "stop"}]
        }
        mock_fallback_resp = mock.MagicMock()
        mock_fallback_resp.headers = {"Content-Type": "application/json"}
        mock_fallback_resp.read.return_value = json.dumps(fallback_resp_payload).encode("utf-8")
        mock_fallback_resp.__enter__.return_value = mock_fallback_resp

        captured = {}
        def urlopen_side_effect(req, *args, **kwargs):
            if "responses" in req.full_url:
                raise http_404
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return mock_fallback_resp

        try:
            with mock.patch("urllib.request.urlopen", side_effect=urlopen_side_effect):
                result = client._single_call(
                    "grok-4.6",
                    [{"role": "user", "content": "hi"}],
                    temperature=0.2,
                    max_tokens=100,
                    timeout_seconds=30,
                    min_chars=0,
                    required_markers=None,
                )
            self.assertIn("fallback ok", result)
            self.assertNotIn("stream", captured["body"])
        finally:
            http_404.close()
            err_fp.close()

    def test_load_dotenv_edge_cases_and_ci_gate(self) -> None:
        llm_client_mod.reset_dotenv_loaded_state()
        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".env") as f:
            f.write("# comment line\n")
            f.write("TEST_ENV_A=simple_value\n")
            f.write("TEST_ENV_B='quoted_val'\n")
            f.write("TEST_ENV_C=\"double_quoted\"\n")
            f.write("  TEST_ENV_D = spaced_val  \n")
            temp_path = f.name

        try:
            # Normal local load
            with mock.patch.dict(os.environ, {}, clear=True):
                loaded = llm_client_mod.load_dotenv(temp_path)
                self.assertEqual(loaded.get("TEST_ENV_A"), "simple_value")
                self.assertEqual(loaded.get("TEST_ENV_B"), "quoted_val")
                self.assertEqual(loaded.get("TEST_ENV_C"), "double_quoted")
                self.assertEqual(loaded.get("TEST_ENV_D"), "spaced_val")

            # In CI (GITHUB_ACTIONS set), load_dotenv must bypass reading .env
            with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}, clear=True):
                ci_loaded = llm_client_mod.load_dotenv(temp_path)
                self.assertEqual(ci_loaded, {})
                self.assertNotIn("TEST_ENV_A", os.environ)
        finally:
            os.unlink(temp_path)

    def test_media_ocr_fallback_chain(self) -> None:
        mock_llm = mock.MagicMock()
        mock_llm.chat_completion.return_value = "OCR Result: Detected Pixelify Settings UI"
        ocr_config = {
            "model": "gemini-3.7-flash-high",
            "fallbackModels": ["mimo-v2.5-free", "grok-4.6"],
            "maxBytesPerItem": 1000000,
            "maxSummaryChars": 2000,
            "timeoutSeconds": 30,
        }
        item = media_ocr_mod.MediaItem(
            label="screenshot.png",
            source="https://example.com/screenshot.png",
            kind="url",
            media_type="image",
        )
        context = media_ocr_mod.build_media_context(
            mock_llm,
            items=[item],
            ocr_config=ocr_config,
            soul_prompt="soul",
            role_prompt="role",
        )
        self.assertIn("Detected Pixelify Settings UI", context)
        mock_llm.chat_completion.assert_called_once()
        _, kwargs = mock_llm.chat_completion.call_args
        self.assertTrue(kwargs.get("allow_fallback"))
        self.assertEqual(kwargs.get("fallback_models"), ["mimo-v2.5-free", "grok-4.6"])
        self.assertEqual(kwargs.get("timeout_seconds"), 30)

    def test_media_ocr_failure_records_error(self) -> None:
        mock_llm = mock.MagicMock()
        mock_llm.chat_completion.side_effect = llm_client_mod.LLMClientError("gemini-3.7-flash-high: unusable")
        ocr_config = {
            "model": "gemini-3.7-flash-high",
            "fallbackModels": ["mimo-v2.5-free"],
            "maxBytesPerItem": 1000000,
            "maxSummaryChars": 2000,
            "timeoutSeconds": 30,
        }
        item = media_ocr_mod.MediaItem(
            label="screenshot.png",
            source="https://example.com/screenshot.png",
            kind="url",
            media_type="image",
        )
        context = media_ocr_mod.build_media_context(
            mock_llm,
            items=[item],
            ocr_config=ocr_config,
            soul_prompt="soul",
            role_prompt="role",
        )
        self.assertIn("OCR failed", context)
        mock_llm.chat_completion.assert_called_once()

    def test_load_dotenv_preserves_existing_environment(self) -> None:
        llm_client_mod.reset_dotenv_loaded_state()
        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".env") as f:
            f.write("EXISTING_KEY=new_file_value\n")
            f.write("BRAND_NEW_KEY=file_value\n")
            temp_path = f.name

        try:
            with mock.patch.dict(os.environ, {"EXISTING_KEY": "prior_value"}, clear=True):
                loaded = llm_client_mod.load_dotenv(temp_path)
                self.assertEqual(os.environ["EXISTING_KEY"], "prior_value")
                self.assertNotIn("EXISTING_KEY", loaded)
                self.assertEqual(loaded.get("BRAND_NEW_KEY"), "file_value")
        finally:
            os.unlink(temp_path)

    def test_load_dotenv_default_scan_is_idempotent(self) -> None:
        llm_client_mod.reset_dotenv_loaded_state()
        with tempfile.TemporaryDirectory() as tmp:
            env_file = Path(tmp) / ".env"
            env_file.write_text("IDEMPOTENT_ONLY=first_value\n", encoding="utf-8")
            try:
                content = env_file.read_text(encoding="utf-8")
                with mock.patch.dict(os.environ, {}, clear=True):
                    with mock.patch.object(Path, "is_file", autospec=True) as is_file, \
                         mock.patch.object(Path, "read_text", autospec=True) as read_text:
                        def fake_is_file(self, *args, **kwargs):
                            return str(self).endswith(".env")
                        def fake_read_text(self, *args, **kwargs):
                            return content
                        is_file.side_effect = fake_is_file
                        read_text.side_effect = fake_read_text
                        first = llm_client_mod.load_dotenv()
                        os.environ.pop("IDEMPOTENT_ONLY", None)
                        second = llm_client_mod.load_dotenv()
                self.assertEqual(first.get("IDEMPOTENT_ONLY"), "first_value")
                self.assertEqual(second, {})
                self.assertNotIn("IDEMPOTENT_ONLY", os.environ)
            finally:
                os.environ.pop("IDEMPOTENT_ONLY", None)
                llm_client_mod.reset_dotenv_loaded_state()

    def test_fetch_models_dev_free_ids_success_and_error(self) -> None:
        # Success case
        mock_payload = {
            "opencode": {
                "models": {
                    "free-model-a": {"cost": {"input": 0, "output": 0}},
                    "paid-model-b": {"cost": {"input": 5, "output": 10}},
                }
            }
        }
        mock_resp = mock.MagicMock()
        mock_resp.read.return_value = json.dumps(mock_payload).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp

        with mock.patch("urllib.request.urlopen", return_value=mock_resp):
            ids = llm_client_mod.fetch_models_dev_free_ids(timeout_seconds=5)
            self.assertIn("free-model-a", ids)
            self.assertNotIn("paid-model-b", ids)

        # Network error fallback returns empty set cleanly
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("Network unreachable")):
            fallback_ids = llm_client_mod.fetch_models_dev_free_ids(timeout_seconds=5)
            self.assertEqual(fallback_ids, set())

    def test_cpa_responses_404_fallback_to_chat_completions(self) -> None:
        client = llm_client_mod.LLMClient.__new__(llm_client_mod.LLMClient)
        client.default_provider = "cpa"
        client.providers = {
            "cpa": {
                "name": "cpa",
                "baseUrl": "https://cpa.example.com/",
                "apikey": "cpa-key",
                "api": "responses",
                "stream": False,
                "timeoutSeconds": 30,
            }
        }
        client.models = {"grok-4.6": {"id": "grok-4.6", "_provider": "cpa"}}
        client.enable_streaming = False
        client.reject_finish_reasons = set()

        # Mock responses endpoint returning 404, fallback openai call returning 200
        err_fp = io.BytesIO(b"Not Found")
        http_404 = urllib.error.HTTPError("https://cpa.example.com/responses", 404, "Not Found", {}, err_fp)

        fallback_resp_payload = {
            "choices": [{"message": {"content": "CLASSIFICATION\nbug\nSUMMARY\nfallback ok"}, "finish_reason": "stop"}]
        }
        mock_fallback_resp = mock.MagicMock()
        mock_fallback_resp.headers = {"Content-Type": "application/json"}
        mock_fallback_resp.read.return_value = json.dumps(fallback_resp_payload).encode("utf-8")
        mock_fallback_resp.__enter__.return_value = mock_fallback_resp

        def urlopen_side_effect(req, *args, **kwargs):
            if "responses" in req.full_url:
                raise http_404
            return mock_fallback_resp

        try:
            with mock.patch("urllib.request.urlopen", side_effect=urlopen_side_effect):
                result = client._single_call(
                    "grok-4.6",
                    [{"role": "user", "content": "hi"}],
                    temperature=0.2,
                    max_tokens=100,
                    timeout_seconds=30,
                    min_chars=0,
                    required_markers=None,
                )
                self.assertIn("fallback ok", result)
        finally:
            http_404.close()
            err_fp.close()


if __name__ == "__main__":
    unittest.main()
