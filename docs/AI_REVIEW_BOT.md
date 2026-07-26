# AI Review Bot

This repository includes an advisory multi-agent bot for **issue investigation** and **PR code review**.

It is inspired by:

- [Vegan-scan-app multi-agent OpenCode bot](https://github.com/richardchen10345/Vegan-scan-app) — role prompts, sticky comments, OpenCode free models
- [OpenClaw automation posture](https://github.com/openclaw/openclaw) — least-privilege permissions, concurrency, draft skips, security-sensitive path awareness, evidence-first issue completeness fields

The bot never replaces CI or human review. Required hard gates remain `./.github/workflows/ci.yml` and the maintainer release process.

Public bot comments intentionally **do not disclose model names**. Model routing is maintainer configuration only.

## What it does

| Trigger | Mode | Behavior |
| --- | --- | --- |
| PR opened / reopened / synchronize / ready_for_review | `review` | Parallel PR roles: identity/safety, Android/Xposed, docs/public surface + aggregate verdict |
| Issue opened | `triage` | Issue investigation: quality score, missing info, root-cause hypotheses, labels, security routing |
| Comment containing `/review` on a **PR** | `review` | Re-run multi-agent PR code review |
| Comment containing `/review` or `/triage` on an **issue** | `triage` | Issue investigation (never PR-style verdicts) |
| Comment containing `/explain` on a **PR** | `explain` | Plain-language PR explanation |
| Comment containing `/explain` on an **issue** | `triage` | Routed to issue investigation |
| Manual `workflow_dispatch` | chosen mode | Maintainer-triggered run |

Issue and PR reports use **different titles, sections, and sticky markers**.

### Issue investigation report

Title: `Pixelify Infinity Issue Investigation`

Includes:

- decision-first sections (`CLASSIFICATION`, `ACTIONABILITY`, blocking missing info)
- local field-quality hints (`strong | weak | missing`, not mere keyword presence)
- thread-aware context: recent issue comments + already-asked suppression
- Pixelify load/VERIFY playbook grounding (toast/notification interpretation)
- `ISSUE_QUALITY_SCORE` 0–100 with band `actionable | needs-info | insufficient`
- quality breakdown across problem clarity / environment / reproduction / expected vs actual / evidence
- missing-info checklist and ranked root-cause hypotheses with confidence + validation steps
- suggested labels, risk, security routing, reporter/maintainer next steps

Issue quality scoring follows an OpenClaw-style completeness mindset: score only fields grounded in observed evidence; prefer `NOT_ENOUGH_INFO` over speculation.

Incomplete or truncated model drafts are **fail-closed**: the bot retries/repairs once, then publishes a structured stub instead of a mid-sentence fragment. Public comments never disclose model names.

### PR code review report

Title: `Pixelify Infinity PR Code Review`

Includes:

- role verdict matrix (`APPROVE` / `NEEDS_CHANGES` / `COMMENT`)
- sensitive path hits
- deterministic safety prechecks
- per-role findings
- aggregate `FINAL_VERDICT`

## Sticky comment markers

Reports are posted as issue/PR comments and updated in place using HTML markers:

- `<!-- PIXELIFY_AI_REVIEW_REPORT -->` — PR code review
- `<!-- PIXELIFY_AI_TRIAGE_REPORT -->` — issue investigation
- `<!-- PIXELIFY_AI_COMMAND_REPORT -->` — PR explanation / misc command output

## Required secret

Create a repository secret:

- Name: `OPENCODE_API_KEY`
- Value: your OpenCode Zen API key

The workflow fails closed if the secret is missing. The bot never uses release-signing secrets.

## Free model defaults (maintainer config only)

Configured free OpenCode models (price 0). These are **not** printed in public comments:

| Model | Default use |
| --- | --- |
| `ling-3.0-flash-free` | Primary coding/docs reviewer (identity + docs) |
| `laguna-s-2.1-free` | Primary Android/Xposed coding reviewer |
| `mimo-v2.5-free` | Multimodal OCR for screenshots/media before review/investigation |
| `deepseek-v4-flash-free` | Issue investigation / explain + first fallback |
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
2. Calls the configured multimodal OCR model
3. Injects the OCR/UI summary into issue investigation or PR review context

OCR failures are non-fatal: the run continues with an OCR error note.


## Local dry run

```bash
export OPENCODE_API_KEY='...'   # do not commit
export PYTHONPATH=github_bot/src
python3 github_bot/src/github_runner.py --mode=review --dry-run
python3 github_bot/src/github_runner.py --mode=triage --dry-run
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
