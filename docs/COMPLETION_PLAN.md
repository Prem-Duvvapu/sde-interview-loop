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

- [x] Exercise Setup and general practice at desktop (1440px), laptop (1080px), and phone
  (375px) widths. Done 2026-09-14 (docs/TASKS.md H1 findings) — clean at all three, no
  console errors. **Still open:** company single-module and company full-loop at these
  widths (reachable without a key; not yet repeated since the profile/loop UI last changed).
- [ ] Exercise first interviewer response, code/scratch/diagram surfaces, completion, dashboard,
  replay, and reconnect affordance. **Still open** — all need a real LLM call to progress past
  the opening screen (session creation and the opening brief template render with no key, per
  the 2026-09-14 findings, but `beginRound` itself resolves a provider and fails fast without
  one — see docs/TASKS.md H1).
- [x] Voice toggle and microphone state. Done 2026-09-14: both the TTS toggle and the mic
  button work with no console errors in Chromium. **Partially open:** genuinely
  unsupported-browser behavior (Safari/Firefox showing the mic button disabled) is
  unverified — no such browser is available in this environment.
- [ ] Fix the known full-loop dead-air state: evaluation can take roughly a minute and the UI
  currently needs an explicit in-progress/next-round status while waiting. **Still open** —
  needs a live full-loop round to confirm any fix against the real timing.
- [x] Reproduce and isolate the intermittent React "Maximum update depth exceeded" warning and
  burst of 404s noted in `docs/TASKS.md` H1. **Partially resolved:** found and fixed an
  unrelated, always-reproducible favicon 404 (no favicon was ever declared) — real, but
  deterministic and present on every load, so almost certainly not the same bug as the
  intermittent one tied to full-loop interview-view entry. That original mystery is still
  open and still needs a live full-loop repro; see docs/TASKS.md H1 for why they're
  believed distinct.

**Acceptance criteria**

- One recorded/manual browser walkthrough covers the flows above without clipped controls,
  unreadable contrast, horizontal overflow, or unexplained console errors. Partial — see
  scope checklist above; the reachable-without-a-key half is done, the rest needs a key.
- Full-loop completion shows a clear waiting state until `next_round_ready`, an evaluation warning,
  or an actionable retry/return path. Still open.
- Findings are documented in `docs/TASKS.md`; a real defect adds an `RCA.md` entry. Done —
  the favicon fix is routine enough not to need its own RCA entry per RCA.md's own bar
  ("not every bug fix needs an entry — routine fixes with an obvious cause don't").

### C2 — Add endpoint, WebSocket, and browser integration coverage

**Why:** the current Spring scripted-provider integration test covers orchestration but not the
actual REST/WS/browser contracts.

**Scope**

- [x] Add Spring tests for `POST /api/sessions` with a company, omitted `companyProfileId`
  (general practice), and illegal general-practice full loop (400). Done 2026-09-14:
  `SessionControllerTest` (`@AutoConfigureMockMvc`) — 6 tests covering single-module and
  full-loop creation with a company, the general-practice default, the 400 on
  general-practice + full-loop, and `GET /api/sessions`/`GET /api/sessions/{id}` round-trips.
  No mock provider needed — session creation stores `providerId`/`modelId` as plain strings,
  resolved only when a round actually starts.
