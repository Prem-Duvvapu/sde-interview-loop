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
> single full round can use 10+. Prefer T1/T3/T5/T8 (no live calls) when quota is spent.
> Quota is per-model: switching to `gemini-3.6-flash` in settings gives a separate bucket.

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
