# Companion assets

- `kaka.png`: Droy's existing black-cat artwork, reused as Kaka's default avatar.
- `maimai.png`: the MaiBot role illustration from [Mai-with-u/MaiBot at fe88ec6b](https://github.com/Mai-with-u/MaiBot/blob/fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb/depends-data/maimai-v2.png). The UI frames the original illustration as an avatar. See the upstream repository for its licensing and attribution.
- `test-sticker-*.png`: generated temporary test stickers, intended to be replaced with the product's sticker collection.
- `sticker-support`, `sticker-rest`, `sticker-surprised`, `sticker-sad`, `sticker-wave`, and `sticker-thinking`: original Droy vector artwork, distributed under this repository's AGPL-3.0-only license. Editable SVG sources are in `source/`; run `node tools/render_companion_stickers.mjs` from the repository root to reproduce the native PNG assets using the installed runtime's Sharp dependency. No third-party artwork or fonts are used.

- `wallpaper-01` through `wallpaper-05`: original Droy tonal geometric artwork; editable vector sources are in `source/`. Full images are 1080×2400 and thumbnails are 180×400. These replace the former paper-plane illustrations.
