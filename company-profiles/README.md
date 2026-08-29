# company-profiles/

Calibration data for the interview loop. **These are data files, not code.** Edit them
freely — the backend loads and validates them at startup and on demand; nothing here
requires a rebuild or a code change.

## Why YAML and not JSON

Chosen deliberately:

1. **Comments.** This directory's whole purpose is to accumulate corrections from real
   interviews (`# corrected 2026-09-14 after MSFT screen — LLD round was 45min, not 60`).
   JSON cannot hold that provenance inline, and provenance is the most valuable thing
   in this dataset.
2. **Multi-line strings.** `interviewer_behavior` blocks are prompt fragments injected
   verbatim into the interviewer's system prompt. YAML block scalars (`|`) keep them
   readable; JSON would force `\n`-escaped one-liners.
3. **Anchors/aliases.** Shared round definitions (a standard 60-min DSA screen) can be
   defined once and referenced, keeping profiles diff-friendly.
4. **Hand-edit ergonomics.** You will be editing these under time pressure, from a
   phone screen debrief. Fewer braces and commas means fewer broken files.

The cost of YAML is whitespace sensitivity and a weaker type story. Both are mitigated:
`_schema.json` is a JSON Schema applied to the parsed YAML, validated at application
startup (fail-fast, with the offending file and line reported). For standalone validation,
use the scratch-venv command in `AGENTS.md`.

## Trust level

Every profile ships with `calibration.confidence`. All 11 are currently
**`seeded-unverified`** — assembled from the seed table plus general public knowledge of
these companies' loops. Round counts, durations, and especially the difficulty targets
are *plausible defaults, not verified facts*. Treat every number here as a hypothesis
until you have sat in the actual round and set `confidence: verified` with a dated note
in `calibration.sources`.

## Fields that actually drive behavior

Most fields are metadata. These three change what the AI does:

| Field | Effect |
|---|---|
| `loop.rounds[].module` + `difficulty_target` | Selects the interviewer module and the question difficulty band |
| `quirks[].interviewer_behavior` | Injected verbatim into that round's system prompt |
| `emphasis` weights | Weights the per-module readiness rollup into a single company readiness score |

`readiness.bar_band` and `readiness.module_minimums` define what "ready for this company"
means. They are opinions, not measurements — tune them as you gather real signal.

## Adding a company

Copy the closest existing file, change `id` (must match the filename stem), and run
validation. No code change required.
