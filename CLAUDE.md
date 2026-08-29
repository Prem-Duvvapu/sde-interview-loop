# CLAUDE.md

Guidance for any AI coding agent working in this repository. Read this file completely
before writing code. It is written to be followed literally — if something here conflicts
with what seems reasonable, follow this file and raise the conflict with the owner.

## What this project is

An AI-powered mock-interview platform for rehearsing full interview loops for **SDE-2
backend** roles at Indian product companies. The app runs an interview round against an
LLM, scores it against a rubric, and tracks readiness over time.

**Single user, runs locally, no auth, no cloud deployment.** The owner is the only user.

---

## Current state (updated 2026-08-24)

**Working and verified end-to-end.** You can start a round in the browser, be interviewed
by an AI, and get a scored report at the end.

### Built

| Area | State |
|---|---|
| Backend foundations (domain, persistence, WS transport, session state machine) | done |
| LLM provider layer (Gemini + Claude adapters, runtime key/provider switching) | done |
| Turn orchestration (streaming, control tools, cache-stable prompt assembly) | done |
| **All 5 interviewer modules** — DSA, LLD, HLD, CS fundamentals, Java deep-dive | done |
| Round evaluation + report (rubric scoring, readiness band) | done |
| Web client (React + Vite, Monaco editor, diagram canvas, settings UI) | done |
| Test suite (38 tests, `./mvnw test` passes) | partial — content/module tests only |

### Not built yet

These are the real remaining gaps. Do not assume anything below exists.

- **Full-loop round chaining (Phase 6).** `SessionManager.createSession` creates all
  rounds for a `full_loop` session, but nothing chains them — no carry-over brief between
  rounds, no difficulty curve application, no automatic advance to the next round.
- **Readiness dashboard / trends (rest of Phase 7).** `session_report` and
  `readiness_snapshot` tables exist and are mapped, but **nothing writes to them**. There
  is no `progress` package. Per-round evaluation works; cross-round rollup does not.
- **Transcript replay UI.** Backend endpoints exist (`GET /api/rounds/{id}/transcript`,
  `/artifacts`); a `ReplayView.tsx` exists in the web client but is not verified working.
- **Voice mode (Phase 8).** Nothing built. See DM-1 in `PROJECT_PLAN.md`.
- **Cost ceilings, mid-round failure recovery, packaged startup (Phase 9).** Nothing built.
- **No integration tests.** No test starts Spring, hits an endpoint, or exercises
  `TurnOrchestrator`. The 38 existing tests cover question banks and module prompt text.
- **`mvn validate` profile-validation binding.** Planned in Phase 1, never added. Profile
  validation happens at application startup instead (fail-fast in `ProfileLoader`).

Read `PROJECT_PLAN.md` for architecture, data model, and the full phase roadmap. This
file covers what an agent needs to not break things.

---

## Hard constraints

These come from the owner and override reasonable-sounding defaults.

- **SDE-2 backend only.** The owner has ~2 years of experience. Never calibrate content,
  difficulty, or rubrics at SDE-3/SDE-4/senior level — including when a company's own
  ladder name sounds senior. LinkedIn "Sr. SWE", Walmart "SWE-4", and Apple "ICT4" were
  already retargeted to their SDE-2 equivalents; each carries a `LEVEL NOTE` in its
  profile recording the change.
- **No added cost.** The AI layer is multi-provider with bring-your-own keys. Never
  propose a paid service that requires a new subscription or vendor relationship. If a
  capability needs a key, it must be a key the owner already supplies.
- **16GB WSL2 laptop**, running alongside IntelliJ IDEA and Docker Desktop. RAM budget is
  a real design constraint — it is why there is no database container and no self-hosted
  speech stack.
- **The owner's Gemini free tier is 20 requests/day per model.** Live testing burns it
  fast. Prefer unit tests; when you must test live, use a different model
  (`gemini-3.6-flash`) rather than the default, and stop testing once you have one clean
  confirmation. Never loop retries against a quota-exhausted key.

## Decisions already made — do not re-litigate

Recorded as DM-1 … DM-7 in `PROJECT_PLAN.md` §5.1, with reasoning. Summarised so you do
not reopen them from a cold start:

| | |
|---|---|
| Voice | Web Speech API as zero-key default, behind a `VoiceProvider` SPI |
| HLD diagrams | Structured node/edge graph serialised to JSON — not images to a vision model |
| DSA code | No sandboxed runner; the interviewer must dry-run code against a concrete input |
| Java | 17 |
| LLM layer | Multi-provider SPI (Gemini, Claude, …), BYO keys |
| Storage | Embedded H2 in **file** mode, portable ANSI SQL, MySQL-ready |
| Default provider | **Google Gemini** (`gemini-3.7-flash`), interviewer and evaluator |

