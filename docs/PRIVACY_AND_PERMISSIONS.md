# NEX Privacy &amp; Permissions

NEX is a companion for students, often children. The privacy bar is therefore not "what the
Play policy allows" but "what a parent would accept if they read the source". This document
lists every permission NEX declares, exactly when each one may be requested, what data each one
can produce, and what NEX deliberately does not do.

**Nothing here is aspirational.** Each claim points at the file that enforces it.

---

## 1. Declared permissions — the complete list

`android/app/src/main/AndroidManifest.xml` declares exactly five:

| Permission | Type | Why | When it may be requested |
| ---------- | ---- | --- | ------------------------ |
| `INTERNET` | install-time | Reach the NEX backend over HTTPS | Never prompted (install-time) |
| `ACCESS_NETWORK_STATE` | install-time | Distinguish "offline" from "server problem" so NEX can tell the truth | Never prompted (install-time) |
| `RECORD_AUDIO` | runtime | Speech recognition for the voice companion | Only after the user turns voice mode on, and only after an explanation dialog |
| `CAMERA` | runtime | One-frame "What is this?" answers | Only after an explicit request — the button, or a recognised `VISION_REQUEST` utterance |
| `ACCESS_COARSE_LOCATION` | runtime | Local weather and "where am I" answers | Only for a direct weather/place question, only while in the foreground, only once explained |

`<uses-feature>` marks the microphone as required and camera/location/touchscreen as optional,
so the app is not filtered onto devices that cannot use a feature it only uses on request.

`<queries>` is limited to two intents — `android.speech.RecognitionService` and
`android.intent.action.TTS_SERVICE` — which is the minimum needed to detect whether a
recogniser and a TTS engine exist. It is not used to enumerate or fingerprint installed apps.

### Explicitly absent, and enforced as absent

`privacy/PermissionPolicy.kt` carries a `NEVER_REQUESTED` list of **17** permissions that NEX
will never ask for. `PermissionPolicyTest` pins that list entry by entry and asserts it does not
overlap `REQUESTABLE`, so the *policy* cannot drift silently. The manifest itself contains none
of them — verify with the `grep` in §11, since a JVM unit test cannot read the manifest:

contacts (`READ_CONTACTS`, `WRITE_CONTACTS`), SMS (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`),
call log and dialling (`READ_CALL_LOG`, `CALL_PHONE`), storage and media
(`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`),
accessibility (`BIND_ACCESSIBILITY_SERVICE`), notification listener
(`BIND_NOTIFICATION_LISTENER_SERVICE`), background recording
(`RECORD_AUDIO_BACKGROUND`, `FOREGROUND_SERVICE_MICROPHONE`), and precise or background
location (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`).

Also absent from the manifest: `POST_NOTIFICATIONS`, `READ_PHONE_STATE`, `BODY_SENSORS`,
`ACTIVITY_RECOGNITION`, `QUERY_ALL_PACKAGES`, any `<service>`, any `<receiver>` (including
`BOOT_COMPLETED`), any content provider, any exported component other than the single launcher
activity, and any foreground-service type. **There is no code path that can run while the app is
closed.**

`REQUESTABLE` is exactly the three runtime permissions above — microphone, camera, coarse
location — each with the documented moment it may be requested.

---

## 2. Microphone

### 2.1 One gate, one function

`PermissionPolicy.mayOpenMicrophone(...)` is the single authority. It evaluates, in order, and
returns the first reason that blocks:

1. `PERMISSION_NOT_GRANTED` — `RECORD_AUDIO` not granted
2. `VOICE_MODE_OFF` — the user has not enabled voice mode
3. `APP_IN_BACKGROUND` — the activity is not resumed
4. `SPEECH_IN_PROGRESS` — TTS is speaking
5. `TURN_IN_PROGRESS` — a recogniser turn is already open
6. `MICROPHONE_ALREADY_OPEN` — the recogniser is already listening
7. `AUDIO_ROUTE_SETTLING` — fewer than 850 ms since speech ended
8. otherwise `MicDecision.Allowed`

Every microphone open in `voice/SpeechController.kt` goes through this gate, and
`PermissionPolicyTest` asserts the priority order plus **all 64 combinations** of the boolean
inputs. No UI code and no callback can start recognition on its own.

