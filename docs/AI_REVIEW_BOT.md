# AI Review Bot (maintainer)

This repository includes an advisory multi-agent review bot for issues and pull requests.

It is inspired by:

- [Vegan-scan-app multi-agent OpenCode bot](https://github.com/richardchen10345/Vegan-scan-app) — role prompts, sticky comments, OpenCode free models
- [OpenClaw automation posture](https://github.com/openclaw/openclaw) — least-privilege permissions, concurrency, draft skips, security-sensitive path awareness

The bot never replaces CI or human review. Required hard gates remain `./.github/workflows/ci.yml` and the maintainer release process.

## What it does

| Trigger | Mode | Behavior |
| --- | --- | --- |
| PR opened / reopened / synchronize / ready_for_review | `review` | Parallel roles: identity/safety, Android/Xposed, docs/public surface |
| Issue opened | `triage` | Completeness, risk, suggested labels, clarifying questions |
| Comment containing `/review`, `/triage`, or `/explain` | command | Re-run the matching mode |
| Manual `workflow_dispatch` | chosen mode | Maintainer-triggered run |

Reports are posted as issue/PR comments and updated in place using HTML markers:

- `<!-- PIXELIFY_AI_REVIEW_REPORT -->`
- `<!-- PIXELIFY_AI_TRIAGE_REPORT -->`
- `<!-- PIXELIFY_AI_COMMAND_REPORT -->`

## Required secret

Create a repository secret:

- Name: `OPENCODE_API_KEY`
- Value: your OpenCode Zen API key

The workflow fails closed if the secret is missing. The bot never uses release-signing secrets.

## Free model defaults

Configured free OpenCode models (price 0):

| Model | Default use |
| --- | --- |
| `ling-3.0-flash-free` | Primary coding/docs reviewer (identity + docs) |
| `laguna-s-2.1-free` | Primary Android/Xposed coding reviewer |
| `mimo-v2.5-free` | Multimodal OCR for screenshots/media before review/triage |
| `deepseek-v4-flash-free` | Triage/explain + first fallback |
| `north-mini-code-free` | Coding fallback |
| `big-pickle` | Final free fallback |
| `nemotron-3-ultra-free` | Available large-context swap |

Fallback chain for text/code roles:

1. `deepseek-v4-flash-free`
2. `north-mini-code-free`
3. `big-pickle`

### Multimodal OCR

When an issue/PR body contains image/media URLs (Markdown images, GitHub user-attachments, bare media links) or the change set includes media files, the bot:

1. Discovers up to `mediaOcr.maxItems` items
2. Calls `mimo-v2.5-free` with the media OCR prompt
3. Injects the OCR/UI summary into reviewer/triage context

OCR failures are non-fatal: the review continues with an OCR error note.


## Local dry run

```bash
export OPENCODE_API_KEY='...'   # do not commit
export PYTHONPATH=github_bot/src
python3 github_bot/src/github_runner.py --mode=review --dry-run
```

Without GitHub event context, the runner prints the report to stdout.

## Safety posture for this repo

The bot prompts encode Pixelify Infinity invariants:

- package/Xposed identity must stay consistent
- no private signing material in Git or comments
- no APK/AAB/`app/build` commits
- security reports stay private per `SECURITY.md`
- advisory comments only; no auto-commit `/fix` path

Sensitive path hits are highlighted when a PR touches workflows, certificates, wrapper scripts, signing docs, or publication checks.

## Tuning

1. Edit role prompts under `github_bot/prompts/`.
2. Adjust models or pipeline order in `github_bot/config/bot_config.json`.
3. Keep `OPENCODE_API_KEY` only in GitHub Actions secrets or local env.
4. Do not commit generated review transcripts unless deliberately archiving an audit.

## Permissions

Workflow permissions are limited to:

- `contents: read`
- `pull-requests: write`
- `issues: write`

The bot uses the default `GITHUB_TOKEN` for comments and does not receive signing configuration.
