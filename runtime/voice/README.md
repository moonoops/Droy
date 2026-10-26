# Droy Voice

The voice plugin reuses DSH conversations, drafts, queue and credentials. The composer offers hold-to-talk and voice-conversation controls; Voice Settings manages readout and local keyword wake.

## Input and readout

Hold to talk records while pressed and submits the recognized text once on release. Sliding upward or losing focus cancels that capture. Voice conversation uses local SenseVoice recognition, the selected conversation model and optional reply readout. Muting stops input; readout pauses capture until playback finishes. Exiting voice does not cancel unrelated DSH tasks.

Recognition preserves existing drafts and attachments. A conflicting edit pauses automatic submission for review. Session changes, cancellation and exit invalidate pending recognition.

SenseVoice, tokens, Silero VAD and the keyword model are prepared by the build and bundled in the APK. Android system speech is the initial readout choice; optional MeloTTS and Qwen3-TTS weights are downloaded in Voice Settings and verified against fixed sizes and hashes. Component licenses and sources are listed in [THIRD_PARTY.md](THIRD_PARTY.md).

Configured compatible speech endpoints use 24 kHz mono PCM16 with `response_format: pcm`. Credentials use the DSH credential provider; configuration reads return only presence, never key values.

## Wake and privacy

Keyword wake is off by default. It uses local Chinese/English phrases or the current companion name, runs only in its selected unlocked-screen/foreground conditions, and yields to active voice or recording. An accepted wake starts a new conversation; locking the screen cancels pending startup.

After personal-data protection activation, system or external speech requires a conversation known to be clean. Protected, forgotten or unknown conversations use only the supported local speech engines. Text-model consent does not authorize a separate speech provider; revocation stops affected queued and ongoing readout.

Internal voice-input files are temporary; explicitly saved recordings and imported audio remain user data.

## Build and checks

```sh
python3 tools/prepare_audio.py
node --test runtime/voice/tests/*.test.mjs
node tools/prepare_dsh.mjs --refresh-mobile
node --expose-internals runtime/mobile/tests/profile-smoke.mjs
```
