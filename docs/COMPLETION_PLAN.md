# Completion Plan — SDE Interview Loop

This is the handoff plan for completing the product after the current voice and general-practice
work. Treat `AGENTS.md` as the operating contract; this document orders the work, records the
remaining acceptance criteria, and prevents a new agent from mistaking a compile for validation.

## Current baseline

The application can already run a scored SDE-2 mock interview end to end. It has seven modules
(DSA, LLD, HLD, CS fundamentals, Java deep-dive, Behavioral, Resume), embedded H2 persistence,
raw WebSocket streaming, multi-provider LLM support, per-round evaluation/reports, dashboard,
replay, ordered company full loops, browser-native voice controls, and general single-module
practice with no company tag.

The two practice contexts have deliberately different guarantees:

| Context | What it means | Valid modes | Progress calculation |
|---|---|---|---|
| Company profile | Seeded company-specific loop, emphasis, and interviewer quirks | Single module or full loop | Company-weighted and gated; calibration remains unverified unless owner upgrades it |
| `general-practice` | Uncalibrated SDE-2 backend practice with no company claim | Single module only | Separate, evenly weighted module mean; never presented as company readiness |

## Non-negotiable invariants

Read `AGENTS.md` and `RCA.md` before changing any of these. They fail silently when broken.

1. Prompt assembly remains stable-prefix-first: `tools → rubric → persona → problemBlock → cache
   breakpoint → transcript → phaseDirective → artifact`. Do not put phase, time, IDs, or per-turn
   content into the stable methods.
2. Tool schema maps must retain `LinkedHashMap` iteration order. Never replace them with `Map.of`
   or `Map.copyOf`.
3. The model requests state transitions; `SessionStateMachine` decides. A rejected tool call is
   normal and must not be bypassed.
4. Every interviewer turn must contain spoken text. Preserve both the persona instruction and the
   one-shot tools-withheld retry in `TurnOrchestrator`.
5. Provider streaming remains outside a database transaction.
6. Lazy parent relations must not reach Jackson; use `@JsonIgnore` / explicit fetch joins.
7. Do not add transactional annotations to private self-invoked orchestrator helpers.
8. Resume content and API keys must never reach commits, logs, fixture data, or error messages.

## Completion sequence

Work in this order unless the owner explicitly reprioritises. Each card is independently
committable and should include its own tests and honest live verification note.

### C1 — Close the browser-flow validation gap

**Why first:** the product’s primary value is the browser flow; compilation and service tests do
not prove responsive UI, socket behavior, or transition feedback.

**Scope**

- Exercise Setup, general practice, company single-module, and company full-loop at desktop
  (1440px), laptop (1080px), and phone (375px) widths.
- Exercise first interviewer response, code/scratch/diagram surfaces, voice toggle, unsupported
  microphone state, completion, dashboard, replay, and reconnect affordance.
- Fix the known full-loop dead-air state: evaluation can take roughly a minute and the UI currently
  needs an explicit in-progress/next-round status while waiting.
- Reproduce and isolate the intermittent React “Maximum update depth exceeded” warning and burst
  of 404s noted in `docs/TASKS.md` H1. Do not suppress them without a cause.

**Acceptance criteria**

- One recorded/manual browser walkthrough covers the flows above without clipped controls,
  unreadable contrast, horizontal overflow, or unexplained console errors.
- Full-loop completion shows a clear waiting state until `next_round_ready`, an evaluation warning,
  or an actionable retry/return path.
- Findings are documented in `docs/TASKS.md`; a real defect adds an `RCA.md` entry.

### C2 — Add endpoint, WebSocket, and browser integration coverage

**Why:** the current Spring scripted-provider integration test covers orchestration but not the
actual REST/WS/browser contracts.

**Scope**

- Add Spring tests for `POST /api/sessions` with a company, omitted `companyProfileId` (general
  practice), and illegal general-practice full loop (400).
- Add scripted-provider WebSocket coverage for `start_round`, candidate turns, streamed deltas,
  `turn_complete`, silent-turn repair, and reconnect/restart behavior.
- Add a small browser smoke suite (Playwright or an already-available equivalent; do not add a
  paid service) that starts the local app, creates a general mock, and confirms mobile controls.
- Keep provider traffic fully mocked; never spend Gemini free-tier quota in CI.

**Acceptance criteria**

- Tests prove JSON field names, status codes, and WS frame ordering actually consumed by React.
- The test setup uses an isolated H2 database and the scripted provider.
- `./mvnw -o test`, frontend typecheck, and browser smoke test pass from a clean checkout.

### C3 — Make full-loop and disconnect recovery explicit

**Scope**

- Define and document resume semantics: which session/round state is restored after a browser
  reload, how an active WebSocket rebinds, and when an abandoned round can be resumed.
- Add a session list/resume entry point rather than relying on manually supplied replay IDs.
- Preserve pending candidate drafts/artifacts locally across a transient reconnect where safe.
- Show clear user-facing states for provider timeout, evaluator failure, socket reconnecting, and
  session completion. Completion must remain durable even if evaluation fails.

**Acceptance criteria**

- A killed/reloaded browser can resume an in-progress round without duplicate `start_round` or
  duplicate persisted turns.
- A failed evaluator never prevents a full loop from reaching its next enabled round.
- Recovery behavior is tested through REST/WS, not only unit tests.

### C4 — Cost ceilings and provider observability

**Scope**

