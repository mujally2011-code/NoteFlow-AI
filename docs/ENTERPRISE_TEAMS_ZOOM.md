# Enterprise administration + Microsoft Teams & Zoom (v1.2.0)

## Server startup

1. Use Python 3.11+ and `pip install -r backend/requirements.txt`; run `uvicorn app.main:app` inside `backend/`.
2. Set the secrets from `backend/.env.example` **in the server environment**, using distinct random values for gateway pairing, enterprise bootstrap, enterprise signing and Fernet encryption. Never commit `.env`. The server does **not** parse `.env` automatically.
3. `NOTEFLOW_PUBLIC_ORIGIN` must be the public HTTPS origin only, such as `https://notes.example.org`. Register exact callback URLs:
   - `https://notes.example.org/v1/enterprise/integrations/teams/callback`
   - `https://notes.example.org/v1/enterprise/integrations/zoom/callback`
4. Run initial bootstrap **only once**:

```bash
curl -X POST https://notes.example.org/v1/enterprise/bootstrap \
 -H 'Content-Type: application/json' \
 -d '{"bootstrap_secret":"INSERT-BOOTSTRAP-SECRET", "organization":"Example University", "email":"admin@example.org", "password":"LONG-ADMIN-PASSWORD"}'
```

5. Open `https://notes.example.org/v1/enterprise/panel`; sign in and connect your Teams/Zoom account. HTTPS reverse proxy, strict firewall, backups and rate limits are expected for production.

## Teams integration

Create an Azure / Microsoft Entra app registration as a **web** app; configure client secret and delegated permissions for `User.Read`, `offline_access` and `OnlineMeetingTranscript.Read.All` (subject to tenant permissions and admin consent). Set `MS_TENANT_ID` to the tenant identifier as appropriate. Users can list transcripts for an accessible *onlineMeeting ID* and import a selected transcript's `text/vtt` content. Import does not record meetings or read every meeting automatically. Some tenants or meeting types block transcript access; use Microsoft Graph error diagnostics.

## Zoom integration

Register an OAuth application, configure `ZOOM_CLIENT_ID`, `ZOOM_CLIENT_SECRET`, the exact redirect and suitable **cloud recording read** scopes for your Zoom app type. Zoom account prerequisites and scope names vary by app type. The panel lists recent files (date range ≤31 days), and permits explicit import of `TRANSCRIPT` or `M4A`. For M4A the self-hosted gateway requires an ASR provider. A single file is limited to 25 MiB; source URLs are validated and redirects are not followed. Some Zoom accounts have no cloud recordings or no permission to download them.

## Roles and data

- `admin`: view dashboard, add members, audit events, connect personal providers, import or delete transcript records.
- `editor`: connect personal providers, import or delete transcript records, read organization imports.
- `viewer`: read own organization imports and dashboard; cannot connect, import or delete.

Each connector belongs to an individual user, tokens are encrypted at rest, and all import reads are scoped by organization. Exported TXT can be manually imported into Android. Bootstrap is disabled after the first organization is created. Data stored: organization IDs, salted Scrypt credential hashes, OAuth encrypted tokens, imported text and audit entries; audio remains transient in memory during processing.

**Not a production identity provider:** enterprise auth is a self-hosted baseline, with one-hour bearer sessions and limited administrative workflows. Deploy with TLS, external rate limiting/WAF, regular backups, password reset, account suspension, and an SSO integration before handling sensitive data at scale. Do not grant online meeting permissions more broadly than required.

## Test and inspect

```bash
cd backend
python -m pip install -r requirements-dev.txt
PYTHONPATH=. pytest -q
```

Browser uses self-hosted CSS/JavaScript; no credentials are stored in `localStorage`. Never paste personal recordings into bug reports.
