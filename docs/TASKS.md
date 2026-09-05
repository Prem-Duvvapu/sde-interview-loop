# TASKS.md — what to work on next

**Read `AGENTS.md` before starting anything here.**

Tasks are ordered by value-per-effort. Each card is self-contained: goal, why it matters,
which files, concrete steps, and how to know you are done.

**Pick one task. Finish it. Verify it. Commit it.** Do not start three at once — the
invariants in `AGENTS.md` make half-finished work in this codebase risky.

> **Status note (2026-08-29):** T1 and T3–T6 are complete. Their detailed cards remain
> below as implementation history; select from the remaining-work table instead.

**Before starting any task**, confirm you are on a green build:

```bash
git status && git log --oneline -5     # has someone else worked here since?
./mvnw -o test                          # expect: Tests run: 53, Failures: 0
```

| # | Task | Size | Needs a live LLM call? |
|---|---|---|---|
| H1 | Browser walkthrough: responsive setup, dashboard, replay and a full-loop transition | M | one controlled round / loop |
| H2 | Measure and document Gemini prompt-cache behaviour | M | a few |
| H3 | Provider parity check | S | two controlled rounds |
| H4 | Cost ceilings, disconnect recovery and packaged startup | L | no for core work |
| H5 | Anchored evaluator examples to reduce score inflation | M | calibration calls |
| H6 | Grow original question banks and validate profiles from first-hand data | S each | no |

> **Quota warning.** The owner's Gemini free tier is **20 requests/day per model**. A
> single full round can use 10+. Prefer **H4a/H4b/H4c/H6a** (no live calls needed) when
> quota is spent; H1/H2/H3/H5 each need a small, bounded number of live calls — see each
> card's own budget. Quota is per-model: switching to `gemini-3.6-flash` in settings gives
> a separate bucket.

---

## H1 — Browser walkthrough: responsive setup, dashboard, replay and a full-loop transition

**Size:** M · **Live calls:** one controlled round / loop

### Goal
Actually click through the app in a real browser — setup, a live round, a dashboard, and a
full-loop round-to-round transition — at more than one screen width. Report what you find;
fix only what's clearly broken and small.

### Why it matters
T4, T5 and T6 (session reports, readiness dashboard, full-loop chaining) were all verified
through `curl`, a WebSocket test script, or an integration test with a fake provider —
**never through the actual UI.** `AGENTS.md`'s working agreement says to verify against a
running instance; for these three features, "running instance" has only ever meant the
API. A `DashboardView.tsx` and a `ReplayView.tsx` exist and neither has been opened in a
browser since they were written. Layout, data-shape mismatches, and the round-to-round
transition are exactly the class of bug that curl cannot see.

### Files (read, mostly — this is a verification task)
- `web/src/App.tsx` — view routing (`setup | interview | replay | dashboard`)
- `web/src/components/{SetupView,DashboardView,ReplayView,InterviewView}.tsx`
- `web/src/styles.css` — existing breakpoints at `1080px` and `780px`
- `web/src/ws/useInterviewSocket.ts` — reconnect/backoff, if you kill the backend mid-round

### Steps
1. `./mvnw spring-boot:run` and `cd web && npm run dev`. Open the app for real.
2. **Setup screen**, at three widths (resize the window or use devtools): ~1440px,
   ~1080px, ~375px. Confirm nothing overlaps or clips at the two breakpoints that already
   exist in `styles.css`.
3. **Run one round through the UI itself**, not curl — pick any module. Confirm streaming
   text renders live, the phase strip updates, and tool-call activity is visible.
4. **If you pick a `full_loop` session:** complete round 1 and confirm the UI advances to
   round 2 **without a manual reload**, and that round 2's opening brief reflects the
   carry-over handoff from `SessionManager` (T6) — not a repeat of round 1's transcript.
5. **After a session completes,** open the dashboard. Confirm readiness/trend data renders
   with no console errors and no `NaN`/`undefined` in the numbers shown.
6. **Open Replay** for a completed round. This has never been confirmed working — find out
   whether the transcript and artifact scrubbing actually function. If broken, write down
   the exact failure (console error, blank pane, whatever) rather than guessing at a fix.
7. **Kill the backend mid-round** and watch the frontend's reconnect behaviour. Then
   restart the backend and reconnect: **check whether the transcript reappears.** (There is
   reason to suspect it does not — see H4's disconnect-recovery gap. If you confirm this
   here, link the two rather than fixing it in this card.)

### Acceptance criteria
- [ ] All four views function with no console errors at the widths tested. (Setup,
      Interview, Dashboard confirmed clean; Replay not yet reached.)
- [ ] A `full_loop` session advances from round 1 to round 2 unattended, in the browser.
      (Backend-side advance logic confirmed correct via logs; browser-side render of the
      transition not yet observed — see Findings.)
- [ ] Replay is confirmed either working, or broken with a written, reproducible failure.
      (Not yet reached.)
