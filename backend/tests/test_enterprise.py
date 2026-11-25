import base64
import json
import sqlite3
import uuid
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

from app.main import app
from app import enterprise as ep


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("NOTEFLOW_ENTERPRISE_DB", str(tmp_path / "org.db"))
    monkeypatch.setenv("NOTEFLOW_ENTERPRISE_SIGNING_KEY", "s" * 48)
    monkeypatch.setenv("NOTEFLOW_ENTERPRISE_BOOTSTRAP_SECRET", "bootstrap" * 5)
    monkeypatch.setenv("NOTEFLOW_ENTERPRISE_FERNET_KEY", Fernet.generate_key().decode())
    monkeypatch.setenv("NOTEFLOW_PUBLIC_ORIGIN", "https://notes.example.org")
    monkeypatch.setenv("MS_CLIENT_ID", "microsoft-public-client")
    monkeypatch.setenv("MS_CLIENT_SECRET", "microsoft-secret")
    monkeypatch.setenv("ZOOM_CLIENT_ID", "zoom-client")
    monkeypatch.setenv("ZOOM_CLIENT_SECRET", "zoom-secret")
    with TestClient(app) as c:
        yield c


def init(client):
    payload = {"bootstrap_secret": "bootstrap" * 5, "organization": "Research Group",
               "email": "owner@research.example", "password": "strong-test-password-123"}
    r=client.post("/v1/enterprise/bootstrap", json=payload)
    assert r.status_code == 200, r.text
    token=client.post("/v1/enterprise/login", json={"email": payload["email"], "password": payload["password"]})
    assert token.status_code == 200, token.text
    return r.json()["organization_id"], {"Authorization": "Bearer " + token.json()["access_token"]}


def add_user(client, headers, email, role):
    r=client.post("/v1/enterprise/members", headers=headers,
                  json={"email": email, "password": "another-good-password-123", "role": role})
    assert r.status_code == 201, r.text
    r=client.post("/v1/enterprise/login",json={"email":email,"password":"another-good-password-123"})
    return r.json()["access_token"]


def connection_for(user, provider, token):
    with ep.database() as db:
        db.execute("INSERT INTO connections VALUES (?,?,?,?,?)", (user["org_id"],user["id"],provider,
                   ep.encryptor().encrypt(json.dumps({"access_token":token,"expires_in":3600,"refresh_token":"refresh"}).encode()),ep.utc()))


def test_bootstrap_once_login_and_no_auth_leak(client):
    org, headers=init(client)
    assert client.post("/v1/enterprise/bootstrap", json={"bootstrap_secret":"bootstrap"*5, "organization":"Other", "email":"x@y.io", "password":"123456789012"}).status_code == 409
    assert client.get("/v1/enterprise/dashboard").status_code == 401
    assert client.post("/v1/enterprise/login", json={"email":"owner@research.example","password":"invalid"}).status_code == 401
    summary=client.get("/v1/enterprise/dashboard",headers=headers).json()
    assert summary["members"] == 1 and summary["organization"] == "Research Group"
    assert summary["connected"] == []
    assert client.get("/v1/enterprise/audit",headers=headers).status_code == 200


def test_rbac_members_and_tenant_isolation(client):
    org, admin=init(client)
    viewer=add_user(client,admin,"viewer@research.example","viewer")
    editor=add_user(client,admin,"editor@research.example","editor")
    h_view={"Authorization":"Bearer "+viewer}
    h_edit={"Authorization":"Bearer "+editor}
    assert client.get("/v1/enterprise/members",headers=h_view).status_code == 403
    assert client.post("/v1/enterprise/members",headers=h_edit,json={"email":"x@x.org", "password":"some-long-password", "role":"admin"}).status_code == 403
    assert client.get("/v1/enterprise/dashboard",headers=h_view).status_code == 200
    with ep.database() as db:
        # Second tenant created for isolation test. Must not appear in first tenant results.
        other=str(uuid.uuid4())
        db.execute("INSERT INTO organizations VALUES(?,?,?)", (other,"Other organization", ep.utc()))
        db.execute("INSERT INTO imports VALUES(?,?,?,?,?,?,?,?)", (str(uuid.uuid4()),other,"x","teams","a:b","OTHER PRIVATE TRANSCRIPT","secret",ep.utc()))
    data=client.get("/v1/enterprise/imports", headers=h_view).json()
    assert data["imports"] == []
    assert client.get("/v1/enterprise/dashboard", headers=admin).json()["imports"] == 0