**Do not conflate the product's AI with the development tooling.** The application calls
**Gemini** by default at runtime. This repository is *developed* using AI coding agents.
These are unrelated, and "fixing" the provider config to match whichever agent is editing
the code would be wrong.

Three of these have consequences that are easy to undo by accident:

- **Interviewer floats, evaluator is pinned.** Any provider may conduct a round, but
  rubric scoring is pinned to one provider/model. Otherwise readiness trends measure
  provider drift rather than the owner's progress. Changing the pinned evaluator starts a
  new `comparability_epoch` — `SettingsController` enforces an explicit confirmation for
  exactly this reason.
- **Module prompts are tuned against Gemini.** Adding a provider later means re-checking
  those prompts against it, not just writing an adapter.
- **Portable SQL only.** No `jsonb`, no MySQL `JSON`, no vendor-specific types or
  functions. Open-shaped data goes in `TEXT` columns and is deserialised in the
  application layer. This is what keeps the MySQL move a config change.

`PROJECT_PLAN.md` §5.2 lists the decisions that are genuinely still open. Flag them; do
not silently decide them.

---

## Load-bearing invariants — break these and things fail silently

This is the most important section in this file. Each item below has already caused a
real bug in this repository.

### 1. Prompt assembly must stay stable-prefix-first

Requests are assembled strictly stable → volatile (`PROJECT_PLAN.md` §1.4):

```
tools → rubric → persona → problemBlock → [cache breakpoint] → transcript → phaseDirective → artifact
```

The `InterviewerModule` method split *mirrors this layout*. If you fold phase, timing, or
per-turn data into `persona()` or `problemBlock()`, prompt caching silently stops working:
requests still succeed, they just cost several times more. **The only visible symptom is
`cache_read_tokens` collapsing in the cost ledger.** Nothing fails loudly.

### 2. Tool schemas must be byte-stable across JVM runs

`Map.of` and `Map.copyOf` have *unspecified* iteration order, so identical schemas
serialize differently between runs and break the cached prefix. Every schema map in
`InterviewerTools` and `EvaluationTools` is `Collections.unmodifiableMap(new LinkedHashMap<>(...))`
for this reason. Keep it that way.

### 3. The model proposes, the backend disposes

`advance_phase` / `set_hint_level` / `end_round` are *requests* validated by
`SessionStateMachine`. A refused transition is **normal operation** — logged at INFO, not
an error. This is what stops the AI skipping complexity analysis because the candidate
sounded confident. Do not "fix" refusals by loosening the state machine.

### 4. Every interviewer turn must contain spoken words

Standard function-calling protocol trains models to pause after a tool call and wait for
a function *response*. This app's control tools never send one back — they are applied
silently server-side — so a model that calls one will often just stop talking, and the
candidate experiences literal silence.

Two defenses, both required:
- Every module persona says: never send a turn that is only tool calls.
- `TurnOrchestrator` detects a turn with tool calls and zero text, then retries **once**
  with tools withheld (`appendSilentTurnContinuation`).

If you add a module, copy the persona instruction verbatim. If you touch the orchestrator,
keep the retry.

### 5. Streaming must stay outside transactions

A slow provider must not hold a DB connection for the length of a model response. This is
why `RoundContextFactory` materialises a detached `RoundContext` inside a read-only
transaction, and the streaming loop runs outside one.

### 6. Lazy JPA associations must never reach Jackson

`open-in-view` is disabled. Every `@ManyToOne(fetch = LAZY)` back-reference to a parent
entity carries `@JsonIgnore` (or `@JsonBackReference`), because serializing one outside a
session throws `LazyInitializationException`. Session listing uses explicit fetch-join
queries (`findByIdWithRounds`). This has bitten twice — if you add an entity with a parent
link, annotate it.

### 7. Spring self-invocation

`@Transactional` on a method called via `this` bypasses the proxy and does nothing.
`TurnOrchestrator.pinRound` and `persistSignal` are deliberately `private` and un-annotated
with a comment explaining why. Do not "helpfully" re-add the annotation.

---

## Architecture map

Package-by-feature under `com.premd.interviewloop`:

| Package | Responsibility |
|---|---|
| `transport` | REST controllers, WS handler, frame codec |
| `session` | Session/round state machine, turn orchestration |
| `interviewer` | `InterviewerModule` SPI + control tools + 5 module implementations |
| `llm` | Provider SPI + adapters, key store, settings store, prompt assembly, cost ledger |
| `content` | Question banks (file-backed loaders), one subpackage per module |
| `evaluation` | Round scoring against the rubric |
| `transcript` | Turn + artifact persistence |
| `profile` | Company profile loading and schema validation |
| `domain` | JPA entities, enums, repositories |

