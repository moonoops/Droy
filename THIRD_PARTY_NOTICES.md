# Third-party notices

Droy's own code is licensed under AGPL-3.0-only. Third-party code, libraries,
models, dictionaries and other resources retain their original licenses.

| Component | License and source |
| --- | --- |
| DeepSeek Harness (DSH) 0.2.1-rc.5 | MIT, copyright 2026 DeepSeek. [Upstream](https://github.com/deepseek-ai/deepseek-harness). Droy adds Android integration and assembly patches. |
| DSH npm dependencies | Per-package licenses and notices, retained in the generated runtime archive. Versions are pinned in [package-lock.json](runtime/dsh/package-lock.json). |
| dsh-meme sticker search | MIT, copyright 2026 Selfloom contributors. Adapted keyword search and mood grouping from commit `8992a2e8daa169ae85cf762e06d02d0ea96332bb`. The source reference and license are retained under `runtime/mobile/companions/vendor/dsh-meme/`. No upstream image packs are bundled. |
| MemOS local storage and retrieval | MemTensor/MemOS commit `64388b50e2a15528bcdf032e65a1ac792bcf63ee`, local plugin 2.0.16-beta.1. The upstream repository Apache-2.0 license and source hashes are retained under `runtime/memory/memos/upstream/`; the package metadata separately declares MIT without a standalone subdirectory license. See [integration and provenance](runtime/memory/memos/README.md). |
| Local memory embedding | `intfloat/multilingual-e5-small`, MIT as declared in the [official model card](https://huggingface.co/intfloat/multilingual-e5-small). Model revision and resource hashes are pinned in [embedding.lock.json](tools/embedding.lock.json). SentencePiece is Apache-2.0; its license and source references are included in generated APK assets `licenses/embedding/`. ONNX Runtime is reused from the speech runtime. |
| Android Node, Bash, ripgrep and shared libraries | Multiple licenses; see [runtime notices](runtime/THIRD_PARTY_NODE.md). |
| Speech libraries, models and dictionaries | Separate code and model licenses; see [speech notices](runtime/voice/THIRD_PARTY.md). |
| sherpa-onnx Android static dependencies | Includes GPL-3.0-or-later eSpeak NG, MIT Piper and other separately licensed dependencies. Full notices are included in APK assets `licenses/sherpa-static/`; matching source and build materials accompany the APK release. |
| Material Design Icons (search, terminal, groups, repeat) | Apache-2.0, Google LLC. [Upstream](https://github.com/google/material-design-icons). Converted to Android vectors; license included in APK assets `licenses/material-icons/`. |
| AndroidX and Kotlin libraries | Apache-2.0; versions are declared in the Gradle build files. |
| NanoHTTPD 2.3.1 | BSD-3-Clause. [Fixed upstream license](https://github.com/NanoHttpd/nanohttpd/blob/nanohttpd-project-2.3.1/LICENSE.md); a copy is included under APK assets `licenses/nanohttpd/`. |
| Gradle Wrapper, configured for Gradle 9.3.1 | Apache-2.0. The wrapper JAR retains `META-INF/LICENSE`, and the startup scripts retain their upstream notices. Build tooling only; not part of the APK. [Upstream](https://github.com/gradle/gradle/tree/v9.3.1). |

The DSH MIT license is included in APK assets under `licenses/dsh/` and in
the packaged upstream dependency. Other component notices accompany their
generated runtime assets. Binary redistribution must retain applicable notices
and satisfy each component's source-availability requirements.
