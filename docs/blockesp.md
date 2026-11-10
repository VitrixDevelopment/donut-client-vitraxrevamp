# BlockESP

Unfortunately, this module is currently patched on the server.

BlockESP predicts the original locations of **deepslate emerald ore** and **deepslate coal ore** in normal vanilla Minecraft Java **1.21.11** Overworld terrain.

Open **Right Shift → Visual → BlockESP** (or search for `BlockESP` or `deepslate`). Use the module's switch to enable it. The world seed starts at **6608149111735331168**. Choose **Edit world seed** to enter another signed 64-bit numeric seed; Apply validates the value, while Cancel preserves the previous seed. Seeds are saved as strings so JSON cannot round large values.

Both ores start selected. Their switches and colors are independent. **Prediction radius** controls the square of nearby chunks around the player, from 1 to 8 chunks in each horizontal direction. All heights are checked, including deepslate coal above Y=0. A radius of 4 covers 81 chunks. Large initial scans take longer. Travel starts predictions for the new area, nearest chunks first.

World markers show an outline and a light fill through terrain. **Opacity** adjusts the markers; **Tracers** adds lines from the camera. **Coordinates / distance HUD** displays progress, counts, and the nearest selected ore of each type. The HUD respects Minecraft's F1 and Donut's global HUD toggle. The module supports Favorites, the active-module list, and a shortcut assignable in its inspector or Minecraft Controls.

## Areas that do not match the seed

A server can generate one region from the world seed and another with separate feature seeds: the terrain, caves and structures are identical, but ore is placed differently. Nothing in the terrain reveals which case an area is in, and after an RTP you often land in an area that does not match - every marker there is then wrong.

BlockESP tells the two cases apart with ore your client actually received. Anti-xray can disguise buried ore as stone, but it never hides ore already exposed to a cave, so each exposed ore is either a block the prediction also places (a hit) or one it does not place anywhere in that chunk or its eight neighbours (a miss). Counts are kept per **area of 8x8 chunks (128 blocks)** and turn into a verdict:

* **Confirmed** - at least 3 hits and hits are at least half the evidence. Markers here are reliable.
* **Unmatched** - at least 4 misses and hits are under a fifth of the evidence. The server placed ore differently; markers here are hidden and a chat notice appears once per area.
* **Unverified** - anything in between. Markers are shown normally.

Verdicts are saved to `config/donut/blockesp-areas.txt` (one line per area, `key;hits;misses`), so travelling does not discard them. **Show areas that do not match** reveals rejected markers for inspection, **Confirmed areas only** hides everything not yet proven, **Chat notices** announces each verdict, and **Clear learned area evidence** starts the checking over.

## Mined check

Predicted positions that are already air or liquid were removed after generation, because ore never generates into either. Control spots two blocks away at the same height are sampled at the same time, since caves and tunnels also contain air. When predicted spots are air far more often than their controls, the marker count, the seed and the area verdict are corroborated - the ore the module shows was really there. This option defaults on.

## Nearest emerald card

**Nearest emerald card** searches for the closest predicted **deepslate emerald** up to a configurable **Search range** (256-1500 blocks, 1500 by default), well beyond the prediction radius. Emerald only generates in mountain biomes, so the module samples the seeded multi-noise biome layout on a 32-block grid - three heights per column - and generates only the chunks around the six closest candidates. Rejected areas are skipped, and predicted emeralds already seen as air are dropped. The card shows the ore type, distance, an arrow relative to where you are looking, a compass letter, the vertical offset, the coordinates, and whether that area is confirmed. Searches run on the background worker and restart after travelling more than 500 blocks or when the current list is used up.

All markers are **predictions**. A seed reconstructs original generation, not later player changes. A received air block suppresses an obsolete marker, while a solid mismatch does not: Anti-Xray can disguise an existing ore as stone or deepslate. The HUD's counts cover the selected scan area, not the entire world.

Match the version that originally generated the chunks. A server running 1.21.11 may still contain older chunks. Custom world generators, terrain presets, generation datapacks, modified block tags, or separate feature seeds are outside this module's scope. Changing the seed, leaving the Overworld, disconnecting, or disabling clears the displayed results. **Rescan predictions** clears cached predictions and retries a failed scan; it cannot reconstruct a server's mining history.

## Generation and resource use

The module loads the vanilla normal world preset and uses Minecraft's actual chunk scheduler through FULL status. FULL status includes the neighboring feature writes that can cross chunk borders; simulating only ore placement or stopping at FEATURES can miss those writes. It scans generated block states for the two exact block IDs and skips section palettes without either target.

The generation world belongs to one background worker. It has no players or listening sockets and never runs gameplay ticks. Its files are confined to a unique temporary directory under the active instance's `.cache/donut-blockesp/`, which is removed when that generation session closes. It does not access the user's saved worlds or request extra chunks from the connected server. The active world's registry tags are not replaced when loading prediction resources; matching vanilla block tags are required.

One chunk request is in flight at a time. The client keeps at most 512 sparse ore-result chunks. The generation session is recycled after 128 requested chunks to bound its working data, and vanilla request-ticket expiry releases old chunks between batches. Seed/world changes invalidate pending work so an old result cannot appear in a new world. Cancellation lets the current vanilla generation operation finish, then releases the scratch world on its owner thread.

## Validation

Run `./gradlew test` for numeric seed, persistence, exact-block filters, negative-coordinate scheduling, area-verdict thresholds and floors, verdict file round-tripping, mined-test sampling, the emerald search's ring walk and the HUD's text helpers. Run `./gradlew runClientGameTest -PblockEspTestsOnly=true` for the fresh vanilla comparison, exact variant/height fixture, native seed validation, preferences, rendering, shortcut, and lifecycle checks. The focused flag also runs the existing module-keybind regression suite. Test classes are excluded from the release JAR.
