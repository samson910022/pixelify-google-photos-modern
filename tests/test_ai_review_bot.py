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

    def test_redact_private_key_and_password(self) -> None:
        sample = """
-----BEGIN PRIVATE KEY-----
abc
-----END PRIVATE KEY-----
storePassword = "super-secret-value"
Authorization: Bearer abcdefghijklmnop
"""
        redacted = runner_mod._redact_secrets(sample)
        self.assertNotIn("super-secret-value", redacted)
        self.assertNotIn("abcdefghijklmnop", redacted)
        self.assertIn("[REDACTED]", redacted)

    def test_comment_mode_resolution(self) -> None:
        self.assertEqual(runner_mod._resolve_comment_mode("please /review this"), "review")
        self.assertEqual(runner_mod._resolve_comment_mode("/explain for product"), "explain")
        self.assertEqual(runner_mod._resolve_comment_mode("needs /triage"), "triage")
        self.assertIsNone(runner_mod._resolve_comment_mode("just a normal comment"))

    def test_deterministic_findings_flags_apk_and_private_key(self) -> None:
        ctx = orchestrator_mod.ReviewContext(
            title="t",
            body="",
            git_diff="-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
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
