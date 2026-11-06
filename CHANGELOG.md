# Changelog

Every release has a section here: **what's new** and **how to use it**. The same text is published
as the release notes on the [Releases page](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases).

## v1.43.1 - Amethyst See-Through Up To Y 29
**What's new**
- **Both Sus Chunk Finder and Sus Chunk Finder Test** now box grown amethyst clusters from Y -64 up to **Y 29** (was -29), drawn through walls.
- If your saved `amethyst-max-y` was still the old -29, it is raised to 29 when you turn the module on. Change it back if you want the old range.
- **Servers that reveal blocks late** (anti-xray or delayed block updates): the chunks around you are now checked again every 3 seconds, so clusters that appear after the chunk loaded are still found.
- Limit: if a server never sends the real block at all, no client can see it. The chat message "Deep amethyst: none found..." tells you when that may be the case.

**How to use**
- Nothing to switch on: `deep-amethyst` is on by default. Adjust `amethyst-min-y` / `amethyst-max-y`, color and alpha under Deep Amethyst. Replace the old jar with `chukl-addon-1.43.1.jar`.

## v1.43.0 - FPS Boost For ZalithLauncher 2
**What's new**
- **New module: FPS Boost** (Visual category), made for phones running ZalithLauncher 2 with MobileGlues. Those setups are limited by the CPU and draw calls, so it lowers what the game draws and simulates:
  - Presets: **Balanced**, **Aggressive** (default), **Potato** and **Custom** (view distance, simulation distance, entity distance, particles, clouds, entity shadows, view bobbing, biome blend).
  - **Auto Tune**: lowers the view distance when FPS stays under your target and raises it again when there is room, never below your minimum.
  - Your own settings are saved when you turn it on and restored when you turn it off.
- It is generic for now. I do not know your phone model, so the presets are a good starting point and not tuned to one chip yet.

**How to use**
- Turn on **FPS Boost**. Try Aggressive first; if the game still lags, use Potato, or Custom to choose each value.
- Auto Tune: set target-fps to what you want (for example 40). Replace the old jar with `chukl-addon-1.43.0.jar`.

## v1.42.3 - Frost Storage ESP Tracer Width
**What's new**
- **Frost Storage ESP: new `tracer-width` setting.** Tracers can now be thick. The width is in screen pixels, so a tracer looks equally thick near and far. 1 is the old thin line.

**How to use**
- Open Frost Storage ESP, keep `tracers` on and raise `tracer-width` (try 2 to 4). Replace the old jar with `chukl-addon-1.42.3.jar`.

## v1.42.2 - CS16 Player Model: Sitting, Swimming, Head Look, Knife In Freecam
**What's new**
- **Sitting**: a player riding a boat, minecart, horse or anything else now sits (thighs forward, shins down). CS has no sitting animation, so it is built from the leg bones.
- **Swimming**: the flat swim animation now tilts up and down with where the player looks, and the upper body no longer pops upright while swimming. Treading water without swimming uses the CS tread-water animation. Nothing to switch on, it is automatic.
- **Head look**: the model's head now turns to where the player looks (left, right, up, down), like in CS.
- **Knife model in freecam and third person**: the CS knife in your hand is now shown whenever your own body is visible, so it works with Meteor Freecam, other clients' freecam and any addon freecam, and in third person.
- **Swing animation**: the attack animation is faster by default (attack-speed 3.0, was 2.0) so it keeps up with Minecraft swings.
- Not tested in-game yet. If something is still wrong, send a video like before.

**How to use**
- Nothing to set up. Replace the old jar with `chukl-addon-1.42.2.jar`.
- If the head or body tilts the wrong way when looking up or down, turn on invert-pitch in CS16 Player Model > Animation.

## v1.42.1 - CS16 Player Model: Items In Hand
**What's new**
- **Held items now sit in the CS model's hand** and follow its animation (walk, run, attack, crouch), for both hands. Before, they stayed at the invisible vanilla arm.
- New settings in CS16 Player Model > Animation: items-in-hand, item-scale, item-offset-side, item-offset-up, item-offset-forward (to fine-tune the grip).
- The CS16 Knife still attaches p_knife to the hand as before.
- Not tested in-game yet. If an item points the wrong way, send a screenshot.

**How to use**
- Enable CS16 Player Model and hold any item. Use the item offset settings if it sits a bit off the hand.
- Replace the old jar with `chukl-addon-1.42.1.jar`.