**No `progress` package exists yet** — that is the unbuilt readiness-rollup work.

### The `InterviewerModule` SPI — frozen

`interviewer/InterviewerModule.java`. All five modules implement it. **Adding a capability
means adding a `default` method, never a new abstract one** — that would break all five
implementations at once.

Adding a new module is purely additive:
1. Question bank YAML under `question-bank/<module>/`
2. A loader under `content/<module>/` (copy an existing one; they are deliberately
   independent, not sharing a base class)
3. A `@Component` implementing `InterviewerModule` under `interviewer/<module>/`

`ModuleRegistry` picks it up automatically as a Spring bean. Nothing shared needs editing.

### Rubric dimension strings are a contract

Each module's `rubric()` names its dimensions. The model passes those exact strings to
`record_signal`, they are stored in `signal.rubric_dimension`, and `RoundEvaluator` scores
against them. **Renaming a dimension without bumping `rubricVersion()` silently corrupts
score comparability.** Tests pin these strings deliberately.

Current dimensions per module (see `PROJECT_PLAN.md` §3):
- **DSA** (`dsa-v1`): clarification, approach_optimality, correctness, complexity_analysis, edge_cases, communication, response_to_pushback
- **LLD** (`lld-v1`): requirement_extraction, class_model, solid_adherence, extensibility, concurrency_handling, code_quality
- **HLD** (`hld-v1`): requirements_scoping, capacity_estimation, component_design, trade_off_reasoning, bottleneck_identification, depth_on_probe
- **CSF** (`csf-v1`): breadth, depth_on_probe, precision_of_language, honesty_at_boundary
- **Java** (`java-v1`): api_fluency, internals_depth, concurrency_correctness, framework_trade_offs, scenario_diagnosis

### Module content shapes differ

Not every module is "one question per round":
- **DSA / LLD / HLD** — one question per round, from a bank of independent YAML files.
- **CS fundamentals** — a *pack* of topics, each with questions and depth probes. The
  whole pack renders once into the stable problem block; the model walks it adaptively
  inside a single `RAPID_FIRE` phase. Swapping questions mid-round from the backend would
  invalidate prompt caching every turn, which is why the walk is model-driven.
- **Java deep-dive** — one production-failure scenario per round, with a probe ladder in
  the scenario file.

### Work surfaces

`artifactKind()` + `artifactLabel()` tell the frontend and the model what the candidate is
editing:
- DSA, LLD, Java deep-dive → `CODE`, Monaco editor, Java
- HLD → `DIAGRAM`, a structured node/edge graph (`DiagramPane.tsx`) serialised as
  `{nodes, edges}` JSON. **Not a drawing tool** — the model reasons over component names.
- CS fundamentals → `SCRATCH`, plain scratchpad (markdown in the editor)

Note the frontend picks its pane from `moduleType` (`InterviewView.tsx`, `phases.ts`), not
from `artifactKind()` — so a new module needs both sides updated.

Bank sizes today: DSA 5, LLD 5, HLD 4, Java deep-dive 6 scenarios, CSF 2 topic packs.

---

## Running it

```bash
# Backend (port 8080) — needs GEMINI_API_KEY in the environment
./mvnw spring-boot:run

# Frontend (port 5173, proxies /api and /ws to 8080)
cd web && npm install && npm run dev
```

Open http://localhost:5173. First backend boot takes ~45-60s (Flyway + JPA bootstrap);
it is ready when the log says `Started InterviewLoopApplication`.

```bash
./mvnw test          # 38 tests, all passing
./mvnw -o compile    # -o (offline) is faster once deps are cached
```

**Verify a clean build, not an incremental one.** `./mvnw compile` can return success
having compiled nothing when sources are unchanged and stale classes exist — this has
already produced one false "it compiles" claim. Use `clean compile` when it matters.

The owner's key is an `export` in `~/.zshrc`, not a `.env` file. Either works —
`ProviderKeyStore` falls back to `System.getenv()`.

---

## Working agreement

- **Verify against a running instance, not just a compile.** Every feature commit in this
  repo's history was checked by actually starting the app and exercising the endpoint or
  round. Compilation proves nothing about whether a prompt works.
- **Report outcomes honestly.** If you could not verify something live, say so plainly in
  the commit message and to the owner. Do not describe a design as confirmed working when
  it only compiled. Several commits here say exactly what was and was not verified — match
  that standard.
