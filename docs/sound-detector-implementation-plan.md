**Sound Detector: detailed implementation plan for Donut Client**

Design dated November 6, 2026. Target: the client-only Fabric mod for Minecraft Java 1.21.11, JDK 21, official Mojang mappings. The client implementation is included in version 1.19.0. This document retains the original design requirements; consult TESTING.md for implemented and tested scope. Actual-server captures, true simultaneous two-client attribution testing, and individual validation of every special block interaction remain separate validation work. The runtime coverage export explicitly marks special routes that still require review.

The proposed module is named **Sound Detector**, appears in Visual, and detects received evidence of nearby interactions, players, and operating machinery. It presents an independently customizable warning when an event meets the user's definition of abnormal activity. Its strongest design requirement is to distinguish the event that happened from the identity of whoever caused it.

The companion [complete block inventory](sound-detector-block-inventory.md) enumerates every public Block constant found in the project's cached 1.21.11 game classes. It is a review checklist, not a claim that every listed action produces a remotely observable sound.

1. **Product behavior and limits**

The module should answer four questions: What happened? Where was it observed? What evidence links it to a player? Why did it trigger this warning? A concise warning could say, "Unexpected container activity · Chest opened · 22 m NE, 8 m below · Actor unknown." An attributed warning could say, "Player activity · Alex breaking stone · 14 m west." Only the second wording is appropriate when the received evidence actually identifies that player.

Maintain separate labels for confirmed remote player, possible player interaction, machinery, non-player entity, natural/environmental event, local action, and unknown. Keep warning priority separate from attribution: a loud explosion may deserve urgent attention while its cause remains unknown. Numeric scores are heuristic points, never percentages representing a measured probability.

