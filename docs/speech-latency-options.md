# Speech processing speed: findings and options

Summary of a consultation on 2026-11-11. Nothing here is implemented yet. Any option that changes the app needs a SPEC.md proposal first.

## How a request flows today

1. The app captures the microphone and streams audio to Home Assistant **while the user speaks**:
   - Direct mode: binary frames over the Assist pipeline WebSocket (`AssistSession.kt`, `client.sendBinary`).
   - Satellite mode: `VoiceAssistantAudio` messages over the ESPHome API.
2. HA's VAD detects the end of speech and closes the stream.
3. HA runs speech-to-text (STT), then the conversation agent (Claude), then text-to-speech (TTS).

## The bottleneck found

The ElevenLabs STT integration in HA core (`homeassistant/components/elevenlabs/stt.py`) does not stream. In `async_process_audio_stream` it:

- collects every chunk into one buffer (`audio += chunk`);
- only after the stream closes, sends the whole recording in one request (`speech_to_text.convert`).

So transcription starts only after the user stops talking. The delay after speech ends is the upload, plus the full transcription, plus the agent, plus TTS.

Both Claude pipelines use this engine (`stt.elevenlabs_speech_to_text`), including the preferred one, "עוזר הבית – Claude + קול מקומי". The "OpenAI Live" and "Gemini Live" pipelines do stream to their services.

HA's STT interface itself is a stream. Only this integration buffers it.

## Options considered

### A. Streaming STT inside the app (direct mode only)

The app streams audio straight to ElevenLabs' realtime transcription, then sends only the final text to HA (`conversation/process` with the Claude agent). The prompt, scripts and exposed entities stay in HA.

- **Saves:** the end-of-speech upload and the batch transcription. The text is almost ready when the user stops talking.
- **Costs:**
  - an ElevenLabs API key on the device, which each user of a generic app must supply;
  - direct mode only, because the ESPHome satellite protocol requires HA to run the pipeline from audio;
  - it departs from the SPEC, which is built on HA's pipeline.

### B. The app calls Claude itself and sends HA only commands (rejected)

This duplicates everything HA already does: the prompt, the tools, the exposed entities and the conversation history. The only saving is one round trip on the home network, tens of milliseconds. The real time is spent in STT and in the model, not in talking to HA.

### C. Streaming STT on the HA side (helps every mode)

A small Wyoming STT server that forwards each audio chunk to ElevenLabs realtime as it arrives. It would run next to the Phonikud TTS server on the Ubuntu VM and appear in HA as an ordinary STT engine.

- Helps both satellites and direct mode.
- No app change, the API key stays in HA, and the app stays generic.
- Light on the server: the heavy work happens in ElevenLabs' cloud.
- It is a separate project, like `wyoming-phonikud`.

### D. Android's built-in speech recognizer (direct mode only)

This is how SwiftKey and similar keyboards dictate. They do not transcribe themselves; they hand audio to an existing streaming engine (Microsoft's cloud, or Android's own engine from Google).

The app can use the same engine through `SpeechRecognizer`, then send the text to HA:

- free, with no API key;
- streams, with partial results that can be shown on screen while the user speaks;
- very good Hebrew;
- works offline on some devices.

Limits:

- direct mode only (same reason as A);
- depends on Google speech services being on the device, which nearly all Android phones have;
- the wake-word engine must release the microphone while the recognizer listens. The app already does this when a conversation starts.

## Whisper-style alternatives

| Option | Where it runs | Streaming | Hebrew | Notes |
|---|---|---|---|---|
| ElevenLabs Scribe (current) | Cloud | No, in HA's integration | Very good | The problem is the buffering, not the quality |
| Groq whisper-large-v3-turbo | Cloud | No, but usually under 1 s for a short command | Fair | OpenAI-compatible API. It may connect through the existing OpenAI STT integration if that accepts a custom base URL (unchecked) |
| OpenAI gpt-4o-transcribe | Cloud | No | Good | Already connected (`stt.openai_stt`) |
| Azure Speech / Google Chirp | Cloud | Yes | Good | More setup; HA integration path to be checked |
| ivrit.ai whisper-large-v3-turbo (CT2) | Local CPU | No | Best among Whisper models | Not practical on this hardware, see below |
| Whisper small/base | Local CPU | No | Too weak for commands | |

## Hardware

- Home Assistant runs on a Raspberry Pi 5 (HA OS).
- The Phonikud TTS server runs on an Ubuntu VM on an old Windows host with an i3-4330 (2 cores, 4th gen).
- A large Whisper model on either machine would likely take several seconds, 5 to 10 or more on the i3, for a 3 to 4 second command, which is slower than today. Local ivrit.ai is therefore ruled out.

## Recommended path

1. **Measure first.** HA's pipeline debug view shows the time of each stage (STT, agent, TTS). If STT is not the bulk of the delay, none of the options above helps much.
2. **If STT dominates:**
   - **Direct mode:** option D (Android `SpeechRecognizer`). Free, streaming, no keys.
   - **Satellite mode:** option C (Wyoming streaming bridge to ElevenLabs realtime) on the existing VM.
3. Option A is only worth it if D's Hebrew or reliability turns out to be insufficient.
4. Option B is rejected.

## Related (microphone quality, not speed)

For satellites that will play music, the Ava app (`brownard/Ava`) offers a choice of microphone source (`VOICE_COMMUNICATION`), communication mode and speakerphone routing, to get the device's echo cancellation. This app uses `VOICE_RECOGNITION` only. See the deferred "satellite local player" plan.
