# Security policy

Please report vulnerabilities privately through the GitHub repository security contact after the project is published. Never open a public issue with recordings, user data, signing keys, access tokens or private gateway addresses.

**Expected privacy defaults:** local app-private storage; no user account required; explicit recording consent; Cloud/Auto requires per-session user approval; pairing secret encrypted with Android Keystore; HTTPS-only requests; OpenAI key kept only on gateway; 15-minute bearer token; delete local data and separately retry remote cache deletion.

**Limitations:** local audio files are **not encrypted separately** by the app. Host encryption and phone lock must be enabled. The gateway's SQLite text cache must be protected by operating-system permissions. Rotate all credentials, restrict access and rate-limit pairing. The feature to request remote deletion can fail when offline; Settings contains manual retry, not a background guaranteed workflow. Do not use for patient-identifying material before a separate privacy/security review.

Do not store `.env`, audio recordings, `.jks`, `.keystore` or API tokens in version control. See `.gitignore`.
