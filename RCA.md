# RCA.md — incidents, root causes, and what they taught this project

A running log of things that went wrong (or almost did), why they were missed, and how
they were caught or fixed. This project is worked on by multiple AI agents across
sessions with no shared memory except what's written down — this file exists so the same
mistake doesn't get made twice by two different agents who never talked to each other.

**When you find or fix something RCA-worthy, add an entry.** A bug that only you know
about and never write down might as well not have been fixed, for the next agent's
purposes.

**What belongs here:** anything that shipped wrong, any documented invariant that got
violated anyway, any gap between "claimed done" and "actually verified." Not every bug
fix needs an entry — routine fixes with an obvious cause don't. This is for the ones with
a lesson in them.

---

## Summary

| # | Date | Incident | Status | Fixed by |
|---|---|---|---|---|
| 1 | 2026-08-23 | Duplicate repo checkout accidentally committed into `main` | Fixed | `8636314` |
| 2 | 2026-08-23 | `GET /api/sessions` crashed on lazy JPA associations | Fixed | `bbc9768`, `e4d86e0` |
| 3 | 2026-08-23 | Starting a round crashed on itself (REST + WS both drove the same transition) | Fixed | `e4d86e0` |
| 4 | 2026-08-23 | Interviewer went silent after tool calls — candidate saw nothing | Fixed | `875253f` |
| 5 | 2026-08-23 | API keys could only be set at startup, never from the UI | Fixed (by design change) | `b8698ca` |
| 6 | 2026-08-29 | Docs said "no code written" / "don't code yet" while 5 modules shipped | Fixed | `fba203c`, `221e985` |
| 7 | 2026-08-29 | First readiness-rollup implementation ignored comparability epochs | Fixed same week | `e3b3265` |
| 8 | 2026-08-29 | Full-loop chaining shipped with no browser or live-LLM verification | **Open** | tracked as `H1` |
| 9 | ongoing since Phase 1 | Prompt caching never verified working; likely never implemented | **Open** | tracked as `H2` |
| 10 | 2026-08-22 → 2026-08-29 | Cost ledger recorded `$0` for every call for a week of development | Fixed | `T1` (`d8ebb97`) |
| 11 | 2026-08-22 → 2026-08-29 | Plan specified Zustand + TanStack Query; frontend used neither, plan not corrected | Fixed (docs) | `fba203c` |
| 12 | 2026-08-23 | Live-testing exhausted the owner's Gemini free-tier quota mid-session | Understood, not "fixable" | documented in `AGENTS.md` |
| 13 | 2026-09-05 | `start.sh` killed a backend that had actually started, on a false readiness timeout | Fixed | `start.sh` |

---

## 1 — Duplicate repo checkout committed into `main`

**What happened.** Commit `0b94d00` ("Add .claude worktree...") added **89 files** under
`.claude/worktrees/agent-.../` — a full duplicate copy of the entire source tree at that
point in time, including its own `pom.xml`, `mvnw`, company profiles, and source files.

**Impact.** Repo bloat and a genuinely confusing `git log` — the same file appeared to
exist at two paths. No secrets were exposed (the duplicated `.env.example` was still the
blank template), but a public GitHub repo carrying a stray duplicate of itself is exactly
the kind of thing that looks like a leak until someone checks.

**Root cause.** A background agent working in its own git worktree (a legitimate, isolated
checkout for parallel work) ran a broad `git add` from the wrong working directory,
sweeping its entire worktree into the main branch's commit instead of just its intended
changes.

**How it was found.** Noticed while reviewing `git status`/`git log` output before an
unrelated commit — the file count for a "small" commit was implausibly large.

**Fix.** `git rm -r --cached` on the duplicated path, and `.claude/worktrees/` added to
`.gitignore` so it cannot recur (`8636314`).

**Lesson.** When using git worktrees for parallel agent work, **the worktree's own git
operations must never target the main checkout**, and vice versa. Review `git status`
output for surprising file counts before committing, especially after any parallel-agent
session — a normal-looking commit message can hide an abnormal diff.

