# Provider API guide

`backend/app/core.py` contains an injectable `OpenAIProvider`. The backend is the only holder of `OPENAI_API_KEY`. Endpoints all require `Authorization: Bearer <15min token>` except `/v1/health` and `/v1/auth/token`.

| Path | Request | Response |
|---|---|---|
| `GET /v1/health` | none | readiness flags (no secrets) |
| `POST /v1/auth/token` | `{pairing_secret}` | `{access_token, expires_in:900}` |
| `POST /v1/transcriptions` | multipart `audio`, `session_id` UUID, `segment_index`, `language` | `{id,session_id,segment_index,text,timing:"segment_only"}` |
| `POST /v1/transcriptions/stream` | same multipart | SSE `partial` events `{sequence,delta}` and `final` event with complete transcript |
| `POST /v1/translate` | `{segment_id, revision, text, target:"ar"|"en"}` | `{segment_id,revision,target,text}` |
| `POST /v1/generate` | `{kind:"summary"|"flashcards"|"actions", style, segments:[{id,text}]}` | `{kind,items:[...]}` with `source_ids` |
| `DELETE /v1/sessions/{uuid}` | none | cached text records removed |

Transcription stores only final text + SHA-256 in SQLite to support idempotent retries, never source audio. `(session_id, segment_index)` is unique; same digest returns same stable result, conflicting digest returns HTTP 409. SSE partial messages are non-authoritative until final; clients must use sequence numbers and ignore duplicates/out-of-order events. File upload cap 25 MiB; request timeout is 180 seconds.

A new provider should implement async `transcribe(bytes, filename, language)`, `transcription_events(...)`, `translate(text,target)`, and `generate(kind,segments,style)` and can be swapped through FastAPI dependency injection. Model output is not trusted: `source_ids` must match input IDs. Unverified task owner/date are cleared if no exact cited source contains the text. The Android app never receives an OpenAI key.

In cloud mode or in auto mode with separate user consent, the recorder queues completed M4A files to WorkManager; successful responses persist in Room. In local mode, WorkManager exits before any gateway call. At present no offline ASR provider is bundled; the local-provider interfaces are extension points only.

Deployment: rate-limit `/v1/auth/token`, restrict gateway to trusted users, use HTTPS and a reverse proxy, rotate pairing secrets, do not log plaintext audio/credentials, and protect the server-side SQLite database according to data retention policy.
