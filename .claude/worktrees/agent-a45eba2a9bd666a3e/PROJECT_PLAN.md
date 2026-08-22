# sde-interview-loop — Project Plan

**Status:** awaiting approval. No application code has been written.
**Target:** SDE-2 backend, ~2 years experience, India product companies.
**Last updated:** 2026-08-22

---

## 0. What already exists

Two directories of *data*, not code — safe to land before approval, and editable
without touching any source file:

- **`company-profiles/`** — 11 profiles, a JSON Schema, and a README. All validate
  clean. All carry `calibration.confidence: seeded-unverified`, so start correcting them
  today; nothing below blocks that.
- **`config/providers.yaml`** — the multi-provider LLM and voice configuration from
  DM-5/DM-1. Claude is filled in and enabled; OpenAI, Gemini, and DeepSeek are stubbed
  with `<set-me>` model IDs and flip on when their key appears. Model IDs are left blank
  rather than guessed, since vendor IDs change often and a wrong one fails at runtime.

**Level calibration.** Everything is pitched at the SDE-2 bar. Three entries in the
seed table named levels above SDE-2 and have been retargeted, with a `LEVEL NOTE` in
each file recording the change:

| Company | Seed table said | Retargeted to |
|---|---|---|
| LinkedIn | Sr. SWE | SWE (non-senior) — senior-only architecture round disabled |
| Walmart Global Tech | SWE-4 / Sr | SWE-3 |
| Apple | ICT3 / ICT4 | ICT3 |

Google L4 is the only profile that keeps a `hard` difficulty target (its DSA rounds);
every other loop peaks at `medium-hard`. Readiness bar is `hire` everywhere — no
profile demands `strong-hire`, which would be a senior-loop expectation.

---

## 1. Architecture

### 1.1 Shape

A single-user, locally-hosted, three-container system. No auth, no tenancy, no cloud
deployment. The design constraint that matters most is not scale — it is **your laptop's
16GB** and **your LLM API bill**.

```
┌─────────────────────────────────────────────────────────────────┐
│  Browser (React 19 + Vite)                                      │
│  chat pane │ Monaco editor │ diagram surface │ report/dashboard │
└────────────┬──────────────────────────────┬─────────────────────┘
             │ WebSocket (interview turns)  │ REST (setup, history, reports)
┌────────────▼──────────────────────────────▼─────────────────────┐
│  Spring Boot 3.x                                                │
│                                                                 │
│  transport ──► session (state machine) ──► interviewer modules  │
│                     │                          │                │
│                     │                          ▼                │
│                     │                    llm (provider SPI)     │
│                     │                     · adapters: claude,   │
│                     │                       openai, gemini, …   │
│                     │                     · prompt assembly     │
│                     │                     · streaming           │
│                     │                     · cost ledger         │
│                     ▼                                           │
│              transcript · evaluation · progress · profile       │
└────────────┬────────────────────────────────────────────────────┘
             │ JDBC (portable SQL — no vendor-specific types)
      ┌──────▼───────┐
      │  H2 (file)   │  sessions · transcripts · artifacts · signals · reports
      └──────────────┘  swappable for MySQL/Postgres via datasource config
```

Two processes: `app` and `web` (Vite dev server in development; static files served by
the app in the packaged build). No database container — H2 runs embedded in the JVM,
writing to a local file. Budget roughly 1.5GB total, which leaves real headroom alongside
IntelliJ.

**Docker is now optional (A-3).** `docker` is not available in this WSL2 distro, and once
DM-6 removed the database container the only things left to containerise were a Spring
Boot jar and a Vite dev server — both of which run natively with `mvn spring-boot:run` and
`npm run dev`. Containerising them buys nothing for a single-user local app and costs RAM
on a 16GB box. Compose remains a nice-to-have for packaging, not a prerequisite; enable
Docker Desktop's WSL integration for this distro only if you want it.

### 1.2 The core idea: the interviewer is a state machine, not a chatbot

A free-form chat with a system prompt will drift. It forgets to ask about complexity,
lets you off the hook, and runs long. Each round is therefore an explicit phase machine,
and the model is told which phase it is in:

