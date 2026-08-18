# Changelog — AI Review Bot

This file tracks releases of the advisory GitHub AI review bot that lives under `github_bot/`. It is versioned independently from the `Pixelify Infinity` Xposed module app; the app's changelog is `CHANGELOG.md` at the repository root.

## [1.4.0] - 2026-08-19

### Added

- **AI review bot `1.4.0`**: CPA LLM proxy provider support (`responses` endpoint with SSE chunk streaming and 404 fallback to `chat/completions`).
- Updated primary bot models: identity/docs review `gemini-3.7-flash-high`, Android/Xposed review `claude-opus-4-6-thinking`, issue triage/PR explainer `grok-4.6`, multimodal OCR `gemini-3.7-flash-high` (fallbacks `mimo-v2.5-free`, `grok-4.6`).
- Expanded `opencode` model catalog with 20+ latest free models from `models.dev`.
- Dynamic model discovery with TTL caching, model-ID allowlist validation, and internal registration logging.
- Streaming enabled by default on both CPA and OpenCode endpoints (avoids Cloudflare 524 idle timeouts); per-provider `timeoutSeconds` aligned to 300 s.
- `reasoningEffort` validation (allowed: `low`, `medium`, `high`, `max`) for model catalog and role-level overrides.

### Changed

- Added `grok-composer-2.5-fast` (CPA, text-only, 200 000 context) to the CPA model catalog and as a text-only large-context fallback in `fallbackModels`; primary role models unchanged.
- Fallback candidates whose provider lacks credentials are skipped before any HTTP attempt (no wasted retries on OpenCode-only forks).
- OCR now uses the client's built-in fallback chain (`fallback_models=`) instead of a manual per-model loop, inheriting multimodal skip and length-retry handling.
- Public error sanitization hardened: provider names, env-var names, and compound tokens (`cpa_proxy`, `CPA_API_KEY`, `provider='cpa'`, `vertex/imagen-*`) are scrubbed before model names.
- `.env` default scan is now idempotent (once per process); explicit-path scans still always run.
- Config schema normalized in `load_config`: `contextWindow`/`maxTokens` are mapped to `maxContextTokens`/`maxOutputTokens`.

### Documentation

- Documented provider selection (auto-switch behavior), timeout resolution, and model/OCR defaults in `docs/AI_REVIEW_BOT.md` and `github_bot/README.md`.
