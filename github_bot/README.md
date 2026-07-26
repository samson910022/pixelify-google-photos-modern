# Pixelify Infinity GitHub AI Bot

In-repo advisory bot for:

- **Issue investigation** — quality score, missing info, root-cause hypotheses
- **PR code review** — multi-agent safety/Android/docs review with aggregate verdict

Public comments do not disclose model names.

- Runtime: pure Python 3.12 (stdlib only)
- LLM provider: OpenCode Zen (`OPENCODE_API_KEY`)
- Workflow: `.github/workflows/ai-review.yml`
- Maintainer docs: `docs/AI_REVIEW_BOT.md`

## Commands

```bash
export OPENCODE_API_KEY='...'
export PYTHONPATH=github_bot/src
python3 github_bot/src/github_runner.py --mode=review --dry-run
python3 github_bot/src/github_runner.py --mode=triage --dry-run
python3 github_bot/src/github_runner.py --mode=explain --dry-run
```

Do not commit real API keys or `config/LLM_config.json` if it embeds secrets.

## Model routing (internal)

- Coding primaries: `ling-3.0-flash-free`, `laguna-s-2.1-free`
- Multimodal OCR: `mimo-v2.5-free` (auto on media attachments/files)
- Fallbacks: `deepseek-v4-flash-free` → `north-mini-code-free` → `big-pickle`
