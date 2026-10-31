# ChuklAddon

A Meteor Client addon for Minecraft 1.21.11 (Fabric).

## Modules
- **Deep Block Entity ESP** - highlights block entities below a Y level (default Y15), with per-block colors, tracers and a see-through-walls toggle.
- **Dug Out Highlighter** - highlights big rectangular dug out areas underground, with a see-through-walls toggle.
- **Sus Chunk Finder** - Krypton-style base finder using plant/amethyst growth and rotated deepslate. Zone pillar has selectable styles: Outline, Solid, Light Beam, Pulse Beam, Rainbow Beam (color, width, height, glow, speed).
- **Sus Chunk Finder Test** (`sus-chunk-finder-test`) - experimental copy of the Sus Chunk Finder: the best chunk is drawn as a glowing blue energy orb with electric arcs (or a dig-range column), with a dig shaft, a lightning/beam pillar, tracers and a selectable Y level (fixed or following you). Also scans Y29 to -64 for player-made blocks (storage, utility, redstone, lighting, building).
- **Light Finder** - highlights underground light, which can reveal hidden bases.
- **Water Sus Chunk** (`water-suschunk`) - Water Client's Sus Chunk Finder: amethyst clusters plus underground chests, with flashing base chunks.
- **Vulxts Sus Chunk** (`vulxts-suschunk`) - Vulxts' Sus Chunk Finder: weighted amethyst/kelp/bamboo/berries/vines/dripstone scoring with smart zone merging.
- **Vulxts Freecam** (`vulxts-freecam`) - Vulxts' Freecam: detached camera, your body keeps walking, scroll wheel changes speed.
- **Vulxts FreeLook** (`vulxts-freelook`) - Vulxts' FreeLook: rotate the camera or the player independently in third person, optional see-through-walls.
- **Krispy Chunk Finder** (`krispy-chunk-finder`) - flags chunks whose underground light data is all-zero or all-15 and draws a plate on the surface, with an optional toast, ping and kick-on-flag. Only checks chunks that load after you switch it on.

### Chukl Render category (from Water Client)
Future Debug, Chunk Finder, Amethyst ESP, Bedrock Void ESP, Block ESP, China Hat, Extra ESP, Full Bright, Hole ESP, Jump Circles, Light Debug, Mob ESP, Pearl ESP, Player ESP, Scan Overlay, Spawner Notifier, Storage ESP, TNT Explosion Marker, Water Block Notifier. Module ids are prefixed `water-`.

## Building
Push to GitHub. The **Build** workflow produces the jar as an artifact (Actions -> latest run -> Artifacts).
Locally: install Gradle 9.2+ and JDK 21, then run `gradle build`. The jar is in `build/libs/`.

## Releasing an update
1. Bump `mod-version` in `gradle/libs.versions.toml`.
2. Commit and push.
3. Actions -> **Release** -> Run workflow, and enter the update name.
