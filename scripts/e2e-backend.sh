#!/usr/bin/env bash
# Starts the backend for browser tests: real app + scripted LLM provider, isolated H2 data,
# and no real provider keys in the environment — so a browser run can never spend quota or
# touch the owner's database (./data) or resume.
#
# Usage: scripts/e2e-backend.sh            (port 8124, data under target/e2e-data, wiped first)
#        E2E_BACKEND_PORT=8125 scripts/e2e-backend.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${E2E_BACKEND_PORT:-8124}"
DATA_DIR="target/e2e-data"

./mvnw -o -q test-compile
./mvnw -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/e2e-classpath.txt

rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"

exec env -u GEMINI_API_KEY -u GOOGLE_API_KEY -u ANTHROPIC_API_KEY -u OPENAI_API_KEY \
  -u OPENROUTER_API_KEY -u DEEPSEEK_API_KEY \
  java -Xmx512m -cp "target/test-classes:target/classes:$(cat target/e2e-classpath.txt)" \
  com.premd.interviewloop.e2e.E2eApplication \
  --server.port="$PORT" \
  --spring.datasource.url="jdbc:h2:file:./$DATA_DIR/interview-loop" \
  --spring.h2.console.enabled=false
