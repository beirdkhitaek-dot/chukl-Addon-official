package com.chukl.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Shared look and animation helpers for the two Sus Chunk modules (render thread only):
 * rounded plates, fade in / fade out for chunks that appear or disappear, and a slide for the best-chunk marker.
 */
final class SusAnim {
    /** One plate on screen: its fade (0 to 1) and the alpha it should reach. */
    static final class Plate {
        ChunkPos pos;
        float fade;
        int alpha;
        boolean live;
    }

    /** One best-chunk marker slot: where it is drawn right now (it slides to the target). */
    static final class Slot {
        double x, z;     // centre being drawn
        double tx, tz;   // centre it is moving to
        float fade;
        boolean live;
        boolean init;
    }

    private final Map<ChunkPos, Plate> plates = new HashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private long last = System.nanoTime();
    private float dt = 0.016f;

    /** Call once per frame before anything else. */
    void tick() {
        long n = System.nanoTime();
        dt = Math.min(0.1f, Math.max(0.0005f, (n - last) / 1e9f));
        last = n;
    }

    void clear() {
        plates.clear();
        slots.clear();
    }

    boolean hasPlates() { return !plates.isEmpty(); }

    float fadeOf(ChunkPos p) {
        Plate pl = plates.get(p);
        return pl == null ? 1f : pl.fade;
    }

    /**
     * Fades chunks in and out. {@code targets} maps each chunk that should be visible to the alpha it should have.
     * Chunks that are gone keep drawing while they fade out. With animations off the result is just the targets.
     */
    Iterable<Plate> plates(Map<ChunkPos, Integer> targets, boolean animate, double fadeSeconds) {
        for (Plate p : plates.values()) p.live = false;
        for (Map.Entry<ChunkPos, Integer> e : targets.entrySet()) {
            Plate p = plates.get(e.getKey());
            if (p == null) {
                p = new Plate();
                p.pos = e.getKey();
                p.fade = animate ? 0f : 1f;
                plates.put(e.getKey(), p);
            }
            p.live = true;
            p.alpha = e.getValue();
        }
        float step = animate ? (float) (dt / Math.max(0.05, fadeSeconds)) : 1f;
        for (Iterator<Plate> it = plates.values().iterator(); it.hasNext(); ) {
            Plate p = it.next();
            if (p.live) p.fade = Math.min(1f, p.fade + step);
            else {
                p.fade -= step;
                if (p.fade <= 0f) it.remove();
            }
        }
        return plates.values();
    }

    /**
     * Keeps one slot per best chunk. The slot slides toward its chunk, a big jump fades instead of sliding,
     * and slots that are no longer used fade out.
     */
    List<Slot> slots(List<ChunkPos> best, boolean animate, double fadeSeconds, double slideSeconds) {
        while (slots.size() < best.size()) slots.add(new Slot());
        float fstep = animate ? (float) (dt / Math.max(0.05, fadeSeconds)) : 1f;
        double k = animate ? 1.0 - Math.exp(-dt / Math.max(0.03, slideSeconds / 3.0)) : 1.0;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (i < best.size()) {
                ChunkPos c = best.get(i);
                s.tx = c.getStartX() + 8.0;
                s.tz = c.getStartZ() + 8.0;
                if (!s.init || Math.hypot(s.tx - s.x, s.tz - s.z) > 96) {
                    s.x = s.tx;
                    s.z = s.tz;
                    s.init = true;
                    if (animate) s.fade = 0f;
                } else {
                    s.x += (s.tx - s.x) * k;
                    s.z += (s.tz - s.z) * k;
                }
                s.live = true;
                s.fade = Math.min(1f, s.fade + fstep);
            } else {
                s.live = false;
                s.fade = Math.max(0f, s.fade - fstep);
            }
        }
        return slots;
    }

    private static Color scale(Color c, double f) {
        return new Color(c.r, c.g, c.b, (int) Math.max(0, Math.min(255, c.a * f)));
    }

    /** The outline of a rounded rectangle, counter-clockwise, as x,z pairs. */
    static double[] roundedLoop(double x1, double z1, double x2, double z2, double radius) {
        double r = Math.max(0, Math.min(radius, Math.min(x2 - x1, z2 - z1) / 2.0));
        if (r < 0.05) return new double[]{x1, z1, x2, z1, x2, z2, x1, z2};
        int segs = 4;
        double[] out = new double[(segs + 1) * 4 * 2];
        int n = 0;
        double[][] corners = {{x2 - r, z1 + r, -90}, {x2 - r, z2 - r, 0}, {x1 + r, z2 - r, 90}, {x1 + r, z1 + r, 180}};
        for (double[] c : corners) {
            for (int i = 0; i <= segs; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / segs);
                out[n++] = c[0] + Math.cos(a) * r;
                out[n++] = c[1] + Math.sin(a) * r;
            }
        }
        return out;
    }

    /**
     * Draws a rounded plate from y to y+thick. Only the top is filled for thin plates (so faces never pile up inside
     * one chunk); a tall column gets walls too. {@code fade} scales the alpha.
     */
    static void plate(Render3DEvent e, double x1, double z1, double x2, double z2, double y, double thick,
                      double radius, Color fill, Color line, ShapeMode mode, double fade) {
        if (fade <= 0.003) return;
        double[] p = roundedLoop(x1, z1, x2, z2, radius);
        int n = p.length / 2;
        double top = y + Math.max(0.02, thick);
        boolean tall = thick > 0.6;
        Color f = scale(fill, fade), l = scale(line, fade);

        if (mode != ShapeMode.Lines) {
            double cx = (x1 + x2) / 2.0, cz = (z1 + z2) / 2.0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                e.renderer.quad(cx, top, cz, p[i * 2], top, p[i * 2 + 1], p[j * 2], top, p[j * 2 + 1], p[j * 2], top, p[j * 2 + 1], f);
            }
            if (tall) {
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    e.renderer.quad(p[i * 2], y, p[i * 2 + 1], p[j * 2], y, p[j * 2 + 1], p[j * 2], top, p[j * 2 + 1], p[i * 2], top, p[i * 2 + 1], f);
                }
            }
        }
        if (mode != ShapeMode.Sides) {
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                e.renderer.line(p[i * 2], top, p[i * 2 + 1], p[j * 2], top, p[j * 2 + 1], l);
                if (tall) {
                    e.renderer.line(p[i * 2], y, p[i * 2 + 1], p[j * 2], y, p[j * 2 + 1], l);
                    if (i % 5 == 0) e.renderer.line(p[i * 2], y, p[i * 2 + 1], p[i * 2], top, p[i * 2 + 1], l);
                }
            }
        }
    }
}
