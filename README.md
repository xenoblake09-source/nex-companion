# NEX — Ultimate AI Pet &amp; Intelligent Companion

NEX is a warm, screen-only AI companion: a native Android app with a face made of **56 curated
animations**, a foreground **"Hello NEX"** voice conversation loop, and a small **FastAPI**
backend that is the only place an AI key ever exists.

```
┌────────────────────────────┐        HTTPS only         ┌──────────────────────────┐
│  Android (Kotlin, native)  │ ────────────────────────► │  FastAPI on Render       │
│  • voice state machine     │   POST /v1/converse       │  • Gemini 2.5 Flash      │
│  • 56 screen animations    │   POST /v1/vision         │  • Search grounding      │
│  • local memory (consent)  │ ◄──────────────────────── │  • safety + limits       │
│  • no keys, no secrets     │   reply / sources / error │  • GEMINI_API_KEY here   │
└────────────────────────────┘                           └──────────────────────────┘
```

Design rules that shaped every file in this repository:

* **The Android app holds no credentials.** No API key, no OAuth client, no device identifier.
  The only secret in the whole system is `GEMINI_API_KEY`, entered by hand in the Render
  dashboard and never committed, printed, zipped or shipped in an APK.
* **NEX never lies about what it knows.** Sources appear only when search grounding genuinely
  happened; offline answers say they are offline; a failed turn is never silently queued.
* **NEX never listens in the background.** The wake phrase works only while the app is open,
  the microphone is gated by one pure function, and camera/location are used only for an
  explicit request.
* **Everything that decides behaviour is pure Kotlin or pure Python**, so it can be unit tested
  without an emulator, a device or a network.

---

## Repository layout

```
nex-companion/
├── README.md                  ← this file
├── LICENSE                    ← MIT
├── .gitignore                 ← secrets, build output, venvs, keystores
├── render.yaml                ← Render Blueprint: web service `nex-companion-api`
├── docs/
│   ├── ARCHITECTURE.md        ← components, data flow, state machine, animation system
│   ├── API_CONTRACT.md        ← every endpoint, field, status code and error code
│   ├── PRIVACY_AND_PERMISSIONS.md ← permission-by-permission justification and limits
│   ├── DEPLOYMENT.md          ← Render steps, env vars, health check, verification
│   └── PHYSICAL_DEVICE_QA.md  ← manual test script for a real phone
├── backend/
│   ├── requirements.txt
│   ├── Dockerfile             ← python:3.11-slim, uvicorn on 0.0.0.0:$PORT
│   ├── conftest.py            ← pytest fixtures (a fake provider, no key needed)
│   ├── src/
│   │   ├── main.py            ← FastAPI app factory, routes, error ladder
│   │   ├── config.py          ← pydantic-settings, https-only, provider validation
│   │   ├── models.py          ← request/response schemas and hard limits
│   │   ├── gemini_service.py  ← Gemini HTTP provider (key only in x-goog-api-key)
│   │   ├── grounding_service.py ← source extraction + grounding honesty rules
│   │   └── safety.py          ← input/output safety and secret-redacting log filter
│   └── tests/                 ← 183 tests: health, limits, grounding, safety, vision
└── android/
    ├── settings.gradle.kts    build.gradle.kts   gradle.properties
    ├── gradlew  gradlew.bat   gradle/wrapper/   ← genuine Gradle 8.9 wrapper
    └── app/
        ├── build.gradle.kts   ← AGP 8.7.3, Kotlin 2.0.21, compile/target 35, minSdk 26
        ├── proguard-rules.pro
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── java/com/nexora/robot/       ← 38 Kotlin source files
            │   └── res/                         ← layout, values, drawable, xml, mipmap
            └── test/java/com/nexora/robot/      ← 17 JUnit 4 test classes (3,705 lines, pure JVM)
```

---

## Backend

### Run the tests (no Gemini key required)

```bash
cd backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pytest -q
```

The suite covers: `/health` staying safe and honest with no key configured (`ready: false`),
request size/type validation, the stable response shape with a mocked provider, grounding
metadata appearing **only** when the provider reports real grounding, secret redaction in logs,
and the `/v1/vision` single-frame path.

### Run it locally

```bash
cd backend
. .venv/bin/activate
uvicorn src.main:app --host 0.0.0.0 --port 8000
curl -s http://127.0.0.1:8000/health
```

Without a key, `/health` answers `200` with `"ready": false` and `POST /v1/converse` answers
`503 provider_not_configured` — a deliberate, honest failure instead of a fake reply.

To exercise a full conversation locally without a key, run the built-in stub provider:

```bash
AI_PROVIDER=stub uvicorn src.main:app --host 0.0.0.0 --port 8001
```

Stub replies say so in the text and never claim grounding or live data.

### Environment variables

| Variable            | Default                   | Meaning                                        |
| ------------------- | ------------------------- | ---------------------------------------------- |
| `AI_PROVIDER`       | `gemini`                  | `gemini` or `stub` (local testing only)        |
| `GEMINI_MODEL`      | `gemini-2.5-flash`        | Model id sent to the Gemini API                |
| `GEMINI_API_KEY`    | *(empty)*                 | **Secret.** Dashboard only. Never committed.   |
| `GEMINI_BASE_URL`   | `https://generativelanguage.googleapis.com/v1beta` | HTTPS only |
| `MAX_REPLY_TOKENS`  | `320`                     | Cap on generated reply tokens                  |
| `MAX_INPUT_CHARS`   | `1200`                    | Cap on user text; over it → `422`              |
| `ALLOWED_ORIGINS`   | *(empty)*                 | Comma list; empty means CORS stays disabled    |
| `GROUNDING_ENABLED` | `true`                    | Server-side Google Search grounding            |
| `MAX_IMAGE_BYTES`   | `1500000`                 | Decoded image cap for `/v1/vision`             |
| `MAX_GROUNDING_SOURCES` | `5`                   | Max source links returned per turn             |
| `PROVIDER_TIMEOUT_SECONDS` | `20`               | Upstream timeout; over it → `504 provider_timeout` |
| `ENVIRONMENT` / `LOG_LEVEL` | `production` / `INFO` | Runtime label and log level             |

