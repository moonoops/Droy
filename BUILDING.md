# Build Droy

Run commands from the repository root. The build supports macOS and x86_64 Linux.

## Toolchain

| Tool | Version |
| --- | --- |
| Java | JDK 17 |
| Host Node.js | 22 or 24, with npm |
| Python | 3.9+, with xz support |
| patchelf | 0.19.2 |
| Android SDK | Platform 36, Build Tools 35.0.0, Platform Tools |
| Android NDK | 28.2.13676358 / r28c |
| CMake | 3.22+ |

Configure `JAVA_HOME` and `ANDROID_HOME` for your installation. Install the SDK
components after reviewing the Android SDK licenses:

```sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-36" \
  "build-tools;35.0.0" "ndk;28.2.13676358"
```

The Gradle wrapper, npm lockfile and runtime manifests pin dependencies. The
first build needs access to Android repositories, Gradle, npm, Termux and the
model download sources declared in the manifests.

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

The command does not install an APK. `--skip-deps` reuses installed npm
dependencies for local iteration; release validation uses the full checks.

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

`app/` contains the Android host; `android-use/` defines phone operations;
`runtime/` contains the DSH integration and Droy plugins; `tools/` contains build
and installation tools. Generated dependencies, runtime data and APKs are
excluded from source control. See [TESTING.md](TESTING.md) for checks.
