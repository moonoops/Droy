# Speech components and model licenses

Model weights and dictionaries retain their own terms. The license of an
inference library does not relicense a model loaded by that library.

| Component | Delivery | License and source |
| --- | --- | --- |
| sherpa-onnx 1.13.8 | APK | Apache-2.0 source; the Android native build also links GPL-3.0-or-later eSpeak NG and other separately licensed components. [Source](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8). |
| ONNX Runtime 1.28.2 | APK, through the sherpa-onnx AAR | MIT and bundled third-party notices. [Source](https://github.com/microsoft/onnxruntime/tree/v1.28.2), [Android binary distribution](https://github.com/csukuangfj/onnxruntime-libs/releases/tag/v1.28.2). |
| SenseVoiceSmall int8 ONNX and tokens | APK | FunASR Model Open Source License Agreement v1.1 for the original weights. [Official model card](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [pinned conversion](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07). Attribution: SenseVoiceSmall by FunASR / FunAudioLLM; converted for sherpa-onnx by csukuangfj. |
| Silero VAD | APK | MIT. [Source and license](https://github.com/snakers4/silero-vad). |
| Chinese-English Zipformer KWS model, 2025-12-20 | APK | Apache-2.0 as declared by [the model author](https://www.modelscope.cn/models/pkufool/icefall-kws-zipformer-zh-en-3M-2025-12-20). [Official sherpa-onnx distribution](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models). The packaged `en.phone` dictionary is a separate resource. |
| English pronunciation dictionary (`en.phone`) | APK | CMUdict copyright and redistribution notice. [CMUdict](https://github.com/cmusphinx/cmudict); distributed in the pinned KWS archive, with its notice under `licenses/speech/cmudict-LICENSE.txt`. |
| MeloTTS Chinese | On-demand download | MIT. [Original model](https://huggingface.co/myshell-ai/MeloTTS-Chinese), [pinned ONNX conversion](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/tree/a0d5c6a264c0ef92d70d8661d8cc502d79627cd6). |
| Qwen3-TTS 0.6B CustomVoice and 12Hz tokenizer | On-demand download | Apache-2.0. [Original model](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice), [pinned GGUF conversion](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/tree/b7ee2e8c7459c3bea99da23e3d178125a7d1713c). |
| qwen3-tts.cpp | APK | MIT. [Pinned source](https://github.com/Danmoreng/qwen3-tts.cpp/tree/16bb5afcd06311031c72a8488f8d59660dc2fb46). |
| GGML | APK | MIT. [Pinned source](https://github.com/ggml-org/ggml/tree/af97976c7810cdabb1863172f31c432dab767de7). |

The original SenseVoiceSmall weights use the
[FunASR model agreement](https://github.com/modelscope/FunASR/blob/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE),
separately from the SenseVoice repository's MIT source-code license. Preserve
the model name, source and author attribution when using or redistributing it.

The Android native libraries include eSpeak NG/ucd-tools, Piper, Kaldi, OpenFst,
Eigen and other static dependencies. Their full notices and fixed sources are
listed in APK assets `licenses/sherpa-static/sources.json`. Matching source and
build materials are available in the
[sherpa source attachment](https://github.com/moonoops/Droy/releases/download/v0.0.1/sherpa-source-materials-v0.0.1.tar.gz).

Download URLs and file hashes are pinned in
[audio-models.json](../../app/src/main/assets/audio-models.json),
[prepare_voice_models.py](../../tools/prepare_voice_models.py),
[tts-models.json](../../app/src/main/assets/tts-models.json) and
[qwen_tts.lock.json](../../tools/qwen_tts.lock.json).
License copies are included in APK assets under `licenses/speech/`,
`licenses/onnxruntime/`, `licenses/melo/` and `licenses/qwen-tts/`.
