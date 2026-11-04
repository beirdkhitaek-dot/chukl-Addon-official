# Changelog

Every release has a section here: **what's new** and **how to use it**. The same text is published
as the release notes on the [Releases page](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases).

## v1.40.0 - Seed Finder Structure Analyzer And Solver
**What's new**
- **Structure analyzer:** **Seed Finder** now also recognises structures from the blocks of the chunks you load: desert temples, jungle temples, igloos, swamp huts, ocean monuments, pillager outposts, strongholds, ancient cities, trial chambers, mineshafts, villages, nether fortresses, bastions and ruined portals. Each one is written to `meteor-client/chukl-research/structures-<server>.jsonl` (kind, chunk, a block position, block counts). Setting: `analyze-structures` (on by default).
- **Solver:** a new Solver group tests seeds against the structures you recorded. It uses the compact kinds (desert temple, jungle temple, igloo, swamp hut, pillager outpost, ocean monument) and counts how many of them a seed would have placed where you found them. A seed that explains nearly all of them is almost certainly right.
  - `candidate-seeds`: seeds you want to check (numbers, or text like Minecraft turns into a seed).
  - `search-range`, `search-from`, `search-million`, `solver-threads`: also test a range of numbers in the background.
  - `run-solver`: turn it on to start; the result is in chat and in `seed-solver-<server>.txt`.
- **Honest limits:** a range search only finds seeds that are inside the range you give. Searching every possible seed is far too big for a phone. The placement numbers are written from memory and have not been checked against a world with a known seed, so test with a world whose seed you know first.

**How to use**
1. Turn on **Seed Finder** and walk around a world or server. Watch for the info text "N structures".
2. When you have found at least 3 of the compact kinds (more is better), open the **Solver** group. Put the seed you suspect in `candidate-seeds`, or turn on `search-range`.
3. Turn on `run-solver`. A line like "Seed 12345 explains 5 of 5 structures" means a strong match. Turn the module off to stop a running search.
4. First time: try it on a world where you know the seed, to confirm it reports your seed.

## v1.39.0 - Rounded Plates And Smooth Animations
**What's new**
- **Rounded corners (both Sus Chunk modules):** chunk plates and the best-chunk column now have rounded corners. New setting `corner-radius` (default 3 blocks, 0 = sharp).
- **No more stacking:** each chunk draws exactly one plate (a single top face and outline instead of a full box with a bottom face), and when several zones sit in the same chunk only the strongest marker beam is drawn. The overlapping glow that looked messy inside one chunk is gone.
- **Fade in and fade out:** chunks fade in when they get flagged and fade out when they stop being sus or leave range. The marker beams fade with their chunk.
- **Sliding marker (Chukl Sus Chunk Test):** when the best chunk changes, the column, energy orb, dig shaft and tracer glide to the new place instead of jumping. A very long jump fades in at the new place instead.
- New settings in the Render group of both modules: `animations`, `fade-seconds`, `slide-seconds`.

**How to use**
- Open **Chukl Sus Chunk** or **Chukl Sus Chunk Test**, then the **Render** group. Raise `corner-radius` for rounder plates, or turn `animations` off if you want instant changes.
- Slower phones: if it feels heavy with many chunks, set `corner-radius` to 0, which draws the simplest shape.

## v1.38.0 - Target HUD Focus, Instant Goat ESP And Anticheat Notes
**What's new**
- **Target HUD (both Sus Chunk modules)** now only shows when you look at what is drawn: the chunk plate in **Chukl Sus Chunk**, and the best chunk's column, energy orb or dig shaft in **Chukl Sus Chunk Test** (the plate in All Chunks mode). It no longer shows when you look at empty air above or below a chunk.
- **Goat Block ESP** scans instantly: every chunk in range is scanned the moment you turn it on, change the blocks or Y range, or move into new chunks (nearest first, with a time budget so phones stay smooth). New settings: `instant-scan` (on by default) and `scan-budget-ms`.
- **Deep amethyst (both Sus Chunk modules)** now tells you in chat how many grown clusters it found in the Y range (for example "Deep amethyst: 12 grown clusters in 3 chunks"). If it finds none among 100+ loaded chunks it says so and explains that the server may not send blocks that deep until you are close. Boxes are drawn through walls, so you do not need to go down.
- **Server Data Collector** has a new `anticheat-indicators` option. It is passive: it counts how often the server moved you back (position corrections), notes which anticheat names appear in server messages (names only, never the text), and records the reason when the server kicks you. It sends nothing and probes nothing.

**How to use**
- Target HUD: turn it on in the module's Target HUD group as before, then look at the plate or the marker.
- Goat Block ESP: nothing to do. Lower `scan-budget-ms` if the game stutters right after you move far.
- Deep amethyst: watch the chat for the status line. If it says none were found, the server is probably hiding deep blocks from you.
- Anticheat indicators: turn on `server-data-collector`; the data goes into `meteor-client/chukl-research/server-data-<server>.jsonl`.

## v1.37.1 - Deep Amethyst For The Original Sus Chunk Finder
**What's new**
- The original **Chukl Sus Chunk** (`chukl-sus-chunk`) now has the same **Deep Amethyst** settings as the test version: boxes on fully grown amethyst clusters between Y -64 and -29, visible through walls. Only grown clusters, not buds.
- It also has the `world-seed` setting (stored for the future seed solver, no effect on detection yet).
- Grown clusters already counted as sus evidence in this module through the normal `amethyst` setting at every depth, so the new part only adds the boxes.

