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
- Conversation screen style, per device (section 5.1): Wave (default), Futuristic, Full screen.
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

The user picks one of three styles per device in settings. **Wave** and **Futuristic** are bottom sheets for a phone in the hand; **Full screen** is for a device fixed in one place in the home.

**Wave (default).** A translucent Activity that shows a dark bottom sheet, about a third of the screen tall, over whatever is on screen. It slides up when it opens and slides down when it closes. Tapping the dimmed area above it closes it.

- **Status row:** a small animated icon per state, the state's name and a close button.
  - The states are connecting / listening / thinking / speaking / done / error.
  - While speaking and when done, the label is the assistant's name ("Home"). When done, its icon becomes a check mark that animates in once.
  - Listening and thinking add three bouncing dots after the label.
- **Conversation:** the recognized user text in a speech bubble, then the assistant's reply text. Hebrew niqqud (vowel points, often added to steer TTS) is removed from the shown reply; the spoken audio keeps it. Each text's direction (LTR/RTL) comes from the text itself.
- **Bottom slot** (fixed height, so the sheet does not jump):
  - While the reply plays, a waveform scrolls in from the right edge. It follows the reply's loudness (`Visualizer` on the player's audio session), or a generic speech pattern when the device refuses.
  - After a turn: a "Speak again" mic button. After an error: "Try again". The mic button is hidden while a turn is running.

**Futuristic.** The same sheet, with an animated orb of dots in place of the waveform:

- The orb sits in the bottom slot for the whole turn, larger than the slot and drawn behind the text; the user's bubble is translucent so it shows through. The status row has no bouncing dots.
- Each state has its own orb: connecting = a web of nodes, listening = a rippling sphere that speeds up with the microphone's level, thinking = orbits, speaking = a ribbon that speeds up with the reply's loudness (the same `Visualizer` level, or a generic speech pattern).
- States cross-fade. When done, the orb's own dots fly into a check mark, which turns solid, holds for about 2 s and then gives way to "Speak again".
- The orb geometry is ported from thinking-orbs (MIT License, credited in About). It is drawn in Compose only while the session UI is open; it allocates nothing per frame beyond the drawing calls.

**Full screen.** For a tablet or phone fixed on a wall or a shelf. It takes over the whole screen (system bars hidden), fades in and out, and is meant to be read from across the room:

- Near-black background with a soft glow of the accent colour around a large orb (the same orbs as Futuristic, denser).
- Before there is any text, the orb is the hero in the middle of the screen. When text arrives the orb moves aside (landscape: to the end side, text on the start side) or up (portrait: text below it).
- Large type: state label about 48 sp, the user's words in a bubble about 40 sp, the reply about 54 sp bold, on a 1280×800 (or 800×1280) design scaled to the screen. A large close button in the top corner.
- When done, the check mark forms in the orb, then a large "Speak again" button takes the orb's place. Errors show "Try again" there.
- It works in both orientations and follows the device's rotation.

The UI must work over the lock screen (`setShowWhenLocked`, `setTurnScreenOn`), since the device usually sits idle with its screen off.

The user can choose the app as the device's digital assistant (Settings → Apps → Default apps → Digital assistant app; on Samsung, also Side key → Press and hold). The system's assistant gesture (side key, long-press home) then opens a hands-free session, just like the wake word: chime first, close on silence.

- Android lists only apps that provide a `VoiceInteractionService`. An activity that handles `ACTION_ASSIST` is not enough.
- The app therefore declares a minimal voice-interaction service, a session service and a stub `RecognitionService`, which the declaration requires. All of them hold no UI.
- When invoked, the session opens the normal session Activity with `startAssistantActivity`; from the lock screen it uses `onLaunchVoiceAssistFromKeyguard`.
- The session Activity also handles `android.intent.action.ASSIST` directly.

### 5.2 Auto-close