- Add per-round and per-session budget configuration using existing provider pricing and the
  `CostLedger`; warn before the hard threshold and refuse/finish safely at the ceiling.
- Make the budget basis visible in the status UI: observed cost, threshold, and provider/model.
- Verify whether Gemini prompt caching is actually being created and reused. Current code records
  cache-read tokens if returned, but the cache-creation path is still unverified (H2).
- Add provider parity checks for Gemini, Claude, and OpenRouter: streaming, tool calls, silent
  tool-only repair, usage accounting, and error mapping.

**Acceptance criteria**

- Cost ceilings have deterministic tests with mocked usage events.
- One controlled live check per provider documents the actual model, date, cache result, and any
  quota limitation; never loop retries on a quota-exhausted key.
- Changing the evaluator still creates a new comparability epoch and old/new scores are never
  combined.

### C5 — Improve evaluator trustworthiness

**Scope**

- Build a small, synthetic, non-personal anchor set for every rubric dimension: clearly weak,
  borderline, and strong evidence with expected score ranges.
- Evaluate anchors through the pinned evaluator and compare drift after provider/model changes.
- Show the evaluator/model/epoch alongside reports and make “not comparable” language prominent
  when history crosses an epoch.
- Do not rename rubric dimensions without a rubric-version bump and migration strategy.

**Acceptance criteria**

- Anchor tests or an auditable calibration command detect material score inflation/deflation.
- Reports distinguish evidence-based feedback from low-confidence/no-signal cases.

### C6 — Content depth and owner-led company calibration

**Scope**

- Expand original-prose question banks with coverage matrices rather than copied LeetCode text.
- Fill difficulty gaps intentionally (for example, a bank fallback must remain an explicit warning,
  not hidden coverage).
- Add question-bank loader/module tests for every added content shape.
- Have the owner supply first-hand interview evidence before changing any profile from
  `seeded-unverified` to `partially-verified` or `verified`.

**Acceptance criteria**

- New content validates at boot, has interviewer notes, and uses original wording.
- Profile changes preserve schema, ordinal, duration, and emphasis invariants.
- Calibration claims cite owner evidence in the profile notes; agents must not manufacture it.

### C7 — Complete voice mode safely

**Scope**

- Browser-test the existing Web Speech controls on Chrome/Edge and document Safari/Firefox
  behavior. Recognition may be unavailable; typed fallback must remain excellent.
- Add a compact settings explanation that browser speech recognition can be cloud-backed (notably
  Chrome) and does not send audio through this application backend.
- Verify speech cancellation on exit, next-round transitions, and text streaming boundaries.
- Only design a key-based `VoiceProvider` upgrade after the owner chooses an already-owned key;
  no new vendor/subscription or local speech stack.

**Acceptance criteria**

- TTS only reads completed interviewer turns, never partial deltas or candidate text.
- Dictation always remains editable and is never auto-submitted.
- Unsupported browsers have a clear, non-blocking disabled state.

### C8 — Package and release the local product

**Scope**

- Decide whether “packaged startup” means a Maven-served production frontend, the existing
  `start.sh`, Docker Compose, or all three. This is an owner decision if it changes the preferred
  workflow.
- If serving the built frontend from Spring, add a reproducible Maven/frontend build step and
  ensure `/api` and `/ws` keep their behavior. Keep dev mode fast and documented.
- Review `start.sh`, `start-docker.sh`, ports, shutdown behavior, data-path backups, and fresh
  machine setup.
- Add a release checklist: clean install, startup readiness, one local mock, backup/restore H2,
  and no secret leakage.

**Acceptance criteria**

- A fresh local checkout reaches a usable UI with one documented command and no manual path edits.
- The launcher terminates both backend and frontend cleanly.
- README commands and actual ports match.

## Deferred/open owner decisions

Do not silently choose these:

1. The exact break/pause policy within a live interview and whether pauses alter planned timing.
2. The intended packaged distribution form (single JAR, scripts, Docker, or a supported subset).
3. Any optional key-backed voice provider after browser-native speech is validated.
4. Any claim that a company profile is calibrated or verified.
5. Any change to the pinned evaluator/provider/model policy that affects comparability epochs.

## Standard verification and commit checklist

Before each handoff commit:

1. Run `git status`, inspect the targeted diff, and keep unrelated user changes untouched.
2. Run `./mvnw -o clean compile` when backend sources changed, then `./mvnw -o test`.
3. Run `cd web && npx tsc --noEmit -p tsconfig.app.json`; run the production build for bundling
   changes.
4. Start the running app and exercise the changed flow once. Prefer scripted tests; do not consume
   live LLM quota without a focused reason.
5. State exactly what was live-tested and what was not. A compile is not a browser verification.
6. Add an `RCA.md` entry for a real defect or invariant violation; update this plan/tasks if scope
   changes.
7. Check `git diff --check`, never stage API keys/resumes/`data`/`target`/`node_modules`, then use
   a commit message explaining why the change exists. Fetch/rebase before pushing if origin moved.

## Suggested milestone commits

1. `fix: make full-loop transitions observable and recoverable`
2. `test: cover REST and WebSocket interview contracts`
3. `feat: enforce interview cost ceilings without losing session state`
4. `test: anchor evaluator scoring across rubric dimensions`
5. `feat: make local startup reproducible from a fresh checkout`
6. `docs: record browser/provider verification and calibration evidence`

Keep changes smaller if a card naturally splits; do not wait for the entire plan before producing
a reviewable, tested commit.
