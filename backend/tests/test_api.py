import os
import uuid

import pytest
from fastapi.testclient import TestClient

from app.core import file_kind, make_token, require_token
from app.main import app, get_provider


class FakeProvider:
    async def transcribe(self, audio, filename, language):
        return "نراجع Database Index لتحسين الاستعلامات"

    async def transcription_events(self, audio, filename, language):
        yield {"type": "partial", "text": "نراجع Database"}
        yield {"type": "partial", "text": " Index"}
        yield {"type": "final", "text": "نراجع Database Index لتحسين الاستعلامات"}

    async def translate(self, text, target):
        return "We review Database Index" if target == "en" else "نراجع فهرس قاعدة البيانات"

    async def generate(self, kind, segments, style):
        known = segments[0]["id"]
        if kind == "actions":
            return [{"text": "جهز التقرير", "owner": "", "due_date": "", "decision_state": "proposal", "source_ids": [known]},
                    {"text": "fabricated", "source_ids": ["nonexistent"]}]
        if kind == "flashcards":
            return [{"question": "ما هو الفهرس؟", "answer": "وسيلة تسريع الوصول", "source_ids": [known]}]
        return [{"text": "نوقش الفهرس", "source_ids": [known]}]


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("NOTEFLOW_PAIRING_SECRET", "long-pair-secret")
    monkeypatch.setenv("NOTEFLOW_SIGNING_KEY", "a-very-long-signing-key-that-must-remain-secret")
    monkeypatch.setenv("NOTEFLOW_CACHE_DB", str(tmp_path / "cache.db"))
    app.dependency_overrides[get_provider] = FakeProvider
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


def auth(client):
    res = client.post("/v1/auth/token", json={"pairing_secret": "long-pair-secret"})
    assert res.status_code == 200
    return {"Authorization": "Bearer " + res.json()["access_token"]}


def fake_wav():
    return b"RIFF" + (36).to_bytes(4, "little") + b"WAVEfmt " + b"\x00" * 40


def test_authentication_and_expiration(client):
    assert client.post("/v1/auth/token", json={"pairing_secret": "wrong"}).status_code == 401
    assert client.post("/v1/translate", json={}).status_code == 401
    hdr = auth(client)
    require_token(hdr["Authorization"])
    bad = {"Authorization": hdr["Authorization"] + "x"}
    assert client.post("/v1/translate", headers=bad, json={}).status_code == 401


def test_idempotent_transcription_conflict_and_deletion(client):
    session = str(uuid.uuid4()); headers = auth(client)
    kw = dict(data={"session_id": session, "segment_index": 0, "language": "ar"}, files={"audio": ("part.wav", fake_wav(), "audio/wav")}, headers=headers)
    first = client.post("/v1/transcriptions", **kw)
    assert first.status_code == 200, first.text
    second = client.post("/v1/transcriptions", **kw)
    assert second.json() == first.json()
    other = dict(kw, files={"audio": ("part.wav", fake_wav() + b"extra", "audio/wav")})
    assert client.post("/v1/transcriptions", **other).status_code == 409
    assert client.delete(f"/v1/sessions/{session}", headers=headers).json()["status"] == "deleted"
    assert client.post("/v1/transcriptions", **kw).status_code == 200


def test_sse_partial_and_final(client):
    headers = auth(client)
    res = client.post("/v1/transcriptions/stream", headers=headers,
        data={"session_id": str(uuid.uuid4()), "segment_index": 4, "language": "ar"},
        files={"audio": ("part.wav", fake_wav(), "audio/wav")})
    assert res.status_code == 200
    assert "event: partial" in res.text and "event: final" in res.text


def test_translation_generation_preserve_provenance(client):
    headers = auth(client)
    tr = client.post("/v1/translate", headers=headers, json={"segment_id": "abc", "revision": 2, "text": "الفهرس", "target": "en"})
    assert tr.status_code == 200 and tr.json()["revision"] == 2
    out = client.post("/v1/generate", headers=headers, json={"kind": "actions", "segments": [{"id": "seg-1", "text": "جهز التقرير"}]})
    assert out.status_code == 200 and len(out.json()["items"]) == 1
    task = out.json()["items"][0]
    assert task["due_date"] is None and task["owner"] is None and task["status"] == "draft"
    assert task["source_ids"] == ["seg-1"]


