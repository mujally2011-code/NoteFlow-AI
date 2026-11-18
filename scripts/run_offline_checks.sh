#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python -m compileall -q backend/app
(cd backend && PYTHONPATH=. pytest -q)
if command -v kotlinc >/dev/null;then
  mkdir -p /tmp/noteflow-logic-test
  kotlinc app/src/main/java/org/noteflow/app/domain/ReviewScheduler.kt app/src/main/java/org/noteflow/app/domain/TranscriptReducer.kt scripts/DomainSmokeTest.kt -include-runtime -d /tmp/noteflow-logic-test/logic.jar
  java -jar /tmp/noteflow-logic-test/logic.jar
fi
python scripts/static_guardrails.py