- [x] Add scripted-provider WebSocket coverage for `start_round`, candidate turns, streamed
  deltas, `turn_complete`, silent-turn repair, and reconnect/restart behavior. Done 2026-09-14:
  `InterviewWebSocketHandlerTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + a real
  `StandardWebSocketClient` against `/ws/interview`) — 6 tests covering the opening-brief
  frame sequence, a full candidate turn's frame order (ack → deltas → tool_call →
  phase_advanced → usage → turn_complete, the *actual* wire order, documented as such —
  `round_started` arrives last, after `beginRound` completes, not first), the silent-turn
  retry delivering real words over the socket, unknown-type and missing-roundId error frames,
  and reconnect idempotency (a repeated `start_round` for an already-pinned round does not
  re-send the opening brief). Extracted the scripted-provider harness from
  `TurnOrchestratorIntegrationTest` into a shared, public `testsupport.ScriptedProviderSupport`
  so this and future tests don't each duplicate a mock `LlmProvider`.
- [ ] Add a small browser smoke suite (Playwright or an already-available equivalent; do not add a
  paid service) that starts the local app, creates a general mock, and confirms mobile controls.
  **Still open** — no frontend or browser-driven tests exist in this repo yet.
- Keep provider traffic fully mocked; never spend Gemini free-tier quota in CI.

**Acceptance criteria**

- [x] Tests prove JSON field names and status codes for `POST/GET /api/sessions*` actually
  consumed by React (`SessionControllerTest`).
- [x] WS frame ordering actually consumed by React (`InterviewWebSocketHandlerTest`, see above).
- [x] The test setup uses an isolated H2 database (existing `src/test/resources/application.yaml`
  in-memory config, unchanged).
- [x] `./mvnw -o test` passes from a clean checkout (106 tests, 0 failures as of this update).
  **Still open:** frontend typecheck as a CI/checklist step for this card specifically (it
  already runs in `.github/workflows/ci.yml`, just not re-verified here) and a Playwright
  browser smoke test — no frontend or browser-driven tests exist in this repo yet.

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

- [x] Browser-test the existing Web Speech controls on Chrome/Edge. Done 2026-09-14 for
  Chrome-family (Chromium, headless — no Edge available in this environment): mic toggle
  and TTS toggle both work with no console errors; see docs/TASKS.md H1 findings. **Still
  open:** document Safari/Firefox behavior — neither is installed in this environment.
  Recognition may be unavailable there; typed fallback must remain excellent (unchanged,
  not touched).
- [ ] Add a compact settings explanation that browser speech recognition can be cloud-backed
  (notably Chrome) and does not send audio through this application backend. **Still open** —
  no such explanation exists in the Settings overlay yet (confirmed by the 2026-09-14
  screenshot — Settings has Roles/Resume/Providers sections, nothing about voice).
- [x] Verify speech cancellation on exit and next-round transitions. Verified **by reading
  the code**, not a live call (no completed interviewer turn was reachable without an LLM
  key): `App.tsx` calls `voice.cancelSpeech()` on session exit and on the next-round
  transition/voice-toggle-off paths; `VoiceProvider.cancelSpeech()` calls
  `speechSynthesis.cancel()`. **Text streaming boundaries — confirmed by reading, not a
  live call:** `App.tsx`'s `turn_complete` handler is the only call site for `voice.speak(...)`,
  gated on `ttsEnabled`; no `text_delta` handler calls `speak`, so TTS cannot fire on a
  partial in-progress turn by construction. A live confirmation (hearing it actually happen
  against a real streamed turn) is still open.
- Only design a key-based `VoiceProvider` upgrade after the owner chooses an already-owned key;
  no new vendor/subscription or local speech stack. Untouched — no owner decision yet.

**Acceptance criteria**

- TTS only reads completed interviewer turns, never partial deltas or candidate text. Confirmed
  by code reading (see above); not yet confirmed by ear against a live round.
- Dictation always remains editable and is never auto-submitted. Confirmed by code reading:
  `Composer.tsx`'s dictation `onUpdate` callback only calls `setText(...)`; no code path from
  dictation reaches `submit()`.
- Unsupported browsers have a clear, non-blocking disabled state. Confirmed in code
  (`Composer.tsx`: `disabled={disabled || !voice.supportsRecognition()}`, with a title
  explaining why) and confirmed the *supported* path works live in Chromium. The actual
  *unsupported* rendering (Safari/Firefox) remains unverified live — no such browser here.

### C8 — Package and release the local product

**Scope**

- [x] Decide whether "packaged startup" means a Maven-served production frontend, the
  existing `start.sh`, Docker Compose, or all three. **Owner decision, asked and answered
  2026-09-14/15: Maven-served single JAR**, additive alongside the existing `start.sh` /
  `start-docker.sh` / `npm run dev` — none of those were removed or changed.
- [x] If serving the built frontend from Spring, add a reproducible Maven/frontend build
  step and ensure `/api` and `/ws` keep their behavior. Keep dev mode fast and documented.
  Done 2026-09-15: a `packaged` Maven profile (`pom.xml`) runs `npm ci` + `npm run build`
  (via `exec-maven-plugin`, using the system's own npm — no second Node download) then
  copies `web/dist` into `target/classes/static` (via `maven-resources-plugin`, bound to
  `generate-resources`/`process-resources` so it lands before `package`). Deliberately a
  profile, not the default lifecycle: `./mvnw test`/`clean compile` never touch `web/` and
  stay exactly as fast as before — verified live, `./mvnw clean test` still 106/106 with no
  `target/classes/static` created when the profile isn't passed.
- [ ] Review `start.sh`, `start-docker.sh`, ports, shutdown behavior, data-path backups, and
  fresh machine setup. **Still open** — not touched in this pass; `start.sh`'s readiness-check
  fix (RCA.md #13) and `start-docker.sh` are unchanged.
- [ ] Add a release checklist: clean install, startup readiness, one local mock, backup/restore
  H2, and no secret leakage. **Still open.**

**Acceptance criteria**

- [x] A fresh local checkout reaches a usable UI with one documented command and no manual
  path edits. Verified live: `./mvnw clean package -Ppackaged && java -jar target/*.jar`
  serves the full UI at `http://localhost:8123/` — confirmed with a real Chromium browser,
  zero console errors, identical rendering to `npm run dev`. `/api/profiles` (JSON,
  unaffected) and a built static asset both confirmed serving correctly from the same port
  via `curl`.
- [ ] The launcher terminates both backend and frontend cleanly. N/A to this specific
  addition (`java -jar` is one process, no separate frontend process to terminate) — the
  existing `start.sh`/`start-docker.sh` termination behavior is unchanged and untouched.
- [x] README commands and actual ports match. `README.md`'s new "Or, one process" section
  documents the exact two commands verified above, alongside the existing two-process and
  `start.sh`/`start-docker.sh` paths (kept, not replaced).

## Deferred/open owner decisions

Do not silently choose these:

1. The exact break/pause policy within a live interview and whether pauses alter planned timing.
2. ~~The intended packaged distribution form (single JAR, scripts, Docker, or a supported
   subset).~~ **Resolved 2026-09-14/15, asked and answered: Maven-served single JAR**,
   additive — see C8. `start.sh`/`start-docker.sh` remain available too.
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
