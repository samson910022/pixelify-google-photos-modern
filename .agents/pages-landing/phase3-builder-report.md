# Phase 3 Builder Report — Harden (Pixelify Infinity Pages landing)

**Date:** 2026-07-30  
**Repo:** `pixelify-google-photos-modern`  
**Phase:** 3 (Harden)  
**Status:** Complete (no commit / no push / Pages not enabled / no Phase 4 screenshots)

---

## Summary

Hardened the static landing under `site/` for GitHub project Pages:

1. Full **link audit** (internal resolve + external HTTP HEAD) — all green.
2. **A11y pass** — skip link, landmarks/labels, focus rings, alts, heading order, callout contrast; small CSS/HTML fixes only (no redesign).
3. **404** — switched to project-root absolute paths under `/pixelify-google-photos-modern/` so nested missing URLs still load CSS/home.
4. **Workflow** — confirmed path filters + least privilege + no secrets; **pinned action commit SHAs** (resolved via `git ls-remote`); Pages-not-enabled comments retained.
5. **Cheap Phase 2 nits** — EN download-card label length parity; zh-TW 「選定的」→「特定」 to match docs.

---

## Files changed

| Path | Change |
|------|--------|
| `site/404.html` | Project-base absolute asset/home/lang links; `noindex`; usable copy; footer label |
| `site/assets/css/site.css` | Skip-link `:focus-visible`; extra focus rings on footer/card/note links; callout contrast note |
| `site/index.html` | Footer `aria-label`; shorter download-card link labels |
| `site/zh-TW/index.html` | Docs synonym 特定; footer `aria-label` |
| `site/zh-CN/index.html` | Footer `aria-label` |
| `site/ja/index.html` | Footer `aria-label` |
| `.github/workflows/pages.yml` | SHA-pin actions; keep path filters / least privilege / Pages-not-enabled comments |

**Not touched:** Android code, `ci.yml`, `ai-review.yml`, README website badge, analytics, APK hosting, commits/PRs.

---

## 1. Link audit checklist

### 1.1 Internal relative / project-absolute (filesystem resolve)

All resolved **OK** (scripted audit against `site/` tree):

| Page | Link pattern | Result |
|------|--------------|--------|
| `site/index.html` | `assets/*`, `./`, `zh-TW/`, `zh-CN/`, `ja/`, `#…` | OK |
| `site/zh-TW/index.html` | `../assets/*`, `../`, `./`, `../zh-CN/`, `../ja/`, `#…` | OK |
| `site/zh-CN/index.html` | `../assets/*`, `../`, `../zh-TW/`, `./`, `../ja/`, `#…` | OK |
| `site/ja/index.html` | `../assets/*`, `../`, `../zh-TW/`, `../zh-CN/`, `./`, `#…` | OK |
| `site/404.html` | `/pixelify-google-photos-modern/` + assets + locale dirs | OK → maps under `site/` |

### 1.2 Language switcher matrix (re-verified)

| Page | EN | zh-TW | zh-CN | ja | `aria-current` |
|------|----|-------|-------|-----|----------------|
| EN `index.html` | `./` | `zh-TW/` | `zh-CN/` | `ja/` | EN |
| zh-TW | `../` | `./` | `../zh-CN/` | `../ja/` | 繁中 |
| zh-CN | `../` | `../zh-TW/` | `./` | `../ja/` | 简中 |
| ja | `../` | `../zh-TW/` | `../zh-CN/` | `./` | 日本語 |
| 404 | `/…/` | `/…/zh-TW/` | `/…/zh-CN/` | `/…/ja/` | (none — not a locale page) |

### 1.3 External URLs (authoritative; not invented)

All returned **HTTP 200** via HEAD (User-Agent audit client), 2026-07-30:

| URL | Purpose | Status |
|-----|---------|--------|
| `https://github.com/samson910022/pixelify-google-photos-modern` | Source | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/releases` | Releases | 200 |
| `https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases` | Xposed mirror | 200 |
| `https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos` | LSPosed listing | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/blob/master/docs/RELEASE_SIGNING.md` | Signing | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/blob/master/PRIVACY.md` | Privacy | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/blob/master/SECURITY.md` | Security | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/blob/master/FORK_NOTICE.md` | Fork notice | 200 |
| `https://github.com/samson910022/pixelify-google-photos-modern/blob/master/LICENSE` | MIT | 200 |
| `https://github.com/BaltiApps/Pixelify-Google-Photos` | Upstream attribution | 200 |

Matches root `README.md` official channel list. Local files also exist: `PRIVACY.md`, `SECURITY.md`, `FORK_NOTICE.md`, `LICENSE`, `docs/RELEASE_SIGNING.md`.

