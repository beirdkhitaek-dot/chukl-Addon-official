package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GoodRoad: draws a bright line on the ground for you to follow to the best place to look for bases.
 * It picks a target (unnatural spawner, best sus chunk, manual coordinates or an exploring direction),
 * plans a walkable route over the chunks the server has loaded, and adapts to the server's real view distance.
 */
public class GoodRoad extends Module {
    public enum TargetMode { Auto, Best_Sus_Chunk, Unnatural_Spawner, Explore, Manual }
    public enum Heading { Auto, Forward, North, East, South, West }
    public enum PathMode { Walk_Path, Straight_Line }
    public enum ColorMode { Gradient, Rainbow, Single }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdaptive = settings.createGroup("Adaptive");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----
    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Auto = unnatural spawner first, then the best sus chunk, then manual coordinates, then exploring.")
        .defaultValue(TargetMode.Auto)
        .build());

    private final Setting<Heading> exploreHeading = sgGeneral.add(new EnumSetting.Builder<Heading>()
        .name("explore-heading")
        .description("Explore direction. Auto walks toward the chunks you have visited the least.")
        .defaultValue(Heading.Auto)
        .build());

    private final Setting<Integer> manualX = sgGeneral.add(new IntSetting.Builder()
        .name("manual-x").description("Manual target X.")
        .defaultValue(0).noSlider().build());

    private final Setting<Integer> manualZ = sgGeneral.add(new IntSetting.Builder()
        .name("manual-z").description("Manual target Z (0 and 0 means not set).")
        .defaultValue(0).noSlider().build());

    private final Setting<Integer> arriveDistance = sgGeneral.add(new IntSetting.Builder()
        .name("arrive-distance").description("Counts as arrived inside this many blocks.")
        .defaultValue(6).min(2).sliderMax(30).build());

    private final Setting<PathMode> pathMode = sgGeneral.add(new EnumSetting.Builder<PathMode>()
        .name("path-mode")
        .description("Walk Path avoids cliffs, lava and walls. Straight Line just draws a line over the surface.")
        .defaultValue(PathMode.Walk_Path)
        .build());

    private final Setting<Integer> maxFall = sgGeneral.add(new IntSetting.Builder()
        .name("max-fall").description("Biggest drop the route may contain.")
        .defaultValue(3).min(1).sliderMax(10).build());

    private final Setting<Boolean> avoidWater = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-water").description("Keep the route out of water.")
        .defaultValue(true).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce").description("Chat message when the target changes or you arrive.")
        .defaultValue(true).build());

    // ---- Adaptive ----
    private final Setting<Boolean> adaptive = sgAdaptive.add(new BoolSetting.Builder()
        .name("adaptive-mode")
        .description("Fits the route to the server: uses the real loaded chunk range and tunes the search size to your device speed.")
        .defaultValue(true).build());

    private final Setting<Integer> maxNodes = sgAdaptive.add(new IntSetting.Builder()
        .name("max-nodes").description("Base search size per plan. Lower is lighter for phones.")
        .defaultValue(6000).min(500).sliderMax(30000).build());

    // ---- Render ----
    private final Setting<ColorMode> colorMode = sgRender.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode").defaultValue(ColorMode.Gradient).build());

    private final Setting<SettingColor> startColor = sgRender.add(new ColorSetting.Builder()
        .name("start-color").description("Gradient color near you.")
        .defaultValue(new SettingColor(0, 255, 255, 255)).build());

    private final Setting<SettingColor> endColor = sgRender.add(new ColorSetting.Builder()
        .name("end-color").description("Gradient color far away.")
        .defaultValue(new SettingColor(255, 0, 200, 255)).build());

    private final Setting<SettingColor> singleColor = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Color for Single mode.")
        .defaultValue(new SettingColor(0, 255, 120, 255)).build());

    private final Setting<Integer> thickness = sgRender.add(new IntSetting.Builder()
        .name("line-thickness").description("Parallel lines that make the ribbon.")
        .defaultValue(3).min(1).max(5).build());

    private final Setting<Double> spacing = sgRender.add(new DoubleSetting.Builder()
        .name("line-spacing").description("Gap between the parallel lines.")
        .defaultValue(0.08).min(0.02).sliderMax(0.3).build());

    private final Setting<Boolean> plates = sgRender.add(new BoolSetting.Builder()
        .name("road-plates").description("Flat glowing tiles under the line.")
        .defaultValue(true).build());

    private final Setting<Integer> plateAlpha = sgRender.add(new IntSetting.Builder()
        .name("plate-alpha").defaultValue(70).min(10).max(200).build());

    private final Setting<Boolean> poles = sgRender.add(new BoolSetting.Builder()
        .name("poles").description("Tall markers along the road so you can see the way far ahead.")
        .defaultValue(true).build());

    private final Setting<Integer> poleSpacing = sgRender.add(new IntSetting.Builder()
        .name("pole-spacing").defaultValue(8).min(3).sliderMax(30).build());

    private final Setting<Double> poleHeight = sgRender.add(new DoubleSetting.Builder()
        .name("pole-height").defaultValue(4.0).min(1).sliderMax(15).build());

    private final Setting<Boolean> chase = sgRender.add(new BoolSetting.Builder()
        .name("chase").description("A bright pulse that runs along the road in your travel direction.")
        .defaultValue(true).build());

    private final Setting<Double> chaseSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("chase-speed").defaultValue(0.8).min(0.1).sliderMax(3).build());

    private final Setting<Integer> renderLength = sgRender.add(new IntSetting.Builder()
        .name("render-length").description("How many blocks of road ahead to draw.")
        .defaultValue(150).min(20).sliderMax(400).build());

    private final Setting<Double> heightOffset = sgRender.add(new DoubleSetting.Builder()
        .name("height-offset").description("Lift the line above the ground.")
        .defaultValue(0.12).min(0).sliderMax(1.5).build());

    private final Setting<Boolean> goalBeam = sgRender.add(new BoolSetting.Builder()
        .name("goal-beam").description("Lightning beam at the target.")
        .defaultValue(true).build());

    private final Setting<Integer> goalBeamHeight = sgRender.add(new IntSetting.Builder()
        .name("goal-beam-height").defaultValue(80).min(10).sliderMax(300).build());

    // ---- State ----
    private record Plan(List<double[]> pts, BlockPos start, BlockPos goal, long tick, boolean partial) {}

    private final Set<Long> visited = ConcurrentHashMap.newKeySet();
    private volatile Plan plan = null;
    private final AtomicBoolean planning = new AtomicBoolean(false);
    private ExecutorService exec;
    private double nodeScale = 1.0;
    private int radiusChunks = 8;
    private int heading = -1; // index into DIRS
    private long ticks = 0;
    private BlockPos goal = null;
    private String goalKind = "";
    private boolean arrived = false;
    private String lastAnnounced = "";
    private String dim = "";
    private int nearestIdx = 0;
    private volatile int lastDir = 0;

    private static final int[][] DIRS = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};

    public GoodRoad() {
        super(ChuklAddon.CATEGORY, "good-road",
            "Draws a bright line on the ground to follow toward the best place to find bases. Adapts to the server.");
    }

    @Override
    public void onActivate() {
        visited.clear();
        plan = null;
        goal = null;
        arrived = false;
        heading = -1;
        nodeScale = 1.0;
        lastAnnounced = "";
        ticks = 0;
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "goodroad-path");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (exec != null) exec.shutdownNow();
        exec = null;
        plan = null;
        planning.set(false);
    }

    // ================= Tick =================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || exec == null) return;
        ticks++;

        String d = mc.world.getRegistryKey().getValue().toString();
        if (!d.equals(dim)) {
            dim = d;
            visited.clear();
            plan = null;
            goal = null;
            heading = -1;
            arrived = false;
        }

        ChunkPos here = mc.player.getChunkPos();
        visited.add(here.toLong());

        if (ticks % 40 == 1) measureRadius(here);
        if (ticks % 10 != 0) return;

        BlockPos pp = mc.player.getBlockPos();
        BlockPos newGoal = chooseGoal(pp);
        if (newGoal == null) {
            goal = null;
            plan = null;
            return;
        }

        boolean changed = goal == null || goalXZDist(goal, newGoal) > 6;
        // explore carrots move with the player; only re-announce when the kind changes
        if (changed) {
            goal = newGoal;
            if (!goalKind.equals(lastAnnounced)) {
                lastAnnounced = goalKind;
                arrived = false;
                if (announce.get()) info("Heading to " + goalKind + " at " + goal.getX() + ", " + goal.getZ() + ".");
            }
        }

        double hd = Math.hypot(pp.getX() - goal.getX(), pp.getZ() - goal.getZ());
        if (!goalKind.equals("explore") && hd <= arriveDistance.get()) {
            if (!arrived) {
                arrived = true;
                if (announce.get()) info("Arrived at " + goalKind + ". Dig down here and look around.");
            }
            plan = null;
            return;
        }
        arrived = false;

        if (needReplan(pp)) startPlan(pp, goal);
    }

    private void measureRadius(ChunkPos here) {
        if (!adaptive.get() || mc.world == null) {
            radiusChunks = mc.options.getViewDistance().getValue();
            return;
        }
        int best = 2;
        for (int[] dd : DIRS) {
            int r = 1;
            while (r <= 32 && mc.world.getChunkManager().getWorldChunk(here.x + dd[0] * r, here.z + dd[1] * r) != null) r++;
            best = Math.max(best, r - 1);
        }
        radiusChunks = best;
    }

    private static double goalXZDist(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ---- target selection ----

    private BlockPos chooseGoal(BlockPos pp) {
        TargetMode m = targetMode.get();
        if (m == TargetMode.Auto || m == TargetMode.Unnatural_Spawner) {
            ChuklSpawnerNotifier sp = Modules.get().get(ChuklSpawnerNotifier.class);
            if (sp != null && sp.isActive()) {
                BlockPos n = nearest(sp.getUnnaturalSpawners(), pp);
                if (n != null) { goalKind = "unnatural spawner"; return n; }
            }
        }
        if (m == TargetMode.Auto || m == TargetMode.Best_Sus_Chunk) {
            SusChunkFinderTest sc = Modules.get().get(SusChunkFinderTest.class);
            if (sc != null && sc.isActive()) {
                BlockPos n = nearest(sc.getBestTargets(), pp);
                if (n != null) { goalKind = "best sus chunk"; return n; }
            }
        }
        if ((m == TargetMode.Auto || m == TargetMode.Manual) && (manualX.get() != 0 || manualZ.get() != 0)) {
            goalKind = "manual point";
            return new BlockPos(manualX.get(), 0, manualZ.get());
        }
        if (m == TargetMode.Manual) return null;
        if (m == TargetMode.Auto || m == TargetMode.Explore) {
            goalKind = "explore";
            return exploreCarrot(pp);
        }
        return null; // a specific target mode with nothing found yet
    }

    private BlockPos nearest(List<BlockPos> list, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : list) {
            double dd = goalXZDist(b, from);
            if (dd < bd) { bd = dd; best = b; }
        }
        return best;
    }

    private BlockPos exploreCarrot(BlockPos pp) {
        int dirIdx = pickHeading(pp);
        double dist = Math.max(48, Math.min(160, radiusChunks * 16 * 0.8));
        double len = Math.hypot(DIRS[dirIdx][0], DIRS[dirIdx][1]);
        int gx = pp.getX() + (int) Math.round(DIRS[dirIdx][0] / len * dist);
        int gz = pp.getZ() + (int) Math.round(DIRS[dirIdx][1] / len * dist);
        // keep inside the world border
        if (mc.world.getWorldBorder() != null && !mc.world.getWorldBorder().contains(new BlockPos(gx, pp.getY(), gz))) {
            gx = pp.getX() + (int) Math.round(DIRS[dirIdx][0] / len * 24);
            gz = pp.getZ() + (int) Math.round(DIRS[dirIdx][1] / len * 24);
        }
        return new BlockPos(gx, 0, gz);
    }

    private int pickHeading(BlockPos pp) {
        Heading h = exploreHeading.get();
        switch (h) {
            case North: return heading = 0;
            case East: return heading = 2;
            case South: return heading = 4;
            case West: return heading = 6;
            case Forward: {
                double yaw = mc.player.getYaw();
                double rad = Math.toRadians(yaw);
                double dx = -Math.sin(rad), dz = Math.cos(rad);
                int bi = 0;
                double bs = -9;
                for (int i = 0; i < 8; i++) {
                    double l = Math.hypot(DIRS[i][0], DIRS[i][1]);
                    double s = (DIRS[i][0] * dx + DIRS[i][1] * dz) / l;
                    if (s > bs) { bs = s; bi = i; }
                }
                return heading = bi;
            }
            default: break;
        }
        int cx = pp.getX() >> 4, cz = pp.getZ() >> 4;
        int bestI = 0, bestS = Integer.MIN_VALUE;
        int[] scores = new int[8];
        for (int i = 0; i < 8; i++) {
            int s = 0;
            for (int k = 3; k <= 10; k++) {
                int px = cx + DIRS[i][0] * k, pz = cz + DIRS[i][1] * k;
                if (!visited.contains(ChunkPos.toLong(px, pz))) s++;
                if (mc.world.getWorldBorder() != null
                    && !mc.world.getWorldBorder().contains(new BlockPos(px * 16 + 8, pp.getY(), pz * 16 + 8))) s -= 5;
            }
            scores[i] = s;
            if (s > bestS) { bestS = s; bestI = i; }
        }
        if (heading >= 0 && scores[heading] >= bestS - 2) return heading; // hysteresis
        return heading = bestI;
    }

    // ---- replan rules ----

    private boolean needReplan(BlockPos pp) {
        if (planning.get()) return false;
        Plan p = plan;
        if (p == null) return true;
        if (goalXZDist(p.goal, goal) > 6) return true;
        if (ticks - p.tick > 80) return true;
        if (goalXZDist(p.start, pp) > 8) return true;
        List<double[]> pts = p.pts;
        if (pts.size() < 2) return true;
        // too far off the drawn road
        double best = Double.MAX_VALUE;
        for (double[] q : pts) {
            double dd = Math.hypot(q[0] - mc.player.getX(), q[2] - mc.player.getZ());
            if (dd < best) best = dd;
        }
        return best > 5;
    }

    private void startPlan(BlockPos start, BlockPos g) {
        if (!planning.compareAndSet(false, true)) return;
        final boolean walk = pathMode.get() == PathMode.Walk_Path;
        final int fall = maxFall.get();
        final boolean noWater = avoidWater.get();
        final int nodes = (int) Math.max(300, maxNodes.get() * (adaptive.get() ? nodeScale : 1.0));
        final long tk = ticks;
        final World w = mc.world;
        final double sy = mc.player.getY();
        try {
            exec.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    List<double[]> pts = null;
                    boolean partial = false;
                    if (walk) {
                        Object[] r = astar(w, start, g, fall, noWater, nodes);
                        if (r != null) {
                            pts = (List<double[]>) r[0];
                            partial = (Boolean) r[1];
                        }
                    }
                    // fall back to a straight line when nothing walkable was found or the search made no progress
                    if (pts == null || pts.size() < 3) {
                        pts = straight(w, start, g, sy);
                        partial = false;
                    }
                    plan = new Plan(pts, start, g, tk, partial);
                } catch (Throwable ignored) {
                } finally {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    if (ms > 60) nodeScale = Math.max(0.3, nodeScale * 0.8);
                    else if (ms < 20) nodeScale = Math.min(4.0, nodeScale * 1.1);
                    planning.set(false);
                }
            });
        } catch (Throwable t) {
            planning.set(false);
        }
    }

    // ================= Pathfinding (runs off-thread) =================

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static final class Node {
        final int x, y, z;
        double g, f;
        Node parent;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private BlockState stateAt(World w, int x, int y, int z) {
        WorldChunk c = w.getChunkManager().getWorldChunk(x >> 4, z >> 4);
        if (c == null) return null;
        return c.getBlockState(new BlockPos(x, y, z));
    }

    private boolean hazard(BlockState s) {
        if (s == null) return false;
        if (s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.CACTUS)
            || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.WITHER_ROSE)
            || s.isOf(Blocks.POWDER_SNOW)) return true;
        FluidState f = s.getFluidState();
        return f.isIn(FluidTags.LAVA);
    }

    private boolean water(BlockState s) {
        return s != null && s.getFluidState().isIn(FluidTags.WATER);
    }

    /** Free to walk through. null state (unloaded) counts as blocked. */
    private boolean passable(World w, int x, int y, int z, boolean noWater) {
        BlockState s = stateAt(w, x, y, z);
        if (s == null) return false;
        if (hazard(s)) return false;
        if (noWater && water(s)) return false;
        return s.getCollisionShape(w, new BlockPos(x, y, z)).isEmpty();
    }

    private boolean solidFloor(World w, int x, int y, int z) {
        BlockState s = stateAt(w, x, y, z);
        if (s == null || hazard(s)) return false;
        return !s.getCollisionShape(w, new BlockPos(x, y, z)).isEmpty();
    }

    private boolean standable(World w, int x, int y, int z, boolean noWater) {
        return passable(w, x, y, z, noWater) && passable(w, x, y + 1, z, noWater) && solidFloor(w, x, y - 1, z);
    }

    private double heur(int x, int z, int gx, int gz) {
        double dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return (Math.max(dx, dz) + 0.414 * Math.min(dx, dz)) * 1.25;
    }

    /** Returns {List<double[]> points, Boolean partial} or null. */
    private Object[] astar(World w, BlockPos start, BlockPos goalPos, int fall, boolean noWater, int cap) {
        int gx = goalPos.getX(), gz = goalPos.getZ();
        int sy = start.getY();
        // find a floor under/around the start in case the player is mid-jump
        for (int i = 0; i < 4 && !standable(w, start.getX(), sy, start.getZ(), noWater); i++) sy--;
        Node s0 = new Node(start.getX(), sy, start.getZ());
        s0.g = 0;
        s0.f = heur(s0.x, s0.z, gx, gz);

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Node> seen = new HashMap<>();
        open.add(s0);
        seen.put(key(s0.x, s0.y, s0.z), s0);

        Node best = s0;
        double bestH = heur(s0.x, s0.z, gx, gz);
        int expanded = 0;
        Node end = null;

        while (!open.isEmpty() && expanded < cap) {
            Node cur = open.poll();
            expanded++;
            double h = heur(cur.x, cur.z, gx, gz);
            if (h < bestH) { bestH = h; best = cur; }
            if (Math.abs(cur.x - gx) <= 2 && Math.abs(cur.z - gz) <= 2) { end = cur; break; }

            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1];
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag) {
                    // do not cut wall corners
                    if (!passable(w, cur.x + d[0], cur.y, cur.z, noWater) || !passable(w, cur.x, cur.y, cur.z + d[1], noWater)
                        || !passable(w, cur.x + d[0], cur.y + 1, cur.z, noWater) || !passable(w, cur.x, cur.y + 1, cur.z + d[1], noWater))
                        continue;
                }
                int ny = Integer.MIN_VALUE;
                if (standable(w, nx, cur.y, nz, noWater)) ny = cur.y;
                else if (passable(w, cur.x, cur.y + 2, cur.z, noWater) && standable(w, nx, cur.y + 1, nz, noWater)) ny = cur.y + 1;
                else if (passable(w, nx, cur.y, nz, noWater) && passable(w, nx, cur.y + 1, nz, noWater)) {
                    for (int k = 1; k <= fall; k++) {
                        if (!passable(w, nx, cur.y - k + 1, nz, noWater)) break;
                        if (standable(w, nx, cur.y - k, nz, noWater)) { ny = cur.y - k; break; }
                    }
                }
                if (ny == Integer.MIN_VALUE) continue;

                double step = diag ? 1.414 : 1.0;
                if (ny > cur.y) step += 0.6;
                else if (ny < cur.y) step += 0.3 * (cur.y - ny);
                if (!noWater && water(stateAt(w, nx, ny, nz))) step += 1.5;
                double ng = cur.g + step;
                long k = key(nx, ny, nz);
                Node ex = seen.get(k);
                if (ex != null && ex.g <= ng) continue;
                Node nn = new Node(nx, ny, nz);
                nn.g = ng;
                nn.f = ng + heur(nx, nz, gx, gz) + Math.abs(ny - sy) * 0.1;
                nn.parent = cur;
                seen.put(k, nn);
                open.add(nn);
            }
        }

        boolean partial = end == null;
        Node tail = partial ? best : end;
        List<double[]> pts = new ArrayList<>();
        for (Node n = tail; n != null; n = n.parent) pts.add(new double[]{n.x + 0.5, n.y, n.z + 0.5});
        Collections.reverse(pts);
        return new Object[]{pts, partial};
    }

    private List<double[]> straight(World w, BlockPos start, BlockPos g, double playerY) {
        List<double[]> pts = new ArrayList<>();
        double dx = g.getX() - start.getX(), dz = g.getZ() - start.getZ();
        double len = Math.hypot(dx, dz);
        int steps = (int) Math.max(1, Math.min(400, len));
        double lastY = playerY;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(start.getX() + dx * t), z = (int) Math.floor(start.getZ() + dz * t);
            if (w.getChunkManager().getWorldChunk(x >> 4, z >> 4) != null) {
                lastY = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
            }
            pts.add(new double[]{x + 0.5, lastY, z + 0.5});
        }
        return pts;
    }

    // ================= Render =================

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        Plan p = plan;
        double time = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
        if (p != null && p.pts.size() >= 2) renderRoad(event, p.pts, time);
        if (goalBeam.get() && goal != null && !arrived) renderGoal(event, time);
    }

    private void renderRoad(Render3DEvent event, List<double[]> all, double time) {
        // start at the node closest to the player so the line never trails behind you
        double px = mc.player.getX(), pz = mc.player.getZ();
        int from = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            double[] q = all.get(i);
            double dd = Math.hypot(q[0] - px, q[2] - pz);
            if (dd < bd) { bd = dd; from = i; }
        }
        nearestIdx = from;
        int to = Math.min(all.size() - 1, from + renderLength.get());
        int n = to - from;
        if (n < 1) return;

        double lift = heightOffset.get();
        int th = thickness.get();
        double sp = spacing.get();
        double chasePos = chase.get() ? (time * chaseSpeed.get() * 12.0) % Math.max(1, n) : -100;

        for (int i = from; i < to; i++) {
            double[] a = all.get(i), b = all.get(i + 1);
            double t = (double) (i - from) / Math.max(1, n);
            int[] rgb = colorAt(t, time);
            double dist = Math.abs((i - from) - chasePos);
            boolean hot = chase.get() && dist < 3;
            int alpha = hot ? 255 : 235;
            if (hot) rgb = new int[]{Math.min(255, rgb[0] + 130), Math.min(255, rgb[1] + 130), Math.min(255, rgb[2] + 130)};
            Color lc = new Color(rgb[0], rgb[1], rgb[2], alpha);

            double dx = b[0] - a[0], dz = b[2] - a[2];
            double l = Math.hypot(dx, dz);
            double ox = 0, oz = 0;
            if (l > 1e-6) { ox = -dz / l; oz = dx / l; }

            for (int k = 0; k < th; k++) {
                double off = (k - (th - 1) / 2.0) * sp;
                double up = hot ? 0.06 : 0.0;
                event.renderer.line(a[0] + ox * off, a[1] + lift + up, a[2] + oz * off,
                    b[0] + ox * off, b[1] + lift + up, b[2] + oz * off, lc);
            }
            // soft glow lines on the outer edges
            if (th >= 2) {
                Color gl = new Color(rgb[0], rgb[1], rgb[2], 90);
                double go = (th / 2.0 + 1) * sp;
                event.renderer.line(a[0] + ox * go, a[1] + lift, a[2] + oz * go, b[0] + ox * go, b[1] + lift, b[2] + oz * go, gl);
                event.renderer.line(a[0] - ox * go, a[1] + lift, a[2] - oz * go, b[0] - ox * go, b[1] + lift, b[2] - oz * go, gl);
            }

            if (plates.get()) {
                Color side = new Color(rgb[0], rgb[1], rgb[2], plateAlpha.get());
                double yy = a[1] + 0.02;
                event.renderer.box(a[0] - 0.45, yy, a[2] - 0.45, a[0] + 0.45, yy + 0.04, a[2] + 0.45,
                    side, side, ShapeMode.Sides, 0);
            }
            if (poles.get() && (i - from) % poleSpacing.get() == 0) {
                Color pc = new Color(rgb[0], rgb[1], rgb[2], 230);
                double ph = poleHeight.get();
                event.renderer.line(a[0], a[1] + lift, a[2], a[0], a[1] + lift + ph, a[2], pc);
                event.renderer.box(a[0] - 0.12, a[1] + lift + ph - 0.25, a[2] - 0.12,
                    a[0] + 0.12, a[1] + lift + ph, a[2] + 0.12, pc, pc, ShapeMode.Both, 0);
            }
        }
    }

    private void renderGoal(Render3DEvent event, double time) {
        BlockPos g = goal;
        Plan p = plan;
        double gy = 64;
        if (p != null && !p.pts.isEmpty()) gy = p.pts.get(p.pts.size() - 1)[1];
        else gy = mc.player.getY();
        int[] c = goalKind.equals("unnatural spawner") ? new int[]{255, 140, 0}
            : goalKind.equals("best sus chunk") ? new int[]{0, 230, 255}
            : goalKind.equals("manual point") ? new int[]{255, 230, 0}
            : new int[]{80, 255, 120};
        double pulse = 0.65 + 0.35 * Math.sin(time * 4.0);
        double cx = g.getX() + 0.5, cz = g.getZ() + 0.5;
        double top = gy + goalBeamHeight.get();
        Color core = new Color(c[0], c[1], c[2], (int) (170 * pulse));
        event.renderer.box(cx - 0.18, gy, cz - 0.18, cx + 0.18, top, cz + 0.18, core, core, ShapeMode.Sides, 0);
        Color bolt = new Color(Math.min(255, c[0] + 110), Math.min(255, c[1] + 110), Math.min(255, c[2] + 110), 235);
        int seg = 14;
        double x1 = cx, z1 = cz, y1 = gy;
        long seed = (long) (time * 6);
        java.util.Random rnd = new java.util.Random(seed ^ g.asLong());
        for (int i = 1; i <= seg; i++) {
            double y2 = gy + (top - gy) * i / seg;
            double x2 = i == seg ? cx : cx + (rnd.nextDouble() - 0.5) * 1.4;
            double z2 = i == seg ? cz : cz + (rnd.nextDouble() - 0.5) * 1.4;
            event.renderer.line(x1, y1, z1, x2, y2, z2, bolt);
            x1 = x2; y1 = y2; z1 = z2;
        }
        Color ring = new Color(c[0], c[1], c[2], 230);
        event.renderer.box(cx - 1.2, gy, cz - 1.2, cx + 1.2, gy + 0.1, cz + 1.2, core, ring, ShapeMode.Both, 0);
    }

    private int[] colorAt(double t, double time) {
        switch (colorMode.get()) {
            case Rainbow: return hsv((t * 0.8 + time * 0.25) % 1.0);
            case Single: {
                SettingColor c = singleColor.get();
                return new int[]{c.r, c.g, c.b};
            }
            default: {
                SettingColor a = startColor.get(), b = endColor.get();
                return new int[]{
                    (int) (a.r + (b.r - a.r) * t),
                    (int) (a.g + (b.g - a.g) * t),
                    (int) (a.b + (b.b - a.b) * t)};
            }
        }
    }

    private static int[] hsv(double h) {
        double hh = (h % 1.0) * 6.0;
        int i = (int) Math.floor(hh);
        double f = hh - i;
        double q = 1 - f;
        double r, g, b;
        switch (i % 6) {
            case 0: r = 1; g = f; b = 0; break;
            case 1: r = q; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = f; break;
            case 3: r = 0; g = q; b = 1; break;
            case 4: r = f; g = 0; b = 1; break;
            default: r = 1; g = 0; b = q; break;
        }
        return new int[]{(int) (r * 255), (int) (g * 255), (int) (b * 255)};
    }

    @Override
    public String getInfoString() {
        return goal == null ? null : goalKind;
    }
}
