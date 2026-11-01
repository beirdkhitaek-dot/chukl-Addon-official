package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
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
 * Death Ghost: when a player you kill dies, a dark silhouette with a white glowing outline replays the last
 * moves that player made, then collapses and fades away smoothly.
 */
public class DeathGhost extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> anyDeath = sg.add(new BoolSetting.Builder()
        .name("any-player-death").description("Also show the ghost when a player you did not hit dies.")
        .defaultValue(false).build());

    private final Setting<Integer> window = sg.add(new IntSetting.Builder()
        .name("kill-window-ticks").description("A death counts as your kill if you hit the player within this many ticks.")
        .defaultValue(80).min(5).sliderMax(400).build());

    private final Setting<Double> duration = sg.add(new DoubleSetting.Builder()
        .name("fade-seconds").description("How long the whole animation lasts. It fades away smoothly over this time.")
        .defaultValue(2.0).min(0.3).sliderMax(6.0).build());

    private final Setting<Integer> replayTicks = sg.add(new IntSetting.Builder()
        .name("replay-ticks").description("How many of the player's last ticks (20 = 1 second) are replayed.")
        .defaultValue(20).min(1).sliderMax(40).build());

    private final Setting<Boolean> fallOver = sg.add(new BoolSetting.Builder()
        .name("collapse").description("After the replay the body falls over before it fades.")
        .defaultValue(true).build());

    private final Setting<Integer> maxGhosts = sg.add(new IntSetting.Builder()
        .name("max-ghosts").defaultValue(6).min(1).sliderMax(20).build());

    private final Setting<SettingColor> coreColor = sgLook.add(new ColorSetting.Builder()
        .name("core-color").description("The dark body.").defaultValue(new SettingColor(8, 8, 12, 255)).build());

    private final Setting<SettingColor> glowColor = sgLook.add(new ColorSetting.Builder()
        .name("glow-color").description("The glowing outline.").defaultValue(new SettingColor(255, 255, 255, 255)).build());

    private final Setting<Double> glowSize = sgLook.add(new DoubleSetting.Builder()
        .name("glow-size").defaultValue(0.05).min(0.0).sliderMax(0.2).build());

    private final Setting<Boolean> sparks = sgLook.add(new BoolSetting.Builder()
        .name("sparks").description("Little embers rising from the body.").defaultValue(true).build());

    private record Frame(double x, double y, double z, float bodyYaw, float headYaw, float pitch,
                         float limbPos, float limbSpeed, float swing, boolean sneak, boolean lying) {}

    private static final class Ghost {
        final List<Frame> frames;
        final long born = System.currentTimeMillis();
        final long seed;
        Ghost(List<Frame> f, long seed) { this.frames = f; this.seed = seed; }
    }

    private final KillTracker tracker = new KillTracker();
    private final Map<Integer, ArrayDeque<Frame>> history = new HashMap<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private int tick = 0;

    public DeathGhost() {
        super(ChuklAddon.VISUAL_CATEGORY, "death-ghost",
            "When you kill a player, a dark glowing silhouette replays their last moves and fades away.");
    }

    @Override
    public void onActivate() {
        tracker.clear();
        history.clear();
        ghosts.clear();
        tick = 0;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof PlayerEntity) tracker.hit(event.entity.getId(), tick);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tick++;

        // keep the last second or two of every nearby player's movement
        Set<Integer> present = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.isDead() || p.getHealth() <= 0.0F || p.deathTime > 0) continue; // do not record the death itself
            present.add(p.getId());
            ArrayDeque<Frame> q = history.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
            String pose = p.getPose().name();
            boolean lying = p.isSwimming() || pose.equals("SWIMMING") || pose.equals("GLIDING") || pose.equals("FALL_FLYING");
            // walk cycle worked out from how far the player moved since the last tick
            Frame prev = q.peekLast();
            float limbSpeed = 0, limbPos = 0;
            if (prev != null) {
                double moved = Math.hypot(p.getX() - prev.x(), p.getZ() - prev.z());
                limbSpeed = (float) Math.min(1.0, moved * 4.0);
                limbPos = prev.limbPos() + limbSpeed;
            }
            q.addLast(new Frame(p.getX(), p.getY(), p.getZ(), p.getBodyYaw(), p.getHeadYaw(), p.getPitch(),
                limbPos, limbSpeed, p.getHandSwingProgress(1.0F), p.isSneaking(), lying));
            while (q.size() > 45) q.removeFirst();
        }
        history.keySet().removeIf(id -> !present.contains(id) && tick % 40 == 0);

        for (PlayerEntity p : tracker.deaths(mc.world, mc.player, tick, window.get(), anyDeath.get())) {
            ArrayDeque<Frame> q = history.get(p.getId());
            List<Frame> frames = new ArrayList<>();
            if (q != null && !q.isEmpty()) {
                List<Frame> all = new ArrayList<>(q);
                int n = Math.min(all.size(), replayTicks.get());
                frames.addAll(all.subList(all.size() - n, all.size()));
            } else {
                frames.add(new Frame(p.getX(), p.getY(), p.getZ(), p.getBodyYaw(), p.getHeadYaw(), p.getPitch(), 0, 0, 0, false, false));
            }
            ghosts.add(new Ghost(frames, p.getId() * 31L + tick));
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
            if (age >= total) { it.remove(); continue; }
            double t = age / total;
            double fade = 1.0 - t * t * (3.0 - 2.0 * t); // smooth fade 1 -> 0
            drawGhost(event, g, age, fade);
        }
    }

    private void drawGhost(Render3DEvent event, Ghost g, double ageMs, double fade) {
        List<Frame> fr = g.frames;
        double pos = ageMs / 50.0; // 20 frames per second, same as the game
        int i0 = (int) Math.min(fr.size() - 1, Math.floor(pos));
        int i1 = Math.min(fr.size() - 1, i0 + 1);
        double f = pos >= fr.size() - 1 ? 0 : pos - Math.floor(pos);
        Frame a = fr.get(i0), b = fr.get(i1);

        double x = lerp(a.x, b.x, f), y = lerp(a.y, b.y, f), z = lerp(a.z, b.z, f);
        double bodyYaw = lerpAngle(a.bodyYaw, b.bodyYaw, f);
        double headYaw = lerpAngle(a.headYaw, b.headYaw, f);
        double pitch = lerp(a.pitch, b.pitch, f);
        double limbPos = lerp(a.limbPos, b.limbPos, f);
        double limbSpeed = Math.min(1.0, lerp(a.limbSpeed, b.limbSpeed, f));
        double swing = lerp(a.swing, b.swing, f);
        boolean sneak = a.sneak;
        double tilt = a.lying ? Math.toRadians(80) : (sneak ? Math.toRadians(25) : 0);

        // after the replay: slump over, sinking a little
        double replayMs = fr.size() * 50.0;
        double fall = 0;
        if (fallOver.get() && ageMs > replayMs) fall = smooth(Math.min(1.0, (ageMs - replayMs) / 600.0));
        tilt += -Math.toRadians(88) * fall;
        y -= 0.25 * fall;
        limbSpeed *= (1.0 - fall * 0.7);

        double rad = -Math.toRadians(bodyYaw);
        SettingColor core = coreColor.get();
        SettingColor glow = glowColor.get();
        int coreA = (int) Math.max(0, Math.min(255, 235 * fade));
        int glowA = (int) Math.max(0, Math.min(255, 255 * fade));
        Color cCore = new Color(core.r, core.g, core.b, coreA);
        Color cGlow = new Color(glow.r, glow.g, glow.b, (int) (glowA * 0.35));
        Color cEdge = new Color(glow.r, glow.g, glow.b, glowA);

        double armSwing = Math.cos(limbPos * 0.6662) * 1.0 * limbSpeed;
        double legSwing = Math.cos(limbPos * 0.6662) * 1.4 * limbSpeed;
        double hit = -Math.sin(swing * Math.PI) * 1.4;
        double headRot = Math.toRadians(pitch);
        double headTurn = -Math.toRadians(clamp(wrap(headYaw - bodyYaw), -50, 50));
        double sneakLean = sneak ? 0.5 : 0.0;

        double gs = glowSize.get();
        // part: pivot (px), half-size (px/16 handled below), box center offset from pivot, rotX, rotY
        double[][] parts = {
            // pivotX, pivotY, pivotZ,  offY,  hx,  hy,  hz,   rotX,                  rotY
            {0, 18, 0, 0, 4, 6, 2, 0, 0},                                              // torso
            {0, 24, 0, 4, 4, 4, 4, headRot, headTurn},                                // head
            {-6, 22, 0, -6, 2, 6, 2, armSwing + hit, 0},                               // arm
            {6, 22, 0, -6, 2, 6, 2, -armSwing, 0},                                     // arm
            {-2, 12, 0, -6, 2, 6, 2, -legSwing, 0},                                    // leg
            {2, 12, 0, -6, 2, 6, 2, legSwing, 0}                                       // leg
        };
        // torso pivot is its center, the others are joints; convert the torso so its center stays at y = 18
        for (int pass = 0; pass < 2; pass++) {
            for (int pi = 0; pi < parts.length; pi++) {
                double[] p = parts[pi];
                double grow = pass == 0 ? gs * 16.0 : 0.0; // pass 0 = glow shell, pass 1 = dark core
                double hx = p[4] + grow, hy = p[5] + grow, hz = p[6] + grow;
                double[][] c = new double[8][];
                int k = 0;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) for (int sz = -1; sz <= 1; sz += 2) {
                    double lx = sx * hx, ly = p[3] + sy * hy, lz = sz * hz;
                    c[k++] = place(lx, ly, lz, p, pi == 0, tilt + (pi == 0 ? sneakLean * 0 : 0), rad, x, y, z);
                }
                if (pass == 0) fillBox(event, c, cGlow);
                else { fillBox(event, c, cCore); edges(event, c, cEdge); }
            }
        }

        if (sparks.get()) {
            Random r = new Random(g.seed);
            for (int i = 0; i < 14; i++) {
                double ox = (r.nextDouble() - 0.5) * 0.9, oz = (r.nextDouble() - 0.5) * 0.9;
                double speed = 0.5 + r.nextDouble() * 0.8, phase = r.nextDouble();
                double life = ((ageMs / 1000.0) * speed * 0.6 + phase) % 1.0;
                double sy = y + 0.2 + life * 1.8;
                int sa = (int) Math.max(0, Math.min(255, 220 * fade * (1.0 - life)));
                event.renderer.line(x + ox, sy, z + oz, x + ox, sy + 0.12, z + oz, new Color(255, 245, 210, sa));
            }
        }
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
