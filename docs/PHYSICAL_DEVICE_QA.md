# NEX Physical Device QA

This is the manual test script for a **real phone**. It exists because the parts of NEX that
matter most — microphone timing, TTS/microphone interlock, camera hardware, recogniser failures,
permission revocation — cannot be proven on a JVM.

> ### Status
> **Not executed.** The authoring environment has no Android SDK, no Gradle, no emulator and no
> device (JDK 11 only). The Android unit tests were written but not run either. Everything below
> is the script to run yourself; nothing here is a claimed result.
>
> The logic behind most cases is already pinned by pure-JVM unit tests
> (`android/app/src/test/java/com/nexora/robot/…`); this document covers what only hardware can
> show.

---

## 1. What you need

| Requirement | Why |
| ----------- | --- |
| A physical Android phone, **API 26 (Android 8.0) or newer** | `minSdk 26`. Emulators often lack a working `SpeechRecognizer` and TTS voice data |
| A working speech recogniser and TTS engine (Google Speech Services + Speech & Text Engine, or the OEM equivalent) | NEX uses the platform services; it ships no ASR/TTS of its own |
| JDK **17** and Android SDK **35** installed, `ANDROID_HOME` set | AGP 8.7.3 requires JDK 17 |
| A deployed backend URL (`https://…`) | See [`DEPLOYMENT.md`](DEPLOYMENT.md). Until then use `AI_PROVIDER=stub` on a local server and expect stub-labelled replies |
| A quiet room, plus a noisy room and a second person for the echo test | Silence handling and self-listening are the highest-risk behaviours |
| `adb` with USB debugging | Log verification |

### Build and install

```bash
cd /home/user/nex-companion/android
./gradlew test                                  # 17 JUnit 4 classes must pass first
./gradlew assembleDebug -PnexBackendUrl=https://<your-service>.onrender.com
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c                                   # clear, then watch during testing
adb logcat | grep -Ei "nex|SpeechRecognizer|TextToSpeech|CameraX"
```

### The UI you are testing