- **Flag open decisions rather than assuming.** The owner has asked explicitly to be
  consulted on anything requiring their judgement.
- **Commit and push when the owner asks, with a message that explains the *why*.** Commit
  messages in this repo carry root-cause explanations, not just change lists. Match that.
- **Never commit API keys.** `GEMINI_API_KEY` is present in this environment. Check diffs
  before pushing. The GitHub repo is public.
- Phases 2–5 were designed as independent parallel work; they are now all complete, so
  that parallelism no longer applies to remaining work.

---

## Data files, not code

`company-profiles/*.yaml`, `question-bank/**/*.yaml`, and `config/providers.yaml` are
edited by hand and must never require a rebuild to change. This is a design requirement,
not a convenience.

- **Profiles** are validated against `company-profiles/_schema.json` at startup. Beyond
  the schema, four invariants are enforced: `id` equals the filename stem, `emphasis`
  weights sum to 1.0, round `ordinal`s are 1..n contiguous, and `total_wall_clock_min`
  equals the sum of all rounds' `duration_min` (including rounds disabled in v1).
- **Question banks** are validated by their loaders at startup, which fail fast: slug must
  equal the filename stem, difficulty must be one of `easy|medium|medium-hard|hard`, and
  `interviewer_notes` is required. A malformed file stops the app at boot rather than
  surfacing mid-interview.
- **`calibration.confidence`** is currently `seeded-unverified` on all 11 profiles. These
  are plausible defaults, not facts. Only the owner may raise a profile to
  `partially-verified` / `verified`, from first-hand interview data. Never present seeded
  numbers as established.
- **`quirks[].interviewer_behavior`** is injected verbatim into a round's system prompt.
  Edits there change AI behavior directly — treat it as prompt code, not prose.
- **`interviewer_notes`** in question files is injected into the prompt but is
  interviewer-only; the module instructs the model never to reveal it verbatim.
- **Provider model IDs** in `config/providers.yaml` are deliberately `<set-me>` for
  providers with no adapter built. Vendor model IDs change often; look them up rather than
  guessing, since a wrong ID fails at runtime.

### Question bank sourcing — a real constraint

LeetCode and similar problem statements are copyrighted; copying them into this repo is a
licensing problem even for personal use. **Every question in `question-bank/` is original
prose written for this project.** Standard algorithmic patterns are unavoidable and fine;
copied statement text is not. Curated lists (e.g. Striver's A2Z sheet) are useful as
*topic coverage checklists*, not as text to copy.

### Validating data files by hand

No Maven binding exists. Ad-hoc validation needs `pyyaml` + `jsonschema`. System Python is
externally managed (PEP 668), so use a scratch venv rather than `pip install --user`:

```bash
python3 -m venv /tmp/venv && /tmp/venv/bin/pip install -q pyyaml jsonschema
/tmp/venv/bin/python -c "
import json,glob,os,yaml,jsonschema
s=json.load(open('company-profiles/_schema.json'))
for f in sorted(glob.glob('company-profiles/*.yaml')):
    d=yaml.safe_load(open(f,encoding='utf-8'))
    for e in jsonschema.Draft202012Validator(s).iter_errors(d): print(f,e.message)
    assert d['id']==os.path.basename(f)[:-5]
    assert abs(sum(d['emphasis'].values())-1)<=0.01
    assert sum(r['duration_min'] for r in d['loop']['rounds'])==d['loop']['total_wall_clock_min']
print('ok')"
```

Simpler: just start the app. Both the profile loader and every question-bank loader
validate at boot and refuse to start on a bad file.

**YAML gotchas that have already bitten:** a value beginning with `"` is parsed as a
quoted scalar, so `label: "As Appropriate" final round` is a parse error — wrap the whole
value in single quotes. Dates must be quoted (`last_updated: "2026-08-22"`) or YAML parses
them into `date` objects and schema validation fails on the type.

---

## Stack

Java 17 + Spring Boot 3.4.3 (REST + raw WebSocket, not STOMP), React 19 + Vite 7 with
Monaco, embedded H2 file mode, Flyway, Maven. No Docker required — it was made optional
once the database container was dropped (see `PROJECT_PLAN.md` §1.1).

Every provider adapter uses that vendor's own official SDK — never an OpenAI-compatible
shim pointed at a different vendor, which silently loses provider-specific features.
Claude's is `com.anthropic:anthropic-java`; Gemini's is `com.google.genai:google-genai`.

Prompt-caching mechanisms differ sharply between providers (Gemini uses TTL-based cached
content objects with a token floor; Anthropic uses inline breakpoints). A cache strategy
tuned on one does not transfer — see `PROJECT_PLAN.md` §1.5.
