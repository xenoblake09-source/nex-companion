# NEX Architecture

This document explains how the two halves of NEX fit together, why the boundaries are where
they are, and which guarantees each boundary exists to enforce.

* [`API_CONTRACT.md`](API_CONTRACT.md) — the wire contract between them.
* [`PRIVACY_AND_PERMISSIONS.md`](PRIVACY_AND_PERMISSIONS.md) — the permission reasoning.
* [`DEPLOYMENT.md`](DEPLOYMENT.md) — how the backend is deployed and verified.
* [`PHYSICAL_DEVICE_QA.md`](PHYSICAL_DEVICE_QA.md) — manual testing on a real phone.

---

## 1. System overview

```
                 ┌──────────────────────────── Android app (com.nexora.robot) ────────────────────────────┐
                 │                                                                                        │
  user speech ─► │ SpeechRecognizer ─► WakePhraseMatcher ─► IntentDetector ─┐                             │
                 │                                                            │                           │
                 │                            VoiceEvent ─► VoiceReducer ─► VoiceState ─► MainActivity    │
                 │                                              │                │            │           │
                 │                                              │                └─► AnimationDirector   │
                 │                                              │                        └─► NexAnimationView (56 animations)
                 │                                              │                                        │
                 │                            TtsVoice ◄────────┘   LocalMemoryStore (consent-gated)     │
                 │                                                                                        │
                 │                            VisionCoordinator ─► CameraSessionController (1 frame)     │
                 │                                     └─────────► RemoteVisionAnalyzer                   │
                 │                                                                                        │
                 │                            NexApiClient (OkHttp, HTTPS only, no credentials)           │
                 └──────────────────────────────────────────────┬─────────────────────────────────────────┘
                                                                │ TLS 1.2+
                                            ┌───────────────────▼────────────────────┐
                                            │ FastAPI  (backend/src/main.py)         │
                                            │  • request context + secret-free logs  │
                                            │  • schema limits (models.py)           │
                                            │  • safety.py: normalise, inject, scrub │
                                            │  • grounding_service.py: honesty rules │
                                            │  • gemini_service.py: provider adapter │
                                            └───────────────────┬────────────────────┘
                                                                │ x-goog-api-key (server side only)
                                                    ┌───────────▼───────────────┐
                                                    │ Gemini 2.5 Flash REST API │
                                                    │ + Google Search grounding │
                                                    └───────────────────────────┘
```

Two invariants shape everything else:

1. **Secrets live only in the backend process.** The app has no key, no OAuth client id, no
   signing material and no device identifier to leak. A decompiled APK contains nothing worth
   stealing.
2. **Decisions live in pure code.** The conversation state machine, the retry policy, the
   permission gates, the intent detector, the animation catalogue and the grounding honesty
   rules are plain Kotlin objects or plain Python functions with no framework dependency, so
   they are tested directly instead of being inferred from UI behaviour.

---

## 2. Backend

### 2.1 Layering

| Layer | File | Responsibility |
| ----- | ---- | -------------- |
| App factory & routes | `src/main.py` | `create_app(settings, provider)`, middleware, exception handlers, `/`, `/health`, `/v1/converse`, `/v1/vision` |
| Settings | `src/config.py` | `Settings` (pydantic-settings, env-backed), validation (`AI_PROVIDER`, HTTPS-only base URL), derived limits, `public_snapshot()` |
| Schemas | `src/models.py` | Strict request/response models (`extra="forbid"`), hard ceilings, `SourceRef` |
| Safety | `src/safety.py` | Text normalisation, prompt-injection heuristics, system instruction, reply sanitising, secret scrubbing, live-claim detection, log redaction |
| Grounding | `src/grounding_service.py` | `is_grounded`, `extract_sources`, `grounding_summary`, `enforce_grounding_honesty` |
| Provider | `src/gemini_service.py` | `GeminiHttpProvider` (REST), `StubProvider` (offline development), `ProviderError` hierarchy, payload builders, response parsing |

`create_app()` takes optional `settings` and `provider` arguments so tests can inject a fake
provider and a deterministic configuration without touching the environment or the network.

### 2.2 Request lifecycle

1. **Body bound** — a `Content-Length` above `MAX_BODY_BYTES` (3 MB) is rejected with
   `413 payload_too_large` before parsing.
