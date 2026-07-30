# Docs update review — GitHub Pages landing

**Reviewer role:** independent documentation reviewer (did not author these edits)  
**Repo:** `/home/samson1357924/projects/pixelify-google-photos-modern`  
**Branch:** `feat/github-pages-landing`  
**Scope:** unstaged/uncommitted markdown only (this round)  
**Date:** 2026-07-30  

---

## Verdict: PASS

No blocking accuracy, consistency, overclaim, scope, or leak issues found in the reviewed doc edits. Wording about GitHub Pages stays honest (“once enabled” / equivalent / “future” / “may 404”). Language paths match `site/`. APK non-hosting is explicit. CHANGELOG stays under `[Unreleased]`. Diff is docs-only.

---

## Blocking issues

None.

---

## Non-blocking nits

1. **User-facing `site/` path mentions**  
   - `README.md`, translated READMEs, and `SUPPORT.md` mention source path `` `site/` ``. Fine for an open-source repo, slightly more maintainer-oriented than pure installer copy. Optional: keep path detail in `CONTRIBUTING.md` / `AGENTS.md` only; user docs can say “project website / product landing” without the tree path.

2. **SUPPORT bullet URLs omit per-line “once enabled”**  
   - Intro correctly says “intended for GitHub Pages once enabled”, then lists full `github.io` URLs. Acceptable. Slightly stronger if each bullet (or a one-line note under the list) restates that links may 404 until Settings → Pages is enabled—mirrors `docs/PUBLICATION_CHECKLIST.md`.

3. **Japanese colon style**  
   - `docs/README.ja.md` header uses half-width `:` after `（GitHub Pages、有効化後）` while zh-TW/zh-CN use full-width `：`. Pure typography nit; not a meaning issue.

4. **AGENTS “after merge” timing**  
   - `AGENTS.md`: “separate maintainer action **after merge**”. Correct as default guidance; a maintainer *could* enable Pages earlier. Prefer “separate from merging the workflow/site files” if you want zero timing implication.

5. **Redundant website links in READMEs**  
   - Top-of-file website line **and** download-section landing bullet. Helpful, not wrong; could eventually collapse to one primary link + short note if copy bloat matters.

6. **CONTRIBUTING alignment wording**  
   - “locale pages should stay aligned with `docs/README.*.md`” is good policy. Landing pages are shorter product pages, not full README mirrors—current site reality matches that; no change required unless someone over-interprets “aligned” as “identical”.

7. **PUBLICATION_CHECKLIST general least-privilege bullet**  
   - Removed bare “no Pages deployment” from the combined access line and replaced with a dedicated Pages subsection. Correct for this feature. No action needed; note only that the old “confirm no Pages” reading is intentionally retired.

---

## Criteria results

| # | Criterion | Result | Notes |
|---|-----------|--------|-------|
| 1 | Accuracy / not claiming live site | **PASS** | EN “once enabled”; zh-TW「啟用後」; zh-CN「启用后」; ja「有効化後」; CHANGELOG “not enabled… separate maintainer step”; SUPPORT “once enabled”; checklist “may 404”. |
| 2 | Consistency EN + 3 translations + paths | **PASS** | All four READMEs link website; EN → `/`, zh-TW → `/zh-TW/`, zh-CN → `/zh-CN/`, ja → `/ja/`. Matches `site/index.html` + `site/{zh-TW,zh-CN,ja}/index.html`. SUPPORT lists all four. Owner `samson910022` matches `origin`. |
| 3 | No overclaim / no APK hosting | **PASS** | Every user-facing add states landing does **not** host APKs; official channels only. CONTRIBUTING/AGENTS/checklist reinforce no APK/AAB under `site/`. |
| 4 | CHANGELOG Unreleased | **PASS** | Three bullets under `## [Unreleased]` → `### Documentation`. No invented version/tag. Describes site, workflow, links, CONTRIBUTING/AGENTS/checklist. |
| 5 | CONTRIBUTING / AGENTS / checklist | **PASS** | `site/` layout, `.github/workflows/pages.yml`, least privilege (`contents: read`, `pages: write`, `id-token: write`), path filters, no signing secrets, enable step separate from merge. Matches workflow file. |
| 6 | SUPPORT useful without internals dump | **PASS** | Short website section + download preference + no APK host. No bot/CI harness dump in the new block. Mild `site/` path nit only (above). |
| 7 | No secrets / private paths / bot internals in user README | **PASS** | No `/home/...`, no OpenClaw, no keys. Public GitHub owner/repo only. AGENTS bot rules stay in AGENTS (appropriate). |
| 8 | Language-link header consistency | **PASS** | `[English] · [繁體中文] · [简体中文] · [日本語]` still present and correctly relative in all four READMEs. |
| 9 | Diff scoped (no accidental Android code) | **PASS** | Working-tree doc diff is exactly the nine markdown files listed (+65 / −2). No `app/` or Gradle changes in this round. `site/` + `pages.yml` already on branch from prior commit `f5bf605`. |

---

## Checks performed

1. **`git status` / branch** — `feat/github-pages-landing`; unstaged mods only on the nine scoped markdown files; untracked noise (`.agents/`, images, `opencode_model.json`) **out of scope** and not reviewed as product docs.
2. **`git diff`** — full patch for:
   - `README.md`
   - `docs/README.zh-TW.md`
   - `docs/README.zh-CN.md`
   - `docs/README.ja.md`
   - `SUPPORT.md`
   - `CONTRIBUTING.md`
   - `AGENTS.md`
   - `CHANGELOG.md`
   - `docs/PUBLICATION_CHECKLIST.md`
3. **Site reality cross-check** — `site/` contains EN root + `zh-TW` / `zh-CN` / `ja` + `404.html` + `assets/`; tracked on branch.
4. **Workflow cross-check** — `.github/workflows/pages.yml` present; header comments say Pages not enabled yet; permissions and `path: site` match checklist/AGENTS/CONTRIBUTING; push trigger is `master` + path filters + `workflow_dispatch`.
5. **Remote URL** — `origin` = `https://github.com/samson910022/pixelify-google-photos-modern.git` (docs host/path consistent).
6. **Leak scan** — grepped reviewed docs for private home paths / openclaw; none in new user-facing copy.
7. **APK / hosting language** — grepped new and related wording; non-hosting claims consistent.
8. **Language headers** — first lines of all four READMEs compared.
9. **CHANGELOG structure** — confirmed bullets sit under `[Unreleased]` Documentation, not a fake release section.
10. **No commit / push / fixes** — review only; this file is the deliverable.

---

## Summary for parent agent

Docs updates are honest, cross-language consistent, scoped, and aligned with prepared-but-not-enabled Pages. **Ship the doc edits as-is** from a review standpoint; optional nits above are polish only, not gates.
