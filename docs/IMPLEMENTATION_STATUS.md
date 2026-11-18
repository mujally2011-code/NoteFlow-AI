# NoteFlow AI v1.0.0 · verification report (2026-11-18)

**Status: SOURCE CANDIDATE — NOT A VALIDATED APK RELEASE.**

GitHub connection: linked account `mujally2011-code` returned **zero accessible repositories** on both general and owner repository listing. No remote commit, pull request, tag, release or Actions artifact could be created. Local Git commit and local source ZIP are provided separately. Android build tools were not installed in this execution environment; an attempted `./gradlew --version` returned `java.net.UnknownHostException: services.gradle.org` before downloading Gradle. Gradle Android compilation, lint, APK generation and device installation therefore could not execute. There is **no genuine APK file** to deliver here. Never rename a ZIP or placeholder as APK.

## Executed checks

| Check | Actual result | Evidence |
|---|---|---|
| Python syntax | PASS | `python -m compileall backend/app` |
| FastAPI provider gateway unit/integration tests | PASS, 5 tests | `cd backend && PYTHONPATH=. pytest -q` |
| Pure Kotlin algorithms | PASS, executable JVM assertions | `scripts/run_offline_checks.sh` |
| Gradle testDebugUnitTest | NOT EXECUTED | Android SDK and Gradle missing, network blocked |
| Gradle lintDebug | NOT EXECUTED | Android SDK and Gradle missing |
| Gradle assembleDebug | NOT EXECUTED | Android SDK and Gradle missing |
| APK signature | NOT TESTED | No APK produced |
| APK install / emulator test | NOT TESTED | No SDK/emulator or Android device |
| Live OpenAI transcription/translation | NOT TESTED | `OPENAI_API_KEY` not supplied (intentionally) |
| In-app Android PDF bidi visual test | NOT TESTED | APK not runnable; see `docs/pdf-rtl-reference.pdf` for separate desktop visual reference only |
| Source license/security screening | PARTIAL | `scripts/static_guardrails.py` passed, but no dynamic penetration test |

## Acceptance-scenario status

- Lecture: source UI and gateway integration coded; no on-device end-to-end test. At most per-15s chunk near-live, not continuous word-by-word live.
- Meeting with unspecified due date: gateway mock test verified `due_date = null` and `status = draft`. Android review UI coded but untested.
- Research import/edit/search/export: code paths present; import and PDF need device validation; imports >25 MiB cannot be uploaded intact.
- Network loss: segmented capture and WorkManager backoff coded; process termination and recovery not exercised on device. Last open M4A may be unrecoverable.
- Local-only mode: code prevents upload if `mode=local` or per-session consent is absent; packet-capture validation not performed.
- Developer rebuild: sources and checked Gradle bootstrap present; Android dependencies must be fetched on a connected machine. Rebuild not confirmed.

## Limitations not hidden behind the version number

1. No offline audio-to-text model bundled. Local-only retains capture, playback, library and export.
2. No true microphone PCM websocket session. Near-live is one M4A chunk per ~15 seconds, with SSE deltas after upload.
3. Imported large files need slicing and longer server processing; the bundled gateway rejects a >25 MiB chunk.
4. Source seek is at verified audio-chunk boundary, not a word-aligned timestamp.
5. Remote deletion retries are user-initiated from Settings, not a guaranteed unattended erasure SLA.
6. Android app capture and PDF layout require real-device QA. Third-party SDK integration is unverified.
7. This code is **not** a production-approved privacy/security release.

## To validate on GitHub

Create or grant access to a GitHub repository, push the local Git bundle/tree, and invoke `android.yml` in Actions. On success it will publish actual APK/debug signing verification, source ZIP from the same Git commit and hashes. For a commercial release, configure private signing credentials and add a release-signing step; debug key is not production signing.