Full field-by-field contract: [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md).

---

## Android app

* Native Kotlin — no WebView shell. `minSdk 26`, `compileSdk`/`targetSdk 35`,
  AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9 wrapper (JDK 17 required to build).
* `applicationId` / `namespace`: **`com.nexora.robot`**, app label **NEX**.
* English by default, Hindi/Hinglish supported (`res/xml/locales_config.xml`, an in-app
  language switch, Devanagari wake phrases and Devanagari offline answers).
* Voice: explicit microphone consent → wake on **"Hello NEX"** → *"Hi! I'm listening."* →
  continuous conversation with no repeated wake phrase, a ≥ 850 ms post-speech settle window,
  calm handling of ordinary silence (~650 ms to a new turn) and bounded backoff
  **1.2 s → 2.5 s → 5 s → 8 s** with an honest `VOICE SERVICE RECOVERING — PLEASE WAIT`.
* 56 screen-only animations in `animation/AnimationCatalog.kt` (pure Kotlin, no `Context`),
  drawn by `ui/NexAnimationView.kt` on a `Choreographer` loop, with a reduced-motion path for
  every entry, no speaking-mouth animation and no strobing.
* Privacy: `RECORD_AUDIO` after explicit consent, `CAMERA` only for *"What is this?"* with a
  visible in-use indicator, `ACCESS_COARSE_LOCATION` only for a direct local weather/place ask,
  `INTERNET` + `ACCESS_NETWORK_STATE` for the backend, `usesCleartextTraffic="false"` and a
  TLS-only network security config. Nothing else is ever requested.

### Build it

```bash
cd android
./gradlew test            # 17 pure-JVM test classes, JUnit 4 only
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew lint            # lint report with abortOnError = true
```

Point the app at your own backend without editing source (HTTPS only):

```bash
./gradlew assembleRelease -PnexBackendUrl=https://your-service.onrender.com
```

The release build is unsigned on purpose — no keystore is stored in this repository. Sign it
yourself with `apksigner` or Android Studio.

Full permission reasoning: [`docs/PRIVACY_AND_PERMISSIONS.md`](docs/PRIVACY_AND_PERMISSIONS.md).
Manual phone test script: [`docs/PHYSICAL_DEVICE_QA.md`](docs/PHYSICAL_DEVICE_QA.md).

---

## Deployment

`render.yaml` is a Render **Blueprint**: it creates one Web Service named
`nex-companion-api` from `backend/`, builds with `pip install --no-cache-dir -r requirements.txt`,
starts with `uvicorn src.main:app --host 0.0.0.0 --port $PORT` and health-checks `/health`.
`GEMINI_API_KEY` is `sync: false`, so Render prompts for it in the dashboard.

Step-by-step instructions, the exact verification commands and the rollback plan are in
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

---

## Honest project status

| Item | Status |
| ---- | ------ |
| Backend source | Complete |
| Backend tests | **183 passed** locally (CPython 3.13; `python_requires >= 3.11`; image builds on `python:3.11-slim`) |
| Backend live check | `uvicorn` started locally; `/health` and `/v1/converse` verified with `curl` (no key → `ready:false`, `503`; `AI_PROVIDER=stub` → real `200` replies) |
| Android source | Complete (38 Kotlin files, 6,817 lines + resources + manifest + Gradle) |
| Android APK / unit tests / lint | **Not run in this environment** — it provides JDK 11 only, with no Android SDK and no Gradle. Source created; Android APK/test/lint not run in this environment. |
| GitHub remote | Not pushed — no credentials exist in this environment. Local repository only; push commands below. |
| Render service | **Not deployed.** No Render credentials exist here, so `https://nex-companion-api.onrender.com` must be treated as *unverified* until you deploy it and `curl` its `/health`. |
| Secrets | None anywhere in the repository. `GEMINI_API_KEY` is dashboard-only. |
| Extra endpoint beyond the brief | `POST /v1/vision` was added so the app can describe one camera frame without holding a key — documented in [`docs/API_CONTRACT.md`](docs/API_CONTRACT.md) §4 and disclosed here |
| Coarse location disclosure | For a direct local weather/place question only, an approximate 2-decimal lat/lon (≤10 min old) is appended to that one turn's text and sent to the backend. Full reasoning: [`docs/PRIVACY_AND_PERMISSIONS.md`](docs/PRIVACY_AND_PERMISSIONS.md) §4 |
| Known test-only warning | `StarletteDeprecationWarning: Using httpx with starlette.testclient is deprecated` (starlette 1.10.0). Affects tests only, left unpinned deliberately — see [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) §1.1 |

To publish the code yourself:

```bash
cd /home/user/nex-companion
git remote add origin https://github.com/USERNAME/nex-companion.git
git branch -M main
git push -u origin main
```

Then, in Render: **New → Blueprint**, pick the `nex-companion` repository, enter
`GEMINI_API_KEY` when prompted, deploy, and verify:

```bash
curl -s https://<your-service>.onrender.com/health
```

Only after that URL answers is the deployment real — and only then should the Android build be
pointed at it with `-PnexBackendUrl=`.