2. **Request context** — middleware assigns a request id (a client-supplied `x-request-id` is
   sanitised to `[A-Za-z0-9._-]` and truncated), times the call, and writes exactly one log
   line: method, path, status, elapsed milliseconds and the request id. Never a body.
3. **Schema validation** — pydantic enforces types, lengths, patterns and `extra="forbid"`.
   Failures become `422 invalid_request` with field-level details that never echo the value.
4. **Business limits** — the effective input cap (`MAX_INPUT_CHARS`, never above the hard
   8,000-character schema ceiling) produces `422 request_too_large`; blank text produces
   `422 invalid_request`.
5. **Readiness** — no usable key means `503 provider_not_configured`, with copy the app can
   read aloud. The service stays up and `/health` keeps answering.
6. **Safety** — `normalize_text` removes invisible/control characters and collapses
   whitespace; `looks_like_injection` flags instruction-smuggling so the system instruction can
   harden itself; `redact_for_log` keeps only a short scrubbed snippet in logs.
7. **Provider call** — `GeminiHttpProvider` POSTs to
   `{base}/models/{model}:generateContent` with the key **only** in the `x-goog-api-key`
   header, and includes `tools: [{"google_search": {}}]` only when grounding is both enabled
   and plausibly useful.
8. **Reply post-processing** — `sanitize_reply` (length + control characters),
   `scrub_secrets` (the configured key and common credential shapes), then
   `enforce_grounding_honesty`: sources are returned **only** when the provider reported real
   grounding metadata, and an ungrounded reply that still claims live information gets an
   explicit correction appended.
9. **Response headers** — `x-request-id`, `x-content-type-options: nosniff`,
   `referrer-policy: no-referrer`, `cache-control: no-store`.

### 2.3 Error ladder (in evaluation order)

| # | Condition | Status | `error.code` |
| - | --------- | ------ | ------------ |
| 1 | Body larger than 3 MB | 413 | `payload_too_large` |
| 2 | Schema violation (type, pattern, hard length, unknown field) | 422 | `invalid_request` |
| 3 | Text blank after stripping | 422 | `invalid_request` |
| 4 | Text longer than the effective input cap | 422 | `request_too_large` |
| 5 | Bad base64 / empty image / oversize image (vision only) | 422 | `invalid_image`, `image_too_large` |
| 6 | No usable provider key | 503 | `provider_not_configured` |
| 7 | Upstream timeout | 504 | `provider_timeout` |
| 8 | Upstream 401/403 (key rejected) | 503 | `provider_not_configured` |
| 9 | Upstream 429 | 503 | `provider_rate_limited` |
| 10 | Upstream 5xx / unreadable JSON | 502 | `provider_rejected` |
| 11 | Other upstream 4xx | 422 | `provider_rejected` |
| 12 | Transport failure (DNS, TLS, connection reset) | 503 | `provider_unreachable` |
| 13 | Provider-side safety block | 422 | `blocked_by_safety` |
| 14 | Anything unexpected | 500 | `internal_error` (no traceback, no internals) |

Every non-200 body has the same envelope: `{"error": {"code", "message", "request_id"[, "details"]}}`.
Messages are written to be read aloud to a user; technical detail stays in `ProviderError.detail`
on the server and never crosses the wire.

### 2.4 Logging and secrets

* One logger (`nex`) with a `SecretRedactionFilter` that removes the configured key and
  credential-shaped tokens (`AIza…`, `sk-…`, `ghp_…`, JWTs, `xox…`) from any record.
* No request or response bodies are logged. Conversation content appears only as a short
  scrubbed snippet (`redact_for_log`, 60 characters) and only at `INFO`.
* Tracebacks are logged server-side for 500s but never returned to clients.
* `/health` exposes no environment data: only provider name, model id, readiness, grounding
  flag and version.

### 2.5 Stub provider

`AI_PROVIDER=stub` runs a deterministic offline provider so the whole contract can be
exercised with no key and no network. Stub replies identify themselves as stub output, report
`grounded: false` and `sources: []`, and say plainly that live information is unavailable.
`is_ready` returns `true` for the stub so end-to-end flows can be tested; production defaults
to `gemini`, where `is_ready` is true only with a non-blank key.