def test_oauth_url_state_one_time_and_token_encryption(client, monkeypatch):
    _, admin=init(client)
    url=client.get("/v1/enterprise/integrations/teams/authorization-url",headers=admin)
    assert url.status_code == 200
    location=url.json()["url"]
    query=parse_qs(urlsplit(location).query)
    assert query["code_challenge_method"] == ["S256"]
    state=query["state"][0]
    async def handler(req):
        assert req.url.host == "login.microsoftonline.com"
        return httpx.Response(200,json={"access_token":"secret-access-token","refresh_token":"refresh-token","expires_in":3600})
    monkeypatch.setattr(ep,"get_client",lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    callback=client.get(f"/v1/enterprise/integrations/teams/callback?code=any-code&state={state}")
    assert callback.status_code==200,callback.text
    assert client.get(f"/v1/enterprise/integrations/teams/callback?code=any-code&state={state}").status_code==400
    user=ep.current_member(authorization=admin["Authorization"])
    with ep.database() as db:
        value=db.execute("SELECT encrypted_token FROM connections WHERE member_id=?",(user["id"],)).fetchone()[0]
        assert b"secret-access-token" not in value
    assert client.get("/v1/enterprise/dashboard",headers=admin).json()["connected"] == ["teams"]


def test_teams_transcript_explicit_import_and_dedup(client, monkeypatch):
    _, headers=init(client)
    user=ep.current_member(authorization=headers["Authorization"])
    connection_for(user,"teams","t")
    async def handler(req):
        if req.url.path.endswith("/transcripts"):
            return httpx.Response(200,json={"value":[{"id":"transcript1","createdDateTime":"2026-10-08T12:00Z"}]})
        assert req.url.path.endswith("/transcripts/transcript1/content")
        return httpx.Response(200,text="WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nAlice: hello\n\n00:00:03.000 --> 00:00:04.000\nAlice: hello\n")
    monkeypatch.setattr(ep,"get_client",lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    link=client.get("/v1/enterprise/integrations/teams/items?meeting_id=Meeting_123",headers=headers)
    assert link.status_code==200 and len(link.json()["items"])==1
    payload={"meeting_id":"Meeting_123","file_id":"transcript1"}
    first=client.post("/v1/enterprise/integrations/teams/import",headers=headers,json=payload)
    assert first.status_code==201,first.text
    second=client.post("/v1/enterprise/integrations/teams/import",headers=headers,json=payload)
    assert second.json()["status"]=="existing"
    imp=client.get("/v1/enterprise/imports/"+first.json()["id"],headers=headers).json()
    assert imp["transcript"]=="Alice: hello\nAlice: hello"
    assert client.delete("/v1/enterprise/imports/"+first.json()["id"],headers=headers).json()["deleted"]


def test_zoom_refuses_untrusted_download_url(client, monkeypatch):
    _, headers=init(client)
    user=ep.current_member(authorization=headers["Authorization"])
    connection_for(user,"zoom","t")
    async def handler(req):
        assert req.url.host == "api.zoom.us"  # Never fetch download_url if malicious.
        return httpx.Response(200,json={"meetings":[{"uuid":"meeting-zoom","topic":"Weekly","recording_files":[
            {"id":"file-1","file_type":"TRANSCRIPT","download_url":"https://169.254.169.254/latest/meta-data"}]}]})
    monkeypatch.setattr(ep,"get_client",lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    result=client.post("/v1/enterprise/integrations/zoom/import",headers=headers,
                       json={"meeting_id":"meeting-zoom","file_id":"file-1"})
    assert result.status_code==502 and "Unsafe" in result.json()["detail"]


def test_vtt_script_is_self_hosted_and_untrusted_markup_safe(client):
    html=client.get("/v1/enterprise/panel")
    assert html.status_code==200 and "Content-Security-Policy" in html.headers
    script=client.get("/v1/enterprise/panel.js")
    assert script.status_code==200
    assert "textContent" in script.text and "localStorage" in script.text
    assert ep.vtt_text('WEBVTT\n00:00:00.000 --> 00:00:01.000\n<b>مرحبا</b>') == 'مرحبا'
