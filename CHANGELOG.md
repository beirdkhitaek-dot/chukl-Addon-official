# Changelog

Every release has a section here: **what's new** and **how to use it**. The same text is published
as the release notes on the [Releases page](https://github.com/beirdkhitaek-dot/chukl-addon-official/releases).

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
