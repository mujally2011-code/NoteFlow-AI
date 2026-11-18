"""NoteFlow AI self-hostable provider gateway. Audio is not retained on the server."""
import asyncio
import hashlib
import json
import re
import sqlite3
import uuid
from typing import Literal

import httpx
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .core import OpenAIProvider, UnconfiguredProvider, config, connection, file_kind, make_token, require_token

app = FastAPI(title="NoteFlow AI Gateway", version="1.0.0", docs_url="/docs")
_store_lock = asyncio.Lock()


def authenticate(authorization: str | None = Header(default=None)):
    require_token(authorization)


def get_provider() -> OpenAIProvider:
    return OpenAIProvider()


class PairRequest(BaseModel):
    pairing_secret: str = Field(min_length=1)


class TranslateRequest(BaseModel):
    text: str = Field(min_length=1, max_length=20000)
    target: Literal["ar", "en"]
    segment_id: str
    revision: int = Field(ge=0, default=0)


class Segment(BaseModel):
    id: str
    text: str = Field(max_length=20000)


class GenerateRequest(BaseModel):
    kind: Literal["summary", "flashcards", "actions"]
    style: str = Field(default="brief", max_length=40)
    segments: list[Segment] = Field(min_length=1, max_length=200)


@app.get("/v1/health")
async def health():
    return {"status": "ok", "provider_configured": bool(config("OPENAI_API_KEY")), "pairing_configured": bool(config("NOTEFLOW_PAIRING_SECRET") and config("NOTEFLOW_SIGNING_KEY")), "version": "1.0.0"}


@app.post("/v1/auth/token")
async def auth(body: PairRequest):
    return make_token(body.pairing_secret)


def valid_session(session_id: str, index: int):
    try: uuid.UUID(session_id)
    except (ValueError, AttributeError): raise HTTPException(422, "session_id must be UUID")
    if index < 0 or index > 100000: raise HTTPException(422, "segment_index out of bounds")


async def read_audio(audio: UploadFile):
    data = await audio.read(25 * 1024 * 1024 + 1)
    if len(data) > 25 * 1024 * 1024: raise HTTPException(413, "Audio segment exceeds 25 MiB limit")
    if not data: raise HTTPException(422, "Empty recording")
    name = audio.filename or "recording"
    file_kind(data, name)
    return data, name


def cached_transcript(session_id: str, segment_index: int):
    with connection() as db:
        return db.execute("SELECT digest,transcript_id,text,language FROM transcriptions WHERE session_id=? AND segment_index=?", (session_id, segment_index)).fetchone()


def result_dict(row, session_id, index):
    return {"id": row[1], "session_id": session_id, "segment_index": index, "text": row[2], "language": row[3], "timing": "segment_only"}


def save_result(session_id: str, index: int, digest: str, text: str, language: str):
    transcript_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"noteflow:{session_id}:{index}:{digest}"))
    with connection() as db:
        db.execute("INSERT OR IGNORE INTO transcriptions VALUES(?,?,?,?,?,?,strftime('%s','now'))", (session_id, index, digest, transcript_id, text, language))
    return {"id": transcript_id, "session_id": session_id, "segment_index": index, "text": text, "language": language, "timing": "segment_only"}


def check_duplicate(session_id: str, index: int, digest: str):
    row = cached_transcript(session_id, index)
    if row is not None:
        if row[0] != digest: raise HTTPException(409, "Segment index already used with different audio; use a new segment index")
        return result_dict(row, session_id, index)
    return None


@app.post("/v1/transcriptions", dependencies=[Depends(authenticate)])
async def transcribe(session_id: str = Form(...), segment_index: int = Form(...),
    language: str = Form("auto"), audio: UploadFile = File(...), provider=Depends(get_provider)):
    valid_session(session_id, segment_index)
    data, filename = await read_audio(audio)
    digest = hashlib.sha256(data).hexdigest()
    async with _store_lock:
        cached = check_duplicate(session_id, segment_index, digest)
        if cached: return cached
        try: text = await provider.transcribe(data, filename, language)
        except UnconfiguredProvider as e: raise HTTPException(503, str(e))
        except httpx.HTTPError: raise HTTPException(502, "Transcription provider unavailable")
        return save_result(session_id, segment_index, digest, text, language)