---

## 3. Android app

### 3.1 Package layout (38 Kotlin files, 6,817 lines)

| Package | Files | Role |
| ------- | ----- | ---- |
| root | `NexApplication` | Process start: log verbosity only. No services, no workers, no analytics. |
| `animation` | `AnimationSpec`, `AnimationCatalog`, `AnimationDirector` | The 56-animation catalogue (pure data) and the state/intent → animation decision table. |
| `language` | `UserIntent`, `TextNormalizer`, `IntentDetector`, `CurrentInfoDetector` | Script-aware normalisation, intent classification (English/Hindi/Hinglish), live-information signalling. |
| `voice` | `ConversationState`, `VoiceEvent`, `VoiceState`, `VoiceReducer`, `TimingPolicy`, `RetryPolicy`, `RecognizerErrorPolicy`, `WakePhraseMatcher`, `VoiceMessages`, `SpeechController`, `TtsVoice`, `Transcript`, `UiSignal` | The conversation state machine and everything physical around it: microphone, recogniser, TTS, backend turn, transcript, UI signals. |
| `privacy` | `PermissionPolicy` | Single authority for the microphone/camera/location gates and the never-requested list. |
| `memory` | `KeyValueStore`, `LocalMemoryStore`, `PreferencesKeyValueStore` | Consent-gated, bounded, deletable on-device memory. |
| `offline` | `OfflineResponses`, `FocusTimer` | Truthful offline answers and the local focus timer. |
| `net` | `ConverseModels`, `BackendUrlPolicy`, `NexApiClient` | HTTPS-only client, URL policy, stable result types. |
| `vision` | `VisionAnalyzer`, `CameraSessionController`, `VisionCoordinator` | One explicitly requested frame: capture → unbind → analyse → drop. |
| `ui` | `MainActivity`, `NexAnimationView` | The only screen, and the `Choreographer`-driven renderer for all 56 animations. |
| `util` | `AppSettings`, `LogScrubber`, `NexLog` | Preferences, log redaction, logging façade. |

### 3.2 Unidirectional conversation flow

```
     Android callbacks (recogniser, TTS, lifecycle, permissions)
                        │
                        ▼
                 VoiceEvent  ──►  VoiceReducer.reduce(state, event, clock)  ──►  VoiceState
                        ▲                                                            │
                        │                                                            ├──► MainActivity (text, buttons, hints)
     SpeechController ──┘  (performs the physical work the new state asks for)       ├──► animationCue ──► NexAnimationView
                                                                                     └──► UiSignal ──► permissions, camera, notices
```

* `VoiceState` is an immutable data class; `VoiceReducer` is the **only** producer of new
  states. That makes every microphone rule checkable in a unit test.
* `SpeechController` owns `SpeechRecognizer`, `TextToSpeech`, the coroutine scope and the
  backend turn. It reads the reduced state and acts; it never invents transitions.
* `UiSignal` is the one-way door to Android widgets: the voice core asks the activity to run a
  camera turn, request a permission, show a notice or re-resolve reduced motion. No `View`
  object ever reaches the state machine.
* Events that the machine refuses (for example a recognition result arriving while NEX is
  speaking) are not dropped silently: the reducer records them in `ignoredEvent`, which is what
  the tests assert against.

### 3.3 The ten states

| State | Meaning | Microphone |
| ----- | ------- | ---------- |
| `IDLE` | Voice mode off. Nothing is recorded. | closed |
| `PREPARING_VOICE` | Consent given; recogniser/TTS initialising. | closed |
| `LISTENING_FOR_WAKE_PHRASE` | Foreground hunting for "Hello NEX". | open (gated) |
| `LISTENING_FOR_CONVERSATION` | Awake; no wake phrase needed between turns. | open (gated) |
| `RECOGNIZING` | A recognition turn is running. | open |
| `THINKING` | The utterance is with the server (or being answered offline). | closed |
| `SPEAKING` | TTS is playing. | closed, hard-blocked |
| `RECOVERING_VOICE_SERVICE` | Bounded backoff after a real failure. | closed |
| `PAUSED_OR_PERMISSION_BLOCKED` | Background, permission revoked, voice off, or no engine. | closed |
| `ERROR` | Fatal, non-retryable condition; user action required. | closed |