- [x] Findings are written down (append to this task's section, or a linked note) —
      this is a verification task; a silent pass/fail defeats the point.

### Pitfalls
- Do not fix ReplayView blind before confirming what's actually broken.
- This is about *confirming current behaviour*, not building new resilience — leave the
  actual disconnect-recovery fix to H4 even if you spot the gap here.
- One full round and one full-loop transition is enough live-call budget. Do not repeat
  attempts to "be sure."

### Findings (2026-09-05)
Verified with a real Chromium browser (Playwright driver) against a locally-running
backend + Vite dev server, `linkedin` profile, `full_loop` mode. Partial — ran out of
live-call budget (Gemini free-tier quota, both `gemini-3.7-flash` and `gemini-3.6-flash`,
exhausted mid-session) before reaching Replay or the disconnect/reconnect test.

- **Setup screen, 1440px / 1080px / 375px — clean.** No overlap, clipping, or console
  errors at any of the three widths. Profile list, detail panel, loop table (including the
  now-enabled 5th "behavioral / Host-culture" row), mode toggle, module/difficulty
  selects, and the replay form all render correctly, including at 375px where the layout
  has no dedicated breakpoint below 780px.
- **Live round through the UI — confirmed working.** Streaming interviewer text, the
  phase strip (Briefing → Clarifying → Approach → Coding → Complexity → Edge Cases →
  Follow Up → Wrap), and tool-call activity all render live. Control calls
  (`record_signal`, `end_round`) show up as their own inline transcript entries with
  `dimension=`/`score=`/`evidence=` fields — a real behavior worth knowing about, not a
  bug: it means the transcript pane doubles as a scoring-activity log, which is more
  detail than a candidate-facing view probably wants long-term (worth a product decision
  later, not fixed here).
- **Full-loop round 1→2 transition — NOT confirmed end-to-end; found a real UX gap.**
  Round 1 completed naturally via `end_round` (confirmed: phase strip all-ticked, "Round
  complete" banner, composer replaced with "This round is finished"). But round 2 never
  appeared in the browser within a 20s wait. Backend-log cross-reference shows the
  full-loop advance logic itself is correct — `Prepared full-loop round 69 after completed
  round 68` was logged — but only after the evaluator's first call timed out (60s), its
  retry failed with `429 RESOURCE_EXHAUSTED` (quota), and the fallback-to-neutral-handoff
  path ran — a sequence taking roughly 90s, blocking the same thread the whole time. My
  test's browser had already moved on by then, so `next_round_ready` most likely landed on
  an already-closing socket (`WebSocket disconnected: ... reason=client navigating away`
  logged immediately after). **Net finding: if the evaluator is slow or fails, a real user
  sees up to ~90s of silent dead air after "Round complete" with zero progress indicator**
  — screenshot confirms the UI just sits at "This round is finished," no spinner, no
  message. The acceptance criterion "advances from round 1 to round 2 unattended" is
  therefore *not yet confirmed* for the happy path (fast evaluator, no quota issues) —
  needs a re-run once quota resets, with a longer wait and a working evaluator. Worth an
  `RCA.md`/backlog entry for a "preparing round 2…" indicator independent of any actual fix.
- **Dashboard — clean, no console errors, no literal `NaN`/`undefined`.** Shows "Not
  enough data" for current readiness (expected — no scored rounds yet), correct session
  counts, and a session-history list. The open session's report panel correctly reads
  "Report unavailable: the report is not available yet. Evaluation may still be
  finishing" — consistent with the evaluator backlog above, not a bug.
- **Replay and disconnect/reconnect (steps 6–7) — not reached.** Ran out of live-call
  budget; both Gemini models configured this session hit their daily free-tier cap before
  getting here. Remains open.
- **Unresolved, low-confidence: intermittent "Maximum update depth exceeded" React
  warning** plus a burst of 9× HTTP 404s, seen in 2 of ~7 live attempts entering the
  interview view for a full-loop DSA round. Never seen on the Setup screen alone. Isolated
  resize-only and screenshot-only diagnostic runs (3/3 each) didn't reproduce it, so the
  trigger is still unknown — flagging as open rather than dismissing it. No confirmed
  functional impact: turns sent and interviewer replies arrived correctly in runs both
  with and without the warning.
- **Test-design note, not an app bug:** my scripted candidate answers assumed a
  "contains-nearby-duplicate" array problem, but the round actually assigned was "Lowest
  Common Ancestor in a Binary Tree" — a different DSA question entirely. The evaluator
  correctly scored the mismatched answers as a failure (`score=1` across all signals,
  `end_round reason=Candidate repeatedly refused/failed to engage with the problem`) and
  ended the round for a legitimate reason. This still exercised and confirmed real
  `end_round` triggering and phase-strip completion — just not via a "candidate finished
  cleanly" path.

Related: the round-2-transition dead-air gap is a UX/observability issue, distinct from
H4's disconnect-recovery gap (which is about the frontend recovering after losing the WS
connection entirely, not about a slow-but-still-connected evaluator). Track separately.

---

## H2 — Measure and document Gemini prompt-cache behaviour

**Size:** M · **Live calls:** a few (budget 5–6)

*(This is T2, renumbered — still open, nobody has touched `GeminiAdapter`'s caching path.)*

### Goal
Either make prompt caching actually happen, or document precisely why it cannot with the
current provider — and stop the docs claiming a cost lever that has never been confirmed.

### Why it matters
`PROJECT_PLAN.md` §1.4 calls cache hits "the single biggest cost lever" and the entire
`InterviewerModule` SPI is shaped around a stable prefix to enable them. **But every live
turn observed so far — including turns 2 and 3 of the same round, with an identical
prefix — reported `cacheReadTokens: 0`.** This is still true as of T1–T6 landing; none of
that work touched the adapter.

The likely cause, to verify rather than assume: `GeminiAdapter` *reads*
`usageMetadata.cachedContentTokenCount()` but never *creates* a cached-content object.
Gemini's explicit context caching requires creating a `CachedContent` with a TTL and
referencing it on subsequent calls — it is not automatic the way Anthropic's inline
breakpoints or OpenAI's implicit prefix cache are. Gemini also has an *implicit* cache on
some models with a minimum-token floor the current prompts may simply fall under
(observed prompt sizes: ~2,000–4,500 input tokens).

### Files
- `src/main/java/com/premd/interviewloop/llm/adapter/GeminiAdapter.java` — the fix, if any
- `src/main/java/com/premd/interviewloop/llm/PromptAssembler.java` — read only; do not
  reorder anything here (invariant 1 in `AGENTS.md`)
- `src/main/java/com/premd/interviewloop/llm/CostLedger.java` — already logs
  `cache_read=…` on every call; now also logs real `cost=$…` since T1

### Steps
1. **Measure first.** Run one round to at least 3 candidate turns; record `cache_read=`
   from each `CostLedger` log line. Confirm the problem is still real before touching code.
2. Check Gemini's current Java SDK docs for context caching: what creates a cached-content
   object, the **minimum token floor**, and TTL semantics.
3. Compare that floor to real prompt sizes from step 1.
4. Decide, and write down which case it is:
   - **(a)** Prompt is under the provider's cache floor → caching cannot apply as designed.
     Update `PROJECT_PLAN.md` §1.4 to state this plainly. Do not leave the docs implying
     it works.
   - **(b)** Explicit cache creation is simply missing → implement it in `GeminiAdapter`,
     keyed on the stable prefix, TTL longer than one round.
5. If implemented: verify `cache_read` is non-zero by turn 3 of a fresh round, and check
   whether `cost=$…` visibly drops on the cached turns now that T1 pricing is real.

### Acceptance criteria
- [ ] Real measured `cache_read` numbers from a live round, written down.
- [ ] Either caching demonstrably works (non-zero by turn 3), **or** `PROJECT_PLAN.md`
      §1.4 states plainly that it does not, and why.
- [ ] `AGENTS.md` invariant 1 updated if the conclusion changes what matters.

### Pitfalls
- Do not reorder prompt assembly to "help" caching — the order is already correct; the
  missing piece, if any, is provider-side cache creation.
- Do not add caching to `ClaudeAdapter` in the same change — different mechanism entirely.
- Budget: 3 turns × 2 runs is enough. Do not loop trying to force a cache hit.

---

## H3 — Provider parity check

**Size:** S · **Live calls:** two controlled rounds (one per provider)

*(This is T7, renumbered — still open. No `ANTHROPIC_API_KEY` is present in this
environment as of this writing; confirm with the owner before assuming one is available.)*

### Goal
Run the same question through Gemini and Claude and record how differently they behave.

### Why it matters
An unfinished Phase 0 exit criterion, still unfinished after five modules and 53 tests.
It is what proves the provider SPI is a real abstraction rather than one vendor with
indirection — and it gives a first read on how far apart interviewer judgement is, which
is the entire reason the evaluator is pinned rather than floating (§1.5).

### Steps
1. **Ask the owner for `ANTHROPIC_API_KEY`** if it is not present — do not assume.
2. Run the same question slug through a round on each provider (switch the interviewer
   binding via `PUT /api/settings/interviewer` or the settings UI — **not** the evaluator;
   see Pitfalls).
3. Record: does the round complete, do control calls fire correctly, are signals recorded
   with the right dimension strings, and how do the two evaluations differ in score for
   comparable candidate behaviour.
4. Write findings into `PROJECT_PLAN.md`, as a short subsection under §1.5.

### Acceptance criteria
- [ ] Both providers complete a round with no adapter errors.
- [ ] Findings written down, including score divergence, in `PROJECT_PLAN.md`.

### Pitfalls
- **Do not change the pinned evaluator to run this** — that starts a new comparability
  epoch for a purpose that doesn't need one. Vary the *interviewer* binding only.
- Two rounds is the budget. This is a comparison, not a benchmark suite.

---

## H4 — Cost ceilings, disconnect recovery and packaged startup

**Size:** L · **Live calls:** none for the core work; a couple to confirm the ceiling fires

This card bundles three independent Phase 9 items. They can be done in any order, or split
across agents — each has its own acceptance criteria. Do not let the size discourage
picking off just one.

### H4a — Per-session cost ceiling (resolves D-7)

**Goal:** Warn, and optionally hard-stop, a session that crosses a cost threshold.

**Why it matters:** `LlmCallRepository` already has `sumCostByRoundId` and
`sumCostBySessionId` — the querying exists, nothing calls it. Now that T1 filled in real
Gemini pricing, these queries return real numbers for the first time. D-7 in
`PROJECT_PLAN.md` §5.3 explicitly leaves open *whether* the app should warn, hard-stop, or
merely report — **this task should pick one and document the choice as resolving D-7, not
silently implement something and leave D-7 marked open.**

**Files:** `session/TurnOrchestrator.java` (where else would a check run — right after a
turn's cost is recorded), `domain/repository/LlmCallRepository.java` (read),
`llm/CostLedger.java`, wherever session config would live (`config/providers.yaml` is the
existing pattern for provider-level; a ceiling is more naturally session/global — check if
it belongs there or as a new small config).

**Steps:**
1. Decide the mechanism (D-7): a warning threshold surfaced to the UI, and/or a hard stop
   that refuses further turns. A warning-only default is the safer choice for a first cut —
   a hard stop mid-interview is a worse experience than an unexpectedly large bill for a
   single-user local app. Write down which you chose and why.
2. After recording a turn's cost, sum session cost so far (`sumCostBySessionId`), compare
   to the configured threshold, and surface it — a new frame type over the WebSocket is the
   consistent pattern (see how `usage` frames already work in `FrameCodec`).
3. Update `PROJECT_PLAN.md` §5.3 D-7 to record the resolution.

**Acceptance criteria:**
- [ ] A session that crosses the threshold visibly warns (verify with a low threshold set
      deliberately low for the test, not by running up a real bill).
- [ ] D-7 is marked resolved in `PROJECT_PLAN.md`, with the reasoning.

### H4b — Disconnect recovery: resume shows the existing transcript

**Goal:** A reconnecting client sees the round's actual transcript, not a blank screen.

**Why it matters:** This is a **real, previously undocumented gap**, found while writing
this card. `TurnOrchestrator.beginRound` is idempotent on `questionSlug != null` (invariant
4-adjacent behaviour from the round-start fix) — so a `start_round` sent by a *reconnecting*
client on an already-pinned round correctly does **not** re-run the opening brief... but
nothing else re-sends the transcript either. `useInterviewSocket.ts` has solid
exponential-backoff reconnect logic, but `App.tsx`'s `handleSocketOpen` does not appear to
refetch transcript state on reopen. The practical effect: refresh the browser mid-round, or
lose the connection briefly, and the visible chat history may be gone even though it is
safely persisted server-side.

**Files:** `web/src/App.tsx` (`handleSocketOpen`, session/round state), the existing
`GET /api/rounds/{id}/transcript` endpoint (already built, unused for this)

**Steps:**
1. **Confirm the gap first** — do not assume the analysis above is complete. Refresh the
   browser mid-round and check whether the transcript survives.
2. If confirmed: on reconnect (or on mount, if a round id is recoverable — check how the
   frontend currently persists which round is "current," if at all), call
   `GET /api/rounds/{id}/transcript` and `GET /api/rounds/{id}/artifacts` and rebuild the
   visible chat/editor state from them before (or instead of) relying on the socket to
   redeliver anything.
3. Consider whether the round id needs to survive a full page reload (e.g. in the URL or
   `localStorage`) — check current behaviour before adding this; it may already be lost at
   that point regardless of the transcript fix.

**Acceptance criteria:**
- [ ] Refreshing the browser mid-round restores the visible transcript.
- [ ] A brief backend restart, then reconnect, restores the visible transcript.

### H4c — Packaged single-command startup

**Goal:** One command starts the whole app; no separate `npm run dev` needed.

**Why it matters:** Currently two processes, two terminals, per the README. Fine for
development; not "packaged." No static resource serving or frontend build step is wired
into the Maven build today — confirmed absent from `pom.xml`.

**Files:** `pom.xml`, `web/package.json`, `src/main/resources/static/` (does not exist yet)

**Steps:**
1. Add a frontend build step to the Maven build (`frontend-maven-plugin` is the standard
   choice) that runs `npm run build` and copies `web/dist` into
   `src/main/resources/static` (or a build-output equivalent — do not commit built assets).
2. Confirm Spring Boot serves the built frontend at `/` when run as a packaged jar
   (`java -jar target/*.jar`), with `/api` and `/ws` still routed to the backend
   controllers, not swallowed by static-resource handling.
3. Update the README quick-start with the new single-command path, **keeping** the
   two-process dev instructions for active development (hot reload is worth keeping).

**Acceptance criteria:**
- [ ] `./mvnw clean package && java -jar target/*.jar` serves a fully working app on one
      port, no separate frontend process.
- [ ] `npm run dev` two-process development flow still works unchanged.

**Pitfalls (all of H4):**
- Keep these three sub-tasks independent in your commits — a reviewer (human or agent)
  should be able to accept H4b without H4c.
- H4a needs T1's real pricing to mean anything; it was worthless before this session.

---

## H5 — Anchored evaluator examples to reduce score inflation

**Size:** M · **Live calls:** a few, for calibration comparison — see step 4

### Goal
Give the evaluator concrete anchor examples at each score band, not just the abstract
"1 = below bar, 3 = at bar, 5 = above bar" line every rubric already has.

### Why it matters
`PROJECT_PLAN.md` §3 names this as one of Phase 7's anti-inflation measures — "fixed
rubric text, **anchored few-shot examples at each band**, evaluator separated from the
interviewer." The first and third are done; the middle one was never built.
`RoundEvaluator.buildRequest`'s system prompt currently has only the module's `rubric()`
text and a generic calibration paragraph — no worked example of what evidence actually
earns a 2 versus a 4. LLM-as-judge scoring is well known to drift toward the middle or
toward generosity without concrete anchors; this project has no test coverage or
measurement showing whether that is happening here.

### Files
- `src/main/java/com/premd/interviewloop/evaluation/RoundEvaluator.java` —
  `buildRequest(...)`, the `system` string specifically
- Possibly new: `evaluation/EvaluationAnchors.java` if the anchor text is substantial
  enough to want its own home rather than living inline

### Steps
1. **Decide where anchors live — this is a real design choice, make it deliberately:**
   - **(a) Generic, module-agnostic anchors.** Describe, in the abstract, what a 2-scoring
     response pattern looks like versus a 4-scoring one (e.g. "vague, no mechanism, caves
     under one push-back" vs. "specific, names a mechanism, adjusts when challenged"),
     without referencing one specific question. Keeps `evaluation` independent of module
     content, matching the package boundary in `AGENTS.md`'s architecture map.
   - **(b) Per-module anchors.** One short worked example per module, referencing that
     module's actual dimension names. More concrete, more calibrated, but couples
     `evaluation` to module content and means 5 sets of anchors to maintain, not 1.
   - Recommendation: start with (a) — it is additive to `RoundEvaluator` alone, costs
     nothing to maintain as modules are added, and is the lower-risk first step. Note (b)
     as a possible follow-up rather than attempting both at once.
2. Write 2 anchor examples (a clear "below bar" and a clear "above bar" case) covering
   evidence quality, specificity, and response to pushback — the qualities that generalise
   across every rubric's dimensions.
3. Insert them into the `system` prompt in `buildRequest`, after `module.rubric()`, as
   their own clearly-labelled block (e.g. `"CALIBRATION EXAMPLES (not this round's
   candidate):"`) so the model cannot confuse them with the actual round's evidence.
4. **Measure the effect**, don't just assume it helped: take one real round's already-
   recorded signals + transcript (reuse data from an earlier verification round if you
   have one, to avoid a fresh live call) and run it through the evaluator with and without
   the anchors. Compare the scores. Write down what changed.

### Acceptance criteria
- [ ] Anchors are in the evaluator's system prompt, clearly separated from the real
      round's evidence so they cannot be confused with it.
- [ ] A before/after comparison exists and is written down — even a null result ("no
      visible change on this example") is useful information, record it either way.
- [ ] `PROJECT_PLAN.md` §3 updated to reflect anchoring is now implemented.

### Pitfalls
- Anchors that are too close to a real question risk the evaluator pattern-matching
  against them instead of the actual round. Keep them generic per step 1's recommendation.
- Do not let anchor text grow large enough to meaningfully change evaluator cost — this is
  a short, stable block, not a second rubric.

---

## H6 — Grow original question banks and validate profiles from first-hand data

**Size:** S each · **Live calls:** none

This card has two genuinely different halves — read which one applies to you before
starting.

### H6a — Grow the remaining question banks (agent-doable)

DSA grew from 5 to 11 (PR #1); **LLD (5), HLD (4), Java deep-dive (6), and CS fundamentals
(2 packs) have not grown.** Follow the exact process in the original T8 card below
(original prose only, filename stem = slug, `interviewer_notes` required, hand-verify every
worked example, restart to validate at boot). Prioritise LLD and HLD — smaller banks, and
the ones most likely to repeat for the owner soonest.

### H6b — Validate profiles from first-hand data (owner-only — do not attempt this half)

All 11 company profiles are `calibration.confidence: seeded-unverified` — plausible
defaults generated at setup, not facts. **An agent cannot resolve this.** Correcting a
profile's round structure, quirks, or difficulty bar requires the owner's own first-hand
interview experience at that company; there is no source an agent can consult instead
without fabricating exactly the kind of ungrounded claim `AGENTS.md` prohibits ("never
present seeded numbers as established").

If you are an agent and this task reaches you: **do not edit `calibration.confidence` or
invent round details.** The one thing worth doing here is mechanical, not evidentiary —
check whether the profile schema and `ProfileLoader` make it easy for the owner to *record*
a correction (e.g., is `calibration.sources` well-documented enough that a human filling it
in by hand knows the expected shape?). If that tooling is already adequate, this half of
the task has nothing left for an agent to do — say so rather than inventing work.

---

## T1 — Fill in real provider pricing

**Size:** XS (15 minutes) · **Live calls:** none

### Goal
Make `cost_estimate_usd` produce real numbers instead of `0`.

### Why it matters
`PROJECT_PLAN.md` calls cost a first-class concern, and `llm_call` rows are written for
every call — but `config/providers.yaml` has `input: <set-me>` / `output: <set-me>` for
Google, and `CostLedger.estimateCost` returns `0.0` when pricing is missing. So the ledger
currently records **zero for every call**. Nothing downstream (D-7 cost ceilings, cost
display in the UI) can be built until this is real.

### Files
- `config/providers.yaml` — the `google` entry's `pricing_usd_per_mtok` block
- Verify with: `src/main/java/com/premd/interviewloop/llm/CostLedger.java` (read only)

### Steps
1. Look up current per-million-token pricing for the Gemini models listed in
   `config/providers.yaml` (`gemini-3.7-flash`, `gemini-3.6-flash`,
   `gemini-3.5-flash-lite`) on Google's official pricing page. **Do not guess.**
2. Replace `<set-me>` with real numbers under the `google` provider's
   `pricing_usd_per_mtok`. Keep them as plain numbers, not strings.
3. Note in a YAML comment the date you checked and the tier the price is for.
4. Restart the app and run one round turn. Check the log line from `CostLedger` — it
   should show a non-zero `cost=$…`.

### Acceptance criteria
- [ ] `GET /api/rounds/{id}/…` flows still work; app boots clean.
- [ ] A `CostLedger` log line shows non-zero cost after one turn.
- [ ] No `<set-me>` remains under the `google` pricing block.

### Pitfalls
- Pricing is **per million tokens** (`pricing_usd_per_mtok`). Do not enter per-1K prices.
- Providers price input and output differently; do not use one number for both.
- The `anthropic` entry already has real numbers — match that format exactly.
- This is a data file. **No rebuild required, no code change.**

---

## T2 — Find out why prompt caching is not working

**Size:** M · **Live calls:** a few (budget 5–6)

### Goal
Either make prompt caching actually happen, or document precisely why it cannot with the
current provider — and stop the docs claiming a cost lever that is not real.

### Why it matters
`PROJECT_PLAN.md` §1.4 calls cache hits "the single biggest cost lever" and the entire
`InterviewerModule` SPI is shaped around a stable prefix to enable them. **But every live
turn observed so far reported `cacheReadTokens: 0`** — including turns 2 and 3 of the same
round, where the prefix was identical and a cache hit was expected.

The likely cause, which you should verify rather than assume: `GeminiAdapter` *reads*
`usageMetadata.cachedContentTokenCount()` but never *creates* a cached-content object.
Gemini's explicit context caching (per DM-5 and §1.5) requires creating a `CachedContent`
with a TTL and referencing it on subsequent calls; it is not automatic the way Anthropic's
inline breakpoints or OpenAI's implicit prefix cache are. Gemini also has an *implicit*
cache on some models with a minimum-token floor that the current prompts may fall under.

### Files
- `src/main/java/com/premd/interviewloop/llm/adapter/GeminiAdapter.java` — the fix, if any
- `src/main/java/com/premd/interviewloop/llm/PromptAssembler.java` — read only; do not
  reorder anything here (invariant 1 in `AGENTS.md`)
- `src/main/java/com/premd/interviewloop/llm/CostLedger.java` — already logs
  `cache_read=…` on every call

### Steps
1. **Measure first.** Start the app, run one round to at least 3 candidate turns, and
   record the `cache_read=` value from each `CostLedger` log line. Confirm the problem is
   real before changing code.
2. Check Gemini's current Java SDK docs for context caching: what creates a cached content
   object, what the **minimum token floor** is, and what TTL semantics are.
3. Compare that floor to the actual prompt size in a real round (the log line shows
   `in=…` input tokens — observed values were ~2,000–4,500).
4. Decide, and write down which case it is:
   - **(a)** Prompt is under the provider's cache floor → caching cannot apply as designed.
     Record this in `PROJECT_PLAN.md` §1.4 honestly. Do not pretend it works.
   - **(b)** Explicit cache creation is simply missing → implement it in `GeminiAdapter`,
     keyed on the stable prefix, with a TTL that outlives one round.
5. If you implement caching: verify `cache_read` is non-zero by turn 3 of a fresh round.

### Acceptance criteria
- [ ] You have real measured `cache_read` numbers from a live round, written down.
- [ ] Either caching demonstrably works (non-zero by turn 3), **or** `PROJECT_PLAN.md`
      §1.4 is updated to state plainly that it does not, and why.
- [ ] `AGENTS.md` invariant 1 is updated if the conclusion changes what matters.

### Pitfalls
- **Do not reorder prompt assembly to "help" caching.** The current order is already
  correct; the missing piece is provider-side cache creation.
- Do not add caching to `ClaudeAdapter` in the same change — different mechanism entirely
  (inline breakpoints), and mixing them makes the result unattributable.
- This task can burn quota fast. 3 turns × 2 runs is enough; do not loop.

---

## T3 — First integration test for the turn loop

**Size:** M · **Live calls:** none (mock the provider)

### Goal
One test that starts Spring, drives a candidate turn through `TurnOrchestrator` with a
fake `LlmProvider`, and asserts the round state changed correctly.

### Why it matters
All 38 existing tests cover question-bank loading and module prompt text. **Nothing tests
orchestration** — yet that is where every real bug in this repo has been: control-call
refusal, the silent-turn retry, transaction boundaries, lazy-loading blowups. A single
integration test would have caught three of the four bugs fixed during Phases 2–3.

### Files
- New: `src/test/java/com/premd/interviewloop/session/TurnOrchestratorIntegrationTest.java`
- Read: `session/TurnOrchestrator.java`, `llm/LlmProvider.java`, `llm/LlmEvent.java`
- Existing test config: `src/test/resources/application.yaml`

### Steps
1. Write a fake `LlmProvider` (a test class, or `@TestConfiguration` bean) that returns a
   scripted `Flux<LlmEvent>` — no network. Emit, in order: a couple of `TEXT_DELTA`
   events, one `TOOL_CALL` (`advance_phase`), a `USAGE` event, then `DONE`.
2. Register it so `ProviderRegistry` resolves it instead of Gemini. Look at how
   `ProviderFactory` and `ProviderKeyStore` decide what is available — the clean seam is
   a test `ProviderFactory` bean plus a UI key, not editing production code.
3. `@SpringBootTest` that: creates a session, starts a round, calls
   `handleCandidateTurn(...)` with a no-op `TurnSink` (there is a `TurnSink.noop()`
   factory), then asserts:
   - a candidate turn and an interviewer turn were persisted
   - the round's phase advanced
   - an `llm_call` row was written
4. Then add the case that actually matters: emit **only** a `TOOL_CALL` and no text, and
   assert the silent-turn continuation fired (see invariant 4 in `AGENTS.md`) — i.e. the
   provider was called twice and a non-empty interviewer turn was persisted.

### Acceptance criteria
- [ ] `./mvnw -o test` passes with the new test included.
- [ ] The test makes **no network call** (unplug the network and it still passes).
- [ ] The silent-turn case is covered, not just the happy path.

### Pitfalls
- Do not use the real `GEMINI_API_KEY` in a test. No test may hit a real provider.
- H2 is file-mode in production config; make sure the test profile does not write to the
  owner's real `./data/interview-loop.mv.db`. Check `src/test/resources/application.yaml`.
- `TurnOrchestrator` streams **outside** a transaction deliberately (invariant 5). Do not
  wrap the test in `@Transactional` to "make it simpler" — that changes what is tested.

---

## T4 — Session report at end of a session

**Size:** M · **Live calls:** 1–2

### Goal
When every round in a session is complete, write a `session_report` row and expose it.

### Why it matters
The `session_report` table exists and is mapped, but **nothing writes to it.** Per-round
evaluation works (`RoundEvaluator`); there is no session-level view. For a `single_module`
session this is a thin wrapper over one round; for a full loop (T6) it is the actual
deliverable.

### Files
- New: `src/main/java/com/premd/interviewloop/evaluation/SessionReporter.java`
- New endpoint in: `transport/EvaluationController.java` (or a new `ReportController`)
- Read: `evaluation/RoundEvaluator.java` (copy its shape), `domain/SessionReport.java`,
  `domain/repository/SessionReportRepository.java`
- Trigger point: `session/SessionManager.checkSessionCompletion(...)` already detects when
  a session finishes

### Steps
1. `SessionReporter.report(Long sessionId)`:
   - load rounds via `SessionRoundRepository.findBySessionIdOrderByOrdinalAsc`
   - load each round's evaluation via `RoundEvaluationRepository.findByRoundId`
   - skip rounds with no evaluation rather than failing
2. Compute `overallBand`: mean of per-round mean scores, weighted by the company profile's
   `emphasis` for that module type (`ProfileLoader` → `CompanyProfile.getEmphasis()`), then
   `ReadinessBand.fromScore(...)`, stored via `.wireValue()`.
3. Store `perModule` as JSON: module type → its mean score. **`TEXT` column holding JSON,
   deserialised in the app layer** — no vendor JSON types (see `AGENTS.md`, portable SQL).
4. Narrative: either concatenate the per-round narratives, or make one evaluator call that
   summarises them. If you make an LLM call, use the **pinned evaluator**
   (`ProviderRegistry.resolveEvaluator()`), never the interviewer binding.
5. Trigger it where the session transitions to completed, and make it **best-effort**:
   wrap in try/catch, log a warning, never let a reporting failure undo session completion.
   `SessionController.completeRound` shows the exact pattern to copy.
6. Expose `GET /api/sessions/{id}/report`. Return 404 with a clear message when absent.

### Acceptance criteria
- [ ] Completing a single-module session writes exactly one `session_report` row.
- [ ] `GET /api/sessions/{id}/report` returns band, per-module scores, and narrative.
- [ ] A forced failure inside the reporter does **not** stop the session completing.
- [ ] Re-completing does not create duplicate rows (check `findBySessionId` first).

### Pitfalls
- `InterviewSession.rounds` is LAZY and `open-in-view` is disabled — see invariant 6.
  Use a fetch-join repository method, or load rounds separately by session id.
- Do not put the trigger inside the same transaction that completes the session if you
  make an LLM call — see invariant 5.

---

## T5 — Readiness rollup + trend API

**Size:** L · **Live calls:** none

### Goal
Create the `progress` package: turn round evaluations into readiness snapshots and expose
a trend endpoint. This is the "am I getting better?" payoff the project exists for.

### Why it matters
`readiness_snapshot` is mapped with useful query methods already, and **nothing writes to
it.** Without this, the app is a practice tool with no memory — every round is isolated.
`PROJECT_PLAN.md` §3 specifies exactly how readiness is meant to be computed.

### Files
- New package: `src/main/java/com/premd/interviewloop/progress/`
  - `ReadinessCalculator.java` — the maths
  - `ProgressController.java` — or add to `transport/`
- Read: `domain/ReadinessSnapshot.java`, `domain/repository/ReadinessSnapshotRepository.java`,
  `domain/enums/ReadinessBand.java`, `profile/CompanyProfile.java` (readiness config)

### Steps
1. After a round evaluation is written, also write a `ReadinessSnapshot(moduleType,
   companyProfileId, score, sampleSize)` — constructor already exists.
2. Implement company readiness per `PROJECT_PLAN.md` §3, which specifies all four parts:
   - `emphasis`-weighted mean across module scores
   - **recency weighting** — older sessions decay
   - **gated by `readiness.module_minimums`** — one module below its floor blocks "ready"
     regardless of the weighted average
   - confidence driven by `readiness.min_sessions_for_confidence`
3. Expose:
   - `GET /api/progress/readiness/{companyProfileId}` → current band, per-module scores,
     which minimums are failing, confidence level
   - `GET /api/progress/trend?module=dsa` → snapshots over time
4. **Include `comparabilityEpoch` in every trend response.** Scores from different epochs
   are not comparable (§1.5) and the UI must be able to mark the break. Get it from
   `AppSettingsStore.comparabilityEpoch()`; `round_evaluation` also stores it per row.
5. Write unit tests for the maths — decay, minimum-gating, and confidence are exactly the
   kind of logic that is easy to get subtly wrong and never notice.

### Acceptance criteria
- [ ] A completed round writes a `readiness_snapshot` row.
- [ ] Readiness respects `module_minimums` — verify with a deliberately low module score.
- [ ] Trend output carries the comparability epoch per point.
- [ ] Unit tests cover decay, gating, and confidence thresholds.

### Pitfalls
- **Do not average across comparability epochs as if they were one series.** That is the
  precise failure mode the epoch mechanism exists to prevent.
- Honesty matters here (§3): these numbers measure a *model's* idea of a company's bar,
  seeded from `seeded-unverified` profiles. Whatever the UI shows must not imply more
  precision than that. Label it as a trend indicator, not a prediction.
- Portable ANSI SQL only if you add a migration.

---

## T6 — Full-loop round chaining (Phase 6)

**Size:** L · **Live calls:** several (expensive — do T1 first so cost is visible)

### Goal
Make a `full_loop` session actually run as a loop: round 1 → 2 → 3 with continuity.

### Why it matters
The largest missing *feature*. `SessionManager.createSession` already creates one
`session_round` per profile round and marks `enabled_in_v1: false` ones as `SKIPPED` — but
nothing chains them. There is no advance-to-next-round, and no carry-over between rounds.

### Files
- `session/SessionManager.java` — round advancement
- `session/TurnOrchestrator.java` — where a round ends (`ControlCall.EndRound`)
- `interviewer/RoundContext.java` — likely needs a carry-over brief field (**add via a
  builder field; do not break existing callers**)
- `transport/InterviewWebSocketHandler.java` + `web/src/App.tsx` — surfacing the transition

### Steps
1. **Carry-over brief.** Per §1.4, each round gets a *fresh context* seeded with a few
   hundred tokens summarising prior rounds — **not** an accumulating transcript. Generate
   it when a round completes (the evaluator's strengths/gaps are already a good source —
   reuse them rather than making another LLM call if you can).
2. Add the brief to `RoundContext` as a new builder field, and render it in the module
   personas. Keep it in the **stable** half of the prompt: it does not change within a
   round.
3. Advance: when a round completes in a `full_loop` session, find the next non-`SKIPPED`
   round by ordinal and make it startable. Announce skipped rounds to the candidate rather
   than silently omitting them (`enabled_in_v1: false` rounds are real parts of the loop).
4. Apply `loop.difficulty_curve` from the profile to each round's difficulty target.
5. Frontend: show loop progress (round 2 of 5), and handle the round transition without
   requiring a manual page reload.

### Acceptance criteria
- [ ] A `full_loop` session runs at least two rounds back to back.
- [ ] Round 2's prompt contains the carry-over brief and **not** round 1's transcript.
- [ ] Rounds marked `enabled_in_v1: false` are announced and skipped, not silently dropped.
- [ ] A single-module session still works exactly as before.

### Pitfalls
- **Do not accumulate transcripts across rounds.** It is expensive, and §1.4 rejects it on
  realism grounds too — a real interviewer has not read your previous round.
- Keep the brief out of `phaseDirective()` — it is stable, not volatile (invariant 1).
- `SessionStateMachine` governs round status transitions; extend it rather than bypassing.
- This is the most expensive task to test. Consider driving it with the fake provider from
  T3 before spending real quota.

---

## T7 — Provider parity check

**Size:** S · **Live calls:** 2 rounds (one per provider)

### Goal
Run the same question through Gemini and Claude and record how differently they behave.

### Why it matters
An unfinished Phase 0 exit criterion. It is what proves the provider SPI is a real
abstraction rather than one vendor with indirection — and it gives a first read on how far
apart their judgement is, which is the entire reason the evaluator is pinned (§1.5).

### Steps
1. Needs an `ANTHROPIC_API_KEY`; the owner may not have one. **Ask before assuming.**
2. Run the same question slug through a round on each provider (switch via
   `PUT /api/settings/interviewer`, or the settings UI).
3. Record: does the round complete, do control calls fire correctly, are signals recorded
   with the right dimension strings, and how do the two evaluations differ in score.
4. Write findings into `PROJECT_PLAN.md` — a short subsection under §1.5 is the right home.

### Acceptance criteria
- [ ] Both providers complete a round without adapter errors.
- [ ] Findings written down, including score divergence.

### Pitfalls
- Do **not** change the pinned evaluator to run this — that starts a new comparability
  epoch. Vary the *interviewer* binding only.

---

## T8 — Grow the question banks

**Size:** S per question · **Live calls:** none

### Goal
More questions, better topic coverage.

### Why it matters
Banks are starter-sized: DSA 11, LLD 5, HLD 4, Java 6, CSF 2 packs. With a bank this small
you will start recognising questions, which destroys the point. There is also no
recency-avoidance — selection is random within a difficulty band.

**DSA coverage gaps (filled):** binary search, trees, graphs (BFS/DFS/Dijkstra),
backtracking, DP, and tries were added. Remaining coverage to grow: stack/monotonic-stack,
union-find, and two-pointer patterns.

### Steps
1. Copy an existing YAML file in `question-bank/<module>/` as your template.
2. **Write original prose.** Copying LeetCode/GfG statement text is a licensing problem
   even for personal use — see D-5 in `PROJECT_PLAN.md`. Standard algorithmic *patterns*
   are fine; copied *text* is not. Curated lists (Striver's A2Z sheet) are useful as
   coverage checklists, not as text to copy.
3. Filename stem **must** equal the `slug` field — the loader enforces this at boot.
4. `interviewer_notes` is required and is interviewer-only: the expected approach,
   complexity, and the specific pitfalls to probe. This is what the model judges against.
5. **Hand-verify every worked example.** Trace the algorithm yourself. A wrong example in
   the bank teaches the interviewer to accept a wrong answer.
6. Restart the app — loaders validate at boot and refuse to start on a malformed file.

### Acceptance criteria
- [ ] App boots and the log line shows the new count.
- [ ] Every example traced by hand and correct.
- [ ] Difficulty is one of `easy | medium | medium-hard | hard`.

### Pitfalls
- YAML: a value starting with `"` is a quoted scalar — wrap the whole value in single
  quotes. Quote dates or they parse as `date` objects.
- Keep SDE-2 calibration. Do not add senior-level questions (`AGENTS.md`, Hard constraints).

---

## Also worth doing, not yet carded

- **Verify the replay UI.** `web/src/components/ReplayView.tsx` exists but has never been
  confirmed working end to end. Backend endpoints (`/transcript`, `/artifacts`) work.
- **Recency-avoidance in question selection** — stop the same question recurring.
- **Cost display in the UI.** Depends on T1.
- **`mvn validate` profile validation** — planned in Phase 1, never wired. Validation
  currently happens at app startup, which is arguably sufficient.
- **Frontend tests.** There are none.
