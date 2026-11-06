# Bayit Voice — Android voice satellite for Home Assistant

Project spec for Claude Code. Read this file fully before writing code, then work phase by phase. Do not start a phase before the previous one passes its acceptance checks on a real device.

## 1. Goal

A small, generic Android app that turns any Android device, typically a spare phone or tablet on a charger, into a voice assistant for **any** Home Assistant (HA) installation:

- The user enters their HA address, signs in, and chooses an Assist pipeline, or just uses HA's preferred (default) pipeline.
- Optional always-on wake word, with built-in models or a custom model the user imports.
- Streams speech to the chosen pipeline and plays the spoken reply.
- The app fully controls its own UI, including closing automatically after a successful command.
- Keeps listening by itself when the assistant asks a follow-up question.
- Light on resources: it runs in the background all day.

Nothing about a specific home may be hard-coded: no URLs, tokens, pipeline ids, language, wake word or device names. Everything comes from configuration.

## 2. Tech choices

- Kotlin, single-module app, minSdk 26, targetSdk = latest stable.
- Jetpack Compose for UI. UI code must never be loaded by the background service.
- OkHttp for the WebSocket connection and for downloading TTS audio.
- `AudioRecord` for capture: 16 kHz, mono, PCM 16-bit little-endian.
- Media3 ExoPlayer (or `MediaPlayer`) for TTS playback.
- Wake word: openWakeWord models (`.tflite`) run on-device with LiteRT (`com.google.ai.edge.litert`, the renamed TensorFlow Lite; same `org.tensorflow.lite.Interpreter` API) (Phase 2).
- Credentials encrypted with an AES-GCM key held in the Android Keystore (a small in-app store; `androidx.security:security-crypto` / `EncryptedSharedPreferences` is deprecated and not used); never logged.
- UI localized: English and Hebrew to start, following the system locale, with full RTL support. All strings in resources, none in code.
- Package name: `com.avshy.bayitvoice` (placeholder; keep it in one place so it can be changed).

## 3. Onboarding and configuration

### 3.1 Server address

- Two optional URL fields: **home address** (used on the home network) and **external address** (used elsewhere). At least one is required. Accept `http://` or `https://`, a local IP, a hostname, or an external domain, with or without a port. Normalize it: trim, drop trailing `/`, default the scheme to `http://` for bare local IPs.
- Choosing which address to use, per connection:
  - **Home Wi-Fi networks (optional).** The user lists the SSIDs of their home networks ("Add current network" fills it in). Reading the SSID needs the location permission and location turned on; it is requested only when the user adds a network. When the current SSID is known: on a home network use home first, otherwise use external only.
  - **Fallback without SSIDs (default, no permission).** On Wi-Fi or Ethernet, try the home address with a short timeout (about 2 s), then the external one. On mobile data, try external first.
  - A wrong token never triggers a fallback; it is reported as an auth error.
- **Android 17+ local network permission.** Apps targeting API 37 cannot reach local-network addresses without the runtime permission `ACCESS_LOCAL_NETWORK`. Request it when a home address is tested or a session starts with one configured. If it is denied, skip the home address and show a clear error for it in settings.
- "Test connection" tests each configured address separately and shows the result for each.
- Derive the WebSocket URL: `http` → `ws`, `https` → `wss`, path `/api/websocket`.
- Optional (Phase 3): discover local servers via mDNS service `_home-assistant._tcp` and offer them as one-tap choices.
- Plain `http` is allowed only for private/local addresses; show a warning. Configure `network_security_config` accordingly.

### 3.2 Authentication

- **Phase 1:** long-lived access token. Paste field (masked) with a short in-app explanation of where to create it in HA (user profile → Security → Long-lived access tokens).
- **Phase 3:** HA OAuth sign-in in a Custom Tab, so users never copy tokens:
  - `GET /auth/authorize` with `client_id`, `redirect_uri`, `response_type=code`; exchange the code at `POST /auth/token`; store and refresh the access/refresh tokens.
  - HA requires `client_id` to be a URL. A custom-scheme `redirect_uri` (e.g. `bayitvoice://auth-callback`) must be declared on the `client_id` page with `<link rel="redirect_uri" href="...">`. This needs a small static page (for example on GitHub Pages) that the project owns. Verify the details against the current HA auth docs.
