# Spectre amethyst scanner

Donut's **Amethyst Scanner** adapts Spectre 26.2's `AmethystBypassModule` by **4ldenz**, licensed **GPL-3.0-only**. All three reference methods are included:

- **Bypass:** reads received section palettes for small, medium and large amethyst bud entries. It keeps one approximate marker per matching chunk, preferring air beside received amethyst/budding blocks and falling back to the section center. These are palette hints rather than confirmed exact geode coordinates. Received chunk/block/section updates invalidate cached findings.
- **Light:** finds non-emitting local block-light maxima at levels 1, 2, 4 or 5 next to amethyst or budding amethyst in received geode-containing sections.
- **ANBS+ Scan:** uses the shared, source-derived `GeodeGlowScan`, with **Min Cells** from 1 to 100 (default 12) and up to 200 displayed cells per matching chunk.

The source's optional **Tracer**, purple always-visible block markers, render-distance bounds, periodic 20-tick scan cadence and two-millisecond per-tick queue budget are retained. The budget is checked between chunks. The port adds explicit world/disconnect cleanup and resets scans when the selected method or minimum-cell threshold changes. It reads chunks already held by the client and does not request terrain or change outgoing interactions. Results depend on what a server actually transmits.

The scanner is an independent **Visual** module, disabled by default, with favorites, a saved assignable shortcut, settings persistence and an active-module HUD entry. It uses Donut's current native world renderer and key/input infrastructure; the existing Base Finder remains independently usable.

The adapted implementation is `amethyst/AmethystScanner.java`. The original GPL license and a separate amethyst-port notice are bundled in the installable mod and matching source artifact. Original Donut authorship and MIT notices are retained for its existing integration code.

Runtime light fixtures use synthetic packets from a disposable integrated test server to reproduce the reference signatures. They validate decoding and rendering, without asserting that the same signatures are available from every external server or anti-xray implementation.
