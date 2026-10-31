# NEX Deployment Guide

Target: one Render **Web Service** named `nex-companion-api`, built from `backend/` in this
repository, driven by the Blueprint in [`render.yaml`](../render.yaml).

> ### Status of this document's deployment
> **The service has not been deployed.** The environment where this repository was written has
> no Render account, no Render API key and no GitHub credentials, so nothing was pushed and
> nothing was created. Everything below the "Verified locally" heading is *instruction*, not
> *evidence*. The Android build's default `https://nex-companion-api.onrender.com` is therefore a
> **placeholder that must be replaced or verified** before an APK is handed to anyone.
>
> Do not reuse or modify any pre-existing NEX/Nexora Render service: create a brand new one from
> this repository so the URL, logs and key are provably separate.

---

## 1. Verified locally (real output)

Run on 2026-10-31 in the authoring environment, from this exact source tree.

### 1.1 Tests

```console
$ cd backend
$ python -m venv .venv
$ . .venv/bin/activate
$ pip install -r requirements.txt
$ python -V
Python 3.13.16
$ pytest -q
........................................................................ [ 39%]
........................................................................ [ 78%]
.......................................                                  [100%]
=============================== warnings summary ===============================
.venv/lib/python3.13/site-packages/fastapi/testclient.py:1: StarletteDeprecationWarning:
  Using `httpx` with `starlette.testclient` is deprecated; install `httpx2` instead.
-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
183 passed, 1 warning in 0.88s
```

No `GEMINI_API_KEY` was set for any of these tests — the suite injects a fake provider through
`create_app(settings=..., provider=...)` and the fixtures in `backend/conftest.py`.

The single warning is benign and comes from the installed Starlette, not from NEX code:
`starlette 1.10.0` deprecates using `httpx` with `starlette.testclient`. It affects tests only;
the runtime does not use `TestClient`. It is left in place deliberately rather than pinned away,
so the dependency ranges stay honest.

### 1.2 Live server

```console
$ . .venv/bin/activate
$ uvicorn src.main:app --host 0.0.0.0 --port 8000        # no GEMINI_API_KEY
$ curl -s http://127.0.0.1:8000/health
{"status":"ok","provider":"gemini","model":"gemini-2.5-flash","ready":false,"grounding_enabled":true,"version":"1.0.0"}
$ curl -s -X POST http://127.0.0.1:8000/v1/converse -H 'content-type: application/json' -d '{"text":"hi"}'
{"error":{"code":"provider_not_configured","message":"My cloud brain is not configured yet, so I can only answer offline.","request_id":"…"}}   # 503
```

