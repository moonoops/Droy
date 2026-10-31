# Build Droy

Run commands from the repository root. The build supports macOS and x86_64 Linux.

## Toolchain

| Tool | Version |
| --- | --- |
| Java | JDK 17 |
| Android Gradle Plugin / Gradle | 9.1.1 / 9.3.1 |
| Kotlin / Compose compiler plugin | 2.2.21 |
| Compose BOM / Material 3 | 2026.09.00 / 1.5.0-beta02 |
| Navigation Compose | 2.9.8 |
| Host Node.js | 22 or 24, with npm |
| Python | 3.9+, with xz support |
| patchelf | 0.19.2 |
| Android SDK | Platform 37.0, Build Tools 36.0.0, Platform Tools |
| Android NDK | 28.2.13676358 / r28c |
| CMake | 3.22+ |

Configure `JAVA_HOME` and `ANDROID_HOME` for your installation. Install the SDK
components after reviewing the Android SDK licenses:

```sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-37.0" \
  "build-tools;36.0.0" "ndk;28.2.13676358"
```

The Gradle wrapper, npm lockfile and runtime manifests pin dependencies. The
first build needs access to Android repositories, Gradle, npm, Termux and the
model download sources declared in the manifests.

The native workbench uses Material 3 Expressive. Material 3 1.5.0-beta02
is pinned separately from the stable Compose BOM; BOM 2026.09.00 aligns Compose
UI, Foundation, Runtime and instrumentation tests at 1.12.1. The Material 3
artifact requires compile SDK 37 and AGP 9.1 or newer, so this build uses the
37.0 platform, AGP 9.1.1 and Gradle 9.3.1. The minimum Android version remains
API 30 and the app's target SDK remains 36.

Kotlin compilation uses AGP's built-in Kotlin support. The Compose compiler
plugin and Kotlin standard library remain at 2.2.21; keep compiler versions
aligned when updating them. Navigation Compose remains at 2.9.8.

Gradle packages the public PNG/WebP artwork from `runtime/mobile/companions/assets`
into native assets during `preBuild`. Native companion backgrounds and avatars can
therefore load before the engine starts; the runtime uses the same source files.
Private uploaded attachments continue through the authenticated product service.

Migration references: [Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-beta02),
[AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes),
and [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).

## Build

```sh
./tools/build.sh
```

This prepares dependencies and runtime assets, runs host and unit checks, and
builds the development APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The command does not install an APK. It runs the full host/unit suite and is
the build entry point for explicitly requested integration or release validation.
Additional checks and device coverage are listed in [TESTING.md](TESTING.md).

For everyday development, run tests for the changed behavior, its direct callers,
and relevant failure paths first. Build only when compilation or device validation
requires it. After those targeted checks pass, avoid repeating the full suite:

```sh
./tools/build.sh --skip-tests --skip-deps
```

`--skip-tests` skips the script's host/unit tests while still preparing runtime
assets and building both development APKs. It does not replace targeted checks.
`--skip-deps` reuses installed npm dependencies; omit it if dependencies changed
or have not been installed. Before testing generated mobile resources, refresh
them with `node tools/prepare_dsh.mjs --refresh-mobile` when dependencies and DSH
staging already exist; dependency changes require full preparation.

Finishing a chat or making an ordinary commit does not trigger full regression
or validation on both emulator and phone. Run integration validation when the
user explicitly requests it, and release validation for an explicitly requested
release. Use the full build checks at those checkpoints; reuse matching successful
results rather than running the same suite twice. See [TESTING.md](TESTING.md)
for cumulative validation records and checks that must stay in daily development.

The debug app uses package `io.droyagent.app.dev`, the launcher label **Droy Dev**
(**Droy 开发版** in Chinese), and the Android toolchain's local debug signing key.
It installs alongside the official `io.droyagent.app` app with independent data,
configuration and permissions. Keep the local debug keystore stable to update the
development app without losing its data. Development builds and instrumentation
APKs do not use the official keystore or 1Password credentials.

Install a development build on an explicitly selected device:

```sh
./tools/install.sh --serial YOUR_DEVICE_SERIAL --launch
```

The installer defaults to the development package and checks the APK package
before installing. It never uninstalls or clears either app. Configure the new
development app separately; the existing official app is retained.

| Variable | Purpose |
| --- | --- |
| `DROY_NDK_DIR` | Select the pinned NDK installation |
| `DROY_CMAKE` | Select the CMake executable |
| `DROY_PATCHELF` | Select patchelf 0.19.2 |
| `DROY_NODE_RUNTIME_DIR` | Generated Android Node payload; defaults to `../artifacts/runtime-node` |
| `GRADLE_USER_HOME` | Optional Gradle cache directory |

After preparing the runtime assets, `./gradlew :app:assembleRelease` builds an
unsigned release APK. Sign release builds with your own securely stored key;
the official signing key and credentials are not part of this repository.
Use official signing credentials only when the user explicitly requests a GitHub
release, within that release's authorized scope. Ordinary development, testing,
and unsigned release builds do not authorize official signing.

## Project layout

Companion semantic memory includes a pinned multilingual-e5-small quantized
ONNX model and SentencePiece tokenizer. `tools/build.sh` runs
`tools/prepare_embedding.py` after preparing the existing speech runtime, reusing
its ONNX Runtime library. The first preparation downloads the resources listed
in `tools/embedding.lock.json`; subsequent builds verify and reuse the cache.
Model/tokenizer resources are about 123 MB, with an additional native library of
about 2.6 MB. Generated resources remain under `runtime/generated/` and are not
source files. No model API credentials are required for preparation or the
embedding tests.

伙伴语义记忆使用固定版本的多语言量化模型和分词器。构建脚本自动准备资源并复用
语音模块已有的 ONNX Runtime；首次下载后按锁定哈希校验缓存。模型与分词器约
123 MB，另有约 2.6 MB 原生库，生成资源不进入源码。准备和 embedding 测试不需要
模型 API 凭据。

`app/` contains the Android host; `android-use/` defines phone operations;
`runtime/` contains the DSH integration and Droy plugins; `tools/` contains build
and installation tools. Generated dependencies, runtime data and APKs are
excluded from source control. See [TESTING.md](TESTING.md) for checks.
