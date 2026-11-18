# Phase 2 processing boundary

The Android binary must not hold a permanent upstream API secret.

Backend endpoints planned (not implemented in the Phase 1 application):
- POST /v1/sessions/{id}/processing-jobs (scope, consent, provider policy)
- POST /v1/jobs/{id}/audio-upload-init (short-lived upload URL or chunk authorization)
- PUT /v1/uploads/{id}/chunks/{number} (checksum/idempotency)
- GET /v1/jobs/{id} (queued, running, completed, failed, cancelled)
- GET /v1/sessions/{id}/transcript
- POST /v1/sessions/{id}/summary
- POST /v1/sessions/{id}/flashcards
- POST /v1/sessions/{id}/action-items
- DELETE /v1/sessions/{id}/cloud-data

Every response must include stable identifiers, version, timestamps and source span references.
Separate user transcript edits from immutable original recognition results.
Use OIDC/PKCE for end users, scope-limited short-lived tokens, TLS, retention TTLs and audit logs.