```
DSA:   BRIEFING → CLARIFYING → APPROACH → CODING → COMPLEXITY → EDGE_CASES → FOLLOW_UP → WRAP
LLD:   BRIEFING → REQUIREMENTS → CLASS_MODEL → DEEP_DIVE → EXTENSION → WRAP
HLD:   BRIEFING → REQUIREMENTS → ESTIMATION → HIGH_LEVEL → DEEP_DIVE → BOTTLENECK → WRAP
CSF:   rapid-fire; adaptive topic walk, depth tracked per topic
JAVA:  SCENARIO → PROBE → DEPTH_LADDER → TRADE_OFF → WRAP
```

Phase transitions are driven by the model but *enforced* by the backend: the interviewer
returns a structured control block alongside its prose, and the backend decides whether
to honour it. This is what stops the AI from skipping complexity analysis because you
sounded confident.

### 1.3 Turn loop

Per candidate turn:

1. Client sends a turn over WebSocket: chat text, plus the current editor buffer or
   diagram graph if they changed since the last turn.
2. Backend assembles the request in **cache-stable order** (§1.4).
3. Claude call, streamed token-by-token back over the same socket.
4. Model returns prose **and** tool calls: `record_signal`, `advance_phase`,
   `set_hint_level`, `end_round`.
5. Backend persists the turn, applies or rejects the control calls, updates round state.

Structured control via tool use — rather than parsing prose — is what makes the
evaluation incremental. Signals accrue during the round, so the end-of-round report is a
cheap summarisation of already-collected evidence rather than a second full pass over
the transcript.

### 1.4 Prompt assembly and caching

Cache hits are the single biggest cost lever, and caching is prefix-based: any byte
change invalidates everything after it. So the request is assembled strictly
stable-to-volatile:

```
[ tools           ]  fixed per module                    ─┐
[ system: rubric  ]  versioned rubric for the module      │ cached
[ system: persona ]  module persona + company quirks      │ prefix
[ system: problem ]  the question statement               ─┘ ← cache breakpoint
[ messages        ]  rolling transcript
[ latest artifact ]  editor buffer / diagram graph        ← volatile, always last
```

Two rules the implementation must not break: **no timestamps or per-request IDs in the
cached prefix**, and the code artifact goes *after* the last breakpoint, never inside the
system block. Verification is not optional — `usage.cache_read_input_tokens` gets logged
on every call, and a Phase 1 test asserts it is non-zero by the third turn of a round.

For long full-loop sessions, each round gets a **fresh context** seeded with a compact
carry-over brief (a few hundred tokens: what was covered, notable strengths and gaps) —
rather than accumulating a 4-hour transcript. This is both cheaper and closer to reality;
real interviewers do not have your previous round's transcript.

### 1.5 The AI layer: pluggable providers

Per your decision, the interviewer brain is **not hardwired to one vendor**. The `llm`
package exposes a provider SPI and ships adapters for Claude, OpenAI, Gemini, DeepSeek,
and anything else added later. You supply the keys; the app adds no service of its own
and imposes no cost beyond your own usage.

```java
interface LlmProvider {
    String id();                            // "anthropic" | "openai" | "google" | "deepseek"
    Capabilities capabilities();            // streaming, toolUse, promptCaching, vision
    Flux<LlmEvent> stream(LlmRequest req);  // normalised events: TEXT, TOOL_CALL, USAGE, DONE
}
```

- **Default provider is Google Gemini** (`gemini-3.7-flash`), for both interviewer and
  evaluator. Claude (`claude-opus-5`, via `com.anthropic:anthropic-java`) stays
  configured as an alternative interviewer. Note the separation: this is what the
  *product* calls at runtime. Development of this repo is done with Claude Code, which is
  tooling and has nothing to do with the app's provider config.
- **Adapters use each vendor's official SDK** — never an OpenAI-compatible shim pointed
  at a different vendor, which silently loses provider-specific features. Gemini's Java
  SDK coordinates should be confirmed when that adapter is built rather than assumed.
- **Keys are local and server-side only.** Read from `.env` / local config, never
  committed, never sent to the browser. A provider with no key present simply does not
  appear in the UI — no errors, no configuration ceremony.
- **Capability floor: streaming + tool use.** The phase machine (§1.2) is driven by
  structured control calls, so a provider without function calling gets a JSON-mode shim
  in its adapter and is flagged as degraded in the UI. This is enforced at the SPI
  boundary, so a weak provider cannot quietly break the interview structure.
