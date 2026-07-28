# AI Review Bot

This repository includes an advisory multi-agent bot for **issue investigation** and **PR code review**.

It is inspired by:

- [Vegan-scan-app multi-agent OpenCode bot](https://github.com/richardchen10345/Vegan-scan-app) — role prompts, sticky comments, OpenCode free models
- [OpenClaw automation posture](https://github.com/openclaw/openclaw) — least-privilege permissions, concurrency, draft skips, security-sensitive path awareness, evidence-first issue completeness fields

The bot never replaces CI or human review. Required hard gates remain `./.github/workflows/ci.yml` and the maintainer release process.

Public bot comments intentionally **do not disclose model names**. Model routing is maintainer configuration only (`github_bot/config/bot_config.json`, currently bot config version `1.3.0`).

## What it does

| Trigger | Mode | Behavior |
| --- | --- | --- |
| PR opened / reopened / synchronize / ready_for_review | `review` | Parallel PR roles: identity/safety, Android/Xposed, docs/public surface + aggregate verdict |
| Issue opened | `triage` | Issue investigation: quality score, missing info, root-cause hypotheses, suggested labels, security routing |
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
- thread-aware context: up to N issue comments (bounded; currently oldest-first) + already-asked suppression
- Pixelify load/VERIFY playbook grounding (toast/notification interpretation)
- `ISSUE_QUALITY_SCORE` 0–100 with band `actionable | needs-info | insufficient`
- quality breakdown across problem clarity / environment / reproduction / expected vs actual / evidence
- missing-info checklist and ranked root-cause hypotheses with confidence + validation steps
- suggested labels, risk, security routing, reporter/maintainer next steps

Issue quality scoring follows an OpenClaw-style completeness mindset: score only fields grounded in observed evidence; prefer `NOT_ENOUGH_INFO` over speculation.

#### Response quality gates (fail-closed)

Before publishing, the bot:

1. Rejects unusable model completions (`finish_reason` in `{length, max_tokens, content_filter}`, empty/short, or truncated-looking text)
2. On length/truncation, retries the same model once (higher `max_tokens`); otherwise walks the free-model fallback chain
3. Validates required triage sections and minimum length
4. Attempts one repair pass when the draft is incomplete
5. Publishes a structured **fail-closed stub** instead of a mid-sentence fragment when still incomplete
6. Re-checks publishability before posting and can replace an incomplete non-stub draft

Public comments never disclose model names or provider routing. Validation/error text is sanitized for public display.

### PR code review report

Title: `Pixelify Infinity PR Code Review`

Includes:

- role verdict matrix (`APPROVE` / `NEEDS_CHANGES` / `COMMENT`)
- sensitive path hits
- deterministic safety prechecks
- per-role findings
- aggregate `FINAL_VERDICT`

Short but complete PR findings are allowed (default `minResponseChars=0` for non-triage roles). Issue investigation still enforces a higher minimum length and required sections.

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


## Maintainer installation

Operational checklist for enabling the bot on this repository (already applied for the canonical remote):

1. **Workflow present** — `.github/workflows/ai-review.yml` is active on `master`.
2. **Secret** — repository secret `OPENCODE_API_KEY` (OpenCode Zen). Never commit the key. The workflow fails closed if missing.
3. **Permissions** — keep repository default Actions token at **read**; the workflow requests only `contents: read`, `issues: write`, `pull-requests: write`. Do **not** grant `GITHUB_TOKEN` approval rights for PRs.
4. **Labels** — create the allowlisted triage labels the bot may apply:
   - `needs-info`, `needs-triage`, `device-specific`, `android-16`, `android-17`, `photos-version`
   - `security`, `likely-user-setup`, `feature-request`, `support`
   - plus standard `bug` / `documentation` / `enhancement` / …
5. **Label apply policy** — `github_bot/config/bot_config.json` → `triage.applySuggestedLabels` (default `true`) only adds labels from `triage.labelAllowlist`. It never removes labels and never invents labels outside the allowlist.
6. **Verify** — open a test issue or comment `/triage` on an issue; confirm a sticky `Pixelify Infinity Issue Investigation` comment appears and allowlisted labels are added.
7. **Unit tests** — `PYTHONPATH=github_bot/src python3 -m unittest tests.test_ai_review_bot -v`

Fork PRs do not receive `OPENCODE_API_KEY` (GitHub secret isolation). That is intentional.

## Local dry run

```bash
export OPENCODE_API_KEY='...'   # do not commit
export PYTHONPATH=github_bot/src
python3 github_bot/src/github_runner.py --mode=review --dry-run
python3 github_bot/src/github_runner.py --mode=triage --dry-run
python3 github_bot/src/github_runner.py --mode=explain --dry-run
```

Without GitHub event context, the runner prints the report to stdout. For triage dry-runs you may also supply `ISSUE_TITLE`, `ISSUE_BODY`, and optional `ISSUE_COMMENTS_JSON`.

Unit tests for routing, quality gates, sanitization, and fail-closed publish behavior:

```bash
PYTHONPATH=github_bot/src python3 -m unittest tests.test_ai_review_bot -v
```

## Safety posture for this repo

The bot prompts encode Pixelify Infinity invariants:

- package/Xposed identity must stay consistent
- no private signing material in Git or comments
- no APK/AAB/`app/build` commits
- security reports stay private per `SECURITY.md`
- advisory comments only; no auto-commit `/fix` path

Sensitive path hits are highlighted when a PR touches workflows, certificates, wrapper scripts, signing docs, or publication checks.

## Layout

| Path | Purpose |
| --- | --- |
| `.github/workflows/ai-review.yml` | Triggers and runner entry |
| `github_bot/config/bot_config.json` | Roles, models, triage gates, markers |
| `github_bot/prompts/` | Soul + role prompts |
| `github_bot/src/` | Orchestrator, GitHub runner, LLM client, OCR |
| `tests/test_ai_review_bot.py` | Bot unit tests |
| `github_bot/README.md` | Short package-local overview |

## Tuning

1. Edit role prompts under `github_bot/prompts/`.
2. Adjust models, pipeline order, or triage gates in `github_bot/config/bot_config.json`.
3. Keep `OPENCODE_API_KEY` only in GitHub Actions secrets or local env.
4. Do not commit generated review transcripts unless deliberately archiving an audit.

## Permissions

Workflow permissions are limited to:

- `contents: read`
- `pull-requests: write`
- `issues: write`

The bot uses the default `GITHUB_TOKEN` for comments and does not receive signing configuration.
