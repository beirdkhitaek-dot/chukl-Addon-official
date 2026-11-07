package com.chukl.addon.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.function.Consumer;

/**
 * Small helpers that make the chat feedback of the modules clearer: where something is ("420 blocks NE"), how sure a
 * finding is, and gentle tips when a finder has shown nothing for a while. Rule-based, not a language model.
 */
final class ChatCoach {
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    // tip state
    private long startedAt, lastResultAt, lastTipAt;
    private double lastX, lastZ, movedSinceTip;
    private boolean hasPos;
    private int tips;

    /** "420 blocks NE" from the player to a spot. */
    static String where(PlayerEntity p, double x, double z) {
        double dx = x - p.getX(), dz = z - p.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 8) return "right here";
        double bearing = (Math.toDegrees(Math.atan2(dx, -dz)) + 360.0) % 360.0; // north is -Z, east is +X
        return dist + " blocks " + COMPASS[(int) Math.round(bearing / 45.0) % 8];
    }

    static String confidence(int level) {
        return level >= 2 ? "Strong" : level == 1 ? "Likely" : "Weak";
    }

    void reset() {
        long now = System.currentTimeMillis();
        startedAt = now; lastResultAt = now; lastTipAt = now;
        movedSinceTip = 0; hasPos = false; tips = 0;
    }

    /** Call about once a second. Says something helpful when nothing has been marked for a while (at most 4 tips). */
    void tick(MinecraftClient mc, boolean hasResults, int sensitivity, Consumer<String> say) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        double x = mc.player.getX(), z = mc.player.getZ();
        if (hasPos) movedSinceTip += Math.sqrt((x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ));
        lastX = x; lastZ = z; hasPos = true;

        if (hasResults) { lastResultAt = now; return; }
        if (tips >= 4 || now - lastTipAt < 90_000 || now - Math.max(lastResultAt, startedAt) < 90_000) return;

        long idleMin = (now - Math.max(lastResultAt, startedAt)) / 60_000;
        String tip;
        if (movedSinceTip < 48) {
            tip = "Nothing is marked yet and you are standing still. Walk around: the finder can only check the chunks the server has loaded around you.";
        } else if (idleMin < 4) {
            tip = "Nothing marked yet. Keep exploring, away from spawn and from busy farm areas. Bases show up once their chunks load around you.";
        } else {
            tip = "Still nothing after " + idleMin + " minutes. Try a lower Sensitivity (now " + sensitivity + ") or a different area.";
        }
        say.accept(tip);
        tips++;
        lastTipAt = now;
        movedSinceTip = 0;
    }
}