- Keep both methods available after OAuth ships; tokens stay useful for kiosk setups.

### 3.3 Pipeline

- After a successful connection, call `assist_pipeline/pipeline/list` and show a picker.
- First option: **"Default (HA preferred pipeline)"**. When selected, omit the `pipeline` field from `assist_pipeline/run`, so HA uses its preferred pipeline, even if the user changes it later in HA.
- Show each pipeline's name and language. Store the chosen pipeline id; if it no longer exists, fall back to default and show a notice.
- The pipeline decides the speech language. The app must not assume one.

### 3.4 Compatibility check

- Read the HA version from the `auth_ok` message (`ha_version`).
- If it is older than the version that introduced `continue_conversation` (2025.4, verify), show a one-time notice that follow-up questions will need a tap, and disable the auto-continue loop.

### 3.5 Settings screen

- Server: URL, auth method, "test connection" button, sign out.
- Pipeline picker (section 3.3).
- Auto-close delay in seconds (default 5; 0 = never auto-close).
- Wake word: on/off, model choice, sensitivity threshold.
- Chime on/off.
- Export/import settings as a file, without credentials, to set up several devices quickly.

## 4. HA WebSocket protocol

Verify every message shape against the current HA developer docs (WebSocket API and Assist pipeline) before relying on it. The shapes below are the expected ones.

### 4.1 Connect and authenticate

1. Open the derived WebSocket URL.
2. Receive `{"type": "auth_required"}`.
3. Send `{"type": "auth", "access_token": "<token>"}`.
4. Expect `{"type": "auth_ok", "ha_version": "..."}`. On `auth_invalid`, show a settings error and stop.

Every later command carries an incrementing integer `id`.

### 4.2 Run the pipeline

```json
{
  "id": 2,
  "type": "assist_pipeline/run",
  "start_stage": "stt",
  "end_stage": "tts",
  "input": {"sample_rate": 16000},
  "pipeline": "<omit for the default pipeline>",
  "conversation_id": "<omit on first turn, reuse on follow-ups>"
}
```

HA replies with a `result` message, then a stream of `event` messages with the same `id`:

| Event `type` | What to use |
|---|---|
| `run-start` | `data.runner_data.stt_binary_handler_id`: the one-byte prefix for audio frames |
| `stt-vad-start` / `stt-vad-end` | Server-side voice activity detection. After `stt-vad-end`, stop recording and send end-of-audio |
| `stt-end` | `data.stt_output.text`: show as the user's text |
| `intent-end` | `data.intent_output.conversation_id` (store), `data.intent_output.continue_conversation` (bool), `data.intent_output.response.response_type` (`action_done` / `query_answer` / `error`), `data.intent_output.response.speech.plain.speech` (reply text) |
| `tts-end` | `data.tts_output.url`: relative path; prefix it with the HA base URL and play it |
| `run-end` | Run finished |
| `error` | `data.code`, `data.message`: treat as failure |

A pipeline may have no TTS engine. Then there is no `tts-end`; show the reply text only and continue with the same timing rules.

### 4.3 Streaming audio

- Send each audio chunk as a **binary** WebSocket frame: 1 byte `stt_binary_handler_id`, followed by raw PCM.
- Use chunks of about 20 to 100 ms.
- To signal end of audio, send a binary frame that contains only the handler id byte.
- Local safety net: if no `stt-vad-end` arrives within 10 s of speech start, send end-of-audio anyway.

### 4.4 Outcome classification

- **Success:** run reaches `run-end`, no `error` event, and `response_type` is not `error`.
- **Failure:** an `error` event, `response_type == "error"`, WebSocket drop, or timeout.

## 5. Behaviour

### 5.1 Session UI

A translucent Activity that shows a dark bottom sheet, about a third of the screen tall, over whatever is on screen. It slides up when it opens and slides down when it closes. Tapping the dimmed area above it closes it.