- After a **successful** turn with `continue_conversation == false`: wait for TTS playback to finish, then close the UI after the configured delay.
- After a **failure**: show the error text and keep the mic button available; close after 20 s (or the configured delay, if longer), so an unattended device, such as a satellite, never stays open.
- A touch postpones closing instead of cancelling it: once the turn is over, the UI closes 20 s (or the configured delay, if longer) after the last touch. A touch during the turn makes the close after it use that longer delay.
- An auto-close delay of 0 in settings still means the UI never closes by itself.

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

When several devices running the app hear the same wake word, only the one closest to the speaker handles the command. Every device reacts at first, so the person in front of any of them sees it listening and keeps talking instead of repeating the wake word; the others then step aside.

- **Transport:** UDP multicast on the local network (a fixed group address and port; acquire `WifiManager.MulticastLock` only while arbitrating, not all the time). No server and no HA involvement, so it adds no dependency.
- **Grouping:** devices arbitrate only with peers connected to the same HA instance, whatever address each one uses. HA's WebSocket API has no instance id, so the group id is a SHA-256 of the home's location settings from `get_config` (`latitude`, `longitude`, `location_name`, `time_zone`), which are the same for every device of one home. It is refreshed on every connection and on "Test connection". Until it is known, the normalized HA URL is used. The HMAC key is the SHA-256 of the group id.
- **What the user sees:** every device that heard the wake word chimes, opens its sheet and shows "Listening", with no waiting. The user speaks as usual. The nearest device runs the command and answers. Every other device keeps showing "Listening" until the user stops speaking, then shows "Answering on: <device name>" for about 1.5 s and closes without a sound.
- **Flow:**
  1. On detection the service measures how far the wake word (the last second of audio) rose above the device's own noise floor, in dB, and opens the session at once, passing that level, the detection confidence, the noise floor and the detection time. Measuring against the device's own floor cancels differences in microphone gain between phones.
  2. The session chimes and records from the start, keeping the audio on the device; nothing goes to HA yet. While recording it measures the level of the start of the command, also against the noise floor.
  3. 1.5 s after the detection (the same moment on every device, give or take the few tens of milliseconds between their detections) it multicasts its claim `{device_id, device_name, score, timestamp}`: score = the mean of the wake-word level and the command level, in dB, plus 10 × the detection confidence. Using the start of the command as well as the wake word makes "nearest" more reliable than the short wake word alone.
  4. It repeats the claim every 80 ms for a fixed window of 300 ms, collecting the others' claims, then sends it once more. The highest score wins; ties go to the lowest `device_id`. Every device compares the same claims the same way, so all reach the same answer without announcing it.
  5. The winner sends HA everything recorded so far, faster than real time, then streams live. HA waits for the end of speech anyway, so for any command longer than about 1.5 s the reply is not delayed; only a very short command is.
  6. A loser sends nothing to HA, so the command runs once. It keeps listening locally until the user stops speaking (about 0.8 s of silence after speech, or 1.2 s with no speech at all, at most 10 s), shows who is answering, and closes.
  - With no peers on the network the flow is the same: the device waits 1.5 s before sending, which only a very short command would notice.
  - The side key or long-press home picks a device explicitly and is never arbitrated; only wake-word sessions are.
- **Follow-ups:** a continue-conversation turn belongs to the device that won the original session; other devices ignore it.
- **Graceful fallback:** if multicast is blocked (AP/client isolation, guest networks) or no peers answer, the device simply proceeds on its own after the window.
- **Safety:** sign messages with an HMAC keyed by the group id, so devices of a different HA instance on the same network are ignored. Messages older than about 1 s are dropped.
- **Settings:** arbitration on/off (default on) and the device name other devices show ("Answering on: …"), which defaults to the system's device name. Nothing else: the window is fixed and grouping is automatic.
- **Testing with one phone:** `tools/arbitration_peer.js` acts as another device from a computer on the same Wi-Fi (`listen` prints claims, `claim` sends one with a chosen score). Debug builds include an exported `DebugDetectReceiver` that acts as if the wake word was heard: `adb shell am broadcast -n <package>/.service.DebugDetectReceiver --ef score 0.9 --ef snr 20`.
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
- Satellite mode (section 10): ESPHome API server with Noise encryption, mDNS, voice assistant, timers (local countdown and alarm), announcements, wake-word configuration, optional timer sensor; connection-mode setting.

