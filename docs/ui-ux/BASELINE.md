# UI/UX baseline and verification log

Evidence for `docs/UI_UX_CONTENT_IMPLEMENTATION_PLAN.md`. Each row says *how* it was checked.
"Browser" means the real app (Spring Boot + Vite, real REST/WebSocket, isolated H2) driven by
Playwright with the **scripted** provider — zero real LLM calls. Physical microphone/speaker
behaviour is **not** covered by anything below.

## Baseline at `ec3a205` (2026-09-30, before any change)

| Check | Result |
|---|---|
| `./mvnw -o clean test` | 91 tests, 0 failures/errors/skips |
| `npm run typecheck` / `npm run build` | pass / pass; Monaco eagerly bundled (3.85 MB chunk) |
| Frontend tests | none existed (no script) |

## How to run the harness

```bash
cd web
npm test                      # vitest component/unit tests (jsdom, fake Web Speech API)
npx playwright test           # starts scripts/e2e-backend.sh (:8124) + vite (:5274) itself
npx playwright test --project=desktop
```

`scripts/e2e-backend.sh` runs `com.premd.interviewloop.e2e.E2eApplication` from the **test**
classpath: the real app plus `ScriptedProviderFactory` bound as interviewer and evaluator,
H2 under `target/e2e-data` (wiped each start), real provider key env vars unset. The
scripted provider's behaviour is chosen by markers in the candidate's text (`[end]`,
`[signal]`, `[tool-only]`, `[slow]`, `[eval-fail]`, `[advance:PHASE]`, `[code]`). Every browser
test fails on an unexpected console error or HTTP ≥400 (designed "not evaluated yet" 404s
excepted).

## Defects reproduced and fixed (increment 1)

| Defect | Reproduced by | Fixed / guarded by | RCA |
|---|---|---|---|
| Refused send wiped the draft | throwaway test against HEAD `Composer` (passed = defect) | `Composer.test.tsx` | #16 |
| Late dictation result re-filled a sent answer | throwaway test against HEAD `Composer` + `VoiceProvider` | `Composer.test.tsx`, `VoiceProvider.test.ts` | #16 |
| Streaming caret stuck when a notice interleaved deltas | source reading; unit test of reducer | `transcript.test.ts` | #16 |
| `record_signal` score/evidence + rationales streamed to the client | backend test fails with the old forwarding line | `TurnOrchestratorIntegrationTest.privateScoringNeverReachesTheCandidateSink`, browser HTML check | #14 |
| Company names rendered as raw ids (snake_case API vs camelCase client) | browser snapshot showed `google google` | `profiles.test.ts` | #15 |
| Silent screen for up to ~90s while evaluating (H1 finding) | H1 notes | Completion panel; browser tests for scored, failed-evaluation and full-loop advance | — |
| "Send anyway" allowed concurrent turns; reconnect copy claimed auto-send | source reading | Send locked while awaiting; honest copy; browser test | — |
| Unguarded `localStorage` read at startup | source reading | `storage.test.ts` | — |
| Stale product copy (full-loop "lands in Phase 6", prototype empty state) | source reading | copy replaced | — |

## Verification matrix (current)

| Scenario | Unit/component | Browser desktop 1440 | Browser mobile 375 |
|---|---|---|---|
| General DSA: stream, private scoring hidden, `[end]` → scored panel | — | ✅ | ❌ Send not reachable (below) |
| Evaluator failure → "not scored" + review action | — | ✅ | ❌ |
| End round confirm → scoring | — | ✅ | ❌ |
| Send locked while replying; draft editable | ✅ | ✅ | ❌ |
| Tool-only turn repaired with words | backend ✅ | ✅ | not run |
| Full loop round 1 → round 2 after scoring | backend ✅ | ✅ | not run |
| Refused send keeps draft; draft survives remount | ✅ | not browser-tested (needs socket fault injection) | — |
| Dictation: late result, manual edit, unmount, permission denied, unsupported | ✅ (fake API) | — | — |
| Reconnect restores transcript from server | reducer ✅ | **not yet browser-tested** | — |
| Real microphone / speaker | — | **not verified** (no hardware check) | — |

### Open findings

- **Mobile (375×812): Send is never visible/enabled** — the live-round shell is fixed-height
  with stacked panes, so the composer is pushed out of reach. This is the plan's Phase 1/4
  responsive work; not yet fixed.
- Full-page refresh mid-round still returns to setup (routes/rehydration are Phase 3).
- Baseline screenshots at 375/768/1366/1440 not yet captured.
- `npm install` reported 2 moderate advisories in dev dependencies; not yet triaged.
