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

    static final int WHITE = 0xFFE6E6E6, GREY = 0xFF9AA0A6, DIM = 0xFF6B7A8C, CYAN = 0xFF3DD9FF, GREEN = 0xFF4CE06A,
        YELLOW = 0xFFFFD84D, ORANGE = 0xFFFF9A3D, RED = 0xFFFF5C5C;

    /** One reason line: symbol is '+', '-' or '!'. */
    record Ev(char symbol, int color, String text) {}

    /** Everything the panel shows. */
    static final class Data {
        String title = "";
        boolean best;
        int accent = CYAN;
        String verdict = "";
        int conf;
        List<Ev> why = new java.util.ArrayList<>();
        String status = "";           // e.g. "Checking it again... 40%" or "Double-checked: still looks real"
        int statusColor = GREY;
        String actionLabel = "NEXT STEP";
        String action = "";
        String dist = "";
    }

    /** Fills a Data with the verdict text and color for a confidence. */
    static void applyVerdict(Data d, int conf, boolean natural) {
        d.conf = conf;
        if (natural) { d.verdict = "PROBABLY NATURAL"; d.accent = RED; }
        else if (conf >= 70) { d.verdict = "VERY LIKELY A BASE"; d.accent = GREEN; }
        else if (conf >= 45) { d.verdict = "LIKELY A BASE"; d.accent = YELLOW; }
        else if (conf >= 25) { d.verdict = "MAYBE - CHECK IT"; d.accent = ORANGE; }
        else { d.verdict = "WEAK SIGNAL"; d.accent = RED; }
    }

    private static int alpha(int argb, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF); }

    /** Draws the panel next to the crosshair. opacity is 0-100. */
    static void draw(DrawContext ctx, Data d, double scale, int opacity, int offX, int offY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null) return;
        net.minecraft.client.font.TextRenderer tr = mc.textRenderer;

        final int W = 232, PAD = 8, INNER = W - PAD * 2;
        int bgA = Math.max(0, Math.min(255, (int) Math.round(opacity * 2.55)));

        // ---- measure ----
        List<net.minecraft.text.OrderedText> actionLines = tr.wrapLines(net.minecraft.text.Text.literal(d.action), INNER - 16);
        java.util.List<List<net.minecraft.text.OrderedText>> whyLines = new java.util.ArrayList<>();
        int whyH = 0;
        for (Ev e : d.why) {
            List<net.minecraft.text.OrderedText> l = tr.wrapLines(net.minecraft.text.Text.literal(e.text()), INNER - 16);
            whyLines.add(l);
            whyH += Math.max(10, l.size() * 9 + 1) + 3;
        }
        int cardH = 6 + 9 + 2 + actionLines.size() * 10 + (d.dist.isEmpty() ? 0 : 11) + 6;
        int h = PAD + 11 /*header*/ + 5 + 14 /*verdict*/ + 4 + 6 /*bar*/ + 7 + 1 + 6 + 9 /*why title*/ + 4 + whyH
            + (d.status.isEmpty() ? 0 : 12) + 5 + cardH + PAD;

        float sx = mc.getWindow().getScaledWidth() / 2.0F + 14 + offX;
        float sy = mc.getWindow().getScaledHeight() / 2.0F - h * (float) scale / 2.0F + offY;

        Matrix3x2fStack mat = ctx.getMatrices();
        mat.pushMatrix();
        mat.translate(sx, sy);
        mat.scale((float) scale, (float) scale);

        // ---- frame: colored border, dark body ----
        ctx.fill(0, 0, W, h, alpha(d.accent, 150));
        ctx.fill(1, 1, W - 1, h - 1, (bgA << 24) | 0x0C0F14);
        // clip the corners a little for a softer look
        ctx.fill(0, 0, 2, 2, 0); 

        int y = PAD;
        // header: dot, title, best tag
        ctx.fill(PAD, y + 2, PAD + 5, y + 7, d.accent);
        ctx.drawText(tr, d.title, PAD + 9, y, GREY, false);
        if (d.best) {
            int tx = PAD + 9 + tr.getWidth(d.title) + 6;
            ctx.fill(tx, y - 1, tx + tr.getWidth("BEST PICK") + 6, y + 9, alpha(CYAN, 45));
            ctx.drawText(tr, "BEST PICK", tx + 3, y, CYAN, false);
        }
        y += 16;

        // verdict (bigger)
        mat.pushMatrix();
        mat.translate(PAD, y);
        mat.scale(1.5F, 1.5F);
        ctx.drawText(tr, d.verdict, 0, 0, d.accent, true);
        mat.popMatrix();
        y += 15;

        // confidence bar + percent
        String pct = d.conf + "%";
        int pw = tr.getWidth(pct);
        int barW = INNER - pw - 6;
        ctx.fill(PAD, y + 1, PAD + barW, y + 6, 0xFF2A303A);
        ctx.fill(PAD, y + 1, PAD + Math.max(3, (int) (barW * Math.min(100, d.conf) / 100.0)), y + 6, d.accent);
        ctx.drawText(tr, pct, PAD + barW + 6, y - 1, WHITE, true);
        y += 12;

        // divider + section title
        ctx.fill(PAD, y, W - PAD, y + 1, alpha(0xFFFFFF, 22));
        y += 6;
        ctx.drawText(tr, "WHY IT WAS FLAGGED", PAD, y, DIM, false);
        y += 13;

        // reasons
        for (int i = 0; i < d.why.size(); i++) {
            Ev e = d.why.get(i);
            List<net.minecraft.text.OrderedText> lines = whyLines.get(i);
            int rowH = Math.max(10, lines.size() * 9 + 1);
            ctx.fill(PAD, y, PAD + 10, y + 10, alpha(e.color(), 55));
            String sym = String.valueOf(e.symbol());
            ctx.drawText(tr, sym, PAD + 5 - tr.getWidth(sym) / 2, y + 1, e.color(), false);
            for (int k = 0; k < lines.size(); k++) ctx.drawText(tr, lines.get(k), PAD + 15, y + 1 + k * 9, 0xFFD5DEE8, false);
            y += rowH + 3;
        }

        if (!d.status.isEmpty()) {
            ctx.drawText(tr, d.status, PAD, y, d.statusColor, false);
            y += 12;
        }
        y += 5;

        // action card
        ctx.fill(PAD, y, W - PAD, y + cardH, alpha(d.accent, 36));
        ctx.fill(PAD, y, PAD + 2, y + cardH, d.accent);
        int cy = y + 6;
        ctx.drawText(tr, d.actionLabel, PAD + 8, cy, d.accent, false);
        cy += 11;
        for (net.minecraft.text.OrderedText l : actionLines) { ctx.drawText(tr, l, PAD + 8, cy, 0xFFFFFFFF, true); cy += 10; }
        if (!d.dist.isEmpty()) ctx.drawText(tr, d.dist, PAD + 8, cy, GREY, false);

        mat.popMatrix();
    }
}
