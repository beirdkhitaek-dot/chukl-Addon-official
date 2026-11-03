# ChuklAddon

A Meteor Client addon for Minecraft 1.21.11 (Fabric).

**Author:** Chukl · **License:** GPL-3.0 (see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md))
This is the official ChuklAddon repository: https://github.com/beirdkhitaek-dot/chukl-addon-official

> **Is this download official?** Only jars from this repository's [Releases](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases) page are official. Every release jar is built by GitHub Actions straight from the source in this repo (nothing is uploaded by hand), and its SHA-256 is listed in the release notes. ChuklAddon is an independent addon; it is not an official Meteor Client product.

## Install
1. Install Fabric Loader for Minecraft **1.21.11** and [Meteor Client](https://meteorclient.com) for 1.21.11.
2. Download `chukl-addon-<version>.jar` from the latest [release](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases/latest).
3. Put the jar in your `mods` folder next to Meteor Client and start the game. ChuklAddon's modules show up in the Meteor GUI.

Credits for ported modules are in [CREDITS.md](CREDITS.md). Report problems in [Issues](https://github.com/beirdkhitaek-dot/chukl-addon-official/issues).

## Modules
- **Deep Block Entity ESP** - highlights block entities below a Y level (default Y15), with per-block colors, tracers and a see-through-walls toggle.
- **Dug Out Highlighter** - highlights big rectangular dug out areas underground, with a see-through-walls toggle.
- **Sus Chunk Finder** - Krypton-style base finder using plant/amethyst growth and rotated deepslate. Zone pillar has selectable styles: Outline, Solid, Light Beam, Pulse Beam, Rainbow Beam (color, width, height, glow, speed).
- **Chukl Sus Chunk Test** (`chukl-sus-chunk-test`) - experimental copy of the Sus Chunk Finder: the best chunk is drawn as a glowing blue energy orb with electric arcs (or a dig-range column), with a dig shaft, a lightning/beam pillar, tracers and a selectable Y level (fixed or following you). Also scans Y29 to -64 for player-made blocks (storage, utility, redstone, lighting, building). Natural structures (mineshafts, strongholds, dungeons, ancient cities, trial chambers, geodes) can be ignored, and underground light data is checked for box-shaped lit rooms. Adaptive mode tunes it to the server: brand detection, real chunk radius, simulation distance and sensitivity for noisy servers. Experimental light tools (off by default): a Light ESP that boxes the brightest lit blocks underground, colored by light level, and a deep-light signal that counts lit blocks in the deep range as base evidence. New in 1.26.0: optional Verify Chunk re-reads the real data of the best chunk and shows a true scan percentage; false positives are dropped and the next best chunk takes over.
- **Chukl Spawner Notifier** (`chukl-spawner-notifier`) - detects unnatural (player-placed or changed) spawners, tells you which mob it spawns, notifies with a toast, chat message and ping, and marks it with a lightning beam in any color (by mob, one color, or rainbow). Natural dungeon / mineshaft / stronghold / fortress spawners are recognised and ignored.
- **CS16 Knife** (`cs16-knife`, Chukl Visual) - replaces the sword (optionally axes) you hold in first person with a Counter-Strike 1.6 knife view model. Skins are GoldSrc `.mdl` view models that you import yourself: put them in `meteor-client/chukl-visual/cs16-knives/`, then press Refresh / Next skin in the module settings. Plays the model's draw, idle, slash and stab animations (hit swing when your crosshair is on an entity). Drawn by a small software renderer on a background thread; lower `render-scale` / `max-fps` for phones. No models are bundled.
- **Kill Bell** (`kill-bell`) and **Death Ghost** (`death-ghost`) in Chukl Visual: Half-Life bell (built in, custom .wav with Refresh / Next sound / Test buttons) when you kill a player, and a Source-engine style dissolve (black body fades out, rising, with white sparks and lightning; ragdoll shake: the floating body jitters and drifts like a dissolving Half-Life 2 ragdoll; type, duration, particle amount and arcs are settings). Grenade Callout now plays through Minecraft OpenAL (works on Android) and searches several folders. Target HUD for both Sus Chunk Finders, anti-xray detection and natural-light filtering in the Test module, GoodRoad dotted continuation and stable explore legs.
- **Goat Block ESP** (`goat-block-esp`, Chukl Render) - highlights the blocks you pick inside a custom Y range. Boxes and tracers each choose their own color: the block's own color or a custom color. Range, max blocks and rescan interval keep it light for phones.
- **Frost Storage ESP** (`frost-storage-esp`, Chukl Render) - ported from the Frost client: real block shapes, per-type toggles and colors (chests, spawners, shulkers, furnaces, hoppers, pistons, crafters...), idle-piston color, tracers (optionally from in front of your head), under-Y0 filter and a cached scan for extra blocks.
- **Grenade Callout** (`grenade-callout`, new **Chukl Visual** category) - plays your own .wav (like "Fire in the hole!") when you throw a snowball, egg, pearl, potion and similar. Put the .wav in `meteor-client/chukl-visual/` and set `sound-file`; falls back to a vanilla sound if the file cannot play.
- **GoodRoad** (`good-road`) - draws a bright, thick glowing line (with road tiles, poles and a moving pulse) on the ground to follow to the best place to look for bases. Targets unnatural spawners, then the best sus chunk, then manual coordinates, then explores toward unvisited chunks. Plans a walkable route over loaded chunks (avoids lava, cliffs, water) and adapts to the server view distance and your device speed.
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