`ready: false` with a `200` health status is the designed behaviour: Render's health check stays
green, the app can show an honest "cloud brain not configured" notice, and no key is required to
observe it. Full request/response captures, including the offline stub provider run, are in
[`API_CONTRACT.md`](API_CONTRACT.md#6-verified-examples-captured-locally).

### 1.3 Exact versions tested

| Package | Version installed and tested | Constraint in `requirements.txt` |
| ------- | ---------------------------- | -------------------------------- |
| Python (CPython) | 3.13.16 | `>=3.11` (declared); image base `python:3.11-slim` |
| fastapi | 0.162.0 | `>=0.115,<1.0` |
| starlette | 1.10.0 | transitive via fastapi |
| pydantic | 2.14.2 | `>=2.7,<3.0` |
| pydantic-core | 2.50.2 | transitive |
| pydantic-settings | 2.16.2 | `>=2.3,<3.0` |
| httpx | 0.28.1 | `>=0.27,<1.0` |
| uvicorn | 0.54.0 | `>=0.30,<1.0` |
| anyio | 4.15.1 | transitive |
| pytest | 8.4.2 | `>=8.2,<9.0` |

`requirements.txt` is not pinned to exact versions on purpose (Render rebuilds should pick up
patch fixes). If you need reproducible builds, generate a lock file from the versions above:

```bash
cd backend && . .venv/bin/activate && pip freeze > requirements.lock.txt
```

Do **not** commit a `pip install`-generated dependency tree (site-packages, wheels, `.venv`) as
if it were NEX source; `.gitignore` already excludes it.

---

## 2. Prerequisites

* A GitHub repository containing this project (see §3).
* A Render account with permission to create a Web Service.
* A Gemini API key from Google AI Studio — created by you, for this service, and never shared
  with any other project.
* Nothing else. There is no database, no queue, no cache, no cron job and no persistent disk.

---

## 3. Publish the repository

The authoring environment had no GitHub credentials, so the repository exists locally only.
Create an **empty** repository named `nex-companion` on GitHub (no README, no licence, no
`.gitignore` — this project already has all three), then:

```bash
cd /home/user/nex-companion
git remote add origin https://github.com/USERNAME/nex-companion.git
git branch -M main
git push -u origin main
```

Before pushing, confirm nothing secret is staged:

```bash
git status --short
git ls-files | grep -iE "\.env|\.pem|\.key$|\.jks|\.keystore|local\.properties" || echo "no secret files tracked"
```

`.gitignore` already excludes `.env`, `.env.*`, `*.pem`, `*.key`, `*.jks`, `*.keystore`,
`local.properties`, `.venv/`, `venv/`, `__pycache__/`, `*.py[cod]`, `.gradle/`, `build/`,
`**/build/`, `.idea/`, `*.iml`, `node_modules/` and `.DS_Store`.

---

## 4. Deploy with the Blueprint (recommended)

1. Render dashboard → **New** → **Blueprint**.
2. Connect the GitHub account and select the **`nex-companion`** repository.
3. Render reads `render.yaml` and shows exactly one service:

   | Setting | Value |
   | ------- | ----- |
   | Type | Web Service |
   | Name | `nex-companion-api` |
   | Runtime | Python 3 (native, `rootDir: backend`) |
   | Root directory | `backend` |
   | Build command | `pip install --no-cache-dir -r requirements.txt` |
   | Start command | `uvicorn src.main:app --host 0.0.0.0 --port $PORT` |
   | Health check path | `/health` |
   | Env vars | `AI_PROVIDER=gemini`, `GEMINI_MODEL=gemini-2.5-flash`, `GEMINI_API_KEY` *(manual)*, `MAX_REPLY_TOKENS=320`, `MAX_INPUT_CHARS=1200`, `ALLOWED_ORIGINS=""` |

4. **Apply** / **Create**. Render prompts for `GEMINI_API_KEY` because it is `sync: false`.
   Paste the key **in the dashboard only**. It is never written to this repository, a `.env`
   file, a ZIP, the Android source or an APK, and it must never be pasted into chat, an issue, a
   commit message or a log.
5. Wait for the first deploy to finish, then verify (§6).

### Deploying with Docker instead

`backend/Dockerfile` exists for parity in any container runtime (`python:3.11-slim`, non-root
`nex` user, `EXPOSE 8080`, a `HEALTHCHECK` against `/health`, uvicorn bound to `0.0.0.0:${PORT}`).
To use it on Render, create a Web Service with **Runtime: Docker**, root directory `backend`, and
the same environment variables. The native Python runtime in `render.yaml` is the supported path.

### Manual service (if you skip the Blueprint)

Create a Web Service by hand with exactly the settings in the table above, from the same
repository and root directory. The Blueprint is preferred because it makes the configuration
reviewable in version control.

---

## 5. Environment variables

| Variable | Default | Set on Render? | Notes |
| -------- | ------- | -------------- | ----- |
| `AI_PROVIDER` | `gemini` | yes (`gemini`) | `stub` is for local testing only; it answers without a key and labels every reply as stub output |
| `GEMINI_MODEL` | `gemini-2.5-flash` | yes | Must be a model your key can call; a wrong id surfaces as `422/502 provider_rejected` |
| `GEMINI_API_KEY` | *(none)* | **yes, manually** | `SecretStr`; never logged, never returned, redacted from log records |
| `GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta` | no | Validated: HTTPS only, or startup fails fast |
| `GROUNDING_ENABLED` | `true` | no | Turns Google Search grounding on/off server-side |
| `MAX_REPLY_TOKENS` | `320` | yes | Reply length budget (≤ 4096) |
| `MAX_INPUT_CHARS` | `1200` | yes | Per-turn input cap (≤ 8000 hard ceiling) |
| `MAX_IMAGE_BYTES` | `1500000` | no | Decoded cap for `/v1/vision` |
| `MAX_GROUNDING_SOURCES` | `5` | no | Max source links per turn |
| `PROVIDER_TIMEOUT_SECONDS` | `20` | no | Upstream timeout → `504 provider_timeout` |
| `ALLOWED_ORIGINS` | `""` | yes (empty) | Empty = CORS disabled. The Android app needs no CORS |
| `ENVIRONMENT` | `production` | no | Label only, echoed nowhere public |
| `LOG_LEVEL` | `INFO` | **recommended: `WARNING`** | At `INFO` a 60-character scrubbed snippet of each user turn is logged; `WARNING` removes user text from logs entirely while keeping errors |

Render injects `PORT`; the start command binds `0.0.0.0:$PORT` as required. Do not hardcode a
port.

---

## 6. Post-deploy verification (do all of these)

```bash
BASE="https://<your-service>.onrender.com"     # the real URL Render assigned

# 1. Health — expect 200 and "ready": true once the key is saved
curl -s "$BASE/health" | python3 -m json.tool

# 2. A real conversation turn — expect 200 with a reply and no sources unless grounded
curl -s -X POST "$BASE/v1/converse" -H 'content-type: application/json' \
     -d '{"text":"Explain photosynthesis in two sentences for a 12 year old."}'

# 3. A live-information turn — expect grounded:true with https sources, or an explicit
#    "answered from my own knowledge" note. Never invented sources.
curl -s -X POST "$BASE/v1/converse" -H 'content-type: application/json' \
     -d '{"text":"What is the weather in Patna today?","needs_current_info":true}'

# 4. Hindi — expect "language":"hi" and a Hindi reply
curl -s -X POST "$BASE/v1/converse" -H 'content-type: application/json' \
     -d '{"text":"प्रकाश संश्लेषण क्या है?","language":"hi-IN"}'

# 5. Limits — expect 422 request_too_large
python3 -c "import json;print(json.dumps({'text':'a'*1500}))" > /tmp/big.json
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/v1/converse" \
     -H 'content-type: application/json' --data @/tmp/big.json

# 6. Security headers
curl -s -D - -o /dev/null "$BASE/health"

# 7. No secret in any response body or header
curl -s "$BASE/health" "$BASE/" | grep -iE "AIza|api_key|apikey|secret|token" && echo "LEAK!" || echo "clean"
```

Then, in the Render dashboard:

* **Logs** — confirm only `startup {...}`, `http method=… status=… ms=… rid=…`, and
  `converse rid=… chars=… lang=… needs_current=… injection=…` lines. No key, no headers, no
  stack traces, no request bodies.
* **Events** — the health check is passing (`/health` → 200).
* **Environment** — `GEMINI_API_KEY` is present and shown masked.

Expected health output once the key is saved:

```json
{
  "status": "ok",
  "provider": "gemini",
  "model": "gemini-2.5-flash",
  "ready": true,
  "grounding_enabled": true,
  "version": "1.0.0"
}
```

Free-tier note: the service spins down after inactivity, so the first request can take ~50
seconds. That is a cold start, not a failure — the Android app's 40-second call timeout will
report it honestly as a server problem rather than inventing an answer. A paid instance avoids
it.

---

## 7. Point the Android app at the service

The app has **no** key, so the only thing it needs is the HTTPS base URL. Three ways, in order
of preference:

1. **Build property (recommended)** — baked into `BuildConfig.BACKEND_BASE_URL`:

   ```bash
   cd android
   ./gradlew assembleRelease -PnexBackendUrl=https://<your-service>.onrender.com
   ```

2. **In-app setting** — the backend URL field validates through `BackendUrlPolicy` on write and
   silently keeps the previous value if the new one is not clean HTTPS.

3. **Default** — `android/app/build.gradle.kts` defaults to
   `https://nex-companion-api.onrender.com`. That URL is unverified until you deploy. If your
   service gets a different name, change the default or always pass `-PnexBackendUrl`.

An invalid stored URL can never crash the app: `MainActivity.buildApiClient()` catches the
`IllegalArgumentException` and falls back to the build default.

Then verify end to end on a real phone with [`PHYSICAL_DEVICE_QA.md`](PHYSICAL_DEVICE_QA.md).

---

## 8. Operations

| Task | How |
| ---- | --- |
| Update code | Push to `main`; the Blueprint auto-deploys |
| Roll back | Render → service → **Deploys** → previous successful deploy → **Rollback** |
| Rotate the key | Create a new Gemini key, update `GEMINI_API_KEY` in the dashboard, deploy, then delete the old key at Google |
| Change limits | Edit `MAX_INPUT_CHARS` / `MAX_REPLY_TOKENS` env vars — no code change, no rebuild of the app |
| Disable grounding | `GROUNDING_ENABLED=false`; `/health` then reports `grounding_enabled: false` and `sources` is always `[]` |
| Inspect failures | Logs carry `rid=<request id>`; the same id is returned to the app in `error.request_id` and in the `x-request-id` header |
| Scale | One instance is enough for a single companion app; the service is stateless |

There is no database to migrate, no cache to invalidate and no persistent disk to back up. The
service is stateless by design: every request is independent, and nothing about a user is stored
server-side.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
| ------- | ----- | --- |
| `/health` returns `"ready": false` | `GEMINI_API_KEY` missing, blank or whitespace-only | Save a non-blank key in the dashboard and redeploy |
| `503 provider_not_configured` on `/v1/converse` | Same as above, or the key was rejected upstream (401/403) | Check the key, its enabled APIs and its quota |
| `504 provider_timeout` | Upstream slower than `PROVIDER_TIMEOUT_SECONDS` | Raise the value (≤ 120) or retry; the app reports it honestly |
| `503 provider_rate_limited` | Upstream 429 | Wait, or raise the key's quota |
| `502 provider_rejected` | Upstream 5xx or unreadable JSON | Retry; check the Render log for `provider http error … status=` |
| `422 provider_rejected` | Upstream 4xx other than 401/403/429 — usually a bad `GEMINI_MODEL` | Set a model id your key can call |
| `422 blocked_by_safety` | Provider-side safety block | Expected for some prompts; NEX says "let us talk about something else" |
| `413 payload_too_large` | Body over 3 MB | Client bug — the app caps text at 1,200 characters |
| Deploy fails on build | Root directory not `backend`, or a stale build command | Use the Blueprint, or set `rootDir: backend` and the exact build command |
| Health check fails but the app works | Start command not binding `0.0.0.0:$PORT` | Use `uvicorn src.main:app --host 0.0.0.0 --port $PORT` |
| App says "offline" though the service is up | Wrong/unverified base URL, or a cold start over 40 s | Check the in-app URL field; warm the service; see §7 |

---

## 10. Security checklist before going live

- [ ] `GEMINI_API_KEY` exists **only** in the Render dashboard (never in `render.yaml`, a
      `.env`, a commit, a ZIP, an issue, a screenshot or the APK).
- [ ] `git ls-files` shows no `.env`, key, keystore or `local.properties`.
- [ ] `render.yaml` still has `sync: false` for `GEMINI_API_KEY`.
- [ ] `ALLOWED_ORIGINS` is empty (the app is native; CORS is not needed).
- [ ] `LOG_LEVEL=WARNING` if you do not want any user text in logs.
- [ ] `/health` verified over HTTPS on the real Render URL, and `ready` is `true`.
- [ ] A real `/v1/converse` turn verified, and sources appear only when genuinely grounded.
- [ ] Render logs reviewed: no key, no bodies, no tracebacks.
- [ ] The Android build was made with `-PnexBackendUrl=https://<real-url>` and tested on a
      physical device.
- [ ] This is a **new** service — no old NEX/Nexora service, URL or key was reused or edited.
