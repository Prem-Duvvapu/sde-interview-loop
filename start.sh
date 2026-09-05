#!/usr/bin/env bash
# Starts the backend (Spring Boot, :8123) and frontend (Vite, :5273) for local dev,
# using whatever Java/Node/npm is already on this machine — no Docker.
#
# Ports are deliberately non-default (not 8080/5173) — this machine runs several
# unrelated projects that default to those, and collisions between them have bitten us
# before.
#
# Usage: ./start.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# .env is never auto-loaded by Spring — ProviderKeyStore reads keys via System.getenv,
# so they have to actually be exported into this shell, not just sitting in the file.
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [ -z "${GEMINI_API_KEY:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "Warning: neither GEMINI_API_KEY nor ANTHROPIC_API_KEY is set." >&2
  echo "Copy .env.example to .env and fill in at least one key, or export it yourself." >&2
fi

BACKEND_LOG="$(mktemp -t interview-loop-backend.XXXXXX.log)"
echo "Starting backend (log: $BACKEND_LOG)..."
./mvnw -q spring-boot:run >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

CLEANED_UP=0
cleanup() {
  [ "$CLEANED_UP" -eq 1 ] && return
  CLEANED_UP=1
  echo ""
  echo "Stopping backend (pid $BACKEND_PID)..."
  kill "$BACKEND_PID" >/dev/null 2>&1 || true
  wait "$BACKEND_PID" 2>/dev/null || true
  # Belt-and-braces: if `npm run dev` (foreground) didn't get SIGTERM directly from the
  # terminal — e.g. this script was stopped by something other than an interactive
  # Ctrl+C — take down the rest of this script's process group too. Note: this script's
  # own pgid is not always its own pid ($$), so look it up rather than assuming they match.
  local pgid
  pgid="$(ps -o pgid= -p $$ 2>/dev/null | tr -d ' ')"
  if [ -n "$pgid" ]; then
    kill -- "-$pgid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

echo "Waiting for backend to start (first boot takes ~45-60s)..."
READY=0
for _ in $(seq 1 120); do
  if grep -q "Started InterviewLoopApplication" "$BACKEND_LOG" 2>/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Backend process exited early. Log:" >&2
    cat "$BACKEND_LOG" >&2
    exit 1
  fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "Backend did not report ready within 120s. Check $BACKEND_LOG" >&2
  exit 1
fi

echo "Backend is up on http://localhost:8123"
echo "Starting frontend on http://localhost:5273 (Ctrl+C stops both)..."
cd web
npm install
npm run dev
