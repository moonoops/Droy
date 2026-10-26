# Process-memory fixture

This standalone Android test app uses package `io.droyagent.memoryfixture` and its own UID. It contains synthetic native data and guard pages; it has no production game data or networking.

Build with JDK 17, Android SDK 36 and NDK 28.2.13676358:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk sh tools/fixtures/process-memory/build.sh
```

The script creates a local fixture APK and fixture-only debug signing key outside the source tree. `DROY_MEMORY_FIXTURE_OUTPUT_DIR` selects the output directory; the build does not install the APK.

Use the fixture alongside the Droy debug and instrumentation APKs for `ProcessMemoryDeviceTest`. Root must already be available and granted. The read-only provider requires `DUMP`; its addresses and mapping metadata are for deterministic assertions, not model-assisted discovery.