- **Caching survives the abstraction, but the mechanisms genuinely differ.** The
  stable-prefix ordering in §1.4 pays off everywhere, yet Gemini's explicit context
  caching (TTL-based cached-content objects with a minimum token floor) is not shaped
  like Anthropic's inline breakpoints, and neither resembles an automatic prefix cache.
  Each adapter owns its own mechanism; the prompt *assembly order* is what stays
  identical. Do not assume a cache strategy tuned on one provider transfers.
- **Model IDs and prices live in `config/providers.yaml`,** not in code. Both change
  often; neither should require a rebuild.

**The comparability problem — the one real cost of going multi-provider.** Two providers
grading the same round will not produce the same numbers. If the brain changes between
sessions, your readiness trend measures *provider drift* as much as your own progress,
which quietly destroys the thing the dashboard exists to show.

The design response: **the interviewer floats, the evaluator is pinned.** Run any
provider you like as the interviewer — that variety is genuinely useful, since different
models push back differently. But the evaluator that produces rubric scores is pinned in
config to one provider/model, and `round_evaluation` records which one. Changing the
pinned evaluator starts a new *comparability epoch*, marked on the dashboard so a step
change in your scores is never mistaken for a step change in your ability.

### 1.6 Module boundaries

Package-by-feature under `com.premd.interviewloop`:

| Package | Responsibility | Depends on |
|---|---|---|
| `transport` | WS + REST controllers, frame codec | session |
| `session` | Round/session state machine, full-loop chaining | interviewer, profile, transcript |
| `interviewer` | `InterviewerModule` SPI + 5 implementations | llm, content, evaluation |
| `llm` | Provider SPI + adapters, prompt assembly, cache policy, cost ledger | — |
| `content` | Question bank, prompt templates, rubrics (file-backed) | — |
| `evaluation` | Signal aggregation, rubric scoring, report generation | llm |
| `progress` | Readiness rollup, trends, company readiness | evaluation, profile |
| `transcript` | Turn + artifact persistence, replay | — |
| `profile` | Profile loading, schema validation, hot reload | — |

The important boundary is `interviewer`: every module implements one SPI
(`buildSystemPrompt`, `phases`, `handleTurn`, `finalize`), so adding a module is additive
and full-loop mode does not need to know which module it is running.

---

## 2. Data model

**Embedded H2 in file mode, Flyway-migrated, written in portable SQL.**

Two rules keep the MySQL/Postgres door open at near-zero cost:

- **No vendor-specific column types.** Open-shaped data (rubric scores, diagram graphs)
  is stored as `TEXT` holding JSON and mapped in the application layer, not as Postgres
  `jsonb` or MySQL `JSON`. You give up in-database JSON querying — irrelevant here, since
  every one of these reads is "fetch the row, deserialise it".
- **Flyway migrations use ANSI SQL only.** Portable migrations mean the move to MySQL is
  a datasource URL, a dialect property, and a fresh migration run.

**One amendment to your instruction.** Use H2 in *file* mode (`jdbc:h2:file:./data/...`),
not `jdbc:h2:mem:`. Pure in-memory drops everything on restart, which would take the
progress dashboard, readiness trends, and transcript replay — three of your stated
requirements — with it. File mode keeps the zero-ops benefit you're after (no container,
no daemon, no credentials) while the data actually survives. Say the word if you'd rather
have `mem:` anyway for early phases.

```
interview_session
  id, mode(single_module|full_loop), company_profile_id, profile_content_hash,
  started_at, ended_at, status(active|completed|abandoned)

session_round
  id, session_id, ordinal, module_type, phase, status,
  interviewer_provider, interviewer_model,
  question_slug, question_content_hash, difficulty_target,
  planned_duration_sec, actual_duration_sec, started_at, ended_at

transcript_turn                 -- append-only, ordered; the replay source of truth
  id, round_id, ordinal, role(candidate|interviewer|system),
  content, content_type, created_at, latency_ms

artifact_snapshot               -- append-only; enables scrubbable replay
  id, round_id, turn_id, kind(code|class_model|diagram|scratch),
  language, payload(TEXT, JSON for diagram graphs), created_at

signal                          -- emitted DURING the round via tool use
  id, round_id, turn_id, rubric_dimension, score(1-5), confidence, evidence

round_evaluation
  id, round_id, rubric_version, evaluator_provider, evaluator_model,
  comparability_epoch, scores(TEXT/JSON), strengths, gaps,
  readiness_band, narrative_md

session_report
  id, session_id, overall_band, per_module(TEXT/JSON), narrative_md

readiness_snapshot              -- denormalised for the dashboard
  id, taken_at, module_type, company_profile_id, score, sample_size

llm_call                        -- cost + latency observability, per provider
  id, round_id, turn_id, provider, model, role(interviewer|evaluator|summariser),
  input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
  cost_estimate_usd, latency_ms
```

