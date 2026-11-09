# Name Hider integration

The integration was based on static inspection of the supplied `name-hider-1.0.3+mc1.21.11.jar`, not on running its classes. Its SHA-256 is `124abe75d6e784c6a23c995ad184f394d3e556a6b15274964bd5537ebaee17f8`.

The JAR's metadata identifies Name Hider 1.0.3 for Minecraft 1.21.11 under CC0-1.0. It contains a sidebar text sanitizer and a HUD mixin, with no client entrypoint. The original CC0 text is bundled at `licenses/name-hider-CC0-1.0.txt`.

## Observed behavior

- Read the local player's scoreboard name and remove it from the vanilla sidebar title, row text, and formatted score text.
- Match without case sensitivity, using ASCII letters, digits and underscore as username boundaries. A longer player's username that merely contains the local name stays intact.
- Match across styled component segments and retain surrounding text and resolved styles. Replacement is an empty string.
- Sanitize text passed to rendering and width measurements without modifying objective names, scores, teams, server data or source components.

Donut implements this as the **Streaming > Name Hider** module, initially disabled, with a saved toggle, Favorites and a shortcut. The rendering hooks cover the original three draw calls and two title/row width calls. An additional width hook handles cached custom score text widths, so removed names do not leave an unnecessary gap.

The original JAR is not bundled or loaded as a dependency. Its behavior was adapted to this client's Minecraft mappings and Fabric setup. Remove the standalone Name Hider JAR from the active instance when using this module; an independently installed copy would continue hiding names even with Donut's module disabled.

The feature covers the vanilla sidebar scoreboard. Chat, the player list, world name tags and custom HUDs from other mods are outside this module's scope.
