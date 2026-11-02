package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Death Ghost: a killed player becomes a solid black body (blackout), then dissolves Source-engine style:
 * the body fades out while it drifts up, shedding white spark streaks and flickering white lightning.
 * Everything is drawn with Render3DEvent lines/quads from our own particle lists (no game particle API).
 * Animation time is wall-clock based, so it stays smooth at low FPS (no dependency on a tickDelta field).
 */
public class DeathGhost extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    public enum DissolveType { Energy, Heavy_electrical, Light_electrical }

    private final Setting<Boolean> onlyMine = sg.add(new BoolSetting.Builder()
        .name("only-on-my-kills").description("Only players you killed. Off = every player death.")
        .defaultValue(true).build());

    private final Setting<Integer> window = sg.add(new IntSetting.Builder()
        .name("kill-window-ticks").description("A death counts as your kill if you hit the player within this many ticks.")
        .defaultValue(80).min(5).sliderMax(400).build());

    private final Setting<Double> duration = sg.add(new DoubleSetting.Builder()
        .name("duration").description("Seconds for the whole effect (0.3 s blackout, then the dissolve).")
        .defaultValue(2.0).min(0.6).sliderMax(6.0).build());

    private final Setting<Double> riseHeight = sg.add(new DoubleSetting.Builder()
        .name("rise-height").description("How many blocks the body drifts up while it dissolves.")
        .defaultValue(0.3).min(0.0).sliderMax(2.0).build());

    private final Setting<Integer> particleAmount = sg.add(new IntSetting.Builder()
        .name("particle-amount").description("White spark streaks spawned per body.")
        .defaultValue(60).min(0).sliderMax(300).build());

    private final Setting<Boolean> arcs = sg.add(new BoolSetting.Builder()
        .name("arcs").description("White lightning bolts around the dissolving body.").defaultValue(true).build());

    private final Setting<DissolveType> dissolveType = sg.add(new EnumSetting.Builder<DissolveType>()
        .name("dissolve-type").description("Energy: sparks and a few bolts. Heavy: more, thicker bolts. Light: fewer, thinner bolts.")
        .defaultValue(DissolveType.Energy).build());

    private final Setting<Boolean> blueTint = sg.add(new BoolSetting.Builder()
        .name("dark-blue-tint").description("Body turns dark blue toward the end (CS:GO style).").defaultValue(false).build());

    private final Setting<Integer> maxGhosts = sg.add(new IntSetting.Builder()
        .name("max-ghosts").description("Limit for performance (Android).").defaultValue(6).min(1).sliderMax(20).build());

    private static final double BLACKOUT_MS = 300.0;
    private static final int MAX_PARTICLES = 400;

    private record Frame(double x, double y, double z, float bodyYaw, float headYaw, float pitch,
                         float limbPos, float limbSpeed, float swing, boolean sneak, boolean lying) {}

    private static final class Spark {
        double x, y, z, vx, vy, vz, len; double age, life;
    }

    private static final class Bolt {
        double[][] pts; double[] flash;
    }

    private static final class Ghost {
        final Frame pose;
        final long born = System.currentTimeMillis();
        final Random rng;
        final List<Spark> sparks = new ArrayList<>();
        final List<Bolt> bolts = new ArrayList<>();
        long lastBolt = 0, lastStep;
        double emitAcc = 0;
        Ghost(Frame pose, long seed) { this.pose = pose; this.rng = new Random(seed); this.lastStep = born; }
    }

    private final KillTracker tracker = new KillTracker();
    private final Map<Integer, Frame> lastAlive = new HashMap<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private int tick = 0;

    public DeathGhost() {
        super(ChuklAddon.VISUAL_CATEGORY, "death-ghost",
            "A killed player turns black, then dissolves like the Half-Life 2 energy dissolve, with sparks and lightning.");
    }

    @Override
    public void onActivate() {
        tracker.clear();
        lastAlive.clear();
        ghosts.clear();
        tick = 0;
    }

    @Override
    public void onDeactivate() {
        ghosts.clear();
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof PlayerEntity) tracker.hit(event.entity.getId(), tick);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tick++;

        // remember the last living pose of every nearby player (the death pose is the last one before they die)
        Set<Integer> present = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.isDead() || p.getHealth() <= 0.0F || p.deathTime > 0) continue;
            present.add(p.getId());
            Frame prev = lastAlive.get(p.getId());
            String pose = p.getPose().name();
            boolean lying = pose.equals("SWIMMING") || pose.equals("GLIDING") || pose.equals("FALL_FLYING");
            float limbSpeed = 0, limbPos = 0;
            if (prev != null) {
                double moved = Math.hypot(p.getX() - prev.x(), p.getZ() - prev.z());
                limbSpeed = (float) Math.min(1.0, moved * 4.0);
                limbPos = prev.limbPos() + limbSpeed;
            }
            lastAlive.put(p.getId(), new Frame(p.getX(), p.getY(), p.getZ(), p.getBodyYaw(), p.getHeadYaw(), p.getPitch(),
                limbPos, limbSpeed, p.getHandSwingProgress(1.0F), p.isSneaking(), lying));
        }
        if (tick % 40 == 0) lastAlive.keySet().removeIf(id -> !present.contains(id) && mc.world.getEntityById(id) == null);

        for (PlayerEntity p : tracker.deaths(mc.world, mc.player, tick, window.get(), !onlyMine.get())) {
            Frame f = lastAlive.get(p.getId());
            if (f == null) f = new Frame(p.getX(), p.getY(), p.getZ(), p.getBodyYaw(), p.getHeadYaw(), p.getPitch(), 0, 0, 0, false, false);
            ghosts.add(new Ghost(f, p.getId() * 31L + tick));
            while (ghosts.size() > maxGhosts.get()) ghosts.remove(0);
        }
    }

    // ================= Render =================

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (ghosts.isEmpty()) return;
        long now = System.currentTimeMillis();
        double total = duration.get() * 1000.0;

        Iterator<Ghost> it = ghosts.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            double age = now - g.born;
            boolean alive = age < total;
            if (!alive && g.sparks.isEmpty()) { it.remove(); continue; }
            double dissolveMs = Math.max(1.0, total - BLACKOUT_MS);
            double prog = age <= BLACKOUT_MS ? 0.0 : Math.min(1.0, (age - BLACKOUT_MS) / dissolveMs);
            double sm = smooth(prog);
            double rise = riseHeight.get() * sm;
            double dt = Math.min(0.1, (now - g.lastStep) / 1000.0);
            g.lastStep = now;

            if (alive) drawBody(event, g, prog, sm, rise);
            if (alive && age > BLACKOUT_MS) {
                emitSparks(g, dt, dissolveMs, rise, sm);
                if (arcs.get()) updateAndDrawBolts(event, g, now, rise, sm);
            }
            stepAndDrawSparks(event, g, dt);
        }
    }

    private void drawBody(Render3DEvent event, Ghost g, double prog, double sm, double rise) {
        Frame f = g.pose;
        double x = f.x, y = f.y + rise, z = f.z;
        double rad = -Math.toRadians(f.bodyYaw);
        double tilt = f.lying ? Math.toRadians(80) : (f.sneak ? Math.toRadians(25) : 0);
        double alpha = 1.0 - sm; // slow fade 1 -> 0 (stays 1 during the blackout)
        double shrink = 1.0 - 0.2 * sm;

        // very dark, almost no color; optionally drifts to dark blue near the end
        int r = 5, gr = 5, b = 8;
        if (blueTint.get()) {
            double k = clamp((prog - 0.4) / 0.6, 0, 1);
            r = (int) lerp(5, 8, k); gr = (int) lerp(5, 22, k); b = (int) lerp(8, 70, k);
        }
        Color col = new Color(r, gr, b, (int) clamp(255 * alpha, 0, 255));

        double armSwing = Math.cos(f.limbPos * 0.6662) * 1.0 * f.limbSpeed;
        double legSwing = Math.cos(f.limbPos * 0.6662) * 1.4 * f.limbSpeed;
        double hit = -Math.sin(f.swing * Math.PI) * 1.4;
        double headRot = Math.toRadians(f.pitch);
        double headTurn = -Math.toRadians(clamp(wrap(f.headYaw - f.bodyYaw), -50, 50));

        double[][] parts = {
            // pivotX, pivotY, pivotZ, offY, hx, hy, hz, rotX, rotY
            {0, 18, 0, 0, 4, 6, 2, 0, 0},                       // torso
            {0, 24, 0, 4, 4, 4, 4, headRot, headTurn},          // head
            {-6, 22, 0, -6, 2, 6, 2, armSwing + hit, 0},        // arm
            {6, 22, 0, -6, 2, 6, 2, -armSwing, 0},              // arm
            {-2, 12, 0, -6, 2, 6, 2, -legSwing, 0},             // leg
            {2, 12, 0, -6, 2, 6, 2, legSwing, 0}                // leg
        };
        for (int pi = 0; pi < parts.length; pi++) {
            double[] p = parts[pi];
            double hx = p[4] * shrink, hy = p[5] * shrink, hz = p[6] * shrink;
            double[][] c = new double[8][];
            int k = 0;
            for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) for (int sz = -1; sz <= 1; sz += 2) {
                c[k++] = place(sx * hx, p[3] + sy * hy, sz * hz, p, pi == 0, tilt, rad, x, y, z);
            }
            fillBox(event, c, col);
        }
    }

    /** A random point inside the body (world space), following the rise. */
    private double[] bodyPoint(Ghost g, double rise, double shrink) {
        double lx = (g.rng.nextDouble() - 0.5) * 0.5 * shrink;
        double ly = 0.05 + g.rng.nextDouble() * 1.7 * shrink;
        double lz = (g.rng.nextDouble() - 0.5) * 0.25 * shrink;
        double rad = -Math.toRadians(g.pose.bodyYaw);
        double ex = lx * Math.cos(rad) + lz * Math.sin(rad);
        double ez = -lx * Math.sin(rad) + lz * Math.cos(rad);
        return new double[]{g.pose.x + ex, g.pose.y + rise + ly, g.pose.z + ez};
    }

    private void emitSparks(Ghost g, double dt, double dissolveMs, double rise, double sm) {
        int amount = particleAmount.get();
        if (amount <= 0) return;
        double mult = dissolveType.get() == DissolveType.Energy ? 1.0 : (dissolveType.get() == DissolveType.Heavy_electrical ? 0.7 : 0.5);
        g.emitAcc += amount * mult * dt / (dissolveMs / 1000.0);
        while (g.emitAcc >= 1.0) {
            g.emitAcc -= 1.0;
            if (g.sparks.size() >= MAX_PARTICLES) break;
            Spark s = new Spark();
            double[] pt = bodyPoint(g, rise, 1.0 - 0.2 * sm);
            s.x = pt[0]; s.y = pt[1]; s.z = pt[2];
            s.vx = (g.rng.nextDouble() - 0.5) * 0.5;
            s.vy = 0.6 + g.rng.nextDouble() * 0.9; // mostly upward
            s.vz = (g.rng.nextDouble() - 0.5) * 0.5;
            s.len = 0.15 + g.rng.nextDouble() * 0.15;
            s.life = 0.5 + g.rng.nextDouble() * 0.5;
            g.sparks.add(s);
        }
    }

    private void stepAndDrawSparks(Render3DEvent event, Ghost g, double dt) {
        Iterator<Spark> it = g.sparks.iterator();
        while (it.hasNext()) {
            Spark s = it.next();
            s.age += dt;
            if (s.age >= s.life) { it.remove(); continue; }
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt;
            double fade = 1.0 - s.age / s.life;
            double m = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
            double dx = s.vx / m * s.len, dy = s.vy / m * s.len, dz = s.vz / m * s.len;
            // soft glow: slightly longer and offset, low alpha, drawn first (behind the core)
            event.renderer.line(s.x - dx * 0.2 + 0.012, s.y - dy * 0.2, s.z - dz * 0.2 + 0.012,
                s.x - dx * 1.4 + 0.012, s.y - dy * 1.4, s.z - dz * 1.4 + 0.012, new Color(255, 255, 255, (int) (60 * fade)));
            event.renderer.line(s.x, s.y, s.z, s.x - dx, s.y - dy, s.z - dz, new Color(255, 255, 255, (int) (255 * fade)));
        }
    }

    private void updateAndDrawBolts(Render3DEvent event, Ghost g, long now, double rise, double sm) {
        DissolveType t = dissolveType.get();
        if (now - g.lastBolt >= 80 || g.bolts.isEmpty()) { // re-randomize every ~80 ms so they flicker
            g.lastBolt = now;
            g.bolts.clear();
            int count = t == DissolveType.Heavy_electrical ? 3 + g.rng.nextInt(2) : (t == DissolveType.Energy ? 2 + g.rng.nextInt(2) : 2);
            for (int i = 0; i < count; i++) g.bolts.add(makeBolt(g, rise, sm));
        }
        double w = t == DissolveType.Heavy_electrical ? 0.05 : (t == DissolveType.Energy ? 0.03 : 0.015);
        double fade = 1.0 - sm * 0.8;
        for (Bolt b : g.bolts) {
            double[][] p = b.pts;
            for (int i = 0; i < p.length - 1; i++) {
                double[] a = p[i], c = p[i + 1];
                // wider low-alpha outer line (two offset lines stand in for line width)
                Color outer = new Color(255, 255, 255, (int) (70 * fade));
                event.renderer.line(a[0] + w, a[1], a[2], c[0] + w, c[1], c[2], outer);
                event.renderer.line(a[0] - w, a[1], a[2] - w, c[0] - w, c[1], c[2] - w, outer);
                event.renderer.line(a[0], a[1], a[2], c[0], c[1], c[2], new Color(255, 255, 255, (int) (255 * fade)));
                if (t == DissolveType.Heavy_electrical)
                    event.renderer.line(a[0], a[1] + w * 0.5, a[2] + w * 0.5, c[0], c[1] + w * 0.5, c[2] + w * 0.5, new Color(255, 255, 255, (int) (200 * fade)));
            }
            // flash at the end point: bright square with a soft halo (two crossed quads each)
            double[] e = b.flash;
            flashSquare(event, e, 0.14, new Color(255, 255, 255, (int) (50 * fade)));
            flashSquare(event, e, 0.06, new Color(255, 255, 255, (int) (255 * fade)));
        }
    }

    private Bolt makeBolt(Ghost g, double rise, double sm) {
        Bolt b = new Bolt();
        double[] s = bodyPoint(g, rise, 1.0 - 0.2 * sm);
        double[] d = randDir(g.rng);
        double len = 1.0 + g.rng.nextDouble() * 2.0;
        double[] e = {s[0] + d[0] * len, s[1] + d[1] * len, s[2] + d[2] * len};
        int segs = 6 + g.rng.nextInt(5);
        b.pts = new double[segs + 1][];
        b.pts[0] = s;
        b.pts[segs] = e;
        for (int i = 1; i < segs; i++) {
            double f = i / (double) segs;
            double[] j = randDir(g.rng);
            double dot = j[0] * d[0] + j[1] * d[1] + j[2] * d[2];
            double jx = j[0] - d[0] * dot, jy = j[1] - d[1] * dot, jz = j[2] - d[2] * dot; // sideways only
            double amp = 0.25 * len / 2.0;
            b.pts[i] = new double[]{s[0] + (e[0] - s[0]) * f + jx * amp, s[1] + (e[1] - s[1]) * f + jy * amp, s[2] + (e[2] - s[2]) * f + jz * amp};
        }
        b.flash = e;
        return b;
    }

    private static double[] randDir(Random r) {
        double x, y, z, l;
        do { x = r.nextGaussian(); y = r.nextGaussian(); z = r.nextGaussian(); l = Math.sqrt(x * x + y * y + z * z); } while (l < 1e-4);
        return new double[]{x / l, y / l, z / l};
    }

    private void flashSquare(Render3DEvent e, double[] c, double h, Color col) {
        e.renderer.quad(c[0] - h, c[1], c[2] - h, c[0] + h, c[1], c[2] - h, c[0] + h, c[1], c[2] + h, c[0] - h, c[1], c[2] + h, col);
        e.renderer.quad(c[0] - h, c[1] - h, c[2], c[0] + h, c[1] - h, c[2], c[0] + h, c[1] + h, c[2], c[0] - h, c[1] + h, c[2], col);
        e.renderer.quad(c[0], c[1] - h, c[2] - h, c[0], c[1] - h, c[2] + h, c[0], c[1] + h, c[2] + h, c[0], c[1] + h, c[2] - h, col);
    }

    /** Local part coordinates (pixels) -> world position. */
    private double[] place(double lx, double ly, double lz, double[] p, boolean torso, double tilt, double yawRad,
                           double wx, double wy, double wz) {
        // pixels -> blocks, relative to the part pivot (the torso rotates about its own center)
        double px = lx / 16.0, py = ly / 16.0, pz = lz / 16.0;
        // part rotation about its pivot: X (swing / nod) then Y (head turn)
        double rx = p[7], ry = p[8];
        double y1 = py * Math.cos(rx) - pz * Math.sin(rx);
        double z1 = py * Math.sin(rx) + pz * Math.cos(rx);
        double x1 = px, y2 = y1, z2 = z1;
        double x2 = x1 * Math.cos(ry) + z2 * Math.sin(ry);
        double z3 = -x1 * Math.sin(ry) + z2 * Math.cos(ry);
        // pivot offset (torso pivot already is its center at y = 18)
        double bx = x2 + p[0] / 16.0, by = y2 + p[1] / 16.0, bz = z3 + p[2] / 16.0;
        // whole-body tilt about the hips (y = 12 px = 0.75)
        double hipY = 0.75;
        double ty = (by - hipY) * Math.cos(tilt) - bz * Math.sin(tilt) + hipY;
        double tz = (by - hipY) * Math.sin(tilt) + bz * Math.cos(tilt);
        // body yaw
        double ex = bx * Math.cos(yawRad) + tz * Math.sin(yawRad);
        double ez = -bx * Math.sin(yawRad) + tz * Math.cos(yawRad);
        return new double[]{wx + ex, wy + ty, wz + ez};
    }

    // corner index = (sx+1)*2 + (sy+1) + (sz+1)/2  ->  bits: x*4 + y*2 + z
    private static final int[][] FACES = {
        {0, 1, 3, 2}, {4, 5, 7, 6}, // x faces
        {0, 1, 5, 4}, {2, 3, 7, 6}, // y faces
        {0, 2, 6, 4}, {1, 3, 7, 5}  // z faces
    };
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private void fillBox(Render3DEvent e, double[][] c, Color col) {
        for (int[] f : FACES) {
            double[] a = c[f[0]], b = c[f[1]], d = c[f[2]], q = c[f[3]];
            e.renderer.quad(a[0], a[1], a[2], b[0], b[1], b[2], d[0], d[1], d[2], q[0], q[1], q[2], col);
        }
    }

    private void edges(Render3DEvent e, double[][] c, Color col) {
        for (int[] ed : EDGES) {
            double[] a = c[ed[0]], b = c[ed[1]];
            e.renderer.line(a[0], a[1], a[2], b[0], b[1], b[2], col);
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static double wrap(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    private static double lerpAngle(double a, double b, double t) { return a + wrap(b - a) * t; }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }
}