### 2.2 Foreground only — stated, not implied

NEX **cannot** listen while it is force-stopped, swiped away or backgrounded, and the app never
claims it can. `PermissionPolicy` blocks on `APP_IN_BACKGROUND`; the lifecycle callbacks call
`controller.onBackground()` before any other work; and the on-screen hint (`wake_phrase_hint`)
says in plain language that the wake phrase works while NEX is open. There is no
`FOREGROUND_SERVICE_MICROPHONE`, no always-on hotword integration and no background work
manager, so there is nothing to keep the microphone alive.

At launch the app never auto-resumes the microphone, even if voice mode was previously on: it
shows a hint and waits for the user to press the button.

### 2.3 NEX never hears itself

* While `SPEAKING`, the microphone is hard-blocked and any recognition event arriving in that
  window is recorded as `ignoredEvent` rather than acted on (`VoiceReducerTest`).
* After speech ends, `TimingPolicy.micGateAfterSpeech(endedAtMs)` returns `endedAtMs + 850`, so
  the audio route has settled before the microphone reopens.
* Ordinary silence is not an error: `RecognizerErrorPolicy` distinguishes `NormalSilence`
  (`NO_MATCH`/`SPEECH_TIMEOUT` after at least 400 ms of listening) from a genuine failure, so a
  quiet room produces no "I did not catch that" and no retry burst. A new legal turn opens after
  roughly 650 ms of silence.

### 2.4 What leaves the device

Only the recognised **utterance text** (plus, for a direct local weather/place ask, an
approximate location string — see §4). Raw audio is never uploaded, never written to disk and
never cached: recognition runs through the platform `SpeechRecognizer`, which returns text.
Before anything is logged, `util/LogScrubber.kt` strips control characters, replaces e-mail
addresses and phone-shaped digit runs, replaces runs of six or more digits, replaces quoted
speech of 12+ characters, replaces credential shapes, collapses whitespace and truncates at 200
characters. `WakePhraseMatcher.findWakePhrase` returns only the two matched tokens, so even a
wake-word log line cannot carry the rest of what was said.

---

## 3. Camera

* Requested **only** after an explicit request: the "What is this?" button, or an utterance that
  `IntentDetector` classifies as `VISION_REQUEST`. `PermissionPolicy.mayOpenCamera` requires
  permission **and** foreground **and** an explicit request.
* Explained before the first system dialog (`camera_permission_rationale`); the
  `camera_permission_explained` flag means the explanation is not repeated forever.
* Visible in-use state: `VisionUiState.STARTING_CAMERA` → `CAMERA_ON` shows a live `PreviewView`
  and a red indicator; the camera hardware is unbound in a `finally` block **before** `ANALYZING`
  begins, so the indicator and the hardware can never disagree.
* Exactly one frame per request, held in memory (`captureSingleFrame`), sent once to
  `POST /v1/vision`, never written to storage, never thumbnailed, never reused. The backend
  returns `image_stored: false` and the app says so on screen and out loud.
* No image saving by default, and no image-saving code path at all.
* Backgrounding the app switches the camera off immediately.

---

## 4. Coarse location — the one disclosure that matters most

NEX asks for `ACCESS_COARSE_LOCATION` **only** when the user directly asks about local weather
or their own place, and only while the app is in the foreground. `ACCESS_FINE_LOCATION` and
`ACCESS_BACKGROUND_LOCATION` are not declared, and no location listener is ever registered —
there is no background location, no geofence, no tracking and no history.

What actually happens (`ui/MainActivity.kt: coarseLocationHint()`,
`voice/SpeechController.kt: outgoingText()`):

1. If the permission is not granted, the function returns `null` and the question is sent
   without any location. NEX then answers honestly that it cannot know the local weather.
2. If granted, the app reads a **passive last-known fix** (network → passive → GPS provider
   order). With coarse permission only, the platform will not return a fine fix; a
   `SecurityException` is caught and treated as "no location".
3. A fix older than 10 minutes is discarded.
4. The coordinates are rounded to two decimals (roughly 1.1 km) and formatted as `"lat,lon"`.
5. That string is appended to the outgoing text for **`WEATHER` and `LOCATION_QUERY` intents
   only**: `"<what you said> (approximate location from this device: 25.61,85.14)"`.
