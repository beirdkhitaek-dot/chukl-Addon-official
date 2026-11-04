# Changelog

Every release has a section here: **what's new** and **how to use it**. The same text is published
as the release notes on the [Releases page](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases).

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
