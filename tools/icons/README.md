# Droy clear round-line icons

The selected Droy icon direction uses a 24 × 24 grid, 2-unit strokes, round
caps and round joins. Icons use the shared Material theme's foreground color.
The three root tabs retain the Material selection container and selected color;
the Tasks tab has its own four-tile glyph rather than the compose pencil.
Toolbar sizes of 18/20/24 dp use the same vector geometry. Touch targets and
existing content descriptions remain the responsibility of their UI components.

## Source and regeneration

The vendored SVGs are Tabler Icons **outline**, pinned to commit
`ed177a085b652fe04ec4f37ced867c94621e7840`:

- Source: <https://github.com/tabler/tabler-icons/tree/ed177a085b652fe04ec4f37ced867c94621e7840/icons/outline>
- Design guide: <https://docs.tabler.io/icons/design-guide>
- License: [MIT](tabler/LICENSE), Copyright Paweł Kuna.
- Native resource mapping: [catalog.json](catalog.json).

From the repository root, run:

```sh
python3 tools/generate_icons.py
python3 tools/generate_icons.py --check
```

Generation is offline. All SVG paths are preserved, including tiny round-ended
segments used for dots. Unsupported SVG features fail generation instead of
being discarded. Android vectors keep an explicit transparent fill and a black
stroke so Compose's existing theme tint controls their display color.

Existing resource names remain available for callers. The model chip, reasoning
brain, plugin plug, task type, goal, context compaction, font and app-management
glyphs are mapped by meaning. Both playback resource names share one play glyph.
The four currently unused native resources are included for visual consistency.

The retained Web clients in `runtime/mobile/client.js` and
`runtime/mobile/companions/client.js` use the same vendored Tabler geometry and
2-unit round strokes. Their local definitions do not add a runtime dependency or
fetch remote icons. Their extra keyboard, quote, mode-switch and sidebar glyphs
are included in the source inventory.

The context ring and recording/message waveforms retain their dynamic behavior
and use matching 2 dp round strokes. Error and selected markers use vectors
instead of font-dependent punctuation. Product logos, launcher/notification
branding, third-party application icons, avatars, stickers and pet artwork are
separate assets.
