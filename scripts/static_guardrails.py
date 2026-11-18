"""Lightweight audit; not a replacement for Android Lint or dynamic security tests."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
worker=(root/'app/src/main/java/org/noteflow/app/network/TranscriptionWorker.kt').read_text()
gateway=(root/'app/src/main/java/org/noteflow/app/network/BackendGateway.kt').read_text()
assert 'android:usesCleartextTraffic="false"' in manifest
assert 'FOREGROUND_SERVICE_MICROPHONE' in manifest
assert 'session.processingMode=="local" || !session.cloudConsent' in worker
assert 'base.startsWith("https://")' in gateway
assert not (root/'backend/.env').exists()
assert not list(root.glob('**/*.apk'))
print('PASS: static manifest, consent guard, HTTPS and secret/file hygiene checks')