---

## 2 — `GET /api/sessions` crashed on lazy JPA associations

**What happened.** `GET /api/sessions` and `GET /api/sessions/{id}` threw
`LazyInitializationException` when serialising the response. `SessionManager.getSession`
and `.listSessions()` returned entities straight from `findById`/`findAll...`, with
`open-in-view` disabled — so by the time Jackson tried to walk `InterviewSession.rounds`
(a `LAZY` collection), the Hibernate session was already closed.

**Impact.** Basic session listing/lookup was broken. This is core, load-bearing
functionality — not an edge case.

**Root cause.** Session creation happened to work because the `rounds` collection was
still an in-memory, already-populated list at that point (just built during creation,
never re-fetched from the database) — so the bug was invisible on the one code path that
had been exercised. Nobody had called the read endpoints after session creation until
this was found.

The same shape of bug existed in **four more entities** — `TranscriptTurn.round`,
`ArtifactSnapshot.{round,turn}`, `Signal.{round,turn}`, `LlmCall.{round,turn}`,
`RoundEvaluation.round`, `SessionReport.session` — none annotated to skip serialisation.
Only `SessionRound.session` had the correct `@JsonBackReference` already, from Phase 1.

**How it was found.** While manually verifying the DSA module end-to-end, calling
`GET /api/rounds/{id}/transcript` for the first time hit the identical exception in a
different entity.

**Fix.** Added `@JsonIgnore` to every lazy back-reference across all affected entities;
added fetch-join repository queries (`findByIdWithRounds`,
`findAllWithRoundsByOrderByStartedAtDesc`) for the two session-listing paths that
genuinely need the collection populated (`bbc9768`, `e4d86e0`).

**Lesson.** A bug in one entity's JSON serialisation is a strong signal to check every
sibling entity with the same shape (`@ManyToOne(fetch = LAZY)` back-reference), not just
the one that happened to crash first. This class of bug is silent until the exact code
path that serialises the lazy field is exercised — which may not happen until well after
the entity is written. See `AGENTS.md` invariant 6.

---

## 3 — Starting a round crashed on itself

**What happened.** Reported directly by the owner: *"Cannot transition round 5 from
IN_PROGRESS to IN_PROGRESS."* This was not an edge case — it happened on **every** round
start.

**Root cause.** The web client's design (already built by an earlier agent) calls
`POST /rounds/{id}/start` over REST first — which moves the round `PENDING → IN_PROGRESS`
— then sends `start_round` over the WebSocket purely to bind the socket and receive the
opening brief. `TurnOrchestrator.beginRound` re-ran the *same* `PENDING → IN_PROGRESS`
transition unconditionally, colliding with the REST call every single time.

**How it was found.** The owner hit it immediately on first real use and reported the
exact error text.

**Fix.** `beginRound` became idempotent on **`questionSlug != null`** — the real signal
that a round has begun — rather than on round status. It only runs the state transition if
the round is still `PENDING`, and treats an already-pinned round as a no-op acknowledgment
rather than re-running (`e4d86e0`).

**Lesson.** Two components (REST + WebSocket) driving what looks like "the same" state
transition need one of them to own it and the other to defer — checking status equality is
not enough when two independent call paths can legitimately both fire. Verifying "does the
code compile and pass a unit test" would not have caught this; it took reproducing the
exact sequence the real client uses (REST call, *then* WS call, on the same round) to see
it.

---

## 4 — Interviewer went silent after tool calls

**What happened.** Reported directly by the owner mid-round: they asked a clarifying
question and got no answer — only `advance_phase`/`record_signal` tool-call activity, no
text.

