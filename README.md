# sde-interview-loop

An AI-powered mock-interview platform for rehearsing full interview loops for **SDE-2
backend** roles at Indian product companies. It conducts a real interview round against an
LLM — pushing back, escalating hints, refusing to let you skip complexity analysis — then
scores the round against a rubric.

Single user, runs locally, bring your own API key.

---

## Quick start

**Prerequisites:** Java 17, Node 18+, and a Google Gemini API key.

```bash
# 1. Set your key (or export it in your shell profile)
export GEMINI_API_KEY=your-key-here

# 2. Backend — port 8080
./mvnw spring-boot:run

# 3. Frontend — port 5173, in a second terminal
cd web && npm install && npm run dev
```

Open **http://localhost:5173**.

First backend boot takes ~45–60s (Flyway migration + JPA bootstrap). It is ready when the
log prints `Started InterviewLoopApplication`.

You can also add or switch API keys from the settings UI at runtime — no restart needed.

---

## What a round looks like

1. **Pick a company and a round type.** 11 company profiles ship with the repo, each with
   its own round structure, emphasis weights, and interviewer quirks.
2. **Get interviewed.** The AI runs an explicit phase machine — for DSA that's
   `BRIEFING → CLARIFYING → APPROACH → CODING → COMPLEXITY → EDGE_CASES → FOLLOW_UP → WRAP`.
   It asks rather than tells, escalates hints only when you're genuinely stuck, and pushes
   back on hand-waving.
3. **Work in a real surface.** Monaco editor for DSA and LLD; a structured component-graph
   canvas for HLD; a scratch pad for the discussion rounds.
4. **Get scored.** Signals are recorded *during* the round with quoted evidence, then a
   separate evaluator model turns them into per-dimension scores, strengths, gaps, and a
   readiness band.

### The five round types

| Module | Shape | Work surface | Questions |
|---|---|---|---|
| **DSA** | One algorithmic problem, 8 phases | Monaco (Java) | 5 |
| **LLD** | One design problem, 6 phases | Monaco (Java class model) | 5 |
| **HLD** | One system design problem, 7 phases | Node/edge design graph | 4 |
| **CS fundamentals** | Rapid-fire topic pack, adaptive walk | Scratchpad (markdown) | 2 packs |
| **Java deep-dive** | One production-failure scenario, probe ladder | Monaco (Java) | 6 |

The banks are starter-sized, not exhaustive — growing them is straightforward (add a YAML
file; it is picked up at boot).

---

## Design ideas worth knowing

Three things make this different from chatting with an LLM about interview questions.

**The interviewer is a state machine, not a chatbot.** A free-form chat drifts — it forgets
to ask about complexity, lets you off the hook, and runs long. Here the model *requests*
phase transitions through tool calls and the backend decides whether to allow them. It
cannot skip complexity analysis because you sounded confident.

**Evaluation is incremental.** The interviewer records rubric signals with quoted evidence
as the round happens, so the end-of-round report summarises evidence already collected
rather than re-reading the transcript and inventing a story about it.

**The interviewer floats; the evaluator is pinned.** Any provider can conduct a round —
variety is useful, since models push back differently. But rubric scoring is pinned to one
provider/model, because otherwise your readiness trend would measure provider drift rather
than your own progress. Changing the pinned evaluator starts a new *comparability epoch*
and requires explicit confirmation.

---

## Project status

**Working end to end.** All five interviewer modules, per-round evaluation, runtime
provider/key switching, and the web client are built and verified.

**Not built yet:** full-loop round chaining (running a company's whole loop back to back),
readiness dashboard and trends, voice mode, cost ceilings.

The company profiles are all `seeded-unverified` — plausible defaults generated at setup,
not facts. Treat their round structures and difficulty bars as a starting point to correct
from real interview experience, not as ground truth.

---

## Documentation

| File | What it covers |
|---|---|
| **`CLAUDE.md`** | **Read first if you are working on the code** (human or AI). Current state, load-bearing invariants that fail *silently*, architecture map, working agreement. |
| `PROJECT_PLAN.md` | Architecture, data model, rubrics, phase roadmap, and the decision register with reasoning. |
| `company-profiles/README.md` | Profile schema and how to edit them. |

---

## Configuration

Everything below is hand-editable data — **no rebuild required**.

- **`company-profiles/*.yaml`** — company loops, round structures, emphasis weights,
  interviewer quirks. Validated against `_schema.json` at startup.
- **`question-bank/<module>/*.yaml`** — interview questions. All content is original prose
  written for this project (copying copyrighted problem statements would be a licensing
  problem even for personal use). Validated at startup.
- **`config/providers.yaml`** — provider model IDs, capabilities, and pricing. The default
  interviewer/evaluator binding lives here.

A malformed data file stops the app at boot rather than surfacing mid-interview.

---

## API

```
GET    /api/profiles                                    list company profiles
POST   /api/profiles/reload                             hot-reload profiles from disk
POST   /api/sessions                                    create a session (+ its rounds)
GET    /api/sessions                                    list sessions
GET    /api/sessions/{id}                               session with rounds
POST   /api/sessions/{sid}/rounds/{rid}/start           begin a round
POST   /api/sessions/{sid}/rounds/{rid}/complete        end a round (triggers evaluation)
DELETE /api/sessions/{id}                               abandon a session
GET    /api/rounds/{id}/transcript                      full transcript
GET    /api/rounds/{id}/artifacts                       artifact snapshots (for replay)
GET    /api/rounds/{id}/evaluation                      scored report
GET    /api/providers                                   providers, capabilities, key status
PUT    /api/providers/{id}/key                          set an API key at runtime
DELETE /api/providers/{id}/key                          clear a UI-supplied key
POST   /api/providers/{id}/verify                       test a key with a live call
GET    /api/settings                                    interviewer/evaluator bindings
PUT    /api/settings/interviewer                        change the interviewer
PUT    /api/settings/evaluator                          change the evaluator (needs confirmation)

WS     /ws/interview                                    interview turns (streaming)
```

API keys are read server-side only, never returned by any endpoint (masked form only), and
never sent to the browser.

---

## Development

```bash
./mvnw test              # 38 tests
./mvnw -o compile        # offline build, faster once deps are cached
cd web && npx tsc --noEmit   # frontend typecheck
```

**Stack:** Java 17 · Spring Boot 3.4 · raw WebSocket (not STOMP) · embedded H2 in file mode
· Flyway · React 19 · Vite 7 · Monaco.

No Docker required. The database is embedded, so there is nothing to run alongside the app.

**A note on cost:** Gemini's free tier allows 20 requests per day *per model*. A couple of
full rounds will exhaust it. Quota is per-model, so switching to `gemini-3.6-flash` in
settings gives you a separate bucket.