Satellite-mode acceptance:

- HA discovers the device; adding it with the key works; a wrong key is refused.
- "Set a timer for 1 minute" in Hebrew starts a timer for that device only; it rings on that device only, wakes the screen from idle, and stops by voice or touch.
- "How much time is left" and "cancel the timer" work by voice.
- `assist_satellite.announce` plays on the device.
- "Turn off the light here" acts on the device's area.
- Wi-Fi off for 30 s and back: HA reconnects by itself, and a running timer still rings on time.
- Direct mode keeps passing the Phase 1 checks.

### Phase 4: Optional

- Idle screen (section 11): our own always-on display for a fixed device (clock, dates, live weather scene, timers, music in the room, night mode), so it needs no separate clock or screen-saver app. Design approved (`docs/idle-screen-demo.html`).

- Record-only experimental wake word (section 6.1).
- microWakeWord engine behind the `WakeWordEngine` interface, if measurements show it is worth it (lower CPU, or better accuracy with its available models).

## 9. Known limitations and open questions

- In direct mode the app is a raw WebSocket client, **not** an `assist_satellite` device: no HA timers, no `assist_satellite.announce`, no area-aware commands like "turn off the light here". Satellite mode (section 10) provides these; direct mode stays for use away from home.
- `continue_conversation` depends on the conversation agent. HA sets it when the agent's reply ends with a question; agents that phrase questions differently will need a tap.
- Credentials must never be committed to git. Keep developer test values out of the repo (for example in an ignored `local.properties`) if any are needed at all.

## 10. Satellite mode (Phase 3)

Registers the app in HA as a real voice satellite, so HA's built-in timers, announcements and area-aware commands work for every user with no per-home automations. Research and sources: `docs/satellite-mode-research.md`.