### 3.4 Timing and retry policy (`TimingPolicy`, `RetryPolicy`)

| Constant | Value | Why |
| -------- | ----- | --- |
| `POST_SPEECH_MIC_SETTLE_MS` | 850 ms | The microphone never reopens until the audio route has settled after TTS, so NEX cannot hear itself. |
| `NEW_TURN_AFTER_SILENCE_MS` | 650 ms | Ordinary silence opens a fresh legal turn quickly, with no error copy. |
| `RECOGNITION_TURN_BUDGET_MS` | 12 s | A turn that runs longer than this is stalled and is recovered by the watchdog. |
| `IMMEDIATE_FAILURE_WINDOW_MS` | 400 ms | Below this, `NO_MATCH`/`SPEECH_TIMEOUT` mean the recogniser bailed out (a failure), not that the user stayed quiet. |
| `MIN_TURN_GAP_MS` | 120 ms | Minimum gap between turns, so restarts cannot spin. |
| `SPEECH_COMPLETION_GRACE_MS` | 30 s | Upper bound on waiting for a TTS completion callback before the watchdog intervenes. |
| `BACKOFF_MS` | 1200 / 2500 / 5000 / 8000 | Bounded ladder, capped at `MAX_BACKOFF_MS = 8000`. |
| `BURST_WINDOW_MS` | 20 s | A burst of failures expires and resets to attempt 1. |
| `MAX_ATTEMPTS_BEFORE_USER_PROMPT` | 4 | After four attempts NEX stops retrying silently and says `RECOVERY_GIVE_UP`. |

`RecognizerErrorPolicy` maps raw `SpeechRecognizer` codes (mirrored as constants with their
AOSP values: 1 network-timeout, 2 network, 3 audio, 4 server, 5 client, 6 speech-timeout,
7 no-match, 8 busy, 9 insufficient-permissions, 10 too-many-requests, 11 server-disconnected,
12 language-not-supported, 13 language-unavailable, 14 cannot-check-support) to one of
`NormalSilence`, or a `Failure` with a kind (`CLIENT`, `BUSY`, `NETWORK`, `PERMISSION`,
`UNAVAILABLE`, `OTHER`), a retryable flag and honest user copy. Permission and
unavailability failures are never retried.

### 3.5 Wake phrase

`WakePhraseMatcher` is a pure two-token scanner: an attention word (`hello`, `helo`, `hullo`,
`hallo`, `hi`, `hii`, `hey`, `ok`, `okay`, plus Devanagari `हैलो`, `नमस्ते`, `हे`, `ओके`, and any
four-plus-letter token one edit from `hello`) immediately followed by the name (`nex`, Devanagari
`नेक्स`, or a 3–5 letter token one Levenshtein edit away). A blocklist refuses the
near-neighbours `new`, `net`, `nee`, `sew`, `next` and `नेक्स्ट`, so classroom speech does not
wake NEX. `findWakePhrase` returns only the two matched tokens, never the whole utterance, so a
log line cannot carry what the user said.

The wake phrase is **foreground only**. A standard Android app cannot start recognition from a
force-stopped state without a privileged hotword integration, and NEX does not claim otherwise:
the UI states it plainly (`wake_phrase_hint`), and `PermissionPolicy` refuses the microphone
whenever `appInForeground` is false.

### 3.6 Animation system

* **`AnimationSpec`** — one animation: `id` (1–56), `name`, `slug`, `category`, `profile`,
  `palette`, `durationMs`, `loop`, `elementCount`, `speed`, `amplitude`, `flashCount`,
  `variant`, plus derived `reducedMotionDurationMs`/`reducedMotionAmplitude`, and the two
  product guarantees `screenOnly = true` and `animatesMouth = false`. Its `init` block rejects
  any spec that strobes (`flashCount > 1`), animates a mouth, is not screen-only, or whose
  reduced-motion variant is faster or larger than the normal one.
* **`AnimationCatalog`** — the 56 entries in order, indexed by id and slug, with
  `resolve(spec, reducedMotion)` returning a `ResolvedAnimation`. 14 categories and 27 motion
  profiles are all used; `variant` gives animations that share a profile visually distinct
  renderer paths (for example the seven `RADIAL_GLOW` entries differ by variant, palette,
  amplitude, duration and looping).
