# AutoBuilder

For Minecraft Java 1.21.11 with Fabric Loader 0.18.4+ and Fabric API 0.141.4+1.21.11.

1. Press Right Shift, select **Blatant**, and open **AutoBuilder**. Its outlined key box assigns a shortcut shared with Minecraft Controls. Right-click its card to expand presentation settings; the star adds it to Favorites, which includes modules from both categories.
2. Use **Open Folder** to open the active instance's `schematics/donut/` directory. Add `.litematic` files and click **Refresh**. Select a file or use previous/next. Validation failures appear as notices without replacing the last valid schematic.
3. Drag the model to rotate it, scroll to zoom, and use **Reset view** to fit it again. Dimensions, non-air block count, and region count refer to the entire validated schematic. Very large models show a disclosed surface sample.
4. Set X/Y/Z or **At player**. Rotation and mirroring apply to both positions and block states around the normalized schematic origin. **Show in world** closes the menu and displays missing blocks; **Cancel Preview** hides the ghost. Red indicates an obstruction, purple an unloaded position. Glass uses the accent color when its map color is empty. Ghost geometry is limited to 8,192 nearby blocks within 96 blocks for rendering performance.
5. Open **Materials** before **Start**. Search by item name. Total/correct/remaining columns count placement items; progress above the table counts exact block states. A double slab consumes two slabs, a partial double slab needs one more, stacked decorations count individually, and beds/doors/tall plants count their primary item once. Inventory stock excludes the hotbar and offhand. Unknown positions remain separate from known remaining requirements and missing stock. Large lists update across several ticks; the previous completed count stays visible while the next scan runs.
6. In Creative, **Get Materials** fills matching stacks and empty main-inventory slots within their stack limits. It preserves other items and reports unfilled requirements when capacity runs out. Unknown positions are excluded until their chunks load. Survival and Adventure cannot grant materials.
7. **Start** closes the manager, checks the world, selects inventory items, walks into reach, aims, and interacts through vanilla placement. Opening a menu pauses construction unless the builder itself opened a shulker box or auction window. Close it and use **Resume** to continue; **Cancel** stops the build. Freecam, vehicles, death, disconnects, material shortages, occupied positions, rejected states, and blocked access produce pause reasons. Only received server updates or acknowledged/reconciled placement sequences confirm sent interactions.

## Placement and navigation scope
The engine builds low structural blocks before attachments and uses retries for dependencies. Ordinary directional blocks use a predicted vanilla placement context; slabs, stackable blocks, repeater delay, comparator mode, and note values can require several confirmed interactions. Progress is rechecked as the world changes and verified before completion. Rejected placements and server position corrections replan navigation.

Before interacting, AutoBuilder waits for Minecraft to transmit the required rotation and sneak input through its normal tick. It does not send extra movement packets or assign placement sequence numbers; Minecraft's prediction handler supplies those numbers. Server permissions and anti-cheat rules still apply.

Walking uses bounded A* over loaded terrain with collision and hazard checks. Routes may move diagonally without cutting corners, step or jump one block up, and fall up to three blocks like a safe vanilla drop. Each search considers at most 12,000 cells, within 96 horizontal blocks and 24 vertical blocks of its start. It does not mine obstacles, fly, or swim.

If movement stalls or the server rubber-bands the player back, the builder pauses input for a moment, discards the old route, finds a new path from the corrected position, and continues. Repeated failed recoveries at one spot give up on that stance and replan elsewhere. Server position corrections always trigger this recovery cycle.

When no walking route exists and **Build access stairs** is on, the builder places temporary dirt, cobblestone, stone, or netherrack blocks as a staircase or bridge, climbing at most one block per step, so it can walk up or across to the placement stance. These use extra support blocks from inventory, are capped per climb, never overwrite schematic cells, and are tracked and reported at completion. They remain for manual removal. The materials table describes the schematic itself, so bring additional support blocks when building above existing terrain or placing blocks that need a side face.

## Material resupply
When a material runs out mid-build, the builder can restock instead of pausing immediately:

1. **Use shulker boxes** (default on): with a shulker box in the inventory, the builder places it beside the build, opens it, moves the needed items into the inventory, closes the menu, and continues. Shulker boxes it placed earlier are reused first; leftovers are reported at completion. Placement never covers schematic cells.
2. **Buy from /ah** (default on): the builder sends `/ah <item name>` and reads the listing window. A listing is only bought when the stack is exactly the missing item and its parsed price stays at or below **Max price per item** (coins per item; stack totals divide by the stack size). The purchase click opens the confirmation window when the server uses one; the builder re-checks the item and price there, tolerating only a two percent price difference, and only then presses the confirmation control. Unpriced listings, bids without a buyout, changed items, raised prices, and missing confirmation buttons all refuse the purchase and pause with the reason.

Menu interactions are bounded by deadlines and always close the window before continuing. Server-specific menu layouts cannot be predicted; when the auction house does not respond or uses an unfamiliar format, the builder pauses with a clear reason instead of clicking blindly. Disable either option in the module settings to fall back to the plain shortage pause.

Air cells do not trigger demolition. Incorrect existing blocks need manual correction. Fluids, waterlogging, crop growth, several interaction-derived states, and unsupported placement items produce a manual-action notice. Powered/connected states must result from the actual structure. Stored block-entity contents, entities, and scheduled ticks are validated/preserved in the loaded data or reported as file notices, and are not restored by ordinary placement. Exact states beyond vanilla interactions may require [server support](https://github.com/maruohon/litematica/wiki/Easy-Place).

## File limits

The reader accepts post-flattening `.litematic` versions 4–7, compressed or uncompressed NBT, and multiple regions including negative signed sizes. Identical overlaps count once; conflicting overlaps are rejected. Unknown block IDs, invalid properties/entity data, inconsistent packed palettes, unsupported newer Minecraft data, and malformed files are rejected with a reason.

Limits: 32 MiB on disk, 128 MiB decoded NBT, 512 regions, 4,000,000 region cells, and 1,000,000 non-air blocks. Older data is validated against this Minecraft version; re-save it in current Litematica when conversion is required.

## In-game previews

![Blatant category in the ClickGUI](docs/screenshots/autobuilder-clickgui.png)

![Schematic library and positioning controls](docs/screenshots/autobuilder-preview.png)

![Material totals and inventory stock](docs/screenshots/autobuilder-materials.png)

![Positioned world ghost](docs/screenshots/autobuilder-world.png)
