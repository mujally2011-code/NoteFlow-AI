# NoteFlow AI v1.0.0 source candidate

This repository contains source code for a unified Android+gateway prototype. Completed source changes are not automatically verified Android functionality. For actual acceptance gates and missing tests, see [Implementation status](docs/IMPLEMENTATION_STATUS.md).

- [x] One app covering lectures, meetings, research imports; Arabic/English, light/dark.
- [x] Foreground recording into recoverable independent M4A chunks and local Room metadata.
- [x] Import/play/edit/search/export pathways in source.
- [x] Backend provider integration, cached dedupe, SSE deltas, OAuth-like short-lived pairing tokens.
- [x] Source-linked summaries/cards/task drafts and review scheduling.
- [x] CI workflow, custom Gradle bootstrap launcher, backend tests, Kotlin logic smoke test.
- [ ] Compile Android on an Android SDK-equipped machine; fix compilation/lint findings.
- [ ] Real phone/emulator recording, microphone, import, playback, UI accessibility, RTL and PDF verification.
- [ ] Live provider tests with configured `OPENAI_API_KEY` and secure TLS gateway.
- [ ] Large-file segmentation/transcoding for imported files over the 25 MiB gateway cap.
- [ ] True microphone live PCM streaming and optional offline ASR provider/model.
- [ ] Automated device network audit for local-only mode; cloud deletion SLA and retention hardening.
- [ ] Commercial release signing, store checks, external audit.
