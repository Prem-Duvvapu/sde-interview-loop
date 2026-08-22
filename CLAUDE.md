# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state: pre-implementation

**There is no application code in this repository yet, and none should be written until
the owner explicitly approves `PROJECT_PLAN.md`.** This is a standing instruction from
the owner, not a phase artifact.

What exists is the plan plus two directories of hand-editable data. There is no build
system, no test suite, and no lint config — do not invent commands for them or claim to
have run them.

Read `PROJECT_PLAN.md` first. It is the source of truth for architecture, data model,
module boundaries, the phased roadmap, and the decision register.

## Hard constraints

These come from the owner and override reasonable-sounding defaults:

- **SDE-2 backend only.** The owner has ~2 years of experience. Never calibrate content,
  difficulty, or rubrics at SDE-3/SDE-4/senior level — including when a company's own
  ladder name sounds senior. LinkedIn "Sr. SWE", Walmart "SWE-4", and Apple "ICT4" were
  already retargeted to their SDE-2 equivalents for exactly this reason; each carries a
  `LEVEL NOTE` in its profile recording the change.
- **No added cost.** The AI layer is multi-provider with bring-your-own keys. Never
  propose a paid service that requires a new subscription or vendor relationship. If a
  capability needs a key, it must be a key the owner already supplies.
- **16GB WSL2 laptop**, running alongside IntelliJ IDEA and Docker Desktop. RAM budget is
  a real design constraint — it is why there is no database container and no self-hosted
  speech stack.

## Decisions already made — do not re-litigate

Recorded as DM-1 … DM-6 in `PROJECT_PLAN.md` §5.1, with reasoning. Summarised so you do
not reopen them from a cold start:

| | |
|---|---|
| Voice | Web Speech API as zero-key default, behind a `VoiceProvider` SPI |
| HLD diagrams | Structured node/edge graph serialised to text — not images to a vision model |
| DSA code | No sandboxed runner; the interviewer must dry-run code against a concrete input |
| Java | 17 |
| LLM layer | Multi-provider SPI (Gemini, Claude, OpenAI, DeepSeek, …), BYO keys |
| Storage | Embedded H2 in **file** mode, portable ANSI SQL, MySQL-ready |
| Default provider | **Google Gemini** (`gemini-3.7-flash`), interviewer and evaluator |

**Do not conflate the product's AI with the development tooling.** The application calls
**Gemini** by default at runtime. This repository is *developed* using Claude Code. These
are unrelated, and "fixing" the provider config to Anthropic because the coding assistant
is Claude would be wrong.

Three of these have consequences that are easy to undo by accident:

- **Interviewer floats, evaluator is pinned.** Any provider may conduct a round, but
  rubric scoring is pinned to one provider/model in `config/providers.yaml`. Otherwise
  readiness trends measure provider drift rather than the owner's progress. Changing the
  pinned evaluator starts a new `comparability_epoch`.
- **Module prompts will be tuned against Gemini.** Phases 2–5 tune interviewer prompts
  against the default provider's behaviour. Adding a provider later means re-checking
  those prompts against it, not just writing an adapter.
- **Portable SQL only.** No `jsonb`, no MySQL `JSON`, no vendor-specific types or
  functions. Open-shaped data goes in `TEXT` columns and is deserialised in the
  application layer. This is what keeps the MySQL move a config change.

`PROJECT_PLAN.md` §5.2 lists D-5 … D-8, which are genuinely open. Flag them; do not
silently decide them.

## Working agreement

- Each phase gets its own git worktree/branch, a scoped plan, and a review checkpoint
  before merge. No large unreviewed diffs.
- Phases 2–5 (DSA / LLD / HLD / CS-fundamentals+Java modules) are independent by design
  and are the intended parallel-worktree candidates — but only once the Phase 1
  `InterviewerModule` SPI is frozen.
- Flag open decisions rather than assuming. The owner has asked explicitly to be
  consulted on anything requiring their judgement.

## Data files, not code

`company-profiles/*.yaml` and `config/providers.yaml` are edited by hand and must never
require a rebuild to change. This is a design requirement, not a convenience.

- **Profiles** are validated against `company-profiles/_schema.json`. Beyond the schema,
  four invariants are enforced: `id` equals the filename stem, `emphasis` weights sum to
  1.0, round `ordinal`s are 1..n contiguous, and `total_wall_clock_min` equals the sum of
  all rounds' `duration_min` (including rounds disabled in v1).
- **`calibration.confidence`** is currently `seeded-unverified` on all 11 profiles. These
  are plausible defaults, not facts. Only the owner may raise a profile to
  `partially-verified` / `verified`, from first-hand interview data. Never present seeded
  numbers as established.
- **`quirks[].interviewer_behavior`** is injected verbatim into a round's system prompt.
  Edits there change AI behavior directly — treat it as prompt code, not prose.
- **Provider model IDs** in `config/providers.yaml` are deliberately `<set-me>` for
  non-Anthropic providers. Vendor model IDs change often; look them up rather than
  guessing, since a wrong ID fails at runtime.

### Validating data files

No Maven binding exists yet — a `mvn validate` binding is planned for Phase 1. Until
then validation is ad hoc and needs `pyyaml` + `jsonschema`. System Python is
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

YAML gotcha that has already bitten once: a value beginning with `"` is parsed as a
quoted scalar, so `label: "As Appropriate" final round` is a parse error. Wrap the whole
value in single quotes. Dates must be quoted (`last_updated: "2026-08-22"`) or YAML
parses them into `date` objects and schema validation fails on the type.

## Planned stack

Java 17 + Spring Boot 3.x (REST + WebSocket), React 19 + Vite with Monaco, embedded H2,
Docker Compose with two containers (`app`, `web` — no database container). Every provider
adapter uses that vendor's own official SDK — never an OpenAI-compatible shim pointed at
a different vendor, which silently loses provider-specific features. Claude's is
`com.anthropic:anthropic-java`; confirm Gemini's Java SDK coordinates when building that
adapter rather than assuming them.

Prompt-caching mechanisms differ sharply between providers (Gemini uses TTL-based cached
content objects with a token floor; Anthropic uses inline breakpoints). A cache strategy
tuned on one does not transfer — see `PROJECT_PLAN.md` §1.5.

Architecture detail lives in `PROJECT_PLAN.md` §1 — in particular §1.2 (the interviewer
is a phase state machine, not a free-form chatbot) and §1.4 (prompt assembly must stay
stable-prefix-first or prompt caching silently stops working). Both are load-bearing and
non-obvious from reading source alone.
