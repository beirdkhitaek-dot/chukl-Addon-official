package com.chukl.addon.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.Collection;
import java.util.List;

/** Shared helpers for the sus chunk target HUD: which chunk you are looking at, and drawing the info panel. */
final class SusHud {
    private SusHud() {}

    /** The nearest chunk column (from the given set) that your crosshair ray passes through, or null. */
    static ChunkPos lookedChunk(Collection<ChunkPos> chunks, double maxDist, int yMin, int yMax) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || chunks.isEmpty()) return null;
        Vec3d o = mc.player.getCameraPosVec(1.0F);
        Vec3d d = mc.player.getRotationVec(1.0F);

        ChunkPos best = null;
        double bestT = Double.MAX_VALUE;
        for (ChunkPos c : chunks) {
            double t = rayBox(o, d, c.getStartX(), yMin, c.getStartZ(), c.getStartX() + 16, yMax, c.getStartZ() + 16);
            if (t >= 0 && t <= maxDist && t < bestT) {
                bestT = t;
                best = c;
            }
        }
        return best;
    }

    /** Distance along the ray to the box, or -1 when it misses (0 when the ray starts inside). */
    private static double rayBox(Vec3d o, Vec3d d, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tMin = 0, tMax = Double.MAX_VALUE;
        double[] os = {o.x, o.y, o.z}, ds = {d.x, d.y, d.z}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ds[i]) < 1e-9) {
                if (os[i] < lo[i] || os[i] > hi[i]) return -1;
            } else {
                double t1 = (lo[i] - os[i]) / ds[i], t2 = (hi[i] - os[i]) / ds[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return -1;
            }
        }
        return tMin;
    }

    /** One line of the panel. bar >= 0 draws a progress bar (0-100) under the text. */
    static final class Row {
        final String text;
        final int color;
        final int bar;
        Row(String text, int color, int bar) { this.text = text; this.color = color; this.bar = bar; }
        static Row of(String t, int c) { return new Row(t, c, -1); }
    }

    static final int WHITE = 0xFFE6E6E6, GREY = 0xFF9AA0A6, CYAN = 0xFF3DD9FF, GREEN = 0xFF4CE06A,
        YELLOW = 0xFFFFD84D, ORANGE = 0xFFFF9A3D, RED = 0xFFFF5C5C;

    /** Plain-language verdict for a 0-100 confidence. */
    static Row verdict(int conf, boolean natural) {
        if (natural) return Row.of("PROBABLY NATURAL", RED);
        if (conf >= 70) return Row.of("VERY LIKELY A BASE", GREEN);
        if (conf >= 45) return Row.of("LIKELY A BASE", YELLOW);
        if (conf >= 25) return Row.of("MAYBE - CHECK IT", ORANGE);
        return Row.of("WEAK SIGNAL", RED);
    }

    static int barColor(int conf) {
        return conf >= 70 ? GREEN : conf >= 45 ? YELLOW : conf >= 25 ? ORANGE : RED;
    }

    /** Draws a panel next to the crosshair. opacity is 0-100. */
    static void draw(DrawContext ctx, List<Row> rows, double scale, int opacity, int offX, int offY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (rows.isEmpty() || mc.textRenderer == null) return;

        int w = 150;
        int h = 0;
        for (Row r : rows) {
            w = Math.max(w, mc.textRenderer.getWidth(r.text));
            h += r.bar >= 0 ? 17 : 11;
        }
        int bgA = Math.max(0, Math.min(255, (int) Math.round(opacity * 2.55)));

        float sx = mc.getWindow().getScaledWidth() / 2.0F + 14 + offX;
        float sy = mc.getWindow().getScaledHeight() / 2.0F - h * (float) scale / 2.0F + offY;

        Matrix3x2fStack mat = ctx.getMatrices();
        mat.pushMatrix();
        mat.translate(sx, sy);
        mat.scale((float) scale, (float) scale);
        ctx.fill(-5, -5, w + 5, h + 3, (bgA << 24) | 0x0C0C14);
        ctx.fill(-5, -5, -3, h + 3, 0xFF3DD9FF); // accent bar
        int y = 0;
        for (Row r : rows) {
            ctx.drawText(mc.textRenderer, r.text, 0, y, r.color, true);
            y += 11;
            if (r.bar >= 0) {
                ctx.fill(0, y, w, y + 5, 0xFF2A2A33);
                ctx.fill(0, y, (int) (w * Math.min(100, r.bar) / 100.0), y + 5, r.color);
                y += 6;
            }
        }
        mat.popMatrix();
    }
}
