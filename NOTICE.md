# Open-source component acknowledgements

Project-owned source code: Apache License 2.0 (see `LICENSE`). Dependencies are downloaded by Gradle/Python, not vendored into this ZIP.

| Component | Project/attribution | License (check exact dependency version before redistribution) |
|---|---|---|
| Kotlin and Kotlin Coroutines | JetBrains | Apache-2.0 |
| AndroidX Activity, Room, WorkManager, Compose, Material 3 | Android Open Source Project / Google | Apache-2.0 |
| OkHttp | Square | Apache-2.0 |
| FastAPI, Starlette | FastAPI contributors / Encode | MIT / BSD-3-Clause respectively |
| HTTPX | Encode | BSD-3-Clause |
| Uvicorn | Uvicorn contributors | BSD-3-Clause |
| Pydantic | Pydantic contributors | MIT |
| pytest | pytest-dev | MIT |
| Gradle | Gradle Inc. | Apache-2.0 |

The source includes a small **custom** Java Gradle bootstrap launcher, released under the project's Apache-2.0 license, not the official Gradle Wrapper implementation. The Gradle distribution is downloaded over HTTPS and its SHA-256 checked. On a trusted developer workstation, replace it by the official wrapper generated with `gradle wrapper`.
