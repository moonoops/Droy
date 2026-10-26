# Android runtime components

Droy packages the Termux ARM64/bionic Node, Bash and ripgrep executables and
their required shared libraries. Exact package versions, URLs and checksums are
recorded in [node-runtime.lock.json](node-runtime.lock.json).

| Component | Package version | License or notice |
| --- | --- | --- |
| Node.js | 24.22.1 | MIT and bundled third-party notices |
| Bash | 5.3.20 | GPL-3.0-or-later |
| Readline | 8.3.6 | GPL-3.0-or-later |
| libiconv | 1.19 | LGPL-2.1-or-later; additional package notices |
| ripgrep | 15.2.0 | MIT / Unlicense; package notices |
| PCRE2 | 10.49 | BSD-style license |
| c-ares | 1.34.9 | MIT |
| ICU | 78.3 | Unicode License V3 and third-party notices |
| OpenSSL | 1:3.6.5 | Apache-2.0 |
| SQLite | 3.54.1 | Package-provided CC0 notice |
| zlib | 1.3.2 | zlib |
| ncurses | 6.6.20260307+really6.5.20250830 | ncurses permission notice |
| Android libc++ | 30 | Package notice and LLVM license |
| Android support library | 29-1 | Apache-2.0 and package notices |
| CA certificates | 1:2026.10.20-1 | MPL-2.0 |
| node-pty / node-addon-api | See [native lock](native/node-pty.lock.json) | MIT |

The payload includes full package notices under APK assets
`node/licenses/`, with supplemental ICU, LLVM and PCRE2 texts. The generated
`node/runtime-manifest.json` records each shipped file and its source package.
Packages downloaded only to resolve the Termux dependency graph are not all
included in the APK.

The build relocates ELF filenames, dependency entries and search paths for the
Android app. The relocation code is in
[prepare_android_node.py](../tools/prepare_android_node.py); original upstream
build recipes are in the [Termux package repository](https://github.com/termux/termux-packages).
Binary distributors must also provide the matching source and build materials
required by the applicable GPL, LGPL and MPL licenses; license texts alone do
not replace those obligations.

Matching source archives, patches and build recipes for this release accompany
the APK in the [Termux source attachment](https://github.com/moonoops/Droy/releases/download/v0.0.2/termux-source-materials-v0.0.2.tar.gz).

To prepare the locked payload from the repository root:

```sh
python3 tools/prepare_android_node.py --output ../artifacts/runtime-node
```

Native PTY source and loader adaptation retain the upstream MIT notices.
See the [project third-party notices](../THIRD_PARTY_NOTICES.md).
