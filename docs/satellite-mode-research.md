# Satellite mode: research and proposal

Date: 2026-11-08. Status: proposal, not yet in SPEC.md.

## Why

Today the app is a raw WebSocket client of `assist_pipeline/run` (SPEC.md section 9). Home Assistant (HA) does not see it as a voice satellite, so:

- HA's built-in timers ("set a timer for 10 minutes", "how much time is left", cancel, pause) do not work. HA only starts a timer for a device that registered a timer handler.
- No `assist_satellite.announce` / `start_conversation` to the device.
- No area awareness ("turn off the light here"), and automations cannot tell which device heard a command.

Registering the app as a real satellite fixes all of these for every HA user, with no per-home automations.

## Options

| | Wyoming satellite | ESPHome native API |
|---|---|---|
| Status | `rhasspy/wyoming-satellite` archived 2026-01-27; its README points to Linux Voice Assistant, which uses the ESPHome protocol | Protocol of Voice PE and of OHF's Linux Voice Assistant; actively developed |
| Timers | Yes (timer events) | Yes (`VoiceAssistantTimerEventResponse`: started / updated / cancelled / finished, with `total_seconds`, `seconds_left`) |
| Announcements | Yes since HA 2025.3 | Yes (`VoiceAssistantAnnounceRequest` / `AnnounceFinished`) |
| Start / continue conversation | Not native (`restart_on_end` only for always-streaming satellites) | Yes (`start_conversation` flag; `continue_conversation` in intent-end data) |
| Wake word in HA UI | No | Yes (`VoiceAssistantConfigurationRequest/Response`: HA shows the device's wake words and lets the user pick) |
| Pipeline, VAD sensitivity, area | Device page in HA | Device page in HA (select entities created by HA) |
| Existing Android app | Termux hacks only | **Ava** (`brownard/Ava`, Apache-2.0, Kotlin, Android 8+, Jan 2026): ESPHome API server, timers, announcements, media player. No encryption ("no authentication whatsoever") |
| Encryption | None | Noise (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`) with a per-device key |
| HA cannot reach the device (VLAN, NAT) | No answer | HA 2026.10+: device dials HA on TCP 6054 ("outgoing connection"); needs encryption and one first add while reachable |

**Recommendation: ESPHome native API.** Wyoming's satellite is deprecated and lacks continue-conversation; ESPHome is where OHF puts new voice features.

The user's HA is 2026.10.2 (HA OS, Raspberry Pi 5) with five ESPHome devices already, so the integration and the outgoing-connection listener are available.

## How it works (ESPHome)

Naming in `api.proto`: HA is the *client*, the device is the *server*.

1. The app runs a TCP server (port 6053) and advertises `_esphomelib._tcp` over mDNS (`NsdManager`). HA discovers it ("New device found") or the user adds IP:port by hand.
2. Encrypted: the app generates a 32-byte key on first run and shows it (copy button). HA asks for it when adding the device. The key never leaves the device otherwise. **No HA URL or token is needed in satellite mode.**
3. HA connects: `HelloRequest` → `DeviceInfoRequest` (the app answers with name, MAC-like id, model, `voice_assistant_feature_flags` = voice assistant + API audio + timers + announce + start conversation) → `ListEntitiesRequest` (none, or a mic-mute switch later) → `SubscribeVoiceAssistantRequest(flags = API_AUDIO)`.
4. Wake word detected on the device (unchanged openWakeWord + VAD gate + arbitration) → app sends `VoiceAssistantRequest(start, conversation_id, wake_word_phrase)` → streams mic audio as `VoiceAssistantAudio` → receives `VoiceAssistantEventResponse` (STT end text, intent end with `continue_conversation`, TTS end with the URL) → plays TTS as today.
5. Timers: HA sends timer events; the app keeps a local countdown (so a dropped connection does not miss the end), shows it, and plays its own bundled alarm sound on `FINISHED` until stopped by voice or touch.
6. Announcements: HA sends `VoiceAssistantAnnounceRequest(media_id, text, preannounce_media_id, start_conversation)`; the app opens the session UI, plays it, optionally starts listening, then replies `AnnounceFinished`.
7. Wake words: the app reports its installed models in `VoiceAssistantConfigurationResponse`; picking one in HA switches the active model.

Implementation notes:

- `api.proto` from `esphome/aioesphomeapi` (MIT); protobuf-lite codegen in Gradle.
- Noise: a small Kotlin implementation on X25519 + ChaCha20-Poly1305 + SHA-256 (BouncyCastle or Tink; minSdk 26 lacks platform ChaCha20 before API 28). Verify the exact handshake (prologue `NoiseAPIInit\0\0`, frame indicator `0x01`) against aioesphomeapi before coding.
- Feature-flag bit values: take them from `api.proto` (`VoiceAssistantFeature`), not from memory.
- Ava's source is a reference for message order and HA quirks; do not copy code without keeping its Apache-2.0 notice.

## What changes in the app

- **New setting: connection mode, per device.**
  - *Satellite (recommended for a device fixed at home)*: ESPHome API server; HA owns pipeline choice, area and wake-word choice.
  - *Direct (today's mode)*: WebSocket client with URL + token; still the only mode that works away from home (HA must reach a satellite; the external `https://` address is an HTTP proxy and does not carry the raw TCP API, and the outgoing-connection port 6054 is local too).
- **Unchanged:** wake word engine, VAD gate, multi-device arbitration (HA does not arbitrate ESPHome satellites), session UI styles, auto-close, continue conversation, audio hygiene.
- **New UI:** timer countdown and ringing state (design to be shown before building), satellite pairing screen (status, IP:port, key with copy button).
- **Resources:** an idle TCP socket and an mDNS registration; no extra wake locks. HA pings every ~20 s.

## Latency

STT, the conversation agent and TTS run in HA exactly as today, so most of the time per command is unchanged. Possible small gains: HA holds a persistent connection (no connect/auth per command). Measure per stage before and after (Phase 3 latency logging).

## Proposed SPEC.md change

- Section 9: replace the first bullet with "Resolved by satellite mode (section 10)".
- New section 10 "Satellite mode (ESPHome native API)" with the content of "How it works" and "What changes in the app" above.
- Phase 3 gains: "Satellite mode: ESPHome API server with Noise encryption, mDNS, voice assistant, timers (local countdown and alarm), announcements, wake-word configuration; connection-mode setting."
- Phase 3 acceptance additions:
  - HA discovers the device; adding it with the key works; a wrong key is refused.
  - "Set a timer for 1 minute" in Hebrew starts a timer on that device only; the countdown shows; it rings on that device only and stops by voice or touch.
  - `assist_satellite.announce` plays on the device.
  - "Turn off the light here" acts on the device's area.
  - Killing Wi-Fi for 30 s and restoring it: HA reconnects by itself; a timer still rings on time.
- Later (after satellite mode works): merge the Full screen session style with an idle satellite screen (clock, active timers, announcements) so a fixed device needs no separate clock app. Show a design first.

## Sources

- https://github.com/rhasspy/wyoming-satellite (archived 2026-01-27, deprecation notice)
- https://github.com/OHF-Voice/linux-voice-assistant
- https://github.com/OHF-Voice/wyoming
- https://www.home-assistant.io/blog/2025/02/13/voice-chapter-9-speech-to-phrase/ (Wyoming announcements in 2025.3)
- https://github.com/brownard/Ava and https://community.home-assistant.io/t/ava-an-android-voice-assistant-using-the-esphome-protocol/975838
- https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
- https://github.com/home-assistant/core/pull/180961 and https://github.com/esphome/esphome/pull/18919 (device-initiated connections)
- https://github.com/home-assistant/intents/tree/main/sentences/he (Hebrew timer sentences: start, cancel, cancel all, status, pause, unpause)
