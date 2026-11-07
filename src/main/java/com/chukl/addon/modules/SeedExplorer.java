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
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final Setting<Boolean> useRtp = sg.add(new BoolSetting.Builder()
        .name("use-rtp")
        .description("If SeedcrackerX has gathered nothing new for a while, types /rtp to jump to a new area and starts exploring there. For servers that have a /rtp command. It waits out the cooldown by itself.")
        .defaultValue(false).build());

    private final Setting<Integer> minutesWithoutData = sg.add(new IntSetting.Builder()
        .name("minutes-without-new-data")
        .description("How long SeedcrackerX can find nothing new before the explorer uses /rtp.")
        .defaultValue(8).range(2, 60).sliderRange(3, 20)
        .visible(useRtp::get).build());

    private final Setting<String> rtpButton = sg.add(new StringSetting.Builder()
        .name("rtp-menu-button")
        .description("If /rtp opens a menu, the name of the button to click (for example Overworld). If nothing matches, the explorer lists the menu's buttons in chat so you can copy the right name.")
        .defaultValue("overworld")
        .visible(useRtp::get).build());

    private enum Rtp { NONE, AWAIT_MENU, AWAIT_TP }
    private Rtp rtp = Rtp.NONE;
    private long nextRtpAt = 0, lastProgressAt = 0, rtpDeadline = 0, progressAskedAt = 0;
    private int menuSettle = 0;
    private boolean menuListed = false;
    private String latestProgress = "", prevProgress = "";
    private double rtpFromX, rtpFromZ;
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private BlockPos origin = BlockPos.ORIGIN;
    private int index = 0, stuckTicks = 0, retries = 0, waitTicks = 0, progressTicks = 0;
    private boolean moving = false;
    private long lastStatusAt = 0;
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
        rtp = Rtp.NONE; nextRtpAt = 0; lastProgressAt = System.currentTimeMillis(); lastStatusAt = lastProgressAt;
        latestProgress = ""; prevProgress = ""; menuListed = false;
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
        long nowMs = System.currentTimeMillis();
        // SeedcrackerX's answer to our progress command: remembered, to tell whether anything new was collected
        if (progressAskedAt > 0 && nowMs - progressAskedAt < 4000 && t.contains("bit")) latestProgress = t;
        // the server says /rtp is on cooldown: wait that long, then try again
        if (rtp != Rtp.NONE && (t.contains("cooldown") || (t.contains("wait") && t.matches(".*\\d.*")))) {
            Matcher m = DIGITS.matcher(t);
            int sec = m.find() ? Math.min(600, Integer.parseInt(m.group(1))) : 60;
            nextRtpAt = nowMs + sec * 1000L + 2000L;
            rtp = Rtp.NONE;
            if (mc.currentScreen != null && mc.player != null) mc.player.closeHandledScreen();
            info("/rtp is on cooldown. Trying again in about " + (sec + 2) + " seconds.");
        }
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

        long now = System.currentTimeMillis();
        if (now - lastStatusAt >= 120_000L) { // a short status line every 2 minutes
            lastStatusAt = now;
            double sx = mc.player.getX() - origin.getX(), sz = mc.player.getZ() - origin.getZ();
            long quiet = (now - lastProgressAt) / 60_000L;
            info("Status: stop " + (index + 1) + ", " + (int) Math.sqrt(sx * sx + sz * sz) + " blocks from the start"
                + (quiet >= 1 ? ", " + quiet + " min since SeedcrackerX last collected something new." : ", SeedcrackerX is still collecting.")
                + (useRtp.get() ? "" : " Turn on use-rtp to jump to a new area when it stalls."));
        }
        if ((showProgress.get() || useRtp.get()) && rtp == Rtp.NONE
            && FabricLoader.getInstance().isModLoaded("seedcrackerx") && ++progressTicks >= 20 * 60) {
            progressTicks = 0;
            // did the last answer differ from the one before? then SeedcrackerX collected something new
            if (!latestProgress.isEmpty()) {
                if (!latestProgress.equals(prevProgress)) lastProgressAt = now;
                prevProgress = latestProgress;
            }
            latestProgress = "";
            progressAskedAt = now;
            ChatUtils.sendPlayerMsg("/seedcracker data bits"); // handled by SeedcrackerX on your own computer
        }

        if (rtp != Rtp.NONE) { handleRtp(now); return; }
        if (useRtp.get() && now >= nextRtpAt && now - lastProgressAt >= minutesWithoutData.get() * 60_000L) {
            startRtp(now);
            return;
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

    private void startRtp(long now) {
        PathManagers.get().stop();
        moving = false;
        rtpFromX = mc.player.getX(); rtpFromZ = mc.player.getZ();
        menuSettle = 0;
        rtp = Rtp.AWAIT_MENU;
        rtpDeadline = now + 8000;
        info("SeedcrackerX has found nothing new for " + minutesWithoutData.get() + " minutes. Using /rtp to jump to a new area.");
        ChatUtils.sendPlayerMsg("/rtp");
    }

    private void handleRtp(long now) {
        double dx = mc.player.getX() - rtpFromX, dz = mc.player.getZ() - rtpFromZ;
        if (dx * dx + dz * dz > 64 * 64) { // moved far away in one go: the teleport worked
            rtp = Rtp.NONE;
            nextRtpAt = now + 61_000L; // the server's 1 minute cooldown
            if (mc.world.getRegistryKey() != World.OVERWORLD) {
                warning("The teleport left the overworld. Stopped.");
                toggle();
                return;
            }
            origin = mc.player.getBlockPos();
            index = 0; retries = 0; stuckTicks = 0; moving = false;
            waitTicks = 100;
            lastProgressAt = now;
            progressTicks = 0;
            info("Teleported to " + origin.getX() + ", " + origin.getZ() + ". Exploring from here.");
            return;
        }

        if (rtp == Rtp.AWAIT_MENU && mc.currentScreen instanceof GenericContainerScreen gcs) {
            if (++menuSettle < 10) return; // let the menu fill up first
            GenericContainerScreenHandler h = gcs.getScreenHandler();
            String want = rtpButton.get().toLowerCase(Locale.ROOT).trim();
            List<String> names = new ArrayList<>();
            int pick = -1, onlyIdx = -1, named = 0;
            int size = Math.min(h.slots.size(), h.getRows() * 9);
            for (int i = 0; i < size; i++) {
                ItemStack st = h.slots.get(i).getStack();
                if (st.isEmpty()) continue;
                String nm = st.getName().getString();
                String low = nm.toLowerCase(Locale.ROOT);
                if (low.isBlank()) continue; // plain filler panes have no name
                names.add("slot " + i + " = " + nm);
                named++; onlyIdx = i;
                if (pick < 0 && !want.isEmpty() && low.contains(want)) pick = i;
            }
            if (pick < 0 && named == 1) pick = onlyIdx;
            if (pick >= 0) {
                mc.interactionManager.clickSlot(h.syncId, pick, 0, SlotActionType.PICKUP, mc.player);
                rtp = Rtp.AWAIT_TP;
                rtpDeadline = now + 20_000L;
                info("Clicked the /rtp menu button in slot " + pick + ".");
            } else {
                if (!menuListed) {
                    menuListed = true;
                    info("The /rtp menu has these buttons: " + names + ". Put the name of the one you want in rtp-menu-button.");
                }
                mc.player.closeHandledScreen();
                rtp = Rtp.NONE;
                nextRtpAt = now + 70_000L;
            }
            return;
        }

        if (now > rtpDeadline) {
            warning("/rtp did not move you. Trying again in a minute.");
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            rtp = Rtp.NONE;
            nextRtpAt = now + 70_000L;
        }
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