---

## 2. 404 approach (project Pages base path)

**Problem:** GitHub serves `404.html` body while the browser URL stays on the *missing* path. Document-relative `assets/…` and `./` break on nested misses (e.g. `/pixelify-google-photos-modern/zh-TW/nope`).

**Fix:** Use **root-absolute** links prefixed with the project Pages base:

```text
/pixelify-google-photos-modern/
/pixelify-google-photos-modern/assets/...
/pixelify-google-photos-modern/zh-TW/  (etc.)
```

Documented in an HTML comment inside `site/404.html`. Copy remains English-only (optional i18n not required). Home CTA + Releases outbound link present; no APK hosting.

**Local preview note:** Serving only `site/` at `/` will not match production 404 asset paths; Phase 4 should exercise 404 under a `/pixelify-google-photos-modern/` mount (or accept that 404 is production-path-oriented).

---

## 3. A11y pass

| Check | Result | Notes |
|-------|--------|-------|
| Skip link | PASS | Present on all 5 HTML pages; CSS shows on `:focus` / `:focus-visible` |
| Landmarks | PASS | `header`, labeled `nav`s, `main#main`, labeled `footer`; sections `aria-labelledby` |
| Focus visible | PASS | Global `:focus-visible` on links/buttons; extra rings for footer/card/note/steps |
| Img alts | PASS | Decorative brand `alt=""`; banner descriptive alts per locale |
| Heading order | PASS | `h1` → section `h2` → card `h3` (404: single `h1`) |
| Callout contrast | PASS | `#f5d98a` on `#2a1f0a` ≈ **11.7:1** (AAA) |
| Other contrast samples | PASS | Body text ~16.5:1; muted ~8.2:1; accent ~9.8:1; tiny ~5.9:1 |

No full redesign; no third-party a11y widgets.

---

## 4. Workflow `.github/workflows/pages.yml`

| Check | Result |
|-------|--------|
| Path filters | `push` → `master` only for `site/**` + this workflow; plus `workflow_dispatch` |
| Permissions | `contents: read`, `pages: write`, `id-token: write` only |
| Secrets | None |
| Artifact root | `path: site` |
| Concurrency | `group: pages`, `cancel-in-progress: true` |
| Pages enabled? | **No** — comments state Settings → Pages not configured yet |
| Action pins | SHA-pinned (resolved 2026-07-30 via `git ls-remote`) |

| Action | Tag (comment) | Pinned SHA |
|--------|---------------|------------|
| `actions/checkout` | v4 / v4.4.0 | `11d5960a326750d5838078e36cf38b85af677262` |
| `actions/configure-pages` | v5 / v5.0.0 | `983d7736d9b0ae728b81ab479565c72886d7745b` |
| `actions/upload-pages-artifact` | v3 / v3.0.1 | `56afc609e74202658d3ffba0e8f6dda462b719fa` |
| `actions/deploy-pages` | v4 / v4.0.5 | `d6db90164ac5ed86f2b6aed7e0febac5b3c0c03e` |

**Risk note:** SHAs freeze current major-tag targets. Re-resolve when intentionally upgrading. Did **not** jump to newer majors (e.g. checkout v7) to avoid untested workflow breakage.

YAML parses cleanly (`yaml.safe_load`).

---

## 5. Optional Phase 2 nits

| Nit | Action |
|-----|--------|
| Download-card label length parity | EN cards shortened to short labels aligned with locales (`GitHub Releases`, `Official mirror Releases`, `modules.lsposed.org`, `Project repository`) — **URLs unchanged** |
| zh-TW 選定的 vs docs 特定 | Updated meta description, hero tagline, first feature bullet to **特定** |

---

## 6. Out of scope (honored)

- No visual screenshot acceptance (Phase 4)
- No commit / push / PR / enable Pages
- No Android / APK hosting / analytics / new mini-sites
- No README website badge (Phase 5 optional)

---

## 7. Verification commands run

- Filesystem resolve of every internal `href`/`src` under `site/**/*.html`
- HTTP HEAD of all unique external `https://` URLs
- Contrast ratio calculations for key token pairs
- `git ls-remote` for action tag → commit SHA
- `yaml.safe_load` on `pages.yml`
- `git status` — deliverables still untracked (`site/`, `pages.yml`, `.agents/`); no commit

---

## Gate handoff

**Phase 3 builder work complete.** Ready for Phase 3 reviewer. After PASS → Phase 4 visual acceptance (local static server + screenshots). Still do **not** commit/push/enable Pages until Phase 4/5.