def test_audio_validation(client):
    headers = auth(client)
    r = client.post("/v1/transcriptions", headers=headers,
        data={"session_id": str(uuid.uuid4()), "segment_index": 0},
        files={"audio": ("virus.mp3", b"not an MP3", "audio/mpeg")})
    assert r.status_code == 415

# Phase-2 contract tests. These deliberately use a fake provider; they do not
# claim an external model has been exercised.
async def _embed(texts):
    table = {
        "database index": [1.0, 0.0, 0.0],
        "الفهرس يساعد الاستعلامات": [0.98, 0.2, 0.0],
        "المطار بعيد": [0.0, 1.0, 0.0],
    }
    return [table.get(text, [0.1, 0.1, 1.0]) for text in texts]

async def _diarize(data, filename):
    return {"segments": [
        {"speaker": "speaker_0", "start": 0.0, "end": 2.4, "text": "السلام عليكم"},
        {"speaker": "speaker_1", "start": 2.5, "end": 4.0, "text": "أهلاً"},
        {"speaker": "speaker_2", "start": -3.0, "end": 1.0, "text": "invalid"},
    ]}

async def _quiz_generate(self, kind, segments, style):
    if kind == "quiz":
        return [{"question": "ما دور الفهرس؟", "options": ["التسريع", "الحذف", "التشفير", "الترجمة"],
                 "correct_index": 0, "source_ids": [segments[0]["id"]]},
                {"question": "Invalid answer", "options": ["Only one"], "correct_index": 9,
                 "source_ids": [segments[0]["id"]]}]
    return await FakeProvider._legacy_generate(self, kind, segments, style)

FakeProvider.embed = staticmethod(_embed)
FakeProvider.diarize = staticmethod(_diarize)
FakeProvider._legacy_generate = FakeProvider.generate
FakeProvider.generate = _quiz_generate


def test_languages_and_multilingual_translation(client):
    assert "fr" in client.get("/v1/languages").json()["languages"]
    hdr = auth(client)
    ok = client.post("/v1/translate", headers=hdr, json={
        "segment_id": "s", "text": "مرحبا", "revision": 1, "target": "fr"})
    assert ok.status_code == 200
    assert ok.json()["target"] == "fr"
    assert client.post("/v1/translate", headers=hdr, json={
        "segment_id": "s", "text": "hello", "target": "invalid"}).status_code == 422


def test_semantic_ranking_and_source_provenance(client):
    result = client.post("/v1/semantic-search", headers=auth(client), json={
        "query": "database index", "top_k": 2, "segments": [
            {"id": "s-a", "text": "المطار بعيد"},
            {"id": "s-b", "text": "الفهرس يساعد الاستعلامات"}]})
    assert result.status_code == 200, result.text
    rows = result.json()["matches"]
    assert len(rows) == 2 and rows[0]["segment_id"] == "s-b"
    assert rows[0]["score"] >= rows[1]["score"]


def test_diarization_returns_chunk_scoped_speakers(client):
    result = client.post("/v1/diarize", headers=auth(client),
        data={"session_id": str(uuid.uuid4()), "segment_index": 0},
        files={"audio": ("chunk.wav", fake_wav(), "audio/wav")})
    assert result.status_code == 200, result.text
    body = result.json()
    assert body["stable_identity_across_segments"] is False
    assert len(body["turns"]) == 2
    assert body["turns"][1]["start_ms"] == 2500
    assert len({t["id"] for t in body["turns"]}) == 2


def test_multiple_choice_quiz_validates_answer_and_evidence(client):
    result = client.post("/v1/generate", headers=auth(client), json={
        "kind": "quiz", "segments": [{"id": "x", "text": "الفهرس يسرع الاستعلام"}]})
    assert result.status_code == 200, result.text
    cards = result.json()["items"]
    assert len(cards) == 1
    assert cards[0]["correct_index"] == 0
    assert cards[0]["source_ids"] == ["x"]