Capture server-to-client events already delivered during ordinary play. A positional sound packet contains a sound reference, source category, coordinates, volume, pitch, and a variant seed; an entity sound packet refers to an entity ID. Neither its source category nor a player-like sound name proves a human caused it. The seed is not the world seed. [Fabric positional packet mappings](https://github.com/FabricMC/yarn/blob/1.21.11/mappings/net/minecraft/network/packet/s2c/play/PlaySoundS2CPacket.mapping), [entity packet mappings](https://github.com/FabricMC/yarn/blob/1.21.11/mappings/net/minecraft/network/packet/s2c/play/PlaySoundFromEntityS2CPacket.mapping).

The cached vanilla code uses SoundEvent.getRange(volume) for ordinary sound broadcasts: a variable-range event uses 16 times max(1, volume), while fixed-range events use their declared range. Delivery is generally a three-dimensional distance check from the server player's body. Vanilla block-event broadcasts use 64 blocks. These are route-specific baseline observations, not a universal maximum or a promise about the user's server. Sound attenuation, resource packs, plugins, proxy translation, and event-specific paths must be assessed separately. [Fabric's sound documentation](https://docs.fabricmc.net/1.21.11/develop/sounds/using-sounds) explains the volume/range relationship.

The interface must explain that its range control filters events the client receives. It cannot request sounds from farther away. Silent bases and unloaded areas that produce no delivered events provide no sound evidence. No negative result should be interpreted as an empty area.

2. **Independent module with optional Base Finder integration**

Add Sound Detector as module ID 12, preserving IDs 2 through 11. Increase the existing module count from ten to eleven, update its keybinding storage, and audit tests that assume the old count. Keep it independently enabled, independently favorited, searchable by sound, audio, player, activity, alarm, warning, chest, and machinery, and visible in the active-module HUD. Its toggle shortcut starts unbound. The module starts disabled for new configurations.

Use one shared incident model for alerts, history, and optional world markers. Base Finder may display a "Recent sound activity" attachment when both modules are enabled, but sound evidence must not alter the existing growth/light scoring or depend on Base Finder activation. Enabling sound detection should not activate another scanner.

Provide four initial presets: **Balanced activity**, **Players only**, **Base activity**, and **Diagnostics**. Balanced activity accepts useful interaction cues with honest actor labels. Players only requires direct attribution to a known remote player and explains that many unbound sounds will be omitted. Base activity also watches new machinery and repeated building/mining patterns. Diagnostics records all supported observations in bounded memory and does not make unknown sounds alert by default. Applying a preset changes detection settings; replacing appearance or custom rules requires an explicit preset option.

3. **Evidence model and processing pipeline**

Use a pipeline of capture, normalization, duplicate reconciliation, context lookup, attribution, rule evaluation, incident aggregation, and alert delivery. Preserve the original observation in diagnostics so a derived explanation can always be traced to its source.

| Proposed model | Required information |
|---|---|
| SoundObservation | Local observation ID; session/world epoch; monotonic receipt time; packet route; optional sound ID; sound category; volume/pitch/seed when supplied; entity ID when supplied; original position; position precision; block event/type/data; global/local flag |
| ContextSnapshot | Current world key; body and camera positions; existing block state when available; remote-player/non-player candidates; matching local actions; already-known automation; loaded/unloaded/unknown status |
| LogicalActivity | Stable activity ID; action family; observations that describe it; attribution and reason; source location and uncertainty; optional authoritative or inferred block identity |
| ActivityIncident | One bounded source/region cluster; first/last observation; independent action counts; family diversity; score; priority; explanation; muted/acknowledged state |
| AlertPresentation | Immutable incident summary; display text; icon; direction; timing; animation state; chosen visual/audio policy |

Use monotonic time for windows, cooldowns, animation, and expiry. Wall time is only for displayed timestamps and exports. A receipt timestamp is not the exact time the server action occurred. Stamp the current client world at receipt; sound packets do not carry an independently verified originating dimension.

Keep exact coordinates alongside any block/grid bucket. Positional sound coordinates have 1/8-block resolution; block events provide a BlockPos; resolved entity positions are client snapshots at receipt. A chest's sound can originate between the halves of a double chest, so a sound coordinate must not automatically be treated as the exact identity of one block. All coordinate bucketing must use floor semantics at negative coordinates.

4. **Capture routes and threading**

Create a dedicated SoundDetectorPacketMixin rather than expanding BaseFinderPacketMixin into an unrelated event bus. Verify every method descriptor against the cached 1.21.11 classes during implementation. Prefer a RETURN injection after vanilla's thread handoff has succeeded, then enqueue only a compact immutable observation. RETURN should still record an entity sound with an unresolved ID when vanilla returns early because the entity is absent.

| Packet / handler in Mojang mappings | Purpose and handling |
|---|---|
| ClientboundSoundPacket / handleSoundEvent | Primary positional sound; preserve exact sound ID, source category, position, volume, pitch, and seed |
| ClientboundSoundEntityPacket / handleSoundEntityEvent | Preserve the referenced entity ID; resolve cautiously; an unknown ID provides no coordinate or identity by itself |
| ClientboundBlockEventPacket / handleBlockEvent | Container, piston, note-block, bell, and other version-verified block actions; decode per block class and event code |
| ClientboundLevelEventPacket / handleLevelEvent | World effects, break/effect sounds, jukebox and other version-verified events; decode payload per event; preserve global flag |
| ClientboundEntityEventPacket / handleEntityEvent | Version-verified entity status events that can cause sounds; a status byte is not automatically human activity |
| ClientboundExplodePacket / handleExplosion | Preserve explicit explosion center and supplied sound; do not infer the igniter from the packet alone |
| ClientboundBlockDestructionPacket / handleBlockDestruction | Supplemental mining evidence and breaker ID; accept attribution only after resolving and validating its semantics |
| ClientboundDamageEventPacket / handleDamageEvent and hurt animation | Supplemental combat attribution where delivered; distinguish victim, direct cause, and causing entity |
| Received block/section/chunk changes | Update bounded context and known state; state changes alone are not sound observations |
| ClientboundStopSoundPacket / handleStopSoundEvent | Stop or shorten a matching sustained audio observation when applicable; not proof of a player action |

The block-event packet supplies block, position, type, and data. The level-event packet supplies an event ID, data, position, and a global flag. These fields require route-specific interpretation. [Fabric block-event mappings](https://github.com/FabricMC/yarn/blob/1.21.11/mappings/net/minecraft/network/packet/s2c/play/BlockEventS2CPacket.mapping), [world-event mappings](https://github.com/FabricMC/yarn/blob/1.21.11/mappings/net/minecraft/network/packet/s2c/play/WorldEventS2CPacket.mapping).

Do not read the world from a generic network-thread HEAD injection. If a classifier needs a pre-change block state, use a small existing-state cache or an explicitly verified injection after ensureRunningOnSameThread and before the mutation. Deferred vanilla work must not be mistaken for a second event. Disabling the module makes capture return immediately.

Include a configurable client sound-dispatch observer for locally reconstructed movement and environmental sounds, with client-derived environmental detection disabled by default. Give those observations a separate CLIENT_DERIVED provenance and lower default relevance. Observe logical dispatch before audio-device/channel allocation and volume rejection, and correlate with an incoming-handler scope when the same sound was produced by an already captured packet. Preserve known entity context where the call path provides it; a generic sound-engine callback with only coordinates cannot supply an actor. A reconstructed tracked-player sound may identify its tracked entity while retaining client-derived provenance. It must never turn a purely client-generated sound into "server confirmed." Do not depend on actual audio-device playback for primary detection: muted audio, missing resources, and channel exhaustion should not remove a received packet from the detector.

5. **Complete block coverage, including shared sounds and silent behavior**

The implementation should enumerate the runtime block registry and sound-event registry after bootstrap, not rely on a hand-maintained list of familiar blocks. The cached Blocks class currently declares **1,142 public Block constants**, and SoundType declares **123 public material sound groups**. These are static inspection counts; the runtime registry dump is the authoritative count for the final coverage report. The companion inventory makes every inspected constant reviewable.

For every registered block, record its registry ID, implementation class, relevant state variants, material sound group(s), and the sound IDs for break, step, place, hit, and fall. Enumerate distinct SoundType results across valid states where practical; do not assume default state is sufficient. Use family equivalence to avoid repeating identical review work, while retaining one coverage row per block. Include wall/floor forms, standing/wall signs, slabs/stairs/walls, color variants, wood families, waxed/oxidized copper states, lit/powered/open states, and waterlogged cases where sound behavior changes.

Each block action needs an explicit coverage status: **verified packet**, **verified event-derived**, **client-derived only**, **silent/no public signal**, **ambiguous shared sound**, or **unverified**. A block may have several statuses for different actions. Every special interaction should record its actual server call path, payload decoder, range rule, self-action handling, non-player causes, and fixture result. An unverified route is recorded as such and cannot silently become a high-priority detector rule.

Build a reverse multimap from sound ID to candidate block/action families. Many blocks share wood, stone, metal, grass, or other sounds. "Stone broken" does not uniquely identify a furnace, ore, or construction block. Refine the candidate set only when an event payload, already-received state, or reliable context supports it; otherwise display the material/action and retain uncertainty.

| Review family | Blocks/actions that must be covered | Expected interpretation and essential caveat |
|---|---|---|
| Ordinary construction and terrain | Every solid/decorative block, every wood/nether/bamboo family, ores, stone/deepslate/tuff, dirt/mud/sand/gravel, concrete, terracotta, glass, wool, resin, metal/copper, ice/snow, nether/end materials | Audit break/place/hit/step/fall across all 123 material groups; natural destruction, gravity, explosions, and pistons can share outcomes |
| Conventional storage | Single/double chests, trapped chests, ender chests, barrels, every shulker-box color | Open/close and opener/block-event changes; collapse double-chest duplicates; never expose unavailable contents or assume an opener is a player |
| Copper storage | All eight waxed/oxidized copper chest variants | Include variant-specific sound IDs and non-player opening; copper golems invalidate a blanket chest-sound-equals-player rule |
| Shelves and books | All twelve shelves, chiseled bookshelves, ordinary bookshelves | Item insertion/removal/swap and powered multi-swap versus ordinary material sounds; a powered shelf state alone is not evidence of an interaction |
| Workstations and containers | Anvils including damaged states, grindstone, stonecutter, smithing/crafting/cartography tables, loom, fletching table, enchanting table, furnaces, smoker, blast furnace, brewing stand, lectern | Separate audible operations from GUI-only/private/local actions; verify each claimed remote cue; ambient furnace crackle does not prove a fresh player interaction |
| Automation and item transfer | Pistons/sticky pistons/heads/moving blocks, dispensers, droppers, crafter, hoppers, hopper minecart context | Extension/retraction, dispense/fail, craft/fail, and resulting changes; hopper transfers may be silent; automation suggests machinery rather than an active human |
| Redstone | Levers, every button and pressure plate, tripwire/hooks, repeaters/comparators, observers, redstone wire/torches, lamps, copper bulbs, target blocks, daylight detector, redstone blocks | Distinguish audible switches from silent signal propagation; mobs, arrows, items, and clocks can activate components |
| Doors and barriers | Every wood/nether/bamboo/copper/iron door and trapdoor, fence gates, fences/walls | Open/close, latch, and manual/redstone behavior; villagers, mobs, and redstone invalidate automatic human attribution |
| Music and bells | All note-block instruments including custom skull sounds, jukebox songs, bells | Decode actual event/registry payloads; collapse client playback into the originating event; redstone can play music, and bells may be rung by non-player causes |
| Decorated pots and archaeology | Decorated pots, flower pots/potted plants, suspicious sand/gravel, brush interactions | Insert/fail/shatter/extraction versus generic material sound; contents may be inaccessible and generic sounds do not identify them |
| Liquids and containers | Water/lava, cauldrons of each content/level, buckets, bottles, sponges/wet sponges, dripstone filling | Separate an item-use interaction from natural flow/drips/evaporation and dispenser use; a state change is not an automatically audible action |
| Fire, light, and ignition | Fire/soul fire, campfires, candles/candle cakes, torches/lanterns including copper, furnaces, TNT ignition, extinguishing | Distinguish ambient loops, use actions, weather, and fire spread; a torch's static presence does not generate a detection |
| Agriculture and food | Crops/farmland, composter, berries/cave vines, cocoa, kelp, bamboo, sugar cane, cactus, pumpkins/melons, mushrooms, cakes, harvesting/shearing/bonemeal | Growth and many changes can be silent or natural; harvest sounds require route and actor checks; dispensers can perform some actions |
| Bees and hives | Bee nests, crafted beehives, honey collection, shearing, bee entry/exit/work | Separate bee ambience and autonomous behavior from collection; nearby bees alone are not a player signal |
| Plants and biome ambience | Leaves, leaf litter, moss, vines, roots, dripleaf, flowers/eyeblossoms, dry grasses/dead bushes/cactus flowers, aquatic plants | Biome/client animation and random-tick sounds are environmental by default; a growth cue is not a timestamp of human activity |
| Sculk and ancient-city features | Sculk, veins, sensors including calibrated sensors, shriekers, catalysts, reinforced deepslate context | Sensor activation indicates a triggering vibration, with many possible sources; server GameEvent propagation is not a generic client sound-packet stream |
| Geodes and resonance | Amethyst blocks, budding amethyst, every bud size/cluster, calcite, related resonance | Resonance, steps, projectiles, growth, breaking and placement must be separated; natural formations do not prove a base |
| Trials and generated mechanisms | Spawners, trial spawners in all relevant states, vault including ominous context, heavy core | Spawn/ambient/activation/unlock/eject/fail actions can come from generated structures and autonomous processes; verify each route before choosing default alerts |
| Pale Garden and related blocks | Creaking heart, resin/resin bricks/clumps, pale wood/moss, eyeblossom states | Natural activation/ambient behavior is not player activity; manipulation/breaking remains a separate candidate |
| Eggs and hydration | Turtle/sniffer/dragon eggs, frogspawn, dried ghast hydration states, related hatching | Time, water, mobs, and movement can cause effects; hatching/hydration has no automatic human attribution |
| Movement surfaces and gravity | Slime/honey blocks, ladders, scaffolding, powder snow, soul sand, dripleaf, hay, beds, cobwebs, falling blocks | Material/surface sounds do not identify the walker; collapse step/fall/landing cues and respect sneaking/flying/silent paths |
| Transportation | All rails, boats/rafts, minecarts, powered/activator/detector rails | Vehicle movement can be unattended; entity ownership or proximity alone does not identify an operator |
| Portals, respawning, and explosions | Nether/end portals and frames, end gateway, respawn anchor, every bed color, TNT, end crystal/projectile context | Separate global effects from localized explosions; cause remains unknown without supporting entity/action evidence |
| Utility and uncommon interactions | Beacons, conduits, lodestone/compass use, lightning rods, copper golem statues/poses, banners/signs/hanging signs, heads, item frames/armor stands context, snow/pumpkin/golem creation | Audit special use sounds and natural changes; entities are a parallel catalog, not blocks; many editing/GUI actions have no public remote cue |
| Administrative and internal blocks | Air/cave air/void air, bedrock, barrier, light, structure blocks/void, jigsaw, command blocks, moving/piston internals | Explicit silent/generic/internal entries; no inference that a silent administrative block is present, and no reliance on privileged debug feeds |

Copper golems sorting items into copper, ordinary, and trapped chests are documented vanilla behavior. Shelves also have insertion/removal/swap operations beyond their material sounds. These deserve dedicated cases in this target version. [Mojang's Copper Age release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9).

Unknown modded blocks inherit only generic observed material/action classification. Unknown sound IDs and inlined custom sound events remain inspectable with zero default abnormality weight. Server resource packs may change the audio file behind an existing identifier without changing what the packet proves. Rebuild catalogs on resource/registry changes, retain diagnostics for unresolved IDs, and re-audit after a game-version change. Do not classify arbitrary IDs with substring rules such as "contains chest".

6. **Player, entity, item, and combat coverage**

Build a parallel event catalog for movement/steps, jump/landing/swimming, equipment changes, eating/drinking, bows/crossbows, projectiles, shields, melee/sweep attacks, damage/death, item drops/pickups, fishing, ender pearls, teleportation, elytra/fireworks, mounts, and interaction with item frames/armor stands. Audit the actual route for every action. Some actions are locally reconstructed from tracked entity state or use other packets; the presence of an animation or sound ID does not guarantee a dedicated sound packet.

Resolve an entity sound to a player only if that packet's referenced entity resolves to a remote player in the same world/session. A projectile, item, vehicle, or mob remains that entity type; its owner/rider can be used only if known from already-received data and suitable for the event. A damage packet's victim must not be mistaken for its attacker. Tab-list presence without positional/entity data is insufficient to locate or attribute an unbound sound.

Never label the nearest player as the actor. A nearby player is a contextual candidate with a stated distance/time relationship. Multiple candidates, a hidden actor, an unresolved ID, or a nearby copper golem must remain ambiguous. Preserve UUID plus entity lifetime/session so reused numeric entity IDs cannot inherit an old player's identity or trusted status.

7. **Local-action filtering and false positives**

Observe existing local interaction calls or outbound vanilla interactions without modifying or sending any extra packet. Track target block/entity, action family, tick, local prediction sequence when already available, and monotonic time for mining, placement, item use, attacks, and container interactions. Cover actions produced through both existing AutoBuilder engines. Keep this history short and bounded.

Use three self-attribution levels: explicit self ID, strong action/target/time match, and weak proximity-only match. Explicit self events are suppressed by default. A strong local action match can be marked probable self and suppressed or downweighted according to the preset. Weak proximity alone never suppresses a warning by default: another player can operate a chest beside the local player.

Use a configurable match window, initially 0.25-3 seconds with a 1-second default, adjusted conservatively using observed latency when available. An outgoing attempt does not prove success: a rejected block placement or interaction must not suppress an unrelated event. An explicitly identified remote player overrides a weaker self match. Own movement and freecam camera movement must not create other-player activity.

Other built-in controls cover mobs, copper golems, natural ambience, periodic machinery, global world sounds, own projectiles/vehicles when ownership is known, trusted UUIDs, and manually designated expected areas. Being on the same scoreboard team does not automatically make a player trusted. Unknown actors cannot be suppressed simply because a trusted player is nearby.

For global level events, preserve the global flag and do not create a precise nearby-source arrow from an unverified position. For occlusion, a cheap loaded-world raycast may label a source as visually blocked; unknown terrain yields unknown occlusion. Occlusion may increase attention under a user rule but must not increase player-attribution certainty.

8. **Logical event reconciliation and source aggregation**

A single interaction can produce a block event, a sound packet, a state update, and optional playback. These are several observations of one action. Reconcile compatible routes by session, world, action family, authoritative source/nearby coordinates, and time; retain their evidence links but count the logical action once. Begin with a 150-millisecond cross-route reconciliation window and configurable upper bounds for delayed servers.

Do not deduplicate two same-route events solely because their sound ID or seed matches. Repeated note-block pulses, two players acting simultaneously, chest open followed by close, and two different blocks in one second must remain distinct when the evidence supports them. The seed is useful metadata, not a globally unique event key. Handle double chests and other multi-block actions through explicit source canonicalization.

Aggregate stationary sources separately from moving entities. Initial incident buckets are a 4-block horizontal cell with an 8-block vertical tolerance; confirmed entities follow their own identity instead. Make grouping adjustable, and split incompatible sources/actors instead of merging an entire cave into one warning. Keep first-seen, last-seen, logical count, independent family count, most informative block/action, and a maximum score. Location uncertainty survives aggregation.

Start with a 10-second evidence window, a 5-second repeat-notification cooldown per incident, and 30-second marker retention. Counts and scores expire with a sliding window. A quiet period re-arms the source; an endlessly repeating machine should update a count rather than continuously chime. One severity escalation may bypass a source cooldown; cap repeated escalations globally.

9. **Definition of abnormal activity and starting scoring rules**

Use deterministic, inspectable rules. The user can define abnormal as an attributed remote player, an unexpected interaction, a new machine source, a burst of building/mining, or a source entering a watched area. The interface must show which rule fired and which filters were applied.

| Observation after reconciliation | Starting points / policy |
|---|---|
| Validated event directly attributed to a remote player | 70; direct-player rule can qualify immediately |
| Version-verified interaction such as a container opening or specialized workstation operation, actor unknown | 50; warn as an interaction with unknown actor, never as a confirmed player |
| Unattributed generic block placement or break | 25; require a burst, family diversity, watched-area rule, or stronger evidence |
| Machinery-only activity | 15; capped at 35 in Balanced activity; eligible for explicit Base activity/new-source rules |
| Movement-like material sound without an actor | 10; cannot independently confirm a player |
| Mob, ambience, growth, hydration, or global environmental event | 0 by default; optionally visible in history |
| Explicit local actor | Excluded by default |
| Probable local action | Downweight or suppress according to profile, with an explanation |
| Unsupported/custom/unverified event | 0 by default; available to user-defined rules after inspection |

Within the evidence window, start with the strongest logical action, add up to 20 points for genuinely independent action-family diversity and up to 10 points for repetition, then apply explicit profile/rule adjustments. Copies of one action from several packet routes add no repetition or diversity. Automation-only incidents retain their profile cap regardless of volume. An environmental sound cannot become human evidence merely by repeating thousands of times.

Initial Balanced thresholds are 50 for Warning and 85 for Critical. Critical requires either meaningful attributed/combined evidence or an explicit user danger rule, such as an explosion inside a watched radius. These are tuning defaults for fixture/live capture review, not empirically calibrated probabilities. Changing an attention score never changes an actor from unknown to confirmed.

Priority can be Info, Warning, or Critical. Use threshold hysteresis so score fluctuations do not repeatedly promote and demote the same incident. Add a two-second join/teleport/dimension-change warmup that collects observations without noisy alerts; directly attributed events or configured danger rules may bypass it. Detection and abnormality settings can be previewed against recorded fixtures before the user enables them.

10. **Custom rule system**

Support an in-game rule editor with a name, enabled state, priority, match criteria, thresholds, cooldown, and outputs. Criteria can include exact sound IDs, a simple bounded wildcard such as minecraft:block.*.open, block IDs/tags when known, event route, action family, actor class, minimum attribution, known player UUID, dimension/profile, distance, relative height, watched area, count/window, family diversity, source novelty, and optional occlusion.

Use typed AND/OR groups with a limited depth and row count. Do not evaluate arbitrary code or arbitrary regular expressions. Bound pattern length, list length, template length, and nesting. Compile matchers once per settings change. An exact block filter cannot match a guessed candidate from an ambiguous shared sound unless the rule explicitly permits inferred blocks; default to known identity only.

Evaluate hard exclusions and explicit mute zones first, then explicit include rules and preset rules. Detection exclusion is separate from an output mute. If several rules qualify the same logical incident, merge the selected outputs and take the highest priority while retaining every rule reason. Never play several alert sounds because three rules matched the same opening. Show a small precedence explanation in the advanced editor.

Example rules: "Warn for an unexplained container opening within 64 blocks," "Critical when an attributed remote player acts within 12 blocks," "Warn on at least five logical building/mining actions in three seconds," "Record machinery in my farm area without a popup," and "Notify when a previously quiet machinery source starts outside expected areas." These are templates; all counts, ranges, actor requirements, and actions are editable.

11. **Warning card: visual design and content**

Use the client's existing frosted gradient/glass, typography, and accent controls. The card should have a severity strip or icon, a clear event title, concise event description, a direction arrow when justified, distance/height, attribution label, a count badge, and optional coordinates/age. Provide a compact mode and expanded evidence view. UI copy should use friendly labels; raw packet IDs belong in the inspector.

Example compact text:

    UNEXPECTED ACTIVITY                          x3
    Chest opened · Actor unknown
    22 m NE · 8 m below · last seen 0.4 s ago
    Container interaction rule · Warning

Example attributed text:

    PLAYER ACTIVITY
    Alex · block breaking · stone material
    14 m W · same height
    Actor identified by received breaker event

The exact actor explanation must reflect the implemented, verified route. Do not show a player name in the first example or suggest that an unknown chest contains items. Distance defaults to the real player's body. During Freecam, an optional secondary camera distance and camera-relative arrow are clearly labeled; moving the camera never changes what the server delivers.

| Appearance setting | Proposed default and allowed behavior |
|---|---|
| Position | Top-center; nine anchor choices plus drag placement; normalized saved coordinates |
| Offset and safe area | Adjustable X/Y offsets; clamp to usable viewport and account for sidebar, boss bars, subtitles, and existing overlays |
| Scale | 100%; adjustable 50-200%; fit to current GUI/window size |
| Width | Auto; adjustable target width with responsive minimum/maximum |
| Material | Frosted gradient; options for glass, solid, and minimal strip using existing render infrastructure |
| Colors | Independent background top/bottom, text, border, accent, and per-priority icon/stripe |
| Opacity | Background, border, text, and shadow independently adjustable; text remains readable in previews |
| Shape | Corner radius, padding, spacing, border thickness, and optional shadow |
| Typography | Existing bundled font, font size, weight, shadow, compact/expanded density |
| Contents | Toggle icon, action, known block/material, actor label/name, confidence label, direction, distance, relative height, coordinates, time, count, and rule reason |
| Templates | Bounded safe tokens such as {event}, {actor}, {distance}, {direction}, {height}, {count}, {reason}; unknown values have honest fallbacks |
| Stack | Maximum three visible by default; adjustable one to five; newest-first or priority-first |
| Duration | Six seconds for warnings; one to sixty seconds configurable; optional acknowledged critical pin |
| Animation | Short fade/slide; independently configurable durations and direction; instant mode when reduced motion is active |
| Urgency | Optional single accent pulse; flashing defaults off and has a strict rate bound |
| Overflow | Merge an incident's repeats; queue distinct incidents; show "+N more" when capped |

Provide **Preview warning**, **Preview critical**, **Move alert**, **Reset appearance**, and **Reset this field**. Preview uses synthetic data that cannot enter detection history or produce world markers. Show examples with a long player name, unknown actor, missing block identity, and narrow-screen wrapping.

12. **Warning behavior, audio, and acknowledgement**

Each priority and rule can select card, chat message, local chime, world marker, and history entry independently. Keep operating-system notifications and external messaging out of the initial module. Persistent disk logging is opt-in.

| Behavior setting | Starting default |
|---|---|
| Popup policy | Warning and Critical only |
| Chime | One quiet local chime per newly qualified warning; separate critical sound |
| Audio controls | Sound selection, volume 0-100%, pitch 0.5-2.0, optional one-to-three capped repetitions with a safe minimum interval |
| Sound settings | Respect Minecraft's master volume; alert category can stay audible when block sounds are muted, without bypassing master mute |
| Source cooldown | Five seconds; separate from the visible card's lifetime |
| Global popup rate | At most three new cards per five seconds; extra incidents merge or enter a bounded queue |
| Global chime rate | At most one chime per two seconds; critical escalation policy is explicit and bounded |
| Repeat handling | Update count/last-seen in place; do not reset card lifetime forever or chime on every repeat |
| Menus and chat | Continue observing; default to no popups or chimes while editing menus/chat, retain recent incidents in history |
| Resume behavior | Optional one brief recent-activity summary; do not replay a backlog of expired cards |
| F1 / hide GUI | Hide visual cards by default; obey an explicit setting for audio while hidden |
| Global HUD toggle | Respect it by default; offer a clearly named advanced independent-alert option |
| Quiet mode | Timed mute, manual mute toggle, or mute current source/category/area; observation/history can continue |
| Acknowledge | Unbound shortcut acknowledges the highest-priority card; optional source snooze |
| Critical pin | Optional; acknowledgement or a bounded maximum lifetime ends it |
| Join grace | Two seconds with configurable exceptions for attributed/danger events |

Route locally generated alert and preview sounds through a tagged notification path. A playback observer must reject MODULE_ALERT and PREVIEW provenance so an alarm cannot trigger another alarm. Avoid a detector rule that simply ignores an identifier shared with real server sounds; provenance is the more reliable distinction.

Normal HUD cards do not capture mouse clicks, movement keys, attacks, or interaction. Open history/editor through explicit shortcuts or the ClickGUI. Clickable dismiss/mute controls exist only in an intentional interaction screen, where the cursor is already available. This prevents an alert from interfering with normal play.

Alert lifecycle is candidate, qualified, queued/visible, acknowledged or suppressed, then expired. Disabling, disconnecting, or changing worlds cancels queued sounds and cards immediately. Re-enabling never replays old incidents. Rendering consumes immutable snapshots and never recalculates detection rules.

13. **Detection and history settings**

Provide searchable settings groups named Detection, Sources, Abnormal activity, Alerts, Appearance, Audio, History, Profiles, and Diagnostics. Main settings expose presets, range, sensitivity/threshold, actor policy, and preview; advanced controls remain available without crowding the module tile.

Expose independent category switches for containers, workstations, building/mining, switches/doors, machinery, music/bells, movement, combat, transport, portals/explosions, farming, sculk/trials, natural sounds, and custom events. Every group can expand to individual action families, block IDs when known, and sound IDs. Generic material actions have their own controls so every ordinary block is covered without thousands of checkboxes in the main panel.

Range filtering supports 3D or horizontal distance, minimum/maximum distance, relative height, current dimension, and watched/expected areas. Default to 128 blocks of received-event filtering and three-dimensional distance. Support a bounded maximum such as 512 blocks without presenting it as server detection reach. Derive valid height controls from the actual dimension rather than assuming Overworld build limits.

History should show the most recent 500 incidents by default, bounded to 2,000, with filters for priority, action family, attribution, actor, and age. Selecting an entry exposes observations, why it matched, deduplication, self-match reasoning, and settings/rule source. Allow copy coordinates only through an explicit action and only for meaningful positions. Offer a session log export with bounded size and clear observed-versus-inferred fields.

Honor name-hiding/streaming choices for the visible card, history, previews, chat messages, and exports. Offer independent actor-name and coordinate hiding. Color is supplemented with text/icon labels. All controls must work with keyboard navigation and Minecraft GUI scaling.

14. **Configuration, profiles, and validation**

Add SoundDetectorSettings to ClientConfig and sanitize it through the existing ConfigStore path. Organize nested settings into detection, attribution, incidents, alert layout, alert behavior, audio, history, and profiles. Use stable string identifiers for categories/actions/rules rather than persisting enum ordinals or raw packet numbers.

Missing or null settings receive defaults. Bound every numeric value, list size, queue limit, wildcard/template length, and nested rule depth. Reject NaN/infinity in layout, volume, range, and timing controls. Invalid values recover locally to defaults rather than discarding unrelated client preferences. Make unknown enum/string modes fall back safely; preserve editable unresolved registry IDs with a diagnostic status rather than applying arbitrary matches.

Support global defaults and optional per-server overrides scoped by a normalized server identity and world/dimension as needed. Separate user edits from preset versioning so a new release does not overwrite custom rules. Runtime incidents, session IDs, temporary mutes, and warmup state are not automatically persisted. Explicit expected-area/trust/profile choices can persist. Reset detection, appearance, audio, and profile settings independently.

Save through the existing atomic config writer on user changes, not per packet or render frame. Export/import uses a versioned, size-bounded JSON format and does not execute code. Avoid broad changes to the global schema version unless a real migration requires one; give this settings group its own catalog/profile version.

15. **Integration map for this repository**

Use the repository root as the base for the following proposed paths. Names can be consolidated during implementation if responsibilities remain clear.

| Proposed file or package | Responsibility |
|---|---|
| src/client/java/dev/donut/client/sound/SoundDetectorController.java | Own session lifecycle, capture queue, tick processing, snapshots, and reset |
| src/client/java/dev/donut/client/sound/SoundObservation.java and ActivityIncident.java | Immutable provenance and public presentation models |
| src/client/java/dev/donut/client/sound/SoundCatalog.java and BlockSoundCatalog.java | Registry-derived generic coverage plus explicit special-action decoders |
| src/client/java/dev/donut/client/sound/SoundAttribution.java and LocalActionTracker.java | Actor identity, local action matching, uncertainty, and non-player causes |
| src/client/java/dev/donut/client/sound/ActivityReconciler.java and ActivityRuleEngine.java | Logical action deduplication, source clustering, bounded evidence windows, and rule decisions |
| src/client/java/dev/donut/client/sound/AlertManager.java | Queue, cooldown, escalation, acknowledgement, and audio policy |
| src/client/java/dev/donut/client/config/SoundDetectorSettings.java | Defaults, nested settings, migration, and sanitization |
| src/client/java/dev/donut/client/gui/SoundAlertHud.java | Warning cards using existing glass, geometry, and text utilities |
| src/client/java/dev/donut/client/gui/SoundAlertEditorScreen.java | Drag placement and appearance preview |
| src/client/java/dev/donut/client/gui/SoundHistoryScreen.java | Inspect observations and incident reasons without changing world state |
| src/client/java/dev/donut/client/mixin/SoundDetectorPacketMixin.java | Observe verified received handlers on the client thread |
| Local-interaction and client sound-dispatch mixins where callbacks are insufficient | Observe missing action/dispatch routes before volume/device rejection; preserve handler scope and explicit provenance; no packet modification |
| src/main/resources/assets/donut/sound-detector/ | Reviewed exact-ID classification/rule defaults and versioned coverage metadata |
| src/test/java/dev/donut/client/sound/ | Pure logic fixtures for attribution, deduplication, timing, and rule behavior |
| src/gametest/java/dev/donut/client/gametest/SoundDetectorGameTest.java and SoundDetectorGuiGameTest.java | Real event routes, lifecycle, input, alerts, and visual regression fixtures |

Existing integration points are [DonutClient.java](../src/client/java/dev/donut/client/DonutClient.java), [ClientConfig.java](../src/client/java/dev/donut/client/config/ClientConfig.java), [ModuleKeybinds.java](../src/client/java/dev/donut/client/input/ModuleKeybinds.java), [DonutScreen.java](../src/client/java/dev/donut/client/gui/DonutScreen.java), [ModuleArrayList.java](../src/client/java/dev/donut/client/gui/ModuleArrayList.java), [donut.client.mixins.json](../src/main/resources/donut.client.mixins.json), and the language/test manifests. Reuse the progress overlay's normalized-position approach and the discovery card's appearance primitives where suitable.

Register the detector tick and disconnect cleanup in DonutClient, and register its HUD in a separate HudElementRegistry entry so its menu/F1/priority policy is explicit. World markers may reuse the existing highlight infrastructure through a small adapter. Reuse shared blur passes; do not create a second full-screen blur pipeline for each warning. Update all name/category/icon/description/enabled/favorite/keybind branches and module-count assumptions for ID 12.

16. **Performance budgets and bounded behavior**

Use indexed exact-ID lookup and precompiled rule filters. Keep packet capture constant-time and free of disk I/O, formatting, broad entity scans, raycasts, or chunk scans. Perform bounded context work on the client tick. Check at most a small neighborhood of already-loaded blocks for a supported ambiguity refinement; never load a chunk to identify a sound.

Initial hard caps: 4,096 pending observations, 512 active incidents, 2,000 history entries, five visible cards, and 32 pending presentations. Limit rule count/depth and maintain fixed-size local-action/dedup windows. Coalesce or discard low-value repeated ambience first during overload, reserve capacity for new stronger evidence, and expose drop/coalesce counts. A dropped observation is lost evidence; diagnostics must not describe overloaded capture as complete.

Target at most roughly one millisecond of detector work per client tick on the project's representative test machine under the normal stress fixture, with an enforced item/time budget and carryover. Aim for under 16 MiB of bounded detector state excluding existing game/render assets. Measure rather than promise these targets. Cap rendered markers and reuse immutable snapshots to limit per-frame allocation.

Handle packet bursts, server lag, paused integrated worlds, clock changes, resolution changes, resource reloads, entity removal/ID reuse, and dimension/disconnect transitions explicitly. A session/world epoch invalidates every queued observation and context association from the old world. Remember enough position/provenance to label stale history without treating it as a currently loaded entity.

17. **Verification plan**

Pure logic tests should exercise meaningful behavior rather than repeat implementation details. Use an injected clock and fixed observations. Cover explicit other-player attribution, unknown IDs, ID reuse, player-versus-victim interpretation, copper golem/container ambiguity, local rejected attempts, nearby-but-unrelated players, negative coordinates, double-chest origin, shared material ambiguity, global events, and cross-world expiry.

Deduplication fixtures must prove that one interaction observed through several routes counts once, while two genuine identical actions remain two. Include fast note-block/piston loops, two actors at one source, double chests, open/close distinctions, delayed routes, and repeated identical seeds. Rule tests cover precedence, independence, caps, weak/self evidence, threshold hysteresis, novelty, quiet periods, and missing/inferred block data.

Alert tests should prove that repeats update without chime spam, priority escalation is bounded, previews do not feed detection, muted master audio does not erase packet observations, menu/F1/HUD policies work, old queues do not replay on reconnect, and expired cards stay expired. Configuration tests cover old/missing/null settings, persistence, unknown strings/IDs, finite numbers, bounded imports, custom presets, and independent resets.

In Minecraft fixtures, trigger ordinary gameplay events through actual interactions or controlled redstone rather than only constructing packet objects. Exercise representative blocks for each distinct implementation/sound family, then automatically verify catalog coverage for every runtime block and relevant state. Include all copper chest variants, copper golem chest sorting, every shelf family, shulker colors, powered doors, note-block instruments, crafters, dispensers, furnaces, sculk, trial/vault cases, and explicitly silent controls such as hopper transfers. Record absence of a packet as an observed fixture result, not a universal proof that a route never exists.

For attribution, use a true second player connection; the local client's own sound is not a substitute. Test visible and obscured positions, same-height and vertical separation, standing/sneaking/flying, and normal/muted sound categories. Compare detections with packet traces from the same connection. Run the existing project build and relevant Fabric client game tests, then the regression suites affected by integration. Use JDK 21 as documented in [TESTING.md](../TESTING.md).

For a dedicated test server, retain the project's existing EULA behavior: a user-owned acceptance must already be present before running that fixture. The test harness must not create or alter acceptance. This is a later test requirement, not a permission request needed to draft this plan.

Visual review must cover 1280x720, 1920x1080, a small window, GUI scales 1 and 2 plus a large scale, compact/expanded cards, all priorities, long names/localized text, missing identities, three-to-five stacked cards, overlap with the existing HUD/scoreboard/subtitles, drag persistence after resizing, and reduced motion. Validate actual rendered screenshots, not just field values.

Finally capture the user's actual server implementation/version, protocol translation, plugins, and sound/resource settings. Measure each representative action at 8, 16, 32, 48, 64, and 96 blocks, including vertical distance and walls. Record observed delivery range per route and action rather than declaring a universal radius. The live-server report must distinguish vanilla fixture verification from server-specific verification.

18. **Implementation order and completion gates**

| Phase | Deliverable | Gate before continuing |
|---|---|---|
| A: establish observable behavior | Minimal bounded observation recorder, registry dump, route/action matrix, and two-player capture fixtures | Demonstrate a real chest action, generic block action, machine action, identified remote-player event, and silent/environmental negative controls |
| B: catalog all blocks | Registry-derived generic actions, reviewed special interactions, entity/item catalog, explicit unknown/silent statuses | Every runtime block has a coverage row; every exposed high-value action has a verified decoder or a clearly stated limitation |
| C: detection engine | Normalization, reconciliation, attribution, local action tracking, incident windows, and rule engine | Deterministic false-positive and duplicate fixtures pass; unknown actors never become named players by proximity |
| D: warnings | Alert manager, default card, bounded local audio, expiry, mute, acknowledgement, and history | Real detected events drive cards; no alert feedback loop or repeated-tone flood |
| E: customization | All detection categories, presets, advanced rules, visual/audio controls, drag editor, previews, profiles, privacy | Settings round-trip correctly; previews/editors work with native GUI input; reset scopes stay independent |
| F: repository integration | Module ID 12, search/Favorites/keybind/HUD, optional Base Finder attachment, language strings, clean lifecycle | Existing module behavior and saved IDs remain intact; no stale warnings survive world changes |
| G: server/performance validation | Real server range matrix, compatibility review, overload tests, visual QA, tuned defaults | Evidence-based server report, measured bounded performance, passing targeted and affected regression checks |
| H: release | Updated README/TESTING, reviewed catalog limitations, packaged module/assets, release notes | Release JAR includes required mixins/assets and excludes fixtures; documented behavior matches observed tests |

19. **Definition of done**

The feature is complete when it can detect and explain supported delivered sound/event activity, attribute only what the client evidence identifies, cover every current block through an explicit action/status catalog, and show the user's configured abnormal-activity warning without disrupting play. Its warning appearance, content, timing, sound, sensitivity, source filters, presets, mute/acknowledgement behavior, and per-server preferences must be adjustable and persist correctly.

Acceptance additionally requires no automatic player attribution for chest sounds, mobs, natural sounds, or machines; no doubled counts from one action; no stale cross-world alerts; no alert feedback loop; bounded packet-storm behavior; and a clear distinction between an inferred material, a known block, a candidate actor, and an identified player. Actual server behavior and remaining unsupported/silent routes must be recorded.

20. **Optional future server-assisted attribution**

If exact actor identity for ordinary unbound interactions becomes essential, a separately scoped server companion can emit explicit verified interaction metadata over a versioned custom payload. That would be a different capability from inferring actors from vanilla sounds. The client-only module described above remains useful without it, and its interface should retain provenance labels if this extension is later added.