6. The log records only the marker `"approximate location attached to a direct local ask"` —
   never the coordinates.
7. The on-screen transcript keeps the user's own words only; the attached hint is never shown as
   something the user said.

Revoking the permission in system settings takes effect immediately: the next hint returns
`null`, and if the app is open the permission change stops voice and camera safely through the
same gates.

---

## 5. Voice (text-to-speech)

* Android platform TTS is the baseline (`voice/TtsVoice.kt`): pitch `1.15`, rate `0.94` — a warm,
  slightly bright, unhurried companion voice. There is no server voice dependency, and no
  optional server voice path is enabled in this build.
* **No real-person imitation.** No voice model of any named or recognisable person is used,
  requested or offered.
* **No sexualised or deceptively childlike voice.** The pitch/rate choice is a friendly adult
  companion register; nothing in the code or the copy presents NEX as a human child, a romantic
  partner or a medical/therapeutic professional. Copy avoids gendered Hindi forms and never
  claims feelings as facts.
* **Text and voice language are aligned**: `localeFor(language)` selects `hi-IN` for any reply
  language starting with `hi`, otherwise `en-US`, so a Hindi reply is spoken in Hindi.
* NEX never starts speaking while the microphone is open, and never opens the microphone while
  speaking (§2.3).

---

## 6. Local memory

`memory/LocalMemoryStore.kt` with `memory/PreferencesKeyValueStore.kt`:

| Guarantee | Implementation |
| --------- | -------------- |
| Explicit consent first | Every write returns `RefusedNoConsent` until `setConsent(true)`; consent defaults to `false` |
| Bounded | `MAX_ENTRIES = 24`; oldest entry evicted and reported (`MemoryWriteResult.Evicted`), keys ≤ 40 chars, values ≤ 240 chars |
| Visible | `snapshot()` returns every entry newest-first with its saved time; the memory panel in `MainActivity` shows it as text |
| Deletable | `delete(key)`, `deleteAll()` (keeps consent), and `setConsent(false)` which wipes everything and returns the count deleted |
| Cannot be corrupted or smuggled | Keys/values are normalised; the internal separator `\u001F` and control characters are removed; values are encoded as `"<value>\u001F<savedAtMs>"` and decoded with `lastIndexOf` |
| Never leaves the device | Stored in a private `SharedPreferences` file (`nex_local_memory`, `MODE_PRIVATE`); no upload path exists |
| Never backed up | `allowBackup="false"` plus `res/xml/data_extraction_rules.xml` excluding sharedpref/database/file from cloud backup **and** device transfer |
| No hidden profiling | Entries exist only because the user asked NEX to remember something; there is no inference, no scoring, no background collection and no permanent training use |

`PreferencesKeyValueStore` exposes only NEX-namespaced keys, so a snapshot cannot leak unrelated
preferences.

---

## 7. Network and transport

* `usesCleartextTraffic="false"` **and** `res/xml/network_security_config.xml` with
  `cleartextTrafficPermitted="false"` globally, system trust anchors only (a user-installed CA
  cannot intercept conversation traffic), and no `debug-overrides` block in the shipped source.
* `net/BackendUrlPolicy.kt` rejects any base URL that is blank, longer than 300 characters,
  malformed, not HTTPS, has no host, or carries `user:password@` credentials — **before** a
  socket opens, and without echoing the rejected value in a user-facing message. A corrupted
  stored preference falls back to the build default rather than crashing.
* No credentials in the app: no API key, no OAuth client, no token, no device id, no advertising
  id. `BackendUrlPolicyTest`, `PermissionPolicyTest` and a repository-wide grep enforce this.
* Server-side: one log line per request (method, path, status, elapsed ms, request id), a
  secret-redaction log filter, no request/response bodies logged, and no key in any response.

---

## 8. School documents / Drive — status and required policy

**This build contains no Drive, OAuth or document-retrieval code.** There is no Google sign-in,
no `google-services.json`, no Drive dependency and no file picker. The only image source in the
app is the camera, and the only storage the app touches is its own private preferences. This is
stated plainly because the original product brief mentioned a retrieval-only school-document
flow, and it would be dishonest to imply it exists.

If that flow is added later, these are non-negotiable requirements, and they are written here so
the review is easy:

1. **Retrieval-only.** Read scope for explicitly selected documents. No write, no create, no
   delete, no sharing, no "all files" scope — the user picks each document, one at a time.
2. **Nothing is enumerated.** No listing of the whole Drive, no indexing of file names, no
   caching of document lists on the device.
3. **Credentials stay server-side.** OAuth client id/secret and any refresh token live only in
   the backend environment, entered in the Render dashboard, never in the app, the APK, the
   repository or a ZIP.
4. **Content stays server-side.** Document text is processed in the backend and only a short
   answer crosses to the app. Documents are never persisted to the backend disk and never used
   for training.
5. **Revocable.** One visible control in the app removes the link, and revoking the token at the
   provider immediately ends access. The app must say honestly which documents are linked.
6. **Auditable.** Every retrieval logs a document id, size and duration — never content.

---

## 9. Offline behaviour

`offline/OfflineResponses.kt` decides what NEX says when there is no network, no server or no
key:

* Every reply carries `claimsInternet = false`, and `OfflineResponsesTest` asserts this for all
  intents in both languages. NEX never invents an online answer while offline.
* Nothing is silently queued for later upload. There is no outbox, no retry queue and no
  background worker in the app.
* What still works offline, truthfully: greetings and smalltalk, privacy help, permission help,
  study prompts (exactly three per ask, prefixed with *"We are offline, so here is a practice
  prompt."*), memory save/recall/list/delete, settings commands, the local focus timer, and the
  farewell/gratitude animations.
* What is refused offline, with a reason: weather, news, any time-sensitive query, location
  answers and image description. An offline notice (`offlineNotice`) is deliberately different
  copy from a server-unreachable notice (`serverUnreachable`), because they are different facts.

---

## 10. Data inventory

| Data | Created | Stored on device | Sent to the backend | Sent anywhere else |
| ---- | ------- | ---------------- | ------------------- | ------------------ |
| Raw audio | Platform recogniser, in memory | Never | Never | Never |
| Utterance text | Recogniser output | In-memory transcript for the session only | Yes, for cloud turns | No |
| Approximate location (2-decimal lat/lon) | Passive last-known fix | Never | Only for a direct weather/place ask | No |
| Camera frame | One in-memory JPEG | Never | Only for an explicit vision ask | No |
| Local memory entries | User asks NEX to remember | Private preferences, consent-gated, ≤24 entries | Never | Never |
| Settings (URL, language, reduced motion, consent flags) | User choices | Private preferences | Never | Never |
| Analytics / telemetry / ads | — | — | — | **None exist** |
| Server logs | Per request | Render log retention | Method, path, status, ms, request id, a 60-char scrubbed snippet | No |
| `GEMINI_API_KEY` | Dashboard | Never | Only in the `x-goog-api-key` header to the Gemini endpoint | No |

---

## 11. How to verify these claims yourself

```bash
cd /home/user/nex-companion

# 1. The complete permission list, straight from the manifest
grep -n "uses-permission\|uses-feature\|queries\|service\|receiver\|provider" \
  android/app/src/main/AndroidManifest.xml

# 2. Nothing that looks like a credential anywhere in the sources
grep -rniE "api[_-]?key|secret|token|password|client[_-]?id|BEGIN .*PRIVATE KEY" \
  android/app/src backend/src | grep -v "GEMINI_API_KEY\b" | grep -viE "no api key|never|not a|redact|scrub|secret-free"

# 3. Cleartext disabled and trust anchors limited
cat android/app/src/main/res/xml/network_security_config.xml

# 4. Backup and transfer disabled
grep -n "allowBackup\|dataExtractionRules" android/app/src/main/AndroidManifest.xml
cat android/app/src/main/res/xml/data_extraction_rules.xml

# 5. Run the privacy/memory tests (needs JDK 17 + Android SDK)
cd android && ./gradlew test --tests "*PermissionPolicyTest" --tests "*LocalMemoryStoreTest"
```

Backend-side verification (`curl` a deployed `/health`, confirm no key leaks in any response or
log line) is in [`DEPLOYMENT.md`](DEPLOYMENT.md). On-device checks, including a permission-revoke
test and a "does it listen in the background" test, are in
[`PHYSICAL_DEVICE_QA.md`](PHYSICAL_DEVICE_QA.md).