- **Status row:** a small animated icon per state, the state's name and a close button.
  - The states are connecting / listening / thinking / speaking / done / error.
  - While speaking and when done, the label is the assistant's name ("Home"). When done, its icon becomes a check mark that animates in once.
  - Listening and thinking add three bouncing dots after the label.
- **Conversation:** the recognized user text in a speech bubble, then the assistant's reply text. Each text's direction (LTR/RTL) comes from the text itself.
- **Bottom slot** (fixed height, so the sheet does not jump):
  - While the reply plays, a waveform scrolls in from the right edge. It follows the reply's loudness (`Visualizer` on the player's audio session), or a generic speech pattern when the device refuses.
  - After a turn: a "Speak again" mic button. After an error: "Try again". The mic button is hidden while a turn is running.

The UI must work over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`), since the device usually sits idle with its screen off.

The user can choose the app as the device's digital assistant (Settings → Apps → Default apps → Digital assistant app; on Samsung, also Side key → Press and hold). The system's assistant gesture (side key, long-press home) then opens a hands-free session, just like the wake word: chime first, close on silence.

- Android lists only apps that provide a `VoiceInteractionService`. An activity that handles `ACTION_ASSIST` is not enough.
- The app therefore declares a minimal voice-interaction service, a session service and a stub `RecognitionService`, which the declaration requires. All of them hold no UI.
- When invoked, the session opens the normal session Activity with `startAssistantActivity`; from the lock screen it uses `onLaunchVoiceAssistFromKeyguard`.
- The session Activity also handles `android.intent.action.ASSIST` directly.

### 5.2 Auto-close

- After a **successful** turn with `continue_conversation == false`: wait for TTS playback to finish, then close the UI after the configured delay.
- After a **failure**: do not auto-close. Show the error text and keep the mic button available.
- Any touch on the UI cancels the auto-close timer.

### 5.3 Continue conversation

When `continue_conversation == true`, for example a clarifying question or a confirmation:

1. Wait for TTS playback to finish.
2. Play a short "listening" chime.
3. Start a new `assist_pipeline/run` immediately with the same `conversation_id`, without a wake word.
4. If no speech is detected within about 8 s, end the session and close the UI.

### 5.4 Audio hygiene

- Only one consumer owns the microphone at a time: either the wake-word engine or the pipeline stream.
- Pause wake-word detection while TTS is playing, to avoid self-triggering.
- Play a short chime when listening starts (wake word or follow-up).

## 6. Wake word (Phase 2)

- **Engine abstraction.** Define a `WakeWordEngine` interface (feed PCM frames, emit detections with a score). Implement openWakeWord first. The design must allow adding a microWakeWord engine later without touching the service, the VAD gate or the UI. Each model file is tagged with its engine.
- Engine: openWakeWord. The pipeline is three TFLite models: `melspectrogram.tflite` → `embedding_model.tflite` → a wake-word model. Each audio frame is 80 ms (1,280 samples at 16 kHz).
- Models:
  - Bundle a few of openWakeWord's pre-trained models as built-in choices. Check their license before bundling: the pre-trained models are distributed under a non-commercial license (verify), which must be stated in the app's About screen.
  - Also bundle the project's own Hebrew "היי בית" model (`hey_bayit_v0.1.tflite`), trained with `tools/wake_word_training`, as one more built-in choice.
    - It is an option, not the default; the default stays a neutral pre-trained model.
    - It is trained on the same openWakeWord data, so the same non-commercial terms apply.
    - A retrained version replaces the asset under a new version suffix.
  - Let the user import their own `.tflite` model via the system file picker, with a display name, and switch between installed models at any time. Imported models can be renamed or deleted.

### 6.1 User-trained wake words

Users must be able to create and use a wake word of their own choice, in any language.

- **Train-your-word guide (Phase 2).** An in-app screen that explains, step by step, how to train a model in the openWakeWord Colab notebook (link to it), including tips for non-English words: spell the word phonetically in English, avoid sounds English lacks, prefer 3 to 4 syllables, and listen to the generated samples before full training. The result is a `.tflite` file the user imports (section 6).
- **Personal verifier (Phase 3).** openWakeWord supports a small per-user "custom verifier" trained from a few recordings of the user saying the word. Add a "Record my voice" flow: record about 5 to 10 samples of the wake word plus some background speech, train the verifier on-device, and use it to reduce false triggers for that model. Verify the current openWakeWord verifier API and whether training it on-device is practical; if not, document the limitation.
### 6.2 Multi-device arbitration (Phase 3)

When several devices running the app hear the same wake word, only the one closest to the speaker handles the command; the others cancel silently.

- **Transport:** UDP multicast on the local network (a fixed group address and port; acquire `WifiManager.MulticastLock` only while arbitrating, not all the time). No server and no HA involvement, so it adds no dependency.
- **Grouping:** devices arbitrate only with peers connected to the same HA server. The group id is the normalized HA URL (the home address if set, else the external one), and the HMAC key is its SHA-256. Optional user-set "group name" override for devices that reach the same HA by different addresses.
- **Flow on detection:**
  1. Compute a proximity score: how far the last second of audio (the wake word) rose above the device's own noise floor, in dB, plus 10 × the detection confidence. Measuring against the device's own floor cancels differences in microphone gain between phones; loudness stays the main signal for "closest", and confidence breaks near-ties.
  2. Stop wake-word capture and multicast the claim `{device_id, device_name, score, timestamp}`, repeating it every 80 ms so a device that detected slightly later still hears it.
  3. Listen for peer claims for a short window (default 300 ms, configurable 100 to 1,000 ms), then send the claim once more.
  4. The highest score wins; ties go to the lowest `device_id`. The winner opens the session. Losers play nothing and resume wake-word listening.
  - No audio is buffered during the window: the session starts after arbitration and plays its chime, and the user speaks after the chime, so nothing is lost. The window only delays the chime.
- **Follow-ups:** a continue-conversation turn belongs to the device that won the original session; other devices ignore it.
- **Graceful fallback:** if multicast is blocked (AP/client isolation, guest networks) or no peers answer, the device simply proceeds on its own after the window. Show a hint in settings when no peers were ever seen but the user expects several devices.
- **Safety:** sign messages with an HMAC keyed by the group id, so devices of a different HA instance on the same network are ignored. Messages older than about 1 s are dropped.
- **Settings:** arbitration on/off (default on), window length, group name, and a "devices seen recently" list for troubleshooting.
- **Testing with one phone:** `tools/arbitration_peer.js` acts as another device from a computer on the same Wi-Fi (`listen` prints claims, `claim` sends one with a chosen score). Debug builds accept a `debug_detect` service action that acts as if the wake word was heard, with `score` and `snr` extras.
- Acceptance: two or three devices in neighbouring rooms, wake word said once near one of them; in 10 of 10 tries exactly one device responds, and it is the nearest one in at least 9 of 10.

### 6.3 Record-only wake word

- **Record-only wake word (Phase 4, experimental).** A fully in-app option with no external training: the user records the word several times, the app stores their embeddings (from `embedding_model.tflite`) as templates, and detection compares live embeddings to the templates by similarity. Expected to be less robust than a trained model; label it "experimental" in the UI and measure false-accept and false-reject rates before shipping it.
- Detection threshold: configurable, default 0.5. Require a cooldown of about 2 s after each detection.
- Runs inside a foreground service of type `microphone`. Declare the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions, and show a persistent notification.
- Recent Android versions block starting a microphone foreground service from the background. Start the service from the Activity; for always-on devices, offer "start on boot" that opens the Activity, which then starts the service. Guide the user to exclude the app from battery optimisation.
- On detection: stop wake-word capture, open the session UI, and start the Phase-1 flow.
- If no speech follows the wake word within about 8 s (as with follow-ups, section 5.3), end the session and close the UI without an error; it was most likely a false trigger. A manual mic tap with no speech still shows the "no speech" error and stays open.
- When wake-word capture resumes after a session, clear the engine's audio history; otherwise the wake word that started the session is detected again.
- Android blocks activity starts from the background, including from a foreground service. To open the session UI (and the "start on boot" activity) the app needs the "display over other apps" permission (`SYSTEM_ALERT_WINDOW`); settings show its status with a button to grant it. Without it, the app posts a high-priority full-screen-intent notification instead, which the user taps.

## 7. Performance requirements

The app runs all day, so resource use is a first-class requirement.

1. **VAD gate before the wake-word model.** Run a cheap voice-activity check on each frame (energy threshold or a small VAD model). Feed frames to the openWakeWord models only while voice is present, plus a short pre-roll buffer so the start of the wake word is not lost. In a quiet room the heavy models should not run at all.
2. **UI-free background service.** The listening service loads no Compose or Activity code. The session UI is created only after a detection and destroyed when the session closes.
3. **WebSocket strategy, configurable:**
   - "Keep connected" (default): one authenticated idle connection for the fastest response; reconnect with exponential backoff; HA ping/pong to detect drops.
   - "Connect on demand": open the connection only after the wake word, for minimum background activity, at the cost of slightly slower first response.
4. TFLite with the XNNPACK delegate; reuse buffers and interpreters, with no per-frame allocations in app code. (The Java `Interpreter.run` call itself allocates a few small objects per call; that happens only while the VAD gate is open.)
5. No wake locks beyond what the foreground microphone service requires.
6. Measure: log CPU time and memory in debug builds; target well under 5% average CPU on a mid-range phone in a quiet room.

## 8. Phases and acceptance checks

Test on the developer's own HA, but always through the configuration screens. Never with values in code.

### Phase 1: Push-to-talk (no wake word)

- Onboarding: home and external URLs, optional home Wi-Fi networks, token, connection test, pipeline picker with "Default".
- Mic button runs a full turn: stream audio, show texts, play TTS.
- Auto-close after success; stay open on failure.
- Continue-conversation loop.

Acceptance:

- A simple command (e.g. turn on a light) executes, the reply plays, and the UI closes after the configured delay.
- An ambiguous command gets a question, the app re-listens without a tap, the answer is executed, then the UI closes.
- "Default" follows HA's preferred pipeline: change the preferred pipeline in HA and the app uses the new one without changes.
- A wrong token or unreachable server shows a clear error, and the UI does not close.
- Works with both a local `http://` address and an external `https://` address.
- With both addresses set: on home Wi-Fi the home address is used; on mobile data the external one is used, with no change in settings. Works both with and without home SSIDs configured.

