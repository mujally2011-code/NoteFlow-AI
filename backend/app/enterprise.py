"""Self-hosted multi-tenant admin portal and explicit Teams/Zoom imports.

No app credentials or user OAuth tokens are ever sent to the Android device.
The user must explicitly select a provider artifact to import.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import date, timedelta
from pathlib import Path
from typing import Literal
from urllib.parse import quote, urlencode, urlparse

import httpx
from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import HTMLResponse, PlainTextResponse
from pydantic import BaseModel, Field

router = APIRouter(prefix="/v1/enterprise", tags=["enterprise"])


def setting(key: str) -> str:
    return os.environ.get(key, "").strip()


def utc() -> int:
    return int(time.time())


@contextmanager
def database():
    path = setting("NOTEFLOW_ENTERPRISE_DB") or "noteflow-enterprise.sqlite3"
    db = sqlite3.connect(path, timeout=20)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript("""
    CREATE TABLE IF NOT EXISTS organizations(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS members(id TEXT PRIMARY KEY, org_id TEXT NOT NULL REFERENCES organizations(id),
      email TEXT NOT NULL UNIQUE, pass_salt TEXT NOT NULL, pass_hash TEXT NOT NULL,
      role TEXT NOT NULL CHECK(role IN ('admin','editor','viewer')), active INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS oauth_pending(state_hash TEXT PRIMARY KEY, org_id TEXT NOT NULL, member_id TEXT NOT NULL,
      provider TEXT NOT NULL, verifier TEXT NOT NULL, expires_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS connections(org_id TEXT NOT NULL, member_id TEXT NOT NULL, provider TEXT NOT NULL,
      encrypted_token BLOB NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(org_id,member_id,provider));
    CREATE TABLE IF NOT EXISTS imports(id TEXT PRIMARY KEY, org_id TEXT NOT NULL, member_id TEXT NOT NULL,
      provider TEXT NOT NULL, external_key TEXT NOT NULL, title TEXT NOT NULL, transcript TEXT NOT NULL,
      created_at INTEGER NOT NULL, UNIQUE(org_id,provider,external_key));
    CREATE TABLE IF NOT EXISTS audit(id TEXT PRIMARY KEY, org_id TEXT NOT NULL, member_id TEXT,
      action TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL);
    """)
    try:
        yield db
        db.commit()
    finally:
        db.close()


def password_hash(password: str, salt: str) -> str:
    return hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()


def digest_state(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def signing_key() -> bytes:
    key = setting("NOTEFLOW_ENTERPRISE_SIGNING_KEY")
    if len(key) < 32:
        raise HTTPException(503, "Set NOTEFLOW_ENTERPRISE_SIGNING_KEY to a strong random secret (32+ characters)")
    return key.encode()


def encryptor() -> Fernet:
    key = setting("NOTEFLOW_ENTERPRISE_FERNET_KEY")
    try:
        return Fernet(key.encode())
    except (ValueError, TypeError):
        raise HTTPException(503, "Set NOTEFLOW_ENTERPRISE_FERNET_KEY (Fernet key) before OAuth integration")


def token_for(user: sqlite3.Row) -> str:
    payload = json.dumps({"sub": user["id"], "org": user["org_id"], "exp": utc() + 3600}, separators=(",", ":")).encode()
    message = base64.urlsafe_b64encode(payload).decode().rstrip("=")
    mac = hmac.new(signing_key(), message.encode(), hashlib.sha256).hexdigest()
    return message + "." + mac


def current_member(authorization: str | None = Header(default=None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Enterprise bearer token required")
    try:
        part, signature = authorization[7:].split(".", 1)
        correct = hmac.new(signing_key(), part.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(correct, signature):
            raise ValueError("Signature")
        data = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
        if data["exp"] <= utc():
            raise ValueError("Expiry")
    except (KeyError, TypeError, ValueError, UnicodeDecodeError):
        raise HTTPException(401, "Enterprise token invalid or expired")
    with database() as db:
        user = db.execute("SELECT id,org_id,email,role,active FROM members WHERE id=? AND org_id=?",
                          (data["sub"], data["org"])).fetchone()
        if not user or not user["active"]:
            raise HTTPException(401, "Member disabled")
        return dict(user)


def allow(user: dict, *roles: str):
    if user["role"] not in roles:
        raise HTTPException(403, "Role not permitted")


def audit(db: sqlite3.Connection, member: dict, event: str, target: str):
    db.execute("INSERT INTO audit VALUES(?,?,?,?,?,?)", (str(uuid.uuid4()), member["org_id"], member["id"], event, target[:300], utc()))


class Bootstrap(BaseModel):
    bootstrap_secret: str
    organization: str = Field(min_length=2, max_length=120)
    email: str = Field(min_length=3, max_length=190)
    password: str = Field(min_length=12, max_length=200)


class Login(BaseModel):
    email: str
    password: str


class NewMember(BaseModel):
    email: str = Field(min_length=3, max_length=190)
    password: str = Field(min_length=12, max_length=200)
    role: Literal["admin", "editor", "viewer"]


class ImportRequest(BaseModel):
    meeting_id: str = Field(default="", max_length=500)
    file_id: str = Field(min_length=1, max_length=500)
    from_date: str = Field(default="", max_length=10)
    to_date: str = Field(default="", max_length=10)


@router.post("/bootstrap")
def bootstrap(body: Bootstrap):
    configured = setting("NOTEFLOW_ENTERPRISE_BOOTSTRAP_SECRET")
    if len(configured) < 24:
        raise HTTPException(503, "Enterprise bootstrap is not configured")
    if not hmac.compare_digest(body.bootstrap_secret.encode(), configured.encode()):
        raise HTTPException(401, "Invalid bootstrap secret")
    signing_key()
    email = body.email.casefold().strip()
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT 1 FROM organizations LIMIT 1").fetchone():
            raise HTTPException(409, "Bootstrap already completed")
        org, member = str(uuid.uuid4()), str(uuid.uuid4())
        salt = secrets.token_hex(16)
        db.execute("INSERT INTO organizations VALUES(?,?,?)", (org, body.organization.strip(), utc()))
        db.execute("INSERT INTO members VALUES(?,?,?,?,?,?,1)", (member, org, email, salt, password_hash(body.password, salt), "admin"))
        db.execute("INSERT INTO audit VALUES(?,?,?,?,?,?)", (str(uuid.uuid4()), org, member, "bootstrap", "organization", utc()))
    return {"organization_id": org, "admin_email": email}


@router.post("/login")
def login(body: Login):
    with database() as db:
        member = db.execute("SELECT * FROM members WHERE email=? AND active=1", (body.email.casefold().strip(),)).fetchone()
        if not member or not hmac.compare_digest(member["pass_hash"], password_hash(body.password, member["pass_salt"])):
            raise HTTPException(401, "Invalid credentials")
        return {"access_token": token_for(member), "expires_in": 3600, "token_type": "bearer"}


@router.get("/me")
def me(user=Depends(current_member)):
    return {k: user[k] for k in ("org_id", "email", "role")}


@router.get("/dashboard")
def dashboard(user=Depends(current_member)):
    with database() as db:
        org = db.execute("SELECT name FROM organizations WHERE id=?", (user["org_id"],)).fetchone()
        members = db.execute("SELECT COUNT(*) FROM members WHERE org_id=? AND active=1", (user["org_id"],)).fetchone()[0]
        imports = db.execute("SELECT COUNT(*) FROM imports WHERE org_id=?", (user["org_id"],)).fetchone()[0]
        integrations = db.execute("SELECT provider FROM connections WHERE org_id=? AND member_id=?", (user["org_id"], user["id"])).fetchall()
        return {"organization": org[0], "members": members, "imports": imports,
                "connected": [row[0] for row in integrations], "role": user["role"]}


@router.get("/members")
def members(user=Depends(current_member)):
    allow(user, "admin")
    with database() as db:
        return {"members": [dict(row) for row in db.execute("SELECT id,email,role,active FROM members WHERE org_id=?", (user["org_id"],))]}


@router.post("/members", status_code=201)
def add_member(body: NewMember, user=Depends(current_member)):
    allow(user, "admin")
    member = str(uuid.uuid4())
    salt = secrets.token_hex(16)
    with database() as db:
        try:
            db.execute("INSERT INTO members VALUES(?,?,?,?,?,?,1)", (member, user["org_id"], body.email.casefold().strip(), salt, password_hash(body.password, salt), body.role))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Email already registered")
        audit(db, user, "member_added", member)
    return {"id": member, "email": body.email, "role": body.role}


@router.get("/audit")
def audit_log(user=Depends(current_member)):
    allow(user, "admin")
    with database() as db:
        rows = db.execute("SELECT member_id,action,target,created_at FROM audit WHERE org_id=? ORDER BY created_at DESC LIMIT 100", (user["org_id"],)).fetchall()
        return {"events": [dict(row) for row in rows]}


def provider_config(provider: str) -> dict:
    # Redirect URI must exactly match the OAuth application registration.
    callback = setting("NOTEFLOW_PUBLIC_ORIGIN")
    if not callback.startswith("https://"):
        raise HTTPException(503, "Set NOTEFLOW_PUBLIC_ORIGIN to the server's public HTTPS origin")
    if provider == "teams":
        tenant = setting("MS_TENANT_ID") or "organizations"
        return {"client_id": setting("MS_CLIENT_ID"), "secret": setting("MS_CLIENT_SECRET"),
                "authorize": f"https://login.microsoftonline.com/{quote(tenant)}/oauth2/v2.0/authorize",
                "token": f"https://login.microsoftonline.com/{quote(tenant)}/oauth2/v2.0/token",
                "scope": setting("MS_SCOPES") or "offline_access User.Read OnlineMeetingTranscript.Read.All",
                "redirect": callback.rstrip("/") + "/v1/enterprise/integrations/teams/callback"}
    if provider == "zoom":
        return {"client_id": setting("ZOOM_CLIENT_ID"), "secret": setting("ZOOM_CLIENT_SECRET"),
                "authorize": "https://zoom.us/oauth/authorize", "token": "https://zoom.us/oauth/token",
                "scope": setting("ZOOM_SCOPES") or "cloud_recording:read:list_user_recordings",
                "redirect": callback.rstrip("/") + "/v1/enterprise/integrations/zoom/callback"}
    raise HTTPException(404, "Unknown provider")


def get_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=45, follow_redirects=False)


@router.get("/integrations/{provider}/authorization-url")
def authorization_url(provider: Literal["teams", "zoom"], user=Depends(current_member)):
    allow(user, "admin", "editor")
    cfg = provider_config(provider)
    if not cfg["client_id"] or not cfg["secret"]:
        raise HTTPException(503, f"{provider} OAuth client not configured")
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    with database() as db:
        db.execute("INSERT INTO oauth_pending VALUES(?,?,?,?,?,?)", (digest_state(state), user["org_id"], user["id"], provider, verifier, utc() + 600))
        audit(db, user, "oauth_started", provider)
    params = {"response_type": "code", "client_id": cfg["client_id"], "redirect_uri": cfg["redirect"], "state": state}
    if provider == "teams":
        params.update(scope=cfg["scope"], code_challenge=challenge, code_challenge_method="S256")
    return {"url": cfg["authorize"] + "?" + urlencode(params)}


@router.get("/integrations/{provider}/callback", response_class=HTMLResponse)
async def oauth_callback(provider: Literal["teams", "zoom"], code: str, state: str):
    cfg = provider_config(provider)
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        pending = db.execute("SELECT * FROM oauth_pending WHERE state_hash=? AND provider=?", (digest_state(state), provider)).fetchone()
        if not pending or pending["expires_at"] <= utc():
            raise HTTPException(400, "OAuth state invalid or expired")
        db.execute("DELETE FROM oauth_pending WHERE state_hash=?", (digest_state(state),))
        org, member, verifier = pending["org_id"], pending["member_id"], pending["verifier"]
        active = db.execute("SELECT active FROM members WHERE id=? AND org_id=?", (member, org)).fetchone()
        if not active or not active[0]:
            raise HTTPException(403, "User disabled")
    data = {"grant_type": "authorization_code", "code": code, "redirect_uri": cfg["redirect"]}
    if provider == "teams":
        data.update(client_id=cfg["client_id"], client_secret=cfg["secret"], code_verifier=verifier)
        headers = {}
    else:
        headers = {"Authorization": "Basic " + base64.b64encode((cfg["client_id"] + ":" + cfg["secret"]).encode()).decode()}
    async with get_client() as client:
        response = await client.post(cfg["token"], data=data, headers=headers)
        if response.status_code >= 400:
            raise HTTPException(502, "OAuth provider rejected token exchange")
        record = response.json()
    if not record.get("access_token"):
        raise HTTPException(502, "OAuth provider did not supply access token")
    sealed = encryptor().encrypt(json.dumps(record).encode())
    with database() as db:
        db.execute("INSERT INTO connections VALUES(?,?,?,?,?) ON CONFLICT(org_id,member_id,provider) DO UPDATE SET encrypted_token=excluded.encrypted_token,updated_at=excluded.updated_at",
                   (org, member, provider, sealed, utc()))
        audit(db, {"org_id": org, "id": member}, "oauth_connected", provider)
    return HTMLResponse("<html><body><h2>NoteFlow AI: connected successfully</h2><p>Close this window and return to the enterprise dashboard.</p></body></html>")


async def provider_token(user: dict, provider: str) -> str:
    cfg = provider_config(provider)
    with database() as db:
        conn = db.execute("SELECT encrypted_token,updated_at FROM connections WHERE org_id=? AND member_id=? AND provider=?",
                          (user["org_id"], user["id"], provider)).fetchone()
    if not conn:
        raise HTTPException(409, f"Connect {provider} first")
    try:
        body = json.loads(encryptor().decrypt(conn["encrypted_token"]))
    except (InvalidToken, ValueError):
        raise HTTPException(503, "Cannot decrypt integration token")
    expires = int(body.get("expires_in", 3600))
    if utc() - conn["updated_at"] < expires - 120:
        return body["access_token"]
    refresh = body.get("refresh_token")
    if not refresh:
        raise HTTPException(409, "Connect again: refresh token missing")
    data = {"grant_type": "refresh_token", "refresh_token": refresh}
    if provider == "teams":
        data.update(client_id=cfg["client_id"], client_secret=cfg["secret"])
        headers = {}
    else:
        headers = {"Authorization": "Basic " + base64.b64encode((cfg["client_id"] + ":" + cfg["secret"]).encode()).decode()}
    async with get_client() as client:
        resp = await client.post(cfg["token"], data=data, headers=headers)
    if resp.status_code >= 400:
        raise HTTPException(409, "Integration token expired; reconnect")
    updated = resp.json()
    updated["refresh_token"] = updated.get("refresh_token") or refresh
    with database() as db:
        db.execute("UPDATE connections SET encrypted_token=?,updated_at=? WHERE org_id=? AND member_id=? AND provider=?",
                   (encryptor().encrypt(json.dumps(updated).encode()), utc(), user["org_id"], user["id"], provider))
    return updated["access_token"]


def validate_meeting_id(value: str):
    if not value or not re.fullmatch(r"[A-Za-z0-9_=-]{4,450}", value):
        raise HTTPException(422, "Invalid Teams onlineMeeting ID")


def bounded_dates(start: str, end: str) -> tuple[str, str]:
    try:
        first = date.fromisoformat(start) if start else date.today() - timedelta(days=30)
        last = date.fromisoformat(end) if end else date.today()
        if first > last or (last - first).days > 31:
            raise ValueError("date range")
    except ValueError:
        raise HTTPException(422, "Use YYYY-MM-DD and a range of at most 31 days")
    return first.isoformat(), last.isoformat()


async def remote_list(provider: str, token: str, meeting_id: str, start: str, end: str):
    async with get_client() as client:
        if provider == "teams":
            validate_meeting_id(meeting_id)
            url = f"https://graph.microsoft.com/v1.0/me/onlineMeetings/{quote(meeting_id, safe='')}/transcripts"
            r = await client.get(url, headers={"Authorization": "Bearer " + token})
            if r.status_code >= 400:
                raise HTTPException(502, f"Microsoft Graph returned HTTP {r.status_code}; confirm consent and meeting access")
            return [{"id": x["id"], "meeting_id": meeting_id, "title": x.get("createdDateTime", "Teams transcript"), "type": "VTT"}
                    for x in r.json().get("value", [])[:100] if "id" in x]
        start, end = bounded_dates(start, end)
        r = await client.get("https://api.zoom.us/v2/users/me/recordings", params={"from": start, "to": end, "page_size": 100},
                             headers={"Authorization": "Bearer " + token})
        if r.status_code >= 400:
            raise HTTPException(502, f"Zoom returned HTTP {r.status_code}; check cloud recording scope")
        out = []
        for meet in r.json().get("meetings", []):
            for f in meet.get("recording_files", []):
                if f.get("file_type") in ("M4A", "TRANSCRIPT") and f.get("id"):
                    out.append({"id": f["id"], "meeting_id": str(meet.get("uuid", "")),
                                "title": str(meet.get("topic", "Zoom recording"))[:120], "type": f["file_type"]})
        return out


@router.get("/integrations/{provider}/items")
async def integration_items(provider: Literal["teams", "zoom"], meeting_id: str = "", from_date: str = "", to_date: str = "", user=Depends(current_member)):
    allow(user, "admin", "editor")
    token = await provider_token(user, provider)
    return {"items": await remote_list(provider, token, meeting_id, from_date, to_date)}


def vtt_text(text: str) -> str:
    """Preserve speaker names in captions; strip time codes and WEBVTT metadata."""
    chunks = []
    for line in text.splitlines():
        value = re.sub(r"<[^>]+>", "", line.strip())
        if not value or value == "WEBVTT" or value.startswith(("NOTE", "STYLE", "REGION")) or "-->" in value:
            continue
        if re.fullmatch(r"\d+", value) or re.fullmatch(r"[a-f0-9-]{24,}", value, re.I):
            continue
        chunks.append(value)
    return "\n".join(chunks)


async def fetch_selected(provider: str, token: str, item: dict) -> tuple[str, bytes]:
    async with get_client() as client:
        if provider == "teams":
            mid, fid = quote(item["meeting_id"], safe=""), quote(item["id"], safe="")
            url = f"https://graph.microsoft.com/v1.0/me/onlineMeetings/{mid}/transcripts/{fid}/content"
            resp = await client.get(url, headers={"Authorization": "Bearer " + token, "Accept": "text/vtt"})
            if resp.status_code != 200:
                raise HTTPException(502, "Teams transcript could not be downloaded")
            return "vtt", resp.content
        # Re-fetch authorized file metadata, and validate the hostname before requesting bytes.
        start, end = bounded_dates(item.get("from_date", ""), item.get("to_date", ""))
        resp = await client.get("https://api.zoom.us/v2/users/me/recordings",
                                params={"from": start, "to": end, "page_size": 100},
                                headers={"Authorization": "Bearer " + token})
        if resp.status_code >= 400:
            raise HTTPException(502, "Zoom recordings could not be listed")
        matching = [f for meet in resp.json().get("meetings", [])
                    if str(meet.get("uuid", "")) == item["meeting_id"]
                    for f in meet.get("recording_files", []) if f.get("id") == item["id"]]
        if not matching or matching[0].get("file_type") not in ("TRANSCRIPT", "M4A"):
            raise HTTPException(404, "Recording not found in your authorized Zoom recordings")
        recording = matching[0]
        link = recording.get("download_url", "")
        host = urlparse(link)
        if host.scheme != "https" or not (host.hostname and (host.hostname == "zoom.us" or host.hostname.endswith(".zoom.us"))) or host.username or host.password:
            raise HTTPException(502, "Unsafe recording download location")
        if int(recording.get("file_size", 0)) > 25 * 1024 * 1024:
            raise HTTPException(413, "Zoom recording exceeds 25 MiB")
        # Stream and stop after a strict size limit; never follow redirects to unknown hosts.
        chunks = []
        total = 0
        async with client.stream("GET", link, headers={"Authorization": "Bearer " + token}) as result:
            if result.status_code != 200:
                raise HTTPException(502, "Zoom recording could not be downloaded")
            async for chunk in result.aiter_bytes():
                total += len(chunk)
                if total > 25 * 1024 * 1024:
                    raise HTTPException(413, "Zoom recording exceeds 25 MiB")
                chunks.append(chunk)
        return ("vtt" if recording["file_type"] == "TRANSCRIPT" else "m4a", b"".join(chunks))


@router.post("/integrations/{provider}/import", status_code=201)
async def import_remote(provider: Literal["teams", "zoom"], request: ImportRequest, user=Depends(current_member)):
    allow(user, "admin", "editor")
    token = await provider_token(user, provider)
    items = await remote_list(provider, token, request.meeting_id, request.from_date, request.to_date)
    choice = next((entry for entry in items if entry["id"] == request.file_id and
                   (provider == "teams" or entry["meeting_id"] == request.meeting_id)), None)
    if not choice:
        raise HTTPException(404, "Selected recording/transcript not found")
    external = f'{choice["meeting_id"]}:{choice["id"]}'
    with database() as db:
        prior = db.execute("SELECT id FROM imports WHERE org_id=? AND provider=? AND external_key=?", (user["org_id"], provider, external)).fetchone()
        if prior:
            return {"id": prior["id"], "status": "existing", "imported": False}
    kind, content = await fetch_selected(provider, token, {**choice, "from_date": request.from_date, "to_date": request.to_date})
    if len(content) > 25 * 1024 * 1024:
        raise HTTPException(413, "Recording exceeds 25 MiB; import a smaller file into Android")
    if kind == "vtt":
        transcript = vtt_text(content.decode("utf-8-sig", errors="replace"))
    else:
        from .core import OpenAIProvider, UnconfiguredProvider
        try:
            transcript = await OpenAIProvider().transcribe(content, "zoom-recording.m4a", "auto")
        except (UnconfiguredProvider, httpx.HTTPError):
            raise HTTPException(503, "Server speech-to-text provider is not configured or unavailable")
    if not transcript.strip():
        raise HTTPException(422, "No transcript text found")
    identity = str(uuid.uuid4())
    with database() as db:
        try:
            db.execute("INSERT INTO imports VALUES(?,?,?,?,?,?,?,?)", (identity, user["org_id"], user["id"], provider,
                       external, choice["title"], transcript[:200000], utc()))
        except sqlite3.IntegrityError:
            existing = db.execute("SELECT id FROM imports WHERE org_id=? AND provider=? AND external_key=?", (user["org_id"], provider, external)).fetchone()
            return {"id": existing["id"], "status": "existing", "imported": False}
        audit(db, user, "import", f"{provider}:{identity}")
    return {"id": identity, "status": "imported", "imported": True, "title": choice["title"]}


@router.get("/imports")
def imported_items(user=Depends(current_member)):
    with database() as db:
        rows = db.execute("SELECT id,provider,title,member_id,created_at FROM imports WHERE org_id=? ORDER BY created_at DESC LIMIT 100", (user["org_id"],)).fetchall()
        return {"imports": [dict(x) for x in rows]}


@router.get("/imports/{import_id}")
def imported_content(import_id: str, user=Depends(current_member)):
    with database() as db:
        row = db.execute("SELECT id,provider,title,transcript FROM imports WHERE id=? AND org_id=?", (import_id, user["org_id"])).fetchone()
        if not row:
            raise HTTPException(404, "Import not found")
        return dict(row)


@router.get("/imports/{import_id}/export", response_class=PlainTextResponse)
def export_import(import_id: str, user=Depends(current_member)):
    with database() as db:
        row = db.execute("SELECT title,transcript FROM imports WHERE id=? AND org_id=?", (import_id,user["org_id"])).fetchone()
        if not row:
            raise HTTPException(404,"Import not found")
        filename = re.sub(r"[^a-zA-Z0-9_-]", "_", row["title"])[:50] or "transcript"
        return PlainTextResponse(row["transcript"], headers={"Content-Disposition": f'attachment; filename="{filename}.txt"', "Cache-Control":"no-store"})


@router.delete("/imports/{import_id}")
def erase_import(import_id: str, user=Depends(current_member)):
    allow(user, "admin", "editor")
    with database() as db:
        row = db.execute("SELECT id FROM imports WHERE id=? AND org_id=?", (import_id, user["org_id"])).fetchone()
        if not row:
            raise HTTPException(404, "Import not found")
        db.execute("DELETE FROM imports WHERE id=? AND org_id=?", (import_id, user["org_id"]))
        audit(db, user, "import_deleted", import_id)
    return {"deleted": True}


@router.get("/panel", response_class=HTMLResponse)
def panel():
    html_path = Path(__file__).parent / "enterprise_panel.html"
    return HTMLResponse(html_path.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; form-action 'self'; object-src 'none'; frame-ancestors 'none'"})


@router.get("/panel.js")
def panel_script():
    from fastapi.responses import Response
    path = Path(__file__).parent / "enterprise_panel.js"
    return Response(path.read_text(encoding="utf-8"), media_type="text/javascript", headers={"Cache-Control": "no-store"})
