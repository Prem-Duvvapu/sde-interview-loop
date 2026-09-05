#!/usr/bin/env bash
# Builds and runs the whole app (backend + frontend) with Docker Compose.
# Backend: http://localhost:8123  Frontend: http://localhost:8130
#
# Ports are deliberately non-default (not 8080/3000) — this machine runs several
# unrelated projects that default to those, and collisions between them have bitten us
# before.
#
# Usage: ./start-docker.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not on PATH. Install Docker (or, on WSL2, enable" >&2
  echo "Docker Desktop's WSL integration) and try again." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "'docker compose' is not available. Install the Docker Compose plugin." >&2
  exit 1
fi

if [ ! -f .env ]; then
  echo ".env not found — creating it from .env.example." >&2
  cp .env.example .env
  echo "Fill in at least one API key (GEMINI_API_KEY or ANTHROPIC_API_KEY) in .env, then re-run this script." >&2
  exit 1
fi

if ! grep -qE '^(GEMINI_API_KEY|ANTHROPIC_API_KEY)=\S+' .env; then
  echo "Warning: .env has no GEMINI_API_KEY or ANTHROPIC_API_KEY value set — the app will start" >&2
  echo "but no provider will be usable until you add one and restart." >&2
fi

echo "Building and starting containers (backend on :8123, frontend on :8130)..."
docker compose up --build
