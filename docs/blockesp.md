# BlockESP

BlockESP highlights **deepslate emerald ore** and **deepslate coal ore**. It combines two sources:

1. **Received-chunk ESP (always available).** Every tick the exact target blocks in chunks the server
   has already sent are highlighted. No seed, simulation, or server cooperation is involved, so this
   works on any server — including servers where seed prediction has been patched. It can only show
   what the server sends: anti-xray may disguise unexposed ores as stone/deepslate, so hidden ores
   may be absent until they are exposed, while exposed ores are shown truthfully. Received ores are
   remembered per chunk (up to 512 chunks), so an ore the server revealed while you were mining
   stays marked after its chunk unloads; a loaded chunk is authoritative and forgets ores that have
   since been mined.
2. **Vein inference (probable, dimmer).** Deepslate coal generates in connected blobs of 20–30
   blocks, so when part of a vein is received, its face-neighbours that still read as deepslate —
   exactly what obfuscation disguises hidden coal as — are probably the same vein. Those positions
   are shown at one-third opacity as hypotheses. Deepslate emerald generates as single scattered
   blocks and never gets inferred markers. Inferred markers never count as seed-trust evidence.

There is no third source, and there cannot be one: a fully hidden ore on a server that both
obfuscates chunk data and scrambles feature seeds is, information-theoretically, not present in
anything the client receives and not derivable from any seed the client can know. BlockESP extracts
everything the client legitimately receives (including vein shape), and nothing it does not.
2. **Seed prediction (when it still applies).** For vanilla 1.21.11 Overworld terrain generated
   from the configured world seed, a local vanilla generation world reconstructs the original ore
   positions, including ores anti-xray hides.

Open **Right Shift → Visual → BlockESP** (or search for `BlockESP` or `deepslate`). Use the module's switch to enable it. The world seed starts at **6608149111735331168**. Choose **Edit world seed** to enter another signed 64-bit numeric seed; Apply validates the value, while Cancel preserves the previous seed. Seeds are saved as strings so JSON cannot round large values. An unreadable seed disables prediction only — received-chunk ESP keeps working and the HUD says so.

Both ores start selected. Their switches and colors are independent. **Prediction radius** controls the square of nearby chunks around the player, from 1 to 8 chunks in each horizontal direction; it bounds both the received-chunk scan and prediction. All heights are checked, including deepslate coal above Y=0. A radius of 4 covers 81 chunks. Large initial scans take longer. Travel starts work for the new area, nearest chunks first.

World markers show an outline and a light fill through terrain. **Opacity** adjusts the markers; **Tracers** adds lines from the camera. **Coordinates / distance HUD** displays progress, counts, and the nearest selected ore of each type. The HUD respects Minecraft's F1 and Donut's global HUD toggle. The module supports Favorites, the active-module list, and a shortcut assignable in its inspector or Minecraft Controls.

## Why seed prediction stopped working, and how BlockESP detects it

Ore placement is deterministic, but it is not necessarily a function of the world seed a player can
know. Paper servers can enable `feature-seeds.generate-random-seeds-for-all`, which gives every
feature — ores included — its own secret seed; servers can also rotate seeds, change the world seed
for a new season, or generate chunks with custom/independently seeded generation. In all of those
cases the client's faithful vanilla simulation still produces positions, but they are effectively
random with respect to the real world. Showing them would be showing noise.

BlockESP therefore cross-checks prediction against reality every tick, using the ores in received
chunks whose prediction has completed. Only **exposed** received ores (a face-neighbour that does
not fully occlude them: cave openings, fluids, partial blocks) count as evidence. That restriction
matters: anti-xray engine modes 2/3 fabricate buried ores wholesale, so buried received blocks prove
nothing and must never be able to refute — or verify — a seed. Vein inference is likewise only
seeded from exposed received coal.

- A correct seed misses a natural ore with probability **zero** (FULL-status generation reproduces
  every original ore, including neighbouring-chunk feature writes), so every received ore should be
  predicted exactly — position and variant.
- A wrong seed predicts only a handful of target positions per chunk out of
  16 × 16 × 384 = 98,304 block positions, so the chance of it accidentally matching one specific real
  ore is at most roughly 20 / 98,304 ≈ 2×10⁻⁴. Even one exact match is strong evidence the seed
  still governs a chunk.
- **Verified**: every observed ore was predicted. Both sources are shown (they agree).
- **Refuted**: at least 3 observed ores and none predicted (three independent player-placed,
  silk-touched ores in predicted chunks is not a plausible alternative). Predictions are suppressed,
  generation stops instead of wasting CPU on random positions, and the HUD switches to
  received-chunk ESP with the status `Seed no longer matches this world …`.
- **Mixed**: some ores match and some do not — typically chunks generated before a server enabled
  feature seeds alongside newer chunks. Predictions are kept; received ores are added regardless.
- One or two misses with no matches are treated as *suspect* (possibly player-placed ores), not proof.

Changing the seed, rescanning, leaving the Overworld, disconnecting, or disabling clears the
displayed results and re-runs the check. All markers from prediction are **original generation**
positions, not later player changes. A received air block suppresses an obsolete predicted marker,
while a solid mismatch does not: anti-xray can disguise an existing ore as stone or deepslate. The
HUD's counts cover the selected scan area, not the entire world.

Match the version that originally generated the chunks. A server running 1.21.11 may still contain older chunks. Custom world generators, terrain presets, generation datapacks, modified block tags, or separate feature seeds are outside the predictor's scope; received-chunk ESP is unaffected by all of them. **Rescan predictions** clears cached predictions and retries a failed scan; it cannot reconstruct a server's mining history.

## Generation and resource use

The module loads the vanilla normal world preset and uses Minecraft's actual chunk scheduler through FULL status. FULL status includes the neighboring feature writes that can cross chunk borders; simulating only ore placement or stopping at FEATURES can miss those writes. It scans generated block states for the two exact block IDs and skips section palettes without either target. The received-chunk scan reuses the same exact-block section scan over the client's chunk cache and never requests chunks from the server.

The generation world belongs to one background worker. It has no players or listening sockets and never runs gameplay ticks. Its files are confined to a unique temporary directory under the active instance's `.cache/donut-blockesp/`, which is removed when that generation session closes. It does not access the user's saved worlds or request extra chunks from the connected server. The active world's registry tags are not replaced when loading prediction resources; matching vanilla block tags are required.

One chunk request is in flight at a time. The client keeps at most 512 sparse ore-result chunks. The generation session is recycled after 128 requested chunks to bound its working data, and vanilla request-ticket expiry releases old chunks between batches. Seed/world changes invalidate pending work so an old result cannot appear in a new world. Cancellation lets the current vanilla generation operation finish, then releases the scratch world on its owner thread. Generation is not scheduled at all while the configured seed is unreadable, and stops being scheduled once the seed is refuted; rescanning or fixing the seed resumes it.

## Validation

Run `./gradlew test` for numeric seed, persistence, exact-block filters, seed-trust decision math, and negative-coordinate scheduling checks. Run `./gradlew runClientGameTest -PblockEspTestsOnly=true` for the fresh vanilla comparison, exact variant/height fixture, native seed validation, the wrong-seed refutation/received-chunk fallback and seed-restoration checks, preferences, rendering, shortcut, and lifecycle checks. The focused flag also runs the existing module-keybind regression suite. Test classes are excluded from the release JAR.
