#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./gradlew testDebugUnitTest lintDebug assembleDebug
mkdir -p out
cp app/build/outputs/apk/debug/app-debug.apk out/NoteFlow-AI-v1.1.0-phase2-debug.apk
git archive --format=zip --output=out/NoteFlow-AI-v1.1.0-phase2-source.zip HEAD
sha256sum out/NoteFlow-AI-v1.1.0-phase2-* > out/SHA256SUMS.txt
: "${ANDROID_HOME:?Please export ANDROID_HOME}"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs out/NoteFlow-AI-v1.1.0-phase2-debug.apk | tee out/APK_SIGNATURE.txt
printf 'COMMIT=%s\nSIGNING=debug (non-production)\n' "$(git rev-parse HEAD)" > out/BUILD_INFO.txt