Two deliberate choices:

- **Questions and rubrics live in files, not tables.** `question-bank/` and `rubrics/`
  are version-controlled YAML/Markdown. Rounds store `question_slug` +
  `question_content_hash`, so a report always records which version of a question you
  actually faced, even after you edit it. Same pattern as `company-profiles/`.
- **`transcript_turn` and `artifact_snapshot` are append-only.** Replay works by
  re-playing events, and an edited transcript is worthless as an evaluation record.
- **Repository access via Spring Data JPA**, so the storage swap stays a configuration
  change rather than a rewrite.

`llm_call` exists from Phase 1, not as an afterthought. Cost is a first-class concern in
this project and it cannot be managed if it is not measured per round.

---

## 3. Rubrics and readiness

**Scale:** 1–5 per dimension. **Bands:** `no-hire (<2.5) · lean-hire (2.5–3.4) ·
hire (3.5–4.2) · strong-hire (>4.2)`.

Dimensions per module (versioned in `rubrics/`, so a scoring change does not silently
invalidate old sessions):

- **DSA** — clarification, approach & optimality, correctness, complexity analysis, edge
  cases, communication, response to pushback
- **LLD** — requirement extraction, class model, SOLID adherence, extensibility,
  concurrency handling, code quality
- **HLD** — requirements & scoping, capacity estimation, component design, trade-off
  reasoning, bottleneck identification, depth on probe
- **CS fundamentals** — breadth, depth-on-probe, precision of language, honesty at the
  knowledge boundary
- **Java deep-dive** — API fluency, internals depth, concurrency correctness, framework
  trade-off reasoning, scenario diagnosis

**Company readiness** = `emphasis`-weighted mean of module scores, recency-weighted
(older sessions decay), gated by `readiness.module_minimums` — one module below its floor
blocks "ready" regardless of the weighted average — and reported with a confidence level
driven by `min_sessions_for_confidence`.

**The honest limitation:** this measures you against a *model's* idea of each company's
bar, seeded by profiles I generated. It is a rehearsal instrument and a drift detector,
not a calibrated predictor. Phase 7 includes anti-inflation measures (fixed rubric text,
anchored few-shot examples at each band, evaluator separated from interviewer). None of
that makes the absolute numbers trustworthy — the *trend* is the trustworthy part.

---

## 4. Phased roadmap

Each phase: its own branch and git worktree, a scoped plan, a review checkpoint before
merge. No large unreviewed diffs.

### Phase 0 — Spike (throwaway)
**Goal: answer the three questions that could invalidate the whole design.**
One hardcoded DSA question, Monaco, WebSocket streaming, Claude, no database.

Exit criteria — a written measurement, not a demo:
1. **Latency:** p50/p95 time-to-first-token and full-turn latency. If a turn takes 8
   seconds, the interaction model needs rethinking before anything is built on it.
2. **Cost:** measured USD for one complete 45-minute DSA round, with and without prompt
   caching.
3. **Quality:** does a phase-driven, tool-use-controlled interviewer actually push back
   usefully, or does it flatter? Judged by you, on a real problem.
4. **Provider parity:** run the same round through two providers behind the SPI. This
   proves the abstraction is real before Phase 1 builds on it, and gives an early read on
   how far apart their judgement actually is.

**This is throwaway code.** Its output is a decision memo that feeds Phases 1–2.

### Phase 1 — Foundations
Docker Compose (app + web), portable Flyway schema on embedded H2, domain model, profile loader with
fail-fast schema validation, session state machine, transcript persistence, WS transport,
and the `llm` package: provider SPI, the Gemini adapter (default) plus the Claude
adapter, key discovery,
capability gating, cache-stable prompt assembly, and the per-provider cost ledger.
*Exit:* an empty session can be started, turns persisted and replayed; a cache-hit test
passes; the same round runs end-to-end on two providers; `mvn -q validate` runs profile
validation in CI.

Additional adapters (OpenAI, DeepSeek, others) are additive against a frozen SPI and can
land any time after this phase — they are not gating work.