One screen (`ui/MainActivity.kt`), top to bottom: title **NEX** / subtitle *Ultimate AI Pet &
Intelligent Companion*; the animation stage (long-press to step through the catalogue) with the
animation name under it; status line; recovery line; camera state line; permission status line;
backend status line; buttons **Start voice / Stop voice**, **What is this?**, **Preview all 56
animations**, **Memory**, **Check server**; switches **Hindi / Hinglish**, **Reduced motion**,
**Allow NEX to remember things on this device**; **Delete everything remembered**; the focus
timer line; a notice line; sources; the transcript; and two permanent honesty notes —
`privacyNote` and `wakePhraseNote` (*"Listening only while NEX is open. An ordinary app cannot
wake itself from a closed state."*).

---

## 2. Test matrix

Mark each row Pass / Fail. "Unit cover" names the JVM test that already pins the logic.

### A. Installation and first launch

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| A1 | Install, open Android Settings → Apps → NEX → Permissions | Only **Microphone**, **Camera** and **Location** are listed as possible, all denied. No contacts, SMS, call logs, storage/photos, accessibility, notifications | `PermissionPolicyTest` (17-entry `NEVER_REQUESTED`) |
| A2 | Launch NEX | Animation plays (calm idle breathing), transcript says *"Nothing said yet."*, voice is **off**, and **no permission dialog appears** | `VoiceReducerTest` (initial state) |
| A3 | Check the permanent note on screen | It states that listening only works while NEX is open | `VoiceMessagesTest` |
| A4 | Force-stop NEX, then say "Hello NEX" loudly | **Nothing happens.** No notification, no wake, no sound. The app never claims otherwise | `PermissionPolicyTest` (`APP_IN_BACKGROUND`) |
| A5 | Launch with voice mode previously on (kill and reopen) | NEX shows *"Voice mode was on when you last left. Press 'Start voice' when you are ready — NEX never listens by itself."* and the microphone stays closed until you press | `VoiceReducerTest` |

### B. Microphone consent and the wake phrase

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| B1 | Tap **Start voice** | An explanation dialog first (*"NEX listens only after you allow the microphone, and only while the app is open."*), then the system permission dialog | `PermissionPolicyTest` |
| B2 | Deny the permission | Status: *"Microphone permission is off, so NEX cannot listen."* State is paused/blocked; no crash; **no retry loop** | `VoiceReducerTest`, `PermissionPolicyTest` |
| B3 | Tap **Start voice** again after denying | Explanation + a path to system settings (*"Open settings"*), never a silent re-request storm | — |
| B4 | Grant, then wait | Status *"Listening for 'Hello NEX'."*, animation switches to the wake-listening cue | `AnimationDirectorTest` |
| B5 | Say **"Hello NEX"** | NEX replies *"Hi! I'm listening."* (spoken + on screen), animation acknowledgement, and immediately enters conversation mode | `WakePhraseMatcherTest`, `VoiceMessagesTest` |
| B6 | Say near-misses: "hello next", "hello net", "hello new", "Nexa", "hello 넥스" | **No wake.** No false acknowledgement | `WakePhraseMatcherTest` (blocklist `new/net/nee/sew/next/नेक्स्ट`) |
| B7 | Say plausible variants: "helo NEX", "hey NEX", "ok NEX", "हैलो नेक्स", "namaste NEX" | Wake succeeds | `WakePhraseMatcherTest` |
| B8 | Have a second person say "hello next" / "नेक्स्ट" nearby repeatedly (10×) | NEX stays calm; no wake, no error | `WakePhraseMatcherTest` |
| B9 | Say a normal sentence containing "hello" and "Nex" far apart ("hello, can you tell me about the Nexus empire?") | No wake (two-token adjacency required) | `WakePhraseMatcherTest` |

### C. Continuous conversation (no repeated wake phrase)

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| C1 | After B5, ask three questions in a row without saying "Hello NEX" | All three are answered; the wake phrase is **never** required again while awake | `VoiceReducerTest` |
| C2 | Ask something that needs live data ("What is the weather in Patna today?") | Either real sources are shown under **Sources (n)** with title + HTTPS URL, or NEX says it cannot check live information. **Never** a fabricated source list | backend `test_grounding.py`; `CurrentInfoDetectorTest` |
| C3 | Ask a general knowledge question | Answer, `Sources` shows *"No live sources were used for that answer."* | `test_grounding.py` |
| C4 | Ask "What did I just say?" | NEX answers from the visible transcript; the transcript shows your words verbatim | `Transcript` handling |
| C5 | Ask for a study prompt / a maths explanation / a science fact | Correct intent classification and matching animation cue | `IntentDetectorTest`, `AnimationDirectorTest` |
| C6 | Speak a long answer-worthy question (~40 words) | Full text is sent (within the 1,200-character cap); no truncation artefacts spoken aloud | backend `test_main.py` |
| C7 | Leave it awake for 10 minutes without interaction | NEX stays in listening mode, calm, no repeated prompts, no battery-drain warnings, no crash | — |

### D. Silence vs failure (the highest-risk behaviour)

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| D1 | Wake NEX, then say nothing for 20 seconds in a quiet room | NEX stays calm in listening mode. **No** "I did not catch that", **no** error, **no** recovery banner, **no** retry burst | `RecognizerErrorPolicyTest` (`NormalSilence`), `VoiceReducerTest` |
| D2 | After D1, speak normally | The turn is recognised; a new legal turn starts after ~650 ms of silence | `TimingPolicyTest` |
| D3 | Put the phone in a noisy room (fan/TV) and wake NEX | Occasional misrecognition is acceptable; a *persistent* failure must show the honest recovery banner, not fake answers | `RecognizerErrorPolicyTest` |
| D4 | Toggle airplane mode on, then ask a question | NEX says it is offline and gives a truthful local answer (study prompt / greeting / privacy help). **Nothing is queued for later upload** | `OfflineResponsesTest` |
| D5 | Turn airplane mode off and ask again | Normal cloud answer; any earlier retry burst counter is reset by the success | `RetryPolicyTest` |
| D6 | Kill the recogniser mid-turn (some OEMs: Settings → Apps → Speech Services → Force stop) | Bounded backoff 1.2 s → 2.5 s → 5 s → 8 s, banner *"VOICE SERVICE RECOVERING — PLEASE WAIT"*, and after 4 attempts *"My voice service is not recovering. Please toggle voice mode."* | `RetryPolicyTest` (ladder), `VoiceMessagesTest` |
| D7 | During D6 recovery | The wake phrase is **not** demanded again; recovery resumes the conversation directly | `VoiceReducerTest` |
| D8 | Wait 20+ seconds inside a failing burst | The burst expires and the next failure restarts at attempt 1 (1.2 s) | `RetryPolicyTest` (`BURST_WINDOW_MS`) |
| D9 | Device with no speech service (e.g. a fresh AOSP image without Google apps) | *"This device has no speech service I can use."* — no infinite retry | `RecognizerErrorPolicyTest` (`UNAVAILABLE`) |

### E. TTS / microphone interlock

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| E1 | Ask a question that produces a long answer and speak over NEX while it talks | Your speech is **not** captured; no self-recognition, no echo loop, no "you said …" of NEX's own voice | `PermissionPolicyTest` (`SPEECH_IN_PROGRESS`), `VoiceReducerTest` |
| E2 | Immediately after NEX finishes speaking, talk at once | If you speak within ~850 ms the mic is still closed (no capture); after that your turn is taken normally | `TimingPolicyTest` (`POST_SPEECH_MIC_SETTLE_MS = 850`) |
| E3 | Watch logcat during E1/E2 | No recognition result is accepted while `SPEAKING`; ignored events are recorded | `VoiceReducerTest` (`ignoredEvent`) |
| E4 | Ask NEX to speak Hindi, then English, alternately | Spoken language always matches the reply language (`hi-IN` voice for Hindi replies) | `TtsVoice.localeFor` |
| E5 | Listen to the voice quality | Warm, clear, slightly bright, unhurried. **Not** a child impersonation, **not** a real person's voice, no flirtatious or romantic framing | `VoiceMessagesTest` (copy) |
| E6 | Set the system TTS engine to a slow/fast one in Android settings | NEX remains intelligible and the mic gate still holds | — |

### F. Backgrounding, pause and revocation

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| F1 | Press Home mid-conversation | Voice stops immediately; the microphone indicator (system dot) disappears; no background audio | `PermissionPolicyTest` (`APP_IN_BACKGROUND`) |
| F2 | Return to the app | Voice stays **paused** until you press Start voice; nothing was recorded while away | `VoiceReducerTest` |
| F3 | While NEX is listening, revoke the microphone in Settings (split screen or quick settings) | Voice stops safely, status says the microphone is off, no crash, no retry | `PermissionPolicyTest`, `VoiceReducerTest` |
| F4 | Take a phone call during a conversation | Voice pauses and recovers honestly afterwards | — |
| F5 | Lock the screen mid-answer | Speech stops or completes; the microphone is closed; unlocking shows a paused/calm state | `PermissionPolicyTest` |
| F6 | Swipe NEX away from Recents, then check logcat and the system mic indicator for 60 s | No recording, no network calls, no process activity | — |
| F7 | Enable "Turn off voice mode" in the app, then check the mic indicator | Microphone closed, state `IDLE`, no listening | `PermissionPolicyTest` (`VOICE_MODE_OFF`) |

### G. Camera / "What is this?"

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| G1 | Fresh install: tap **What is this?** | Explanation dialog first (*"The camera is used only when you ask about something in front of you, for one question."*), then the system dialog | `PermissionPolicyTest.mayOpenCamera` |
| G2 | Deny camera | *"Camera is off"*; the app keeps working; NEX says it cannot look. Voice and everything else unaffected | `PermissionPolicyTest` |
| G3 | Grant, tap **What is this?** and point at a book | Live preview + red in-use indicator + *"Camera is on — for this question only. Nothing is saved."* → *"Looking at the picture…"* → one spoken description → *"That picture was used for this one answer and was not stored."* | `VisionCoordinator` states |
| G4 | Watch the indicator during G3 | The camera indicator turns **off** before the description arrives (hardware is unbound before analysis) | design invariant |
| G5 | Check the gallery and app storage afterwards | **No image saved anywhere** — not in Photos, not in app storage (`adb shell run-as com.nexora.robot ls files/ cache/` shows no image) | `PRIVACY_AND_PERMISSIONS.md` §3 |
| G6 | Tap **What is this?** 5 times in a row | Five separate single-frame turns; no continuous streaming; no heat/battery anomaly | — |
| G7 | Ask by voice: "What is this?" while awake | Same flow as G3, triggered by the detected `VISION_REQUEST` intent | `IntentDetectorTest` |
| G8 | Background the app during a camera turn | Camera switches off immediately | `PermissionPolicyTest` |
| G9 | Ask "what is this?" offline | Honest refusal: image description needs the server; nothing is queued | `OfflineResponsesTest` (VISION cue) |

### H. Coarse location

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| H1 | Ask "What is the weather here?" with location denied | Explanation dialog (*"Coarse location is asked for only when you want your local weather. Never in the background."*), then the system dialog | `PermissionPolicyTest` |
| H2 | Deny | *"That is completely fine. Tell me your city and I will answer from there."* The question is still sent, **without** coordinates | `PermissionPolicyTest`, `SpeechController.outgoingText` |
| H3 | Grant | *"Thank you. I will use only an approximate location, and only when you ask about your area."* | — |
| H4 | Ask "What is the weather here?" after granting | A local answer. In Settings, the location permission shows as **approximate only**; the system location indicator (if any) shows a coarse, one-shot read | `PermissionPolicyTest` |
| H5 | Ask a non-location question ("explain gravity") after granting | **No** location is read or sent; no location clause in the request | `PermissionPolicyTest` (WEATHER/LOCATION_QUERY only) |
| H6 | Check logcat for the whole session | Only `"approximate location attached to a direct local ask"` — never coordinates | `LogScrubberTest` |
| H7 | Confirm in Settings → Apps → NEX → Permissions | Location is "Allow only while using the app"; there is **no** "Allow all the time" option (no `ACCESS_BACKGROUND_LOCATION`) | manifest |
| H8 | Wait 15 minutes with a stale fix, then ask again | The stale fix (>10 min) is discarded; NEX asks for the city instead of using old data | `MAX_LOCATION_AGE_MS` |

### I. Language: English, Hindi, Hinglish

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| I1 | Default launch | English UI and English answers | — |
| I2 | Toggle **Hindi / Hinglish** | UI text and spoken answers switch to Hindi; animation cue acknowledges the switch | `IntentDetectorTest`, `OfflineResponsesTest` |
| I3 | Say "नमस्ते नेक्स" then "आज मौसम कैसा है?" | Wake + Hindi weather handling (with the H1–H4 location rules) | `WakePhraseMatcherTest` (Devanagari) |
| I4 | Say Hinglish: "NEX, mujhe photosynthesis samjhao" | Understood and answered in the language you used | `TextNormalizerTest`, `IntentDetectorTest` |
| I5 | Say "पाँच मिनट का टाइमर लगाओ" | Focus timer starts for 5 minutes; timer line counts down | `FocusTimerPolicyTest` |
| I6 | Say "मेरा नाम याद रखो" (memory) with consent off | NEX explains consent first; nothing is stored until the switch is on | `LocalMemoryStoreTest` |
| I7 | Use Android 13+ per-app language (Settings → Apps → NEX → Language) | `en` and `hi` are the only offered choices | `res/xml/locales_config.xml` |
| I8 | Check Hindi copy for gender | Impersonal phrasing; no gendered verb forms addressed to the user | copy review |

### J. Local memory

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| J1 | Tap **Memory** with consent off | Panel shows the consent switch and *"Stored only here, never uploaded, never used to train a model."*; asking NEX to remember something is refused politely | `LocalMemoryStoreTest` (`RefusedNoConsent`) |
| J2 | Enable consent, say "Remember that my exam is on 12 March" | Entry appears in the panel immediately with its saved time | `LocalMemoryStoreTest` |
| J3 | Save 30 items | The panel caps at **24**; the oldest are evicted and NEX says so — nothing fails silently | `LocalMemoryStoreTest` (`MAX_ENTRIES`, `Evicted`) |
| J4 | Try to save a very long note (>240 chars) | Truncated or refused with a clear message; the stored value stays readable | `LocalMemoryStoreTest` (`MAX_VALUE_CHARS`) |
| J5 | Ask "What do you remember?" | The full list is read out / shown — the user can always see everything stored | `LocalMemoryStoreTest` (`snapshot`) |
| J6 | Delete one item, then tap **Delete everything remembered** | Panel shows *"Nothing is remembered yet."*; the consent switch stays on | `LocalMemoryStoreTest` (`delete`, `deleteAll`) |
| J7 | Turn consent **off** | Everything is wiped and NEX reports how many items were deleted (*"Deleted N remembered items from this device."*) | `LocalMemoryStoreTest` (`setConsent(false)`) |
| J8 | Kill and relaunch the app | Only consented entries persist; nothing was uploaded (verify with a packet capture or `adb logcat` showing no memory traffic) | `LocalMemoryStoreTest` |
| J9 | Back up the device / use device transfer | NEX data is **excluded** (`allowBackup="false"` + data extraction rules) | manifest, `res/xml/data_extraction_rules.xml` |

### K. Animations (56)

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| K1 | Tap **Preview all 56 animations** | A dialog titled *"NEX animations — 56 total"* listing `1 · Calm idle breathing` … `56 · Farewell star trail` — exactly 56 entries, no duplicates, no gaps | `AnimationCatalogTest` |
| K2 | Tap through all 56 (or long-press the stage repeatedly) | Each one renders visibly and distinctly; the name and id under the stage match the catalogue | `AnimationCatalogTest`, `NexAnimationView` |
| K3 | Watch for a speaking mouth | **None exists.** Speaking is shown with eyes/light only | `AnimationCatalogTest` (`animatesMouth = false`) |
| K4 | Watch for strobing/flashing | No rapid flashing; at most a single soft flash (id 37) | `AnimationCatalogTest` (`flashCount ≤ 1`) |
| K5 | Enable **Reduced motion** | Every animation still renders, slower and gentler, never faster or larger; nothing disappears to a blank frame | `AnimationCatalogTest` (reduced-motion invariants) |
| K6 | Set Android Settings → Accessibility → Remove animations (animator scale 0) | Animations still render (Choreographer-driven, not `ValueAnimator`) | `NexAnimationView` |
| K7 | Trigger state changes (idle → wake → thinking → speaking → recovering → error) | The matching cue plays each time, including the recovery and farewell cues | `AnimationDirectorTest` |
| K8 | Ask about weather, grounding, a subject, gratitude, farewell | The intent-specific cues play (weather cue, grounding cue, subject cues, happy eye crescent, farewell star trail) | `AnimationDirectorTest` |
| K9 | Run the app 30 minutes with animations playing | No ANR, no memory growth, no thermal warning, smooth frame rate | — |

### L. Focus timer, settings and offline commands

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| L1 | "Start a 25 minute focus timer" | Timer line: *"Focus timer running · 25:00 left"*, countdown animation | `FocusTimerPolicyTest` |
| L2 | "Set a timer for 5 hours" | Clamped to the 3-hour maximum, and NEX says what it actually set | `FocusTimerPolicyTest` (`MAX_DURATION_MS`) |
| L3 | Let a timer finish | *"Focus time is complete. Take a short break."* | `FocusTimerPolicyTest` |
| L4 | Offline: say "pause", "resume", "stop timer" | Handled locally, honestly | `OfflineResponsesTest` |
| L5 | Offline: ask for privacy help and permission help | Correct local answers with the privacy/permission cues | `OfflineResponsesTest` |
| L6 | Offline: ask three times for a study prompt | Three distinct prompts each time, prefixed *"We are offline, so here is a practice prompt. "* | `OfflineResponsesTest` (`studyPrompts` = 3) |
| L7 | Offline: ask for news or the score of a match | Explicit refusal — no invented facts, no "let me check" | `OfflineResponsesTest` |
| L8 | Tap **Check server** | The backend status line reports the real state: reachable + ready, reachable + not configured, or unreachable | `NexApiClient`, `BackendUrlPolicyTest` |

### M. Backend, URLs and transport security

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| M1 | With no key on the server, tap **Check server** | Reports "not configured" honestly; NEX still works offline | backend `/health` `ready:false` |
| M2 | Enter an HTTP (non-TLS) URL in the backend setting | Rejected; the previous valid value is kept; no request is ever attempted | `BackendUrlPolicyTest` (`not-https`) |
| M3 | Enter `https://user:password@host` | Rejected as credentials-in-url, and the message does **not** echo the URL back | `BackendUrlPolicyTest` |
| M4 | Enter a 400-character URL / a malformed one / blank | Rejected with the right reason; the app never crashes | `BackendUrlPolicyTest` |
| M5 | Corrupt the stored preference (adb: overwrite `nex.settings`) and relaunch | The app falls back to the build default instead of crashing | `MainActivity.buildApiClient()` |
| M6 | Capture traffic (mitmproxy with a **user-installed** CA) | The connection **fails** — only system CAs are trusted, cleartext is disabled | `res/xml/network_security_config.xml` |
| M7 | Inspect the APK (`apkanalyzer dex packages app-debug.apk \| grep -i key`) | No API key, no OAuth client id, no secret string constants | `PRIVACY_AND_PERMISSIONS.md` §7 |

### N. Logging and data leakage

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| N1 | Run a full session, then `adb logcat -d \| grep -i nex` | Only sizes, statuses, timings and markers. **No** transcripts, no coordinates, no images, no keys | `LogScrubberTest` |
| N2 | Say something containing a phone number and an e-mail address | Logcat shows `[phone]` / `[email]`, never the digits | `LogScrubberTest` |
| N3 | Paste a fake credential-shaped string (`AIza…`) into a conversation | Redacted to `[redacted]` in logs | `LogScrubberTest` |
| N4 | Check the server logs (Render dashboard) for the same session | One line per request with `rid`, plus `chars/lang/needs_current/injection` and a ≤60-character scrubbed snippet at `INFO` (nothing at `WARNING`) | `DEPLOYMENT.md` §5 |
| N5 | Verify no analytics/ads SDK is present | `apkanalyzer dex packages` shows only AndroidX, Material, coroutines, OkHttp and CameraX | `app/build.gradle.kts` |

### O. Stability and device variety

| # | Steps | Expected | Unit cover |
| - | ----- | -------- | ---------- |
| O1 | Rotate the screen mid-answer | No crash, no lost state, no restarted microphone | `configChanges` in the manifest |
| O2 | Change font scale to largest, display size to largest | Layout stays usable and readable | — |
| O3 | Enable dark mode | Theme is coherent; no invisible text | `res/values/themes.xml` |
| O4 | Test on API 26, API 30 and API 34/35 devices if available | Same behaviour; permission dialogs match the platform version | — |
| O5 | Low battery / battery saver on | Animations and TTS still work; nothing is scheduled in the background | — |
| O6 | `adb shell monkey -p com.nexora.robot --throttle 200 -v 5000` | No crash, no ANR | — |
| O7 | Repeatedly toggle voice mode 20× fast | No leaked recogniser instances, no stuck state, mic indicator always matches reality | `RetryPolicyTest`, `VoiceReducerTest` |
| O8 | Two rapid wake phrases in a row | One wake, no double-acknowledgement, no crash | `WakePhraseMatcherTest` |

---

## 3. Pass criteria for release

All of the following must be true:

1. `./gradlew test` passes (17 classes) and `./gradlew lint` reports no errors.
2. Every case in sections A, B, D, E, F, G, H, J and N passes — these are the privacy- and
   microphone-critical paths.
3. Sections C, I, K, L, M, O pass, or every failure is written down with its cause and accepted
   explicitly.
4. No claim in the UI, the store listing or the docs exceeds what the device actually does — in
   particular, nothing suggests NEX can listen while closed or answer live questions offline.
5. The APK contains no credential of any kind.
6. The backend it talks to is the newly deployed service, verified per `DEPLOYMENT.md` §6.

---

## 4. Known limitations (state these to users, do not hide them)

* **No wake from a closed or force-stopped app.** Platform restriction for ordinary apps; NEX says
  so on screen.
* **Speech recognition quality depends on the device's speech service.** OEMs without Google
  speech services may show `NO_SPEECH_SERVICE`; NEX reports it instead of pretending.
* **Camera descriptions need the server.** Offline, NEX refuses rather than guessing.
* **Grounding depends on the provider.** Sources appear only when Google Search grounding truly
  ran; some live questions are answered from model knowledge with an explicit note.
* **Free-tier Render cold starts** can exceed the app's 40-second call timeout; NEX reports a
  server problem honestly instead of inventing an answer.
* **Coarse location is approximate by design** (two-decimal rounding, ≈1.1 km, ≤10 minutes old,
  only for direct local weather/place questions).
