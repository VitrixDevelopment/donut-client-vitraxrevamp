# Spectre Base Finder port

Donut's Base Finder now adapts Spectre 26.2's **Sus Chunk Finder**, as selected by the user. This is separate from Spectre's Chunk Scanner and Prime Chunk Finder. The reference package credits **4ldenz** and uses **GPL-3.0-only**. The adapted scanner, activity classifier, object grouping, light scanner and inspection panel carry that attribution. The GPL text and a separate port notice are bundled in the mod.

The port retains the current Base Finder module slot, activation preference, favorites and `key.donut.toggle_chunk_finder` shortcut. Donut supplies its settings persistence, native GUI, render pipelines and HUD registration. Other module controllers are unchanged.

`ChunkFinderScanner` adapts `SusChunkFinderModule`: its Classic mature-crystal/support classification, DonutFolia palette handling, neighboring-chunk heat and immature-bud vetoes; ANBS+ block-light edge scanning through `GeodeGlowScan`; and Activity clues through `ActivityClues`. All three modes retain Spectre's object discovery. Received chunk, block, section, light and unload packets refresh the findings after vanilla handles them. Only already received world data is read; no extra chunk requests are made.

`SuspectObjects` retains the ten colored categories, block shapes, armor stands/item frames, range and 2,048-detail limit. Connected amethyst is grouped across chunk borders, with separate crystal-stage icons, measured maturity and a minimum cluster count. Kelp, vines, cave vines and bamboo use spatial lengths. Short/immature formations are optional. Bee evidence uses honey-bearing nests and crafted beehives. Natural growth remains inconclusive evidence.

`BaseFinderPanel` adapts `SuspectPanel` to Minecraft 1.21.11's native `GuiGraphics`. It retains the projected connector, grouped nearby item counts, category legend, formation details and readable screen size. Projection uses the active world camera, view matrix and dynamic field of view. Donut's interface scale controls the card size; its independent material settings style the card.

The current card uses a dark frosted-glass gradient with independent top/bottom colors and opacity stored in Base Finder settings. It shares the scene-backed GPU glass renderer with the ClickGUI while keeping the alert palette separate. Geist text, fine dividers and a subdued outline replace the former flat workspace-colored fill. Counts and detection data remain unchanged.

New configurations use Spectre's Classic mode, simulation-distance field of 5, sensitivity of 6, inspect range of 128 blocks and conservative growth defaults. Existing saved activation, favorites, clue filters, numeric values and opacity are retained. The former Smart adjustment preference backs the inverse **Include short / immature growth** control. Legacy global highlight color is retained in JSON; object outlines use independently editable category colors. Simulation distance shapes clue propagation; it does not alter Minecraft's simulation distance. Lower sensitivity values mean fewer required points.

The test-only positive ANBS fixture transmits synthetic block-light data to reproduce the anti-xray-style signature in a disposable integrated-server world. It does not claim that ordinary vanilla lighting produces that signature, or that a remote server was tested.