**How to use**
- Open **Chukl Sus Chunk**, then the **Deep Amethyst** group. `deep-amethyst` turns the boxes on or off, `amethyst-min-y` / `amethyst-max-y` set the range, and `amethyst-max-boxes` keeps it light on phones. Changing these rescans the loaded chunks automatically.
- Boxes inside a natural geode are natural. Overworld only.

## v1.37.0 - Deep Amethyst And Seed Field
**What's new**
- **Chukl Sus Chunk Test** now sees fully grown amethyst clusters deep underground (Y -64 to -29 by default). It draws boxes on them that you can see through walls, and counts them as evidence: each cluster adds 1 point to the chunk's underground score (at most 12 per chunk), which also raises its best-chunk ranking. Only fully grown clusters count, not buds.
- New `world-seed` setting in the General group: type the server's seed (a number, or any text). It is stored for the future seed solver and does not change detection yet.

**How to use**
- Open **Chukl Sus Chunk Test**, then the new **Deep Amethyst** group. `deep-amethyst` turns it on or off, `amethyst-min-y` / `amethyst-max-y` set the range, `count-as-evidence` and `amethyst-esp` choose what it does, and `amethyst-max-boxes` keeps it light on phones.
- Natural amethyst geodes are also full of clusters. Chunks inside a detected geode are still ignored by the natural-structure filter, but if you see boxes in a geode, that is natural.
- `world-seed` is optional. Leave it blank if you do not know the seed.

## v1.36.1 - Phone Screen Fit
**What's new**
- The **CS16 Knife** settings window was too wide for phone screens because of one very long line of text. The text now wraps, so the whole window fits. The same fix is applied to the sound buttons of **Kill Bell** and **Grenade Callout**.

**How to use**
- Nothing to do. The full folder path for your knife models is now printed in chat when you press **Refresh** (it is also printed when you turn the module on).

## v1.36.0 - Steady Chunk Pick And Research Modules
**What's new**
- **Chukl Sus Chunk Test** no longer jumps around. The best chunk now stays put: it keeps its pick for at least 15 seconds, and another chunk only takes over when it is clearly stronger (50% higher score) and stays ahead for 3 seconds. The dig spot of the picked chunk also stays fixed.
- **Chukl Sus Chunk Test** no longer picks chunks that probably have a natural cause: chunks in or next to natural structures (mineshaft, stronghold, dungeon, ancient city, trial chamber, geode) and chunks lit only by light next to lava. Very strong evidence is still allowed, in case a base sits inside a structure.
- New research module **Server Data Collector** (`server-data-collector`, category Chukl): writes technical facts about the server (software brand, version, real chunk radius, tick rate, your ping, packet rates, world border) to a file. It never records other players' names, chat or positions.
- New research module **Seed Finder** (`seed-finder`, category Chukl), stage 1: records the ore veins of the chunks you load (ore type, size, block positions) to a file, as the input for a seed solver. The solver that turns this into a world seed is not included yet.

**How to use**
- Sus Chunk Test: nothing to do, the steadier pick is on by default. In the Render settings you can change `pick-lock-seconds` (0 = may switch any time), `switch-margin-percent` (higher = steadier) and `avoid-natural` (turn off to allow natural-looking chunks again).
- Research modules: turn them on and play normally. Data goes to `meteor-client/chukl-research/` (`server-data-<server>.jsonl` and `ore-veins-<server>.jsonl`, one JSON object per line). The module prints the exact folder when you turn it on.
- Seed Finder works best in areas nobody has mined: ores that players already mined are missing, and servers with anti-xray can hide or fake ores. On phones, keep `chunks-per-tick` at 1.

## v1.35.1 - Release Notes For Everyone
**What's new**
- Every release now comes with plain release notes: what changed and how to use it.
- This `CHANGELOG.md` keeps the full history in one place.

**How to use**
- Open the latest release, read "What's new" and "How to use", and download `chukl-addon-<version>.jar`.
- To update, replace your old ChuklAddon jar in the `mods` folder with the new one.

## v1.35.0 - Death Ghost Ragdoll
**What's new**
- **Death Ghost** now has a Half-Life 2 style ragdoll shake. The dissolving body floats and never holds still: the limbs jitter with random kicks and the whole body drifts and tumbles slowly, while the sparks and lightning follow it.

**How to use**
- Turn on `death-ghost` (category **Chukl Visual**) and kill a player.
- `ragdoll` turns the shake on or off. `ragdoll-strength` (0.1 to 3) sets how hard it shakes. Lower it if it looks too wild.

## v1.34.0 - CS16 Knife
**What's new**
- New module **CS16 Knife** (`cs16-knife`, **Chukl Visual**). Replaces the sword you hold in first person with a Counter-Strike 1.6 knife view model, with its draw, idle, slash and stab animations. Hit swings play when your crosshair is on an entity.

**How to use**
1. Put your `.mdl` knife models in `meteor-client/chukl-visual/cs16-knives/` (the module prints the exact folder when you turn it on).
2. Turn on `cs16-knife`, open its settings and press **Refresh**, then **Next skin** / **Previous skin** to pick one.
3. Hold a sword. Axes too if you enable `include-axes`.
4. On phones, lower `render-scale` (about 0.35) and `max-fps` (about 20). Use `zoom` and the `offset` settings to move the model.
- No knife models are included. First person only.

## v1.33.x - Official release polish
**What's new**
- Install steps, credits (`CREDITS.md`), license info and a clear "official download" note in the README.
- Release notes now include the SHA-256 of the jar and the source commit, so you can check the download.
- New addon icon, plus a transparent and an animated version of the logo (not used in-game yet).

**How to use**
- Only download jars from the Releases page of this repository.
