# v1.2.0-source – Phase 3

- Optional offline Vosk speech engine with user-imported model, SHA-256 verification, ZIP-safe installer, Android MediaCodec PCM conversion and WorkManager queue.
- Separate enterprise FastAPI router: bootstrap, authentication, roles, tenant-scoped transcript imports, audit and HTML/JS dashboard.
- OAuth-based delegated Microsoft Teams VTT transcript import and authorized Zoom cloud-recording import with bounded downloads.
- Organization transcript TXT export and Android TXT import path; no login required for the local Android library.
- New automated tests for role access, OAuth state/PKCE, encrypted tokens, tenant isolation, Teams/Zoom safe import, plus existing phase-2 coverage.

**Known limits:** no Android build verification, no bundled ASR model, no live OAuth integration-test credentials.

# Changelog

## 1.1.0 – Phase 2 source candidate (2026-11-18)

- Added 14-language translation targets with per-session selection.
- Added speaker-turn diarization endpoint, optional provider and Room storage; labels scoped to each chunk.
- Added 4-option generated quizzes with sources, on-device answer persistence and scoring.
- Added semantic vector search endpoint with cosine ranking, and an offline lexical fallback.
- Added Room 3→4 migration and Python/Kotlin tests.
- Retained existing capture, import, near-live Arabic/English ASR, summaries, flashcards, actions, TXT/JSON/PDF export.
- No production APK, Gradle build or real-device verification was possible here.

## 1.0.0 – Initial source candidate

- Offline-first Android recording library and self-hostable FastAPI AI gateway.