* **`AnimationDirector`** — `cueForState`, `cueForIntent`, `cueForWeather`, `cueForGrounding`,
  `specFor`, `isLooping`. `AnimationCue` is a 56-entry enum in bijection with the catalogue ids,
  so the UI can never request an animation that does not exist.
* **`NexAnimationView`** — a single `View` that draws all 56 animations with an exhaustive
  `when` over `MotionProfile` and 27 private draw helpers. It is driven by `Choreographer` and
  `SystemClock.uptimeMillis()` rather than `ValueAnimator`, so a system animator scale of 0
  cannot leave a frozen frame. It pauses when detached or not visible, and honours
  `setReducedMotion` at runtime (initialised from `Settings.Global.ANIMATOR_DURATION_SCALE`
  plus the in-app override).
* Speaking is expressed with eyes and light only — there is no mouth animation anywhere in the
  catalogue, and no entry flashes more than once.

### 3.7 Language support

`TextNormalizer` keeps letters from any script (Latin and Devanagari), digits, apostrophes and
hyphens, converts typographic quotes to ASCII and collapses whitespace. `IntentDetector`
classifies 25 intents with English, Hindi and Hinglish vocabulary, in a deliberate priority
order: vision → permission → privacy → memory (delete, list, recall, save) → settings → timer →
weather → news → music → location → subjects → time-sensitive → social → smalltalk → general.
`CurrentInfoDetector` adds the `needs_current_info` signal, matching single time-markers as
whole tokens (so "know" never looks like "now") and multi-word markers as phrases.
`OfflineResponses` provides English and Hindi text for every intent; the app also declares
`en`/`hi` in `res/xml/locales_config.xml` for per-app language on Android 13+.

### 3.8 Vision pipeline

1. The user asks ("What is this?" button, or a detected `VISION_REQUEST` utterance).
2. `MainActivity` checks `CAMERA`; if absent it explains and requests it (never before).
3. `VisionCoordinator` re-checks permission, binds CameraX (`STARTING_CAMERA` → `CAMERA_ON`,
   with the live `PreviewView` and a red dot as the visible in-use indicator).
4. **One** frame is captured into memory (`captureSingleFrame`), then the camera is unbound in
   a `finally` block **before** any analysis, so the indicator and the hardware agree.
5. `RemoteVisionAnalyzer` sends the JPEG once to `POST /v1/vision`; the backend replies with
   `image_stored: false` and the app says so out loud and on screen.
6. Nothing is written to storage, no thumbnail is kept, and the frame is eligible for garbage
   collection as soon as the turn ends.

### 3.9 Memory, offline and settings

* `LocalMemoryStore` is consent-gated (`RefusedNoConsent` before consent), bounded
  (`MAX_ENTRIES = 24`, keys ≤ 40 characters, values ≤ 240, oldest evicted and reported),
  normalising (control characters and the internal `\u001F` separator can never appear in a
  value), fully visible (`snapshot()` newest-first) and fully deletable (`delete`, `deleteAll`,
  and `setConsent(false)` which wipes everything and returns the count). `PreferencesKeyValueStore`
  is the only writer, into a private `SharedPreferences` file, and only ever exposes
  NEX-namespaced keys.
* `OfflineResponses` covers greetings, farewell, thanks, privacy help, permission help, study
  prompts, memory acknowledgements, settings commands and the local focus timer, and refuses
  live-information intents explicitly. Every reply has `claimsInternet = false`, which the tests
  enforce for all intents in both languages.
* `FocusTimerPolicy` parses durations in English and Hindi, clamps to 1 minute–3 hours
  (`MAX_DURATION_MS = 10_800_000`), and is driven by an injected clock so it is testable.
* `AppSettings` stores the backend URL (validated through `BackendUrlPolicy` on write), the
  language switch, the reduced-motion override, memory consent and the "camera explained once"
  flag. `allowBackup="false"` plus `res/xml/data_extraction_rules.xml` keep all of it out of
  cloud backup and device transfer.

### 3.10 Networking