### Phase 2 — DSA module *(parallel with 3)*
Question bank, phase machine, Monaco integration with debounced artifact snapshots,
complexity/edge-case probing, adaptive follow-ups, DSA rubric.

### Phase 3 — LLD module *(parallel with 2)*
Design prompts, class-model surface, SOLID/extensibility/concurrency probes, LLD rubric.
Bar set high deliberately — your 45-project portfolio means a soft LLD interviewer is
useless to you.

### Phase 4 — HLD module *(parallel with 5)*
Diagram surface, capacity-estimation phase, bottleneck probing, HLD rubric.
Diagram surface is a structured node/edge graph per DM-2 — the model reasons over the
serialised graph, so it can probe named components directly.

### Phase 5 — CS fundamentals + Java deep-dive *(parallel with 4)*
Adaptive rapid-fire walk with per-topic depth tracking; scenario-based Java/Spring
ladder. Two modules, one phase — they share the depth-ladder machinery.

### Phase 6 — Company-calibrated full-loop
Round chaining from `loop.rounds`, difficulty-curve application, quirk injection,
carry-over briefs between rounds, break handling, `enabled_in_v1: false` rounds announced
and skipped.

### Phase 7 — Evaluation, reports, dashboard, replay
Signal aggregation, rubric scoring with anti-inflation anchoring, session reports,
readiness rollups, trend dashboard, transcript replay with artifact scrubbing.

### Phase 8 — Voice mode
Mirrors the LLM layer: a `VoiceProvider` SPI with a zero-key browser-native default and
optional key-based upgrades. See **DM-1** for the decision and reasoning.

### Phase 9 — Polish and hardening
Cost controls (per-session ceiling with a warning threshold), graceful API failure
handling mid-round, session recovery after a disconnect, packaged single-command startup.

### Parallelisation

```
Phase 0 ─► Phase 1 ─┬─► Phase 2 (DSA) ──┬─► Phase 6 ─► Phase 7 ─► Phase 9
                    ├─► Phase 3 (LLD) ──┤
                    ├─► Phase 4 (HLD) ──┤
                    └─► Phase 5 (CSF+Java)
                                         Phase 8 (voice) — independent throughout
```

Phases 2–5 are genuinely independent: separate packages, separate question banks,
separate rubrics, one shared SPI frozen at the end of Phase 1. They are good candidates
for parallel worktrees — but only if the Phase 1 SPI is genuinely stable, since four
agents renegotiating an interface mid-flight is worse than doing the work serially. I'd
suggest starting with 2 and 3 in parallel and judging from there.

---

## 5. Decisions

### 5.1 Made

| | Decision |
|---|---|
| **DM-1 Voice** | Browser-native default, pluggable upgrades — reasoning below |
| **DM-2 Diagrams** | Structured graph (nodes/edges as JSON) |
| **DM-3 Code execution** | No runner; interviewer must dry-run your code against a concrete input |
| **DM-4 Java version** | Java 17 |
| **DM-5 LLM providers** | Multi-provider SPI, bring-your-own keys, pinned evaluator |
| **DM-6 Storage** | Embedded H2 in **file** mode, portable SQL, MySQL-ready |
| **DM-7 Default provider** | **Google Gemini** (`gemini-3.7-flash`) for interviewer and evaluator; Claude available as an alternative |

