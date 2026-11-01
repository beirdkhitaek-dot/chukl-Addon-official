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

    /** Draws a panel next to the crosshair. opacity is 0-100. */
    static void draw(DrawContext ctx, List<String> lines, double scale, int opacity, int offX, int offY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (lines.isEmpty() || mc.textRenderer == null) return;

        int w = 0;
        for (String l : lines) w = Math.max(w, mc.textRenderer.getWidth(l));
        int h = lines.size() * 10;
        int bgA = Math.max(0, Math.min(255, (int) Math.round(opacity * 2.55)));

        float sx = mc.getWindow().getScaledWidth() / 2.0F + 14 + offX;
        float sy = mc.getWindow().getScaledHeight() / 2.0F - h * (float) scale / 2.0F + offY;

        Matrix3x2fStack mat = ctx.getMatrices();
        mat.pushMatrix();
        mat.translate(sx, sy);
        mat.scale((float) scale, (float) scale);
        ctx.fill(-4, -4, w + 4, h + 3, (bgA << 24) | 0x0C0C14);
        ctx.fill(-4, -4, -2, h + 3, 0xFF3DD9FF); // accent bar
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawText(mc.textRenderer, lines.get(i), 0, i * 10, i == 0 ? 0xFF3DD9FF : 0xFFE6E6E6, true);
        }
        mat.popMatrix();
    }
}
