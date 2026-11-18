# NoteFlow AI · Community Android + self-hosted AI gateway

**Version:** 1.0.0-source candidate (Android APK has **not** been compiled or tested in this sandbox).  
**License:** Apache-2.0 · **Package:** `org.noteflow.app` · **Android:** API 26+.

[العربية / Arabic README](README_AR.md) · [Implementation status](docs/IMPLEMENTATION_STATUS.md) · [Privacy](SECURITY.md)

NoteFlow AI is a bilingual, local-first Android audio-notes app for university students, meetings and interviews. The repository includes the Android application, the Python FastAPI gateway, a dependency-free Gradle bootstrap launcher, unit tests, GitHub Actions and provider specifications. **It does not include credentials, recorded audio, an offline ASR model or a pre-built APK.**

## Immediately available in the code (after successful Android build)

- Create lecture, meeting or research sessions without an account. UI in Arabic (RTL) or English (LTR); dark/light mode.
- Consent gate + runtime microphone permission. Foreground `MediaRecorder` creates independent AAC/M4A chunks approximately every 15 seconds; notification provides Stop. Pause closes the current file; resume starts a new chunk.
- Local Room library, audio playback, input audio validation for MP3/M4A/WAV/WebM via Android codecs, searching titles and edited/original transcript text, bookmarks and manual transcript corrections.
- Device-private storage for audio. Separate original and edited text. Import and export TXT, JSON, PDF and CSV (cards), with selectable transcript chunks.
- Summaries with source IDs, draft cards and tasks (owner/date nullable), card grading and spaced-review scheduling.
- If opted in and configured: queued per-chunk transcription with partial and final updates, Arabic ↔ English translation, structured summaries/cards/actions. Every network operation goes through a user-specified HTTPS gateway.

**Limits to know:** Live transcription follows completed ~15-second chunks, not word-by-word microphone streaming. Cloud transcription and text generation require an external provider key and permission. Large imported files over the gateway's 25 MiB *per segment* upload limit need splitting externally. Local-only mode records and plays audio but cannot run ASR without a separately installed local provider. Segmented M4A recording protects completed files, not necessarily the currently open one. Seeking is accurate to the audio *chunk*, not a word-level timestamp. Android PDF output still needs on-device visual validation. No APK/emulator evidence is asserted.

## Install from an APK (once CI has produced one)

Download the CI artifact `NoteFlow-AI-v1.0.0-<commit>` and install `NoteFlow-AI-v1.0.0-debug.apk` from GitHub Actions artifacts, after checking `SHA256SUMS.txt`. It is **debug-signed**, not suitable as a production Play Store release. Enable installation of APKs from the browser/file-manager only if you trust the artifact. The APK is not yet available in this chat.

## Build source locally

1. Install Android Studio, Java 17 and Android SDK platform 35 / build-tools 35.0.0; connect an Android 8+ test device or emulator.
2. Open this repository root (containing `settings.gradle.kts`).
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` on macOS/Linux or `gradlew.bat testDebugUnitTest lintDebug assembleDebug` on Windows. The bundled bootstrap launcher downloads Gradle 8.11.1 over HTTPS and verifies the official distribution SHA-256 before running Gradle. If you have Gradle 8.11.1 already installed, `gradle ...` also works.
4. Check `app/build/outputs/apk/debug/app-debug.apk`; verify signing using Android SDK `apksigner verify --verbose --print-certs` and compute `sha256sum`.
5. For an official Gradle-generated Wrapper, run `gradle wrapper --gradle-version 8.11.1` with Gradle installed, and commit the generated official wrapper files. The included small Java bootstrap launcher is **not** the official Gradle Wrapper binary.

## Run the gateway

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate             # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env            # manually edit; never commit .env
# Export the variables from .env via your secret manager or shell:
export OPENAI_API_KEY='...'
export NOTEFLOW_PAIRING_SECRET='random-private-pair-secret'
export NOTEFLOW_SIGNING_KEY='another-long-random-secret'
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Use a **trusted HTTPS reverse proxy** terminating TLS to expose the gateway. For local emulator development, configure a local HTTPS tunnel; the Android app deliberately rejects HTTP addresses. Open app Settings, paste the HTTPS base URL and pairing secret, save. Choose Cloud-on-demand or Auto, then **explicitly consent per recording**; importing local audio does not grant upload permission by itself. Calls use short-lived 15-minute bearer tokens obtained using the pairing secret. The pairing secret is encrypted at rest in the Android Keystore. The gateway **does not store audio files**; it retains transcript idempotence records in local SQLite until deleted.

To switch providers, replace `OpenAIProvider` in `backend/app/core.py` and inject it through `get_provider` in `backend/app/main.py` (see [Provider guide](docs/PROVIDER_GUIDE.md)). API key never belongs in an APK.

## Run tests

```bash
cd backend && python -m pip install -r requirements-dev.txt && PYTHONPATH=. pytest -q
# With Android SDK + Gradle:
./gradlew testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions workflow `.github/workflows/android.yml` executes Android lint/tests/build, Python tests, verifies APK debug signature, publishes APK, ZIP archive of the exact checked-out commit and SHA-256 checksums as artifacts. Tagging `v1.0.0` publishes them as GitHub Release **if repository rights and CI build permit it**. No tag/release has been created during this conversation because the linked GitHub account exposes no accessible repository.

## Caveats for production use

Add end-to-end capture tests and on-device RTL PDF visual verification; harden gateway pairing with reverse-proxy rate limiting and monitoring; provision TLS; define retention and data protection agreements; consider encrypting on-device audio and a local transcription model. Do not use recordings without the consent required by law and institutional policies.