## v1.42.0 - CS16 Player Model
**What's new**
- **New module: CS16 Player Model** (Visual category). Players are drawn as Counter-Strike 1.6 player models (.mdl) that you import, with real textures, lighting and the vanilla name tag.
- **CS-style animation**: legs play idle, walk, run, crouch, jump and swim at the speed the player really moves; the upper body plays the held item's weapon animation (knife, pistol, rifle...), follows where the player looks, plays an attack animation on every swing, and a dying player plays a CS death animation.
- **CS16 Knife support**: the knife attaches to the CS model's right hand on players using this module.
- Settings: model, own-model, random-per-player, apply-to, size, distance, hide-vanilla-armor, weapon-pose, attack-speed, death-animation, invert-pitch, facing-offset.
- Not tested in-game yet. If a player looks wrong, tell me which model and what you see.

**How to use**
- Put player models (for example `sas/sas.mdl` with its texture file) in the `cs16-players` folder next to your other chukl-visual folders. Press **Refresh** in the module settings to see the exact path and the models found.
- Enable **CS16 Player Model**. Use Previous / Next model to switch.
- Model upside down or facing backwards: try invert-pitch or facing-offset 180.
- Replace the old jar with `chukl-addon-1.42.0.jar`.

## v1.41.2 - Fix Startup Crash From The Knife Hand Mixin
**What's new**
- **Fixed a crash on startup** in v1.41.0 and v1.41.1: the game stopped with "Mixin transformation of net.minecraft.class_10426 failed". The hook that hides the vanilla sword in third person had the wrong method signature for Minecraft 1.21.11 (it was missing the float parameter). It now matches.
- If you were on 1.41.0 or 1.41.1, update to this version.

**How to use**
- Nothing to do. Replace the old jar with `chukl-addon-1.41.2.jar`.

## v1.41.1 - Bundled Knife Models Slot
**What's new**
- The addon can now ship default CS16 knife models. Models placed in `assets/chukl-addon/cs16/` inside the jar (`v_knife.mdl`, `p_knife.mdl`, `w_knife.mdl`) are copied to the user's `cs16-knives` folder the first time CS16 Knife runs, only when that folder has no skins yet. This release ships none; it only adds the slot, so your own models are never overwritten.

**How to use**
- Nothing to do as a player. Put your models in `meteor-client/chukl-visual/cs16-knives/` as before.

## v1.41.0 - CS16 Knife Third Person, Attack Types And Behind The HUD
**What's new**
- **Behind the HUD:** the first-person knife is now drawn before the HUD, so the hotbar, crosshair, health and chat are on top of it instead of the knife covering them.
- **Attack 1 and attack 2 like Counter-Strike:** swords now use the primary attack (slash, and midslash when your crosshair is on an entity). Axes use the secondary attack (stab, and stab_miss when you miss). `include-axes` is now on by default.
- **Third person and other players:** the knife is drawn in the hand of your player in third person and in the hand of other players who hold a sword or axe. It uses a **p_knife.mdl** (the third-person knife of Counter-Strike), or **w_knife.mdl** if there is no p_knife, from the same `cs16-knives` folder. The vanilla sword in that hand is hidden; every other item stays vanilla. Without a p_knife or w_knife the vanilla item is left alone.
- **New Third Person settings:** `third-person`, `other-players`, `world-model`, `world-scale`, `world-brightness`, `world-offset-forward/right/up`, `world-pitch`, `world-swing`, `world-distance`.
- **Ready for a Player Model module:** when a player model module is active, the knife attaches to that model's right hand bone (the way Counter-Strike attaches p_knife) instead of the vanilla hand. Vanilla items keep working because only a hand holding a sword or axe is replaced.
- The Previous/Next skin buttons skip p_ and w_ models, so they only cycle view models.

**How to use**
1. Put your knife models in the `cs16-knives` folder: `v_knife.mdl` (first person) plus `p_knife.mdl` (third person, optional `w_knife.mdl`). Press Refresh in the module.
2. Turn on **CS16 Knife**. Press F5 to see the knife in your own hand, or look at another player holding a sword.
3. If the knife looks too small, big, high or low in third person, change `world-scale` and the `world-offset-*` / `world-pitch` settings in the **Third Person** group.
4. Axes show the stab; swords show the slash. Turn off `include-axes` if you only want swords replaced.

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