`BackendUrlPolicy` rejects anything that is not a clean HTTPS URL (blank, over 300 characters,
malformed, no host, or carrying `user:password@`) **before** a socket is opened, and never
echoes the rejected value in a user-facing message. `NexApiClient` uses OkHttp with
connect/write/read/call timeouts of 8/15/25/40 seconds, sends no credentials, logs only sizes
and statuses through `NexLog`, and returns sealed results (`Success`, `Rejected`, `Unavailable`)
so the UI can tell the truth instead of guessing. `res/xml/network_security_config.xml` disables
cleartext globally, trusts only system CAs and has no debug overrides.

---

## 4. Testing strategy

| Layer | Framework | What is covered |
| ----- | --------- | --------------- |
| Backend | `pytest` (183 tests, `backend/tests/`) | Health without a key, input limits and types, response shape with a fake provider, grounding metadata only when the provider reports grounding, secret redaction, safety normalisation, vision validation |
| Android logic | JUnit 4 on the JVM (17 classes, `app/src/test/`) | Catalogue contract (exactly 56, ids, names, no strobe, no mouth, reduced motion), director decision tables, reducer transitions (silence vs failure, 850 ms gate, speaking blocks, permission/background/voice-off), retry ladder, error classification, wake phrase, intents, current-info signalling, normaliser, permission gates, offline honesty, memory consent/bounds/deletion, focus timer, URL policy, log scrubber |
| Android UI / hardware | Manual, on a real device | `PHYSICAL_DEVICE_QA.md` |

One framework per platform: JUnit 4 only on Android (`testOptions.unitTests.all { useJUnit() }`),
no JUnit 5 mixing, and no Robolectric — everything worth testing was written to be testable
without it. Classes that must touch Android (`NexAnimationView`, `SpeechController`,
`MainActivity`, `CameraSessionController`, `TtsVoice`, `NexApiClient` JSON parsing,
`PreferencesKeyValueStore`) are deliberately thin, and their logic was pushed into the pure
objects above.

> **Environment note (honest):** the backend suite was executed and passes. The Android suite
> was written but **not executed** in the authoring environment, which provides JDK 11 only —
> no Android SDK and no Gradle. Run `./gradlew test` with JDK 17 to execute it.

---

## 5. Threat model summary

| Threat | Mitigation |
| ------ | ---------- |
| Key theft from the APK | No key exists in the app; only the backend holds `GEMINI_API_KEY` |
| Key leakage in logs | Server-side redaction filter; Android `LogScrubber`; no bodies logged anywhere |
| Key leakage in a URL | `BackendUrlPolicy` refuses `user:pass@host`; the key travels only in a header server-side |
| Cleartext interception | `usesCleartextTraffic="false"`, TLS-only network security config, HTTPS-only URL policy |
| Covert background recording | No foreground service, no boot receiver, no background mic permission; the reducer blocks the microphone when `appInForeground` is false |
| Self-listening (TTS feedback) | Hard block while `SPEAKING` plus the 850 ms post-speech settle gate |
| Hidden profiling | Memory is consent-gated, bounded, visible and deletable; nothing is uploaded |
| Fake live answers | Sources only when grounding truly happened; ungrounded live claims get an explicit correction; offline replies never claim internet access |
| Camera abuse | Permission only on an explicit request; hardware unbound before analysis; visible indicator; nothing stored |
| Crash-loop on bad config | Invalid stored URL falls back to the build default; watchdogs recover stalled turns |

---

## 6. Extension points

* **New animation** — add one `spec(...)` entry with the next id, extend `SIZE`, add the
  matching `AnimationCue`, implement (or reuse via `variant`) a `MotionProfile` renderer branch.
  `AnimationCatalogTest` fails until ids stay contiguous and unique.
* **New intent** — add the `UserIntent` value, a vocabulary entry, a `cueForIntent` branch and
  an `OfflineResponses.forIntent` branch; the "every intent is reachable" tests fail otherwise.
* **New provider** — implement the provider protocol used by `create_app` (`converse`,
  `describe_image`, `aclose`) and register it in `SUPPORTED_PROVIDERS`.
* **Server-side document retrieval** — the retrieval-only school-document flow described in
  `PRIVACY_AND_PERMISSIONS.md` is designed to be added behind `POST /v1/documents` with
  credentials and content kept entirely server-side; the app never gains a Drive permission.
