#!/usr/bin/env python3
"""Unit tests for the Pixelify AI review bot helpers."""

from __future__ import annotations

import importlib
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "github_bot" / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

orchestrator_mod = importlib.import_module("agent_orchestrator")
runner_mod = importlib.import_module("github_runner")
media_ocr_mod = importlib.import_module("media_ocr")
llm_client_mod = importlib.import_module("llm_client")


class AiReviewBotTests(unittest.TestCase):
    def test_aggregate_verdict_prefers_needs_changes(self) -> None:
        self.assertEqual(
            orchestrator_mod._aggregate_verdict(["APPROVE", "NEEDS_CHANGES", "COMMENT"]),
            "NEEDS_CHANGES",
        )
        self.assertEqual(orchestrator_mod._aggregate_verdict(["APPROVE", "APPROVE"]), "APPROVE")
        self.assertEqual(orchestrator_mod._aggregate_verdict(["APPROVE", "COMMENT"]), "COMMENT")

    def test_extract_verdict(self) -> None:
        self.assertEqual(
            orchestrator_mod._extract_verdict("VERDICT: NEEDS_CHANGES\n- bad"),
            "NEEDS_CHANGES",
        )
        self.assertEqual(orchestrator_mod._extract_verdict("Looks fine APPROVE overall"), "APPROVE")

    def test_sensitive_path_classification(self) -> None:
        dummy = SimpleNamespace(
            config={
                "sensitivePathGlobs": [
                    "certificates/**",
                    "app/build.gradle.kts",
                    ".github/workflows/**",
                ]
            }
        )
        hits = orchestrator_mod.AgentOrchestrator.classify_sensitive_files(
            dummy,
            [
                "README.md",
                "certificates/pixelifyphotos-release-cert.pem",
                "app/build.gradle.kts",
                ".github/workflows/ci.yml",
            ],
        )
        self.assertEqual(
            hits,
            [
                "certificates/pixelifyphotos-release-cert.pem",
                "app/build.gradle.kts",
                ".github/workflows/ci.yml",
            ],
        )

    @staticmethod
    def _fake_pem_block(body: str = "abc") -> str:
        # Assemble at runtime so static secret scanners do not flag the fixture file.
        begin = "-----BEGIN " + "PRIVATE KEY-----"
        end = "-----END " + "PRIVATE KEY-----"
        return f"{begin}\n{body}\n{end}\n"

    def test_redact_private_key_and_password(self) -> None:
        sample = (
            "\n"
            + self._fake_pem_block()
            + 'storePassword = "super-secret-value"\n'
            + "Authorization: Bearer abcdefghijklmnop\n"
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
        self.assertTrue(any("user-attachments/assets/abcd-1234" in label for label in labels))
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


    def test_issue_quality_hints_detect_missing_fields(self) -> None:
        hints = orchestrator_mod.AgentOrchestrator.issue_quality_hints(
            object(),
            "Something broken",
            "It does not work on my phone.",
        )
        self.assertTrue(any(item.startswith("missing:") for item in hints))
        rich = orchestrator_mod.AgentOrchestrator.issue_quality_hints(
            object(),
            "Unlock fails on Android 15",
            """
            Pixelify Infinity v1.0.4
            LSPosed
            Google Photos
            Steps to reproduce:
            1. Enable module
            Expected: unlock works
            Actual: still locked
            screenshot.png
            """,
        )
        self.assertTrue(any(item.startswith("present: reproduction steps") for item in rich))
        self.assertTrue(any(item.startswith("present: expected vs actual") for item in rich))

    def test_issue_and_pr_reports_differ_and_hide_models(self) -> None:
        orch = orchestrator_mod.AgentOrchestrator.__new__(orchestrator_mod.AgentOrchestrator)
        orch.config = {"roles": {"triage_agent": {"model": "hidden-model", "promptFile": "./prompts/roles/triage_agent.md", "temperature": 0.1, "maxTokens": 10}}}
        orch.soul = "soul"
        orch.llm = SimpleNamespace()

        def fake_run_role(role_id, user_prompt):
            self.assertIn("not a pull-request code review", user_prompt.lower())
            return orchestrator_mod.RoleResult(
                role=role_id,
                model="should-not-appear",
                verdict="COMMENT",
                findings="SUMMARY\nissue body",
            )

        orch._run_role = fake_run_role  # type: ignore[method-assign]
        issue_report = orch.run_triage("title", "body missing details")
        self.assertIn("Issue Investigation", issue_report)
        self.assertIn("issue investigation", issue_report.lower())
        self.assertNotIn("should-not-appear", issue_report)
        self.assertNotIn("Model:", issue_report)
        self.assertNotIn("PR Code Review", issue_report)
        self.assertNotIn("FINAL_VERDICT", issue_report)

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

    def test_non_pr_review_routes_to_issue_investigation(self) -> None:
        self.assertFalse(runner_mod._is_pull_request_context())

    def test_config_models_and_fallbacks(self) -> None:
        import json

        bot = json.loads((ROOT / "github_bot/config/bot_config.json").read_text(encoding="utf-8"))
        llm = json.loads(
            (ROOT / "github_bot/config/LLM_config.example.json").read_text(encoding="utf-8")
        )
        model_ids = {m["id"] for m in llm["opencode"]["models"]}
        self.assertIn("ling-3.0-flash-free", model_ids)
        self.assertIn("laguna-s-2.1-free", model_ids)
        self.assertIn("mimo-v2.5-free", model_ids)
        self.assertEqual(bot["roles"]["identity_safety"]["model"], "ling-3.0-flash-free")
        self.assertEqual(bot["roles"]["android_xposed"]["model"], "laguna-s-2.1-free")
        self.assertEqual(bot["mediaOcr"]["model"], "mimo-v2.5-free")
        self.assertEqual(
            bot["fallbackModels"],
            ["deepseek-v4-flash-free", "north-mini-code-free", "big-pickle"],
        )


if __name__ == "__main__":
    unittest.main()
