package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * Seed Explorer: a helper for the SeedcrackerX mod. SeedcrackerX learns the world seed from structures (igloos, desert
 * pyramids, jungle temples, swamp huts, shipwrecks, pillager outposts, ocean monuments) that show up in the chunks your
 * game loads. This module walks you outward from where you start, in a square spiral using Baritone, so new chunks keep
 * loading. It only walks. It never touches SeedcrackerX's data, never sends anything to the server except the normal
 * movement your game already sends, and only runs a client-side SeedcrackerX command to show progress.
 */
public class SeedExplorer extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> stepSize = sg.add(new IntSetting.Builder()
        .name("distance-between-stops")
        .description("How far to walk between stops, in blocks. Keep it a little under how far the server shows you. 160 is good for most servers.")
        .defaultValue(160).range(48, 512).sliderRange(64, 320).build());

    private final Setting<Integer> maxDistance = sg.add(new IntSetting.Builder()
        .name("stop-after-blocks")
        .description("The explorer stops by itself when it is this far from where you started.")
        .defaultValue(3000).range(200, 30000).sliderRange(500, 10000).build());

    private final Setting<Boolean> showProgress = sg.add(new BoolSetting.Builder()
        .name("show-seedcracker-progress")
        .description("Every minute, runs SeedcrackerX's own progress command so you can see how much data it has collected. Needs SeedcrackerX.")
        .defaultValue(true).build());

    private BlockPos origin = BlockPos.ORIGIN;
    private int index = 0, stuckTicks = 0, retries = 0, waitTicks = 0, progressTicks = 0;
    private boolean moving = false;
    private int[] target = {0, 0};

    public SeedExplorer() {
        super(ChuklAddon.CATEGORY, "seed-explorer",
            "Helper for SeedcrackerX: walks you outward in a spiral with Baritone so new chunks keep loading and SeedcrackerX can collect structures.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone was not found. Seed Explorer needs it to walk.");
            toggle();
            return;
        }
        if (mc.world.getRegistryKey() != World.OVERWORLD) {
            error("Seed Explorer only works in the overworld, where the structures are.");
            toggle();
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("seedcrackerx")) {
            warning("SeedcrackerX is not installed. I will still walk, but nothing is collecting the structures. Install SeedcrackerX to use this.");
        }
        origin = mc.player.getBlockPos();
        index = 0; stuckTicks = 0; retries = 0; waitTicks = 40; progressTicks = 0; moving = false;
        info("Seed Explorer started. It walks a square spiral around this spot, stopping every "
            + stepSize.get() + " blocks to let chunks load. Turn the module off to stop.");
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) PathManagers.get().stop();
        moving = false;
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String t = event.getMessage().getString().toLowerCase(Locale.ROOT);
        boolean seed = t.contains("worldseed") || t.contains("world seed");
        if (seed && (t.contains("found") || t.contains("cracked"))) {
            info("SeedcrackerX reports it found the seed. Stopping the explorer.");
            toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // safety
        if (mc.player.getHealth() <= 8.0f) {
            warning("Health is low (" + (int) mc.player.getHealth() + "). Stopped for safety.");
            toggle();
            return;
        }

        if (showProgress.get() && FabricLoader.getInstance().isModLoaded("seedcrackerx") && ++progressTicks >= 20 * 60) {
            progressTicks = 0;
            ChatUtils.sendPlayerMsg("/seedcracker data bits"); // handled by SeedcrackerX on your own computer
        }

        if (waitTicks > 0) { waitTicks--; return; }

        if (!moving) {
            if (!nextStop()) return;
            return;
        }

        BlockPos here = mc.player.getBlockPos();
        int dx = here.getX() - target[0], dz = here.getZ() - target[1];
        if (dx * dx + dz * dz <= 20 * 20) {
            // arrived: give the server a moment to send chunks, then go to the next stop
            moving = false;
            index++;
            retries = 0; stuckTicks = 0;
            waitTicks = 40;
            return;
        }

        if (!PathManagers.get().isPathing()) {
            if (++stuckTicks > 80) {
                stuckTicks = 0;
                if (++retries > 3) {
                    warning("Could not reach stop " + (index + 1) + ", skipping it.");
                    moving = false;
                    index++;
                    retries = 0;
                    return;
                }
                PathManagers.get().moveTo(new BlockPos(target[0], here.getY(), target[1]), true);
            }
        } else stuckTicks = 0;
    }

    /** Picks the next spiral stop and tells Baritone to go there. Returns false when the run is over. */
    private boolean nextStop() {
        int[] g = spiral(index);
        int x = origin.getX() + g[0] * stepSize.get();
        int z = origin.getZ() + g[1] * stepSize.get();
        int dist = (int) Math.sqrt((double) (x - origin.getX()) * (x - origin.getX()) + (double) (z - origin.getZ()) * (z - origin.getZ()));
        if (dist > maxDistance.get()) {
            info("Reached the distance limit (" + maxDistance.get() + " blocks). Seed Explorer finished.");
            toggle();
            return false;
        }
        target = new int[]{x, z};
        moving = true;
        stuckTicks = 0;
        PathManagers.get().moveTo(new BlockPos(x, mc.player.getBlockY(), z), true);
        return true;
    }

    /** Square spiral grid position: 0 is the start, then it winds outward one cell at a time. */
    static int[] spiral(int i) {
        if (i <= 0) return new int[]{0, 0};
        int n = i + 1;
        int k = (int) Math.ceil((Math.sqrt(n) - 1) / 2);
        int t = 2 * k + 1;
        int m = t * t;
        t -= 1;
        if (n >= m - t) return new int[]{k - (m - n), -k};
        m -= t;
        if (n >= m - t) return new int[]{-k, -k + (m - n)};
        m -= t;
        if (n >= m - t) return new int[]{-k + (m - n), k};
        return new int[]{k, k - (m - n - t)};
    }
}