### Phase 2: Wake word

- Foreground service with openWakeWord, built-in models, custom model import.
- Wake word opens the session over the lock screen and runs the Phase-1 flow.
- VAD gate and the other items in section 7.

Acceptance:

- 10 of 10 wake-word attempts from about 2 m trigger the session.
- No false triggers during 30 min of normal household talk or TV.
- Service survives 24 h on the charger; CPU within the target in section 7.

### Phase 3: Polish and sharing

- Personal verifier for wake words (section 6.1).
- Multi-device arbitration (section 6.2).
- HA OAuth sign-in (section 3.2).
- mDNS discovery (section 3.1).
- Settings export/import.
- Per-stage latency logging in debug builds.

### Phase 4: Optional

- Record-only experimental wake word (section 6.1).
- microWakeWord engine behind the `WakeWordEngine` interface, if measurements show it is worth it (lower CPU, or better accuracy with its available models).

## 9. Known limitations and open questions

- A raw WebSocket client is **not** registered in HA as an `assist_satellite` device. That means no `assist_satellite.announce` to this device, and no area-aware commands like "turn off the light here". If that becomes important, evaluate implementing the ESPHome voice-assistant protocol instead, or check whether the current `assist_pipeline/run` accepts a device id.
- `continue_conversation` depends on the conversation agent. HA sets it when the agent's reply ends with a question; agents that phrase questions differently will need a tap.
- Credentials must never be committed to git. Keep developer test values out of the repo (for example in an ignored `local.properties`) if any are needed at all.