**Root cause, found by reproducing it deliberately.** Standard function-calling protocol
trains models to pause after emitting a tool call and wait for a function *response*
before continuing. This app's control tools (`record_signal`, `advance_phase`,
`set_hint_level`, `end_round`) are applied **silently, server-side** — no function
response is ever sent back within the turn. A model that decided to call one was doing
exactly what it was trained to do afterward: stop talking. Strengthening the persona's
wording alone did **not** reliably fix it — this was verified empirically, not assumed;
the same silent pattern reproduced even after adding an explicit "always speak" instruction.

**Fix, two layers:**
1. Every module's persona explicitly states a turn with only tool calls and no words is
   never acceptable.
2. `TurnOrchestrator` detects a turn that produced tool calls but zero text, and retries
   **once**, with tools withheld — so the model has nothing left to do but produce the
   missing reply (`875253f`).

**Verification note.** The fix was confirmed live against the exact reported scenario.
Further confirmation attempts were cut short by hitting the Gemini free-tier daily quota
(see incident 12) — a real example of quota constraints limiting how much a fix can be
re-verified in one sitting.

**Lesson.** For an architecture where the model's tool calls are consumed silently rather
than round-tripped, **prompt wording is not sufficient defense** — a mechanical backend
safety net is required for behaviour the model was trained into. This is now `AGENTS.md`
invariant 4, and every module added since (LLD, HLD, CSF, Java deep-dive) copied the
persona instruction — worth checking it's still present if a new module is ever added.

---

## 5 — API keys were startup-only; the UI couldn't set them

**What happened.** Not a reported bug — a design gap noticed while implementing the
settings UI the frontend already expected. Provider adapters were `@ConditionalOnProperty`
Spring beans, constructed once at application startup directly from `System.getenv(...)`.
A key pasted into the UI after boot had no code path that could ever reach a live adapter.

**Root cause.** The original Phase 1 design (`PROJECT_PLAN.md` DM-5, as originally written)
assumed keys came from `.env` at startup only — the UI-driven key management the frontend
was already built for was never accounted for in the backend architecture.

**Fix.** Restructured around a `ProviderFactory` SPI that builds a stateless client **per
call** from whatever `ProviderKeyStore` currently resolves (UI-supplied key wins over env),
plus `AppSettingsStore` for the interviewer/evaluator bindings, both changeable at runtime
with no restart (`b8698ca`).