**DM-1 — voice, decided as you asked.** Your constraints were "no extra cost" and
"bring my own key", which rules out the managed-vendor option I would otherwise have
leaned toward, and the 16GB budget already ruled out self-hosted Whisper. So: **Web
Speech API as the zero-key default**, behind a `VoiceProvider` SPI shaped exactly like
the LLM one. It costs nothing, needs no key, uses no RAM, and works on day one. Its
weaknesses are real — robotic TTS, no barge-in, and it mangles technical vocabulary — so
the SPI exists precisely so that a key you have *already supplied* can upgrade it
(OpenAI's speech endpoints, Gemini Live) without adding a vendor or a bill you didn't
already have. Start free, upgrade with a key you own, never a new subscription.

One thing to know rather than discover later: Chrome's Web Speech implementation sends
audio to Google's servers. It is "browser-native", not local.

**DM-7 — default provider is Gemini.** Set now, before any sessions exist, which is the
cheapest possible moment: the pinned evaluator defines the comparability epoch for every
readiness score that follows, and switching it after fifty sessions makes that history
hard to read. `gemini-3.7-flash` is Google's strongest current workhorse for coding and
agentic work, but it is a **preview** model as of 2026-08-22 — confirm your key has
access, and expect preview IDs to move. `gemini-3.6-flash` is the configured fallback.

Keep two things distinct: **the product calls Gemini; this repo is developed with Claude
Code.** They are unrelated, and conflating them would be an easy way to "fix" the config
in the wrong direction later.

This resolves what was D-8. It leaves one real consequence — the module prompts in
Phases 2–5 will be tuned against Gemini's behaviour, so adding a provider later means
re-checking the prompts against it, not just writing an adapter.

**DM-6 — storage.** Your call to skip Postgres for now is a good one: it removes a
container, ~512MB of RAM, and all connection/credential setup from a single-user local
app. The only change I made is file mode over `mem:` (reasoning in §2). The portability
rules there are what make "move to MySQL later" cheap rather than a migration project —
they cost nothing to follow now and a lot to retrofit.

**DM-3 note.** Starting without a sandbox is the right call, but be aware of what it
costs: an interviewer that cannot execute your code will occasionally accept something
that does not compile. The forced dry-run catches a good fraction. If it turns out to
miss too much in practice, a Judge0/Piston container remains a clean Phase 9 addition —
nothing in the design forecloses it.

### 5.2 Still open

**D-5 through D-8 — none block the start of work.**

### D-5 — Question bank sourcing *(Phase 2, before bulk authoring)*
LeetCode problem statements are copyrighted; scraping a few hundred into a local bank is
a real licensing problem even for personal use. Cleanest path is original or paraphrased
prompts (Claude can generate them against a difficulty/tag spec) plus genuinely public
sources. Slightly more work up front, no legal ambiguity, and paraphrased prompts have a
side benefit: you cannot pattern-match a memorised problem.

### D-6 — Behavioral rounds *(Phase 6)*
Google's Googleyness, Atlassian's Values, and Microsoft's AA round are in the profiles
but marked `enabled_in_v1: false` — behavioral was not in your module list. Atlassian's
Values round in particular can sink an otherwise strong loop. Options: leave skipped,
add a thin behavioral module in Phase 6, or make it a Phase 10.

### D-7 — Per-session cost ceiling *(Phase 9, needs Phase 0 data)*
A full 4-hour loop is many hundreds of streamed turns. Once Phase 0 gives a real
$/round number, decide whether the app enforces a hard ceiling, warns, or just reports.
Now per-provider, since the same loop will cost very different amounts on different
backends.

### D-8 — Which providers ship in Phase 1? *(low stakes, easily changed)*
Gemini first, since it is the default. Claude second, which is what proves the SPI is a
real abstraction rather than one vendor with extra indirection. OpenAI and DeepSeek are
additive against a frozen SPI and can land whenever. Say if you would rather reverse the
order.

---

## 6. Stack decisions

Your defaults accepted: Java 17, Spring Boot 3.x, React 19 + Vite, Docker.
The one amendment is the AI layer — Claude is now the default provider behind a
multi-provider SPI rather than the only one (DM-5). Other additions, none controversial:

| Choice | Instead of | Why |
|---|---|---|
| Raw WebSocket + JSON frames | STOMP | One client, one message shape; STOMP's broker semantics buy nothing here |
| Flyway (ANSI SQL) | Hibernate `ddl-auto` | Transcripts are records; schema changes must be reviewable — and portable SQL keeps MySQL one config change away |
| H2 file mode | H2 `mem:` / Postgres container | Zero ops and ~512MB saved vs. a DB container, but data survives restarts (DM-6) |
| Zustand + TanStack Query | Redux | Small app, two state kinds (session socket state, server cache) |
| Monaco | CodeMirror | You asked for it, and it is the IntelliJ-adjacent muscle memory |

---

## 7. What I need from you

1. **Approve or amend this plan** — nothing gets coded until then.
2. **Provider API keys** — whichever you have. One is enough to start Phase 0; a second
   is what proves the multi-provider SPI is real rather than theoretical. Keys go in a
   local `.env`, are read server-side only, and are never committed.
3. **Correct the profiles.** They are all `seeded-unverified`. The highest-value thing
   you can do is replace guesses with real round structures as you learn them.

D-5 through D-8 stay open and none of them block Phase 0.