@app.post("/v1/transcriptions/stream", dependencies=[Depends(authenticate)])
async def stream_transcription(session_id: str = Form(...), segment_index: int = Form(...),
    language: str = Form("auto"), audio: UploadFile = File(...), provider=Depends(get_provider)):
    """File-chunk streaming: deltas are tentative until final; NOT raw-mic realtime."""
    valid_session(session_id, segment_index)
    data, filename = await read_audio(audio)
    digest = hashlib.sha256(data).hexdigest()
    prior = check_duplicate(session_id, segment_index, digest)

    async def events():
        if prior:
            yield f"event: final\ndata: {json.dumps(prior,ensure_ascii=False)}\n\n"
            return
        seq = 0
        try:
            final = None
            async for event in provider.transcription_events(data, filename, language):
                seq += 1
                if event["type"] == "partial":
                    yield f"event: partial\ndata: {json.dumps({'sequence':seq,'delta':event['text']},ensure_ascii=False)}\n\n"
                elif event["type"] == "final": final = event["text"]
            if final is None: raise ValueError("Provider did not produce a final transcript")
            async with _store_lock:
                value = check_duplicate(session_id, segment_index, digest) or save_result(session_id, segment_index, digest, final, language)
            yield f"event: final\ndata: {json.dumps(value,ensure_ascii=False)}\n\n"
        except (UnconfiguredProvider, httpx.HTTPError, ValueError):
            yield 'event: error\ndata: {"detail":"Provider unavailable or incomplete response"}\n\n'
    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/v1/translate", dependencies=[Depends(authenticate)])
async def translate(req: TranslateRequest, provider=Depends(get_provider)):
    try: translated = await provider.translate(req.text, req.target)
    except UnconfiguredProvider as e: raise HTTPException(503, str(e))
    except (httpx.HTTPError, ValueError, KeyError): raise HTTPException(502, "Translation failed")
    return {"segment_id": req.segment_id, "revision": req.revision, "target": req.target, "text": translated}


@app.post("/v1/generate", dependencies=[Depends(authenticate)])
async def generate(req: GenerateRequest, provider=Depends(get_provider)):
    segments = [s.model_dump() for s in req.segments]
    known = {s.id for s in req.segments}
    try: raw = await provider.generate(req.kind, segments, req.style)
    except UnconfiguredProvider as e: raise HTTPException(503, str(e))
    except (httpx.HTTPError, ValueError, KeyError): raise HTTPException(502, "Generation failed")
    items = []
    for item in raw:
        if not isinstance(item, dict): continue
        ids = [s for s in item.get("source_ids", []) if s in known]
        if not ids: continue  # Never publish an ungrounded AI assertion.
        if req.kind == "actions":
            state = item.get("decision_state", "proposal")
            evidence=" ".join(s.text for s in req.segments if s.id in ids)
            owner=item.get("owner") or None
            due=item.get("due_date") or None
            # Require a verbatim evidence span for proposed responsibility/deadline.
            # Normalized or inferred dates are intentionally returned blank.
            if owner and str(owner).casefold() not in evidence.casefold(): owner=None
            if due and str(due).casefold() not in evidence.casefold(): due=None
            items.append({"id": str(uuid.uuid4()), "text": str(item.get("text", "")),
                "owner": owner, "due_date": due,
                "decision_state": state if state in ("decision", "proposal") else "proposal",
                "status": "draft", "source_ids": ids})
        elif req.kind == "flashcards":
            items.append({"id": str(uuid.uuid4()), "question": str(item.get("question", "")),
                          "answer": str(item.get("answer", "")), "status": "draft", "source_ids": ids})
        else:
            items.append({"id": str(uuid.uuid4()), "text": str(item.get("text", "")), "source_ids": ids})
    return {"kind": req.kind, "items": items}


@app.delete("/v1/sessions/{session_id}", dependencies=[Depends(authenticate)])
async def delete_session(session_id: str):
    try: uuid.UUID(session_id)
    except ValueError: raise HTTPException(422, "Invalid session")
    with connection() as db: db.execute("DELETE FROM transcriptions WHERE session_id=?", (session_id,))
    return {"status": "deleted", "stored_audio": False}
