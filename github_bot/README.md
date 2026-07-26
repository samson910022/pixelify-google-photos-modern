# Pixelify Infinity GitHub AI Bot

In-repo advisory bot for:

- **Issue investigation** — quality score, missing-info checklist, root-cause hypotheses, security routing
- **PR code review** — multi-agent safety / Android-Xposed / docs review with aggregate verdict
- **PR explain** — plain-language summary of a pull request

Public comments do not disclose model names or provider routing.

## Package layout

| Path | Role |
| --- | --- |
| `src/github_runner.py` | GitHub Actions entry, sticky-comment publish |
| `src/agent_orchestrator.py` | Issue/PR pipelines, quality gates, fail-closed stubs |
| `src/llm_client.py` | OpenCode client, truncation rejection, fallbacks |
| `src/media_ocr.py` | Media discovery + multimodal OCR |
| `config/bot_config.json` | Roles, models, triage required sections |
| `prompts/` | Soul + role prompts |
| `../tests/test_ai_review_bot.py` | Unit tests |
| `../docs/AI_REVIEW_BOT.md` | Maintainer documentation |
| `../.github/workflows/ai-review.yml` | Workflow triggers |

## Commands

```bash
export OPENCODE_API_KEY='...'   # never commit
export PYTHONPATH=github_bot/src
python3 github_bot/src/github_runner.py --mode=review --dry-run
python3 github_bot/src/github_runner.py --mode=triage --dry-run
python3 github_bot/src/github_runner.py --mode=explain --dry-run
PYTHONPATH=github_bot/src python3 -m unittest tests.test_ai_review_bot -v
```

Optional triage dry-run env vars: `ISSUE_TITLE`, `ISSUE_BODY`, `ISSUE_COMMENTS_JSON`.

Do not commit real API keys or a `config/LLM_config.json` that embeds secrets. Prefer `LLM_config.example.json` as a template only.

## Slash commands (GitHub comments)

| Context | Command | Result |
| --- | --- | --- |
| Pull request | `/review` | Multi-agent PR code review |
| Pull request | `/explain` | PR explanation |
| Issue | `/triage` or `/review` | Issue investigation (not PR review) |
| Issue | `/explain` | Routed to issue investigation |

## Quality posture

- Issue path investigates possible causes and report completeness; it does **not** emit PR merge verdicts
- PR path reviews the provided diff for identity/safety, Android/Xposed, and public docs
- Thread-aware triage suppresses questions already asked in the issue thread
- Incomplete or truncated model drafts are fail-closed into a structured stub
- Media attachments can be OCR'd before analysis

## Model routing (internal maintainer config only)

Configured free OpenCode models (price 0):

- Coding primaries: `ling-3.0-flash-free`, `laguna-s-2.1-free`
- Multimodal OCR: `mimo-v2.5-free` (auto on media attachments/files)
- Issue investigation / explain primary: `deepseek-v4-flash-free`
- Fallbacks: `deepseek-v4-flash-free` → `north-mini-code-free` → `big-pickle`

These names must never appear in public issue/PR comments.