**Lesson.** When a frontend is built ahead of its backend (as happened here — the web
client anticipated a settings API that didn't exist yet), its assumptions are a
specification, not decoration. Check what the frontend already calls before assuming the
existing backend shape is sufficient.

---

## 6 — Docs claimed "no code written" while five modules existed

**What happened.** `PROJECT_PLAN.md` still opened with *"Status: awaiting approval. No
application code has been written"* and `CLAUDE.md` (predecessor to `AGENTS.md`) still
described a pre-implementation state — while, in reality, all five interviewer modules,
round evaluation, runtime key management, and a working web client had shipped.

**Root cause.** Multiple agents worked on this project across separate sessions with no
persistent shared memory beyond the repository itself. Each session that shipped a feature
did not also update the two documents whose entire purpose is to describe project state.
Documentation drift compounds silently — each session that skips the update makes the
next session's drift look normal by comparison.

**How it was found.** Explicitly requested by the owner ("update all the required .md
files"), at which point a full audit against the actual code turned up not just staleness
but specific factual errors — the plan specified Zustand + TanStack Query (incident 11),
and an interim draft of the updated docs itself mis-stated the Java deep-dive module's
artifact kind as `SCRATCH` when the code said `CODE` (caught in self-review before
committing — see "Caught before shipping," below).

**Fix.** Full rewrite of `CLAUDE.md` → `AGENTS.md` (the entry point every agent should
read, regardless of which AI product is running it) and `PROJECT_PLAN.md`'s status/roadmap
sections, with every factual claim spot-checked against the running code rather than
written from memory (`fba203c`).

**Lesson.** Documentation is not a one-time artifact in a multi-agent, multi-session
project — it needs the same "verify, don't assume" discipline as code. **Every claim in a
status document should be checked against the actual code before being written down**, the
same way a test checks behaviour. This RCA file exists partly to make that habit
persistent rather than something that has to be rediscovered each time drift is noticed.

---

## 7 — First readiness rollup ignored comparability epochs

**What happened.** `d8ebb97` shipped `ReadinessCalculator.recordSnapshot(moduleType,
companyProfileId, score)` and `computeReadiness(companyProfileId)` with **no
comparability-epoch parameter at all** — despite `PROJECT_PLAN.md` §1.5 explicitly
existing to prevent exactly this: mixing scores from different pinned evaluators into one
trend, which would measure provider drift rather than the candidate's actual progress.

**Root cause.** The invariant was documented — in the architecture section, and even
spelled out as an explicit "do not" in the task card that specified this exact feature —
but nothing *mechanically* enforced it. A documented rule with no test, no compiler check,
and no code review beyond the implementing agent's own pass is a rule that depends
entirely on that agent remembering to apply it while focused on the rest of the feature.

**How it was found.** Caught within the same working session, one commit later, by the
same agent — not by an external review.

**Fix.** `V2__readiness_snapshot_epoch.sql` added a `comparability_epoch` column;
`ReadinessSnapshot` and `ReadinessCalculator` were extended to filter and record by epoch;
`computeReadiness` now takes an epoch parameter (`e3b3265`).

**Lesson.** A documented invariant is a hint, not a guarantee. **The task card for any
feature involving comparability epochs, rubric dimension strings, or cache-prefix ordering
should include an explicit acceptance-criterion check for it**, not just prose in a "why
it matters" section — this is why later task cards (T5, T6 in `docs/TASKS.md`) list it as
a checkbox, not just a paragraph.

---

## 8 — Full-loop chaining shipped with no browser or live-LLM verification (open)

**What happened.** `5a41471` ("chain full-loop rounds with private handoffs") shipped the
carry-over-brief mechanism and round-to-round chaining with this in its own commit
message: *"Verified: clean compile; 53 Maven tests; frontend typecheck and production
build; running backend applied V3 and returned 200 from profiles and sessions APIs.
**Not browser-driven or live-LLM round tested.**"*

**Why this is worth an entry even though the commit is honest about it.** `AGENTS.md`'s
working agreement says to verify against a running instance, not a compile — and by the
letter of that rule, this *was* verified against a running instance (the API returned
200s). But the feature is a multi-round, UI-driven experience, and nothing about API-level
health checks confirms the actual round-to-round transition renders and works for a real
user. The gap between "the backend didn't crash" and "the feature works" survived an
otherwise careful, self-reported verification note.

**Status.** Open — tracked as task `H1` in `docs/TASKS.md`, which exists specifically to
close this gap with an actual browser walkthrough.

**Lesson.** "Verify against a running instance" needs to mean *the instance a real user
would use*, not the nearest layer that's cheap to check with curl. Live-LLM, browser-driven
verification costs real time and (for this project) real quota — which creates constant
pressure to settle for the cheaper check. Name the specific gap in the commit message when
this trade-off is made (as this commit did) so it becomes a trackable task rather than a
silently accepted risk.

---

## 9 — Prompt caching has never been confirmed working (open)

**What happened.** `PROJECT_PLAN.md` §1.4 calls cache hits "the single biggest cost lever"
and states as a Phase 1 exit criterion: *"a Phase 1 test asserts [`cache_read_input_tokens`]
is non-zero by the third turn of a round."* **That test was never written**, and every
live turn observed across this project's history — including turns 2 and 3 of the same
round, with an identical stable prefix — reported `cacheReadTokens: 0`.

**Root cause, as far as it's been diagnosed.** `GeminiAdapter` reads
`usageMetadata.cachedContentTokenCount()` from responses but **never creates a
cached-content object** — Gemini's explicit context caching requires the caller to create
one with a TTL and reference it, unlike Anthropic's inline breakpoints. This has not been
confirmed as *the* cause, only as the most likely one based on reading the adapter code;
it's possible the prompt sizes involved (~2,000–4,500 input tokens) also fall under an
implicit-cache minimum-token floor.

**Status.** Open — tracked as task `H2`. Five interviewer modules were built on top of a
prompt-assembly design whose entire justification (stable-prefix caching) has never been
confirmed to actually fire.

**Lesson.** An unwritten test from a foundational phase does not become less important as
more is built on top of the assumption it was meant to verify — it becomes *more*
expensive to eventually check, because by the time someone looks, the explanation has to
be reconstructed from behaviour observed months later rather than confirmed at the moment
the mechanism was built. If a plan names a specific test as an exit criterion, treat a
"done" phase that skipped it as incomplete, not as done-with-a-footnote.

---

## 10 — Cost ledger recorded `$0` for every call for a week

**What happened.** `config/providers.yaml` had `input: <set-me>` / `output: <set-me>` for
Google's pricing from the project's very first commit through 2026-08-29 — a full week of
active development, five interviewer modules, and every piece of live verification
described above. `CostLedger.estimateCost` silently returns `0.0` when pricing is absent.
So the `llm_call` table — described in `PROJECT_PLAN.md` as existing "from Phase 1, not as
an afterthought" specifically because cost is a first-class concern — recorded zero cost
for every single call made while validating that concern.

**Root cause.** `<set-me>` is a deliberate placeholder (correctly used elsewhere for
un-built providers, where a wrong guessed value would be worse than an obvious gap) — but
for a provider that **was** actively being used for real calls, the same placeholder
pattern meant a real, currently-relevant number was silently substituted with zero instead
of failing loudly or prompting a check.

**Fix.** Real per-million-token pricing filled in, dated and sourced in a YAML comment
(`d8ebb97`, task `T1`).

**Lesson.** A placeholder value that computes to a valid-looking result (here: zero cost,
which reads as "very cheap" rather than "unmeasured") is more dangerous than one that
throws. If a config placeholder is ever load-bearing for a feature under active
development, prefer failing loudly (or logging a visible warning) over a default that
looks like real data.

---

## 11 — Plan specified a state library the app never used

**What happened.** `PROJECT_PLAN.md` §6 stated: *"Zustand + TanStack Query, instead of
Redux — small app, two state kinds."* The actual frontend (`web/package.json`) has never
depended on either — it uses plain React state and hooks throughout, and this worked fine.

**Root cause.** The plan was written before any frontend code existed; the agent that
actually built the frontend made a different, reasonable call and never reconciled the
plan document with the choice actually made.

**Fix.** Corrected in the same documentation pass as incident 6, with the reasoning kept
rather than erased: *"One screen is live at a time and the socket owns most state. Revisit
only if state handling actually starts hurting"* (`fba203c`).

**Lesson.** Lower severity than the others here, but the same root cause as incident 6 at
smaller scale: an implementation decision that diverges from the plan is fine and often
correct, but it needs to be written back into the plan, or the plan becomes actively
misleading rather than merely incomplete.

---

## 12 — Live-testing exhausted the owner's Gemini quota

**What happened.** While verifying incident 4's fix, repeated live round tests hit
`429 RESOURCE_EXHAUSTED`. The actual quota, read directly from the API's own error
response: `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, **20 requests/day**, for
`gemini-3.7-flash` specifically.

**Root cause.** No agent working on this project had visibility into the quota limit
before hitting it — it was discovered reactively, mid-verification, by an AI agent's own
testing consuming a scarce resource the human owner also needs for their actual use of the
app.

**Mitigation, not a fix (the constraint is real and external).** Switching the
interviewer/evaluator binding to `gemini-3.6-flash` provides a **separate quota bucket**
per model, confirmed by directly comparing which model name appeared in the 429 message.
`AGENTS.md`'s Hard Constraints section now states the limit explicitly, and every task
card in `docs/TASKS.md` states its own live-call budget up front.

**Lesson.** For a project whose whole premise is "bring your own key, no added cost," an
AI agent's own verification activity is itself a cost the human owner pays for, out of a
resource with a hard daily ceiling. **State the budget before spending it, not after
hitting the wall.** This is now standard practice in this repo's task cards.

---

## 13 — `start.sh` killed a backend that had actually started, on a false readiness timeout

**What happened.** The owner ran the newly-added `start.sh` for the first time. It printed
`Backend did not report ready within 120s`, killed the backend it had just launched, and
exited. The backend's own log, read afterward, showed `Started InterviewLoopApplication`
at ~87 seconds — well inside the 120s budget — immediately followed, within the same
second, by a graceful-shutdown sequence.

**Root cause.** The readiness loop polled the redirected backend log file for the literal
string `Started InterviewLoopApplication`. `./mvnw spring-boot:run` forks the app into a
child JVM and pipes that child's output back through Maven's own console before it reaches
the shell redirect; that pipe can sit unflushed for tens of seconds, especially under
system load, before the line is actually written to the log file the script was grep-ping.
The script's own `SIGTERM` — sent because it had already concluded the backend failed — is
what forced the buffered output to flush, which is why the "Started" line and the shutdown
lines appear within the same timestamp in the log: the app had been up the whole time, the
log file just hadn't caught up.

**Fix.** Poll the actual HTTP endpoint (`curl http://localhost:8123/api/profiles`) instead
of grepping the log file. A listening socket answering real requests is a
buffering-independent readiness signal; log content from a forked child process is not.
Budget also raised 120s → 150s for margin. The log file is still written and still named
in the failure message, for when something has genuinely gone wrong.

**Lesson.** A log file redirected from a process that itself forks a child (as the
Spring Boot Maven plugin does) is not a reliable real-time signal — buffering can happen
at a hop the script never sees, and nothing forces a flush until the buffer fills or the
process exits. When a script's readiness check has a real running service to probe, probe
the service itself, not a log file about it.

---

## Caught before shipping (no incident — included for the discipline)

Not every near-miss needs a numbered incident, but it's worth recording ones that show the
verification habit working as intended, not just the cases where it failed.

- **Java deep-dive artifact kind, mis-stated in a documentation draft.** While writing the
  Aug 29 documentation update (incident 6's fix), a first draft stated the Java deep-dive
  module used `ArtifactKind.SCRATCH`. A grep against the actual source
  (`JavaDeepDiveInterviewerModule.java`) before committing showed it was actually `CODE`
  (Monaco, Java) — only CS fundamentals uses `SCRATCH`. Caught by the same "verify every
  claim against the code" pass the rest of incident 6 required, before anything was
  committed.

---

## Open risks — flagged, not yet an incident

Things that haven't caused a problem yet but share a shape with something on this list.

- **No CI.** `grow-dsa-question-bank` (PR #1) merged with no automated check beyond
  whatever the contributing agent ran locally. The 53 tests that would have caught a
  regression only run when someone remembers to run them.
- **No cost ceiling (D-7, tracked as `H4a`).** Now that incident 10 is fixed and cost
  numbers are real, nothing stops a session from running an arbitrarily large bill.
- **Anchored evaluator examples still absent (`H5`).** LLM-as-judge scoring is known to
  drift toward generosity without concrete calibration anchors; `PROJECT_PLAN.md` §3 named
  this as a required anti-inflation measure and it has never been built. No measurement
  exists showing whether or how much this project's evaluator scores are inflated.
- **Disconnect recovery gap, found while writing `H4b`.** `TurnOrchestrator.beginRound`'s
  idempotency fix (incident 3) correctly stops a reconnecting client from re-triggering the
  opening brief — but nothing re-sends the existing transcript on reconnect either.
  Refreshing the browser mid-round may show a blank screen despite the transcript being
  safely persisted. Not yet confirmed live; see `H1` and `H4b`.