- **Protocol: the ESPHome native API**, not Wyoming. The Wyoming satellite was archived in January 2026 and its successor (OHF's Linux Voice Assistant) uses ESPHome; ESPHome also has start/continue conversation and wake-word configuration that Wyoming lacks.
- **Connection mode, per device** (setting):
  - *Satellite* (recommended for a device fixed at home): the app is an ESPHome API server. HA owns the pipeline choice, the area and the active wake word (on the device's page in HA). In this mode the settings screen hides what only the direct link uses (HA addresses, home networks, token, pipeline list, keep-connected / on-demand, conversation style) and says that HA chooses the assistant; while unpaired it shows the key and the device's `ip:6053` for adding it by hand. Switching links never clears the other link's values. No HA URL or token is needed.
  - *Direct*: today's WebSocket client (sections 3 and 4). The only mode that works away from home, since in satellite mode HA must reach the device on the local network.
- **Server:** TCP port 6053, advertised as `_esphomelib._tcp` with `NsdManager`. HA discovers it, or the user adds IP:port by hand. In HA's protocol naming HA is the client and the device the server.
- **Encryption:** always on. The app generates a 32-byte key on first use and shows it with a copy button; HA asks for it when adding the device. Noise (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`) on X25519, ChaCha20-Poly1305 and SHA-256 from a library (BouncyCastle or Tink; the platform has no ChaCha20 below API 28). Verify the handshake details against `aioesphomeapi` before coding. A key also allows HA 2026.10+ to accept connections the device opens itself (TCP 6054), for networks where HA cannot reach the device.
- **Messages:** `api.proto` from `esphome/aioesphomeapi` (MIT), protobuf-lite. The device answers hello, ping, disconnect, device info (with the voice-assistant feature flags for voice assistant, API audio, timers, announce and start conversation; bit values from `api.proto`), and lists entities. Voice: `VoiceAssistantRequest` to start a run (with `conversation_id` and the wake-word phrase), mic audio as `VoiceAssistantAudio`, pipeline events as `VoiceAssistantEventResponse` (STT text, intent end with `continue_conversation`, TTS URL), which drive the same session UI as today.
- **Timers:** HA owns timers and sends started / updated / cancelled / finished events with total and remaining seconds. The app keeps its own countdown for each timer inside the process, so a dropped connection does not delay the end. An exact `AlarmManager` alarm is not used: from Android 14 it needs a permission the user must grant by hand, and the listening service already holds the microphone, which keeps the process awake. A small chip over other apps (the clock screen saver included) shows the next running timer; tapping it opens the timer screen, where a timer can also be dismissed on this device. On finish the app plays a generated alarm beep on the alarm stream (no bundled file) and opens a timer screen over the lock screen and other apps (the same `SYSTEM_ALERT_WINDOW` path as the session UI). It rings until a touch on "Stop" or the wake word (which then only silences it, and claims that wake word in the device arbitration with a score no proximity can beat, so other devices that heard it stand down instead of opening a session), and stops by itself after 5 minutes. Optional: a sensor entity with the active timer and its remaining time, for dashboards and automations.
- **Announcements:** `VoiceAssistantAnnounceRequest` (media URL, text, pre-announce sound, `start_conversation`) opens the session UI, plays it, optionally starts listening, then sends `AnnounceFinished`.
- **Wake words:** the app reports its installed models in `VoiceAssistantConfigurationResponse`; choosing one in HA switches the active model.
- **Unchanged:** wake-word engine and VAD gate, multi-device arbitration (HA does not arbitrate between satellites), auto-close, continue conversation, audio hygiene. A satellite is a device fixed in the home, so its conversations are always shown in the Full screen style; the style picker is hidden and the style chosen for the direct link stays saved.
- **New UI** (show designs before building): satellite pairing (status, IP:port, key), timer countdown and ringing screen.
- **Resources:** one idle TCP socket and an mDNS registration; no extra wake locks; the service stays UI-free (section 7).
- Ava (`brownard/Ava`, Apache-2.0) is an existing Android ESPHome satellite; use it as a reference for message order and HA quirks, keeping its notice if any code is reused.

## 11. Idle screen (Phase 4)

A device fixed in the home always shows the app's own display, so it needs no separate clock, weather or screen-saver app. The approved design is `docs/idle-screen-demo.html` (a live, animated mock-up).

- **Hosts.** The same Compose content runs in two places:
  - the system **screen saver** (a `DreamService`), shown while the device charges or is docked, once the user picks the app under Settings → Display → Screen saver;
  - an **idle-screen Activity**, shown over the lock screen and kept on while charging. The user opens it from settings, or turns on "Open automatically while charging". That setting is the main path on systems that remove Android's screen saver (Realme, Oppo / ColorOS and others):
    - the listening service (wake word or satellite) registers a receiver, with no UI code, for the charger and screen-off broadcasts;
    - it opens the idle screen when the charger is connected;
    - it opens it again, waking the screen, if the screen turns off while charging while another app was in front (the screen timed out);
    - if the idle screen itself was in front, the user turned the screen off on purpose, and it stays off;
    - an idle screen opened this way closes itself when the charger is removed.
    It uses the same "display over other apps" permission as the session UI, and only works while the listening service runs.
  The session UI, announcements and a ringing timer open over it as today. When they close, the idle screen is still there underneath (Activity), or the system starts the screen saver again (Dream).
- **Layout** (designed first for a 6.5" phone in landscape, about 20:9; it also works in portrait and on tablets). The weather scene fills the screen inside a thin black frame with rounded corners. Everything else sits on the scene, sized to be read from 2–3 m:
  - the clock, about half the screen height, in the system's 12/24-hour format;
  - the Gregorian date, and under it the Hebrew date (`android.icu.util.HebrewCalendar`, offline). The Hebrew date has an on/off setting, on by default when the UI language is Hebrew;
  - the weather: current temperature, the condition in words, and today's high and low; humidity and wind in a glass chip at the bottom;
  - the next running timer in a large glass chip at the bottom (the same timers as section 10).
- **Weather scene.** Drawn in code, with no bundled images: a gradient sky by condition and time of day; layered ridges fading into haze; Mediterranean woodland in front (rounded oaks and carobs, stone pines, the odd cypress, shrubs, each crown a single irregular shape shaded from the light's side); soft drifting clouds; sun with glow and rays, or moon and stars; rain, lightning, fog and snow as needed. Every HA weather condition maps to a scene; sunrise and sunset come from `sun.sun`, with fixed hours as a fallback. Motion is slow and subtle.
- **Data.** Entities are chosen in settings, never hard-coded: one `weather` entity and the room's `media_player`.
  - *Satellite link:* the device asks HA for those entities' states over the ESPHome API (`SubscribeHomeAssistantStates` → `SubscribeHomeAssistantStateResponse` per entity and attribute → `HomeAssistantStateResponse`). Today's high and low come from `weather.get_forecasts`, called as a Home Assistant action with a response, if the API and HA version support it (verify against `aioesphomeapi` first). Actions need "Allow the device to perform Home Assistant actions" on the device's page in HA; settings explain this. Without it, the high and low and the music controls are hidden; nothing else is affected.
  - *Direct link:* the same data over the existing WebSocket (`subscribe_entities`, and `call_service` with `return_response` for the forecast).
- **Music.** While the room's media player plays, the screen shows what is playing instead of the weather details: artwork, title, artist, progress, play/pause, next and volume. It goes back when playback stops. Controls are HA actions on that media player.
- **Night mode.** Between hours set in settings (default 23:00–06:00), only the clock shows, dim red on black, with no animation. Or, by a setting, the screen is off: nothing is drawn and the backlight is off. The wake word, a ringing timer or an announcement still turns the screen on, since their screens open over the idle screen. In every mode the content shifts by a few pixels each minute so a static screen does not burn in.
- **Screen and brightness.** The Activity keeps the screen on while the device charges; the screen saver is kept on by the system. By day the backlight follows the system (auto-brightness included), or a fixed level from settings. At night it drops to the minimum, or off. It moves between the day and night levels over about 3 minutes. All of this sets only the idle screen's own window: the system's brightness setting is never changed, so no permission to change system settings is needed.
- **Resources.** Animation runs only while the idle screen is visible, at about 30 fps. Scene geometry and gradients are built once per size and condition, with no allocations per frame, and drawing stops in night mode. The background service is unchanged: it loads no idle-screen code (section 7).
- **Settings** ("Idle screen" section): open automatically while charging, weather entity, media player, night hours, at night dim clock or screen off, fixed day brightness (off = follow the system), Hebrew date, a button to open the idle screen, and a shortcut to the system screen-saver settings.
- **Stages,** each checked on a device before the next:
  1. Hosts, clock, dates, night mode and burn-in shift, on a plain background.
  2. The weather scene engine and weather data, with the forecast high and low.
  3. Timer chip and music.
- **Acceptance:**
  - The clock and the timer are readable from 3 m on a 6.5" phone.
  - The idle screen starts by itself on the charger: as the screen saver where the system has one, and through automatic opening on Realme-type systems.
  - A conversation opened by wake word over the idle screen returns to it afterwards.
  - The scene matches HA's current weather, and day or night.
  - Music started in the room appears within 2 s, and its controls work.
  - Night mode switches on and off at the set hours.
  - CPU use while the idle screen is visible stays within an agreed budget (measured, then recorded here).
