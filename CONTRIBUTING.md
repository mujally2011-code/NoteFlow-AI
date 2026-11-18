# Contributing to NoteFlow AI

Apache-2.0 contributions welcome. Open issues for bugs and feature requests, and pull requests with tests. Protect recordings and credentials: do not attach personal audio, `.env` files, API keys, or signing material. Every new provider must implement its own contract and support explicit consent. Avoid writing model outputs that omit source references. Review Arabic/English bidirectional output at the UI and PDF layer before release.

Run `./scripts/run_offline_checks.sh` for Python and pure Kotlin smoke tests, then on a configured Android machine run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
