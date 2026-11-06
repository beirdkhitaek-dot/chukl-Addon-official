package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frost Storage ESP: a port of the Storage ESP from the Frost client.
 * Draws storage blocks through walls using their real shapes (slightly inset so it layers cleanly with other ESPs),
 * with per-type toggles and colors, tracers, an under-Y0 filter and a cached scan for extra non-storage blocks.
 */
public class FrostStorageEsp extends Module {
    private static final double INSET = 0.12;
    private static final int MAX_TRACERS = 512;

    private enum G {
        Chest(150, 85, 3), Trapped_Chest(200, 91, 0), Ender_Chest(117, 0, 255), Spawner(255, 52, 52),
        Shulker_Box(134, 0, 158), Furnace(125, 125, 125), Blast_Furnace(80, 80, 80), Smoker(100, 100, 100),
        Barrel(255, 140, 140), Dispenser(100, 100, 100), Dropper(120, 120, 120), Hopper(144, 238, 144),
        Piston(50, 205, 50), Sticky_Piston(50, 205, 50), Crafter(255, 165, 0);

        final int r, g, b;
        G(int r, int g, int b) { this.r = r; this.g = g; this.b = b; }
    }

    private static final SettingColor PISTON_IDLE = new SettingColor(173, 255, 47, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTypes = settings.createGroup("Storage Types");
    private final SettingGroup sgExtra = settings.createGroup("Extra Blocks");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("esp-range").description("How far to draw, in blocks.")
        .defaultValue(64).sliderMax(512).build());

    private final Setting<Boolean> fill = sgGeneral.add(new BoolSetting.Builder()
        .name("fill-esp").description("Draw the filled boxes.").defaultValue(true).build());

    private final Setting<Integer> fillAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("fill-alpha").defaultValue(100).sliderMax(500).build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers").description("Lines from you to each storage block.").defaultValue(true).build());

    private final Setting<Integer> tracerAlpha = sgGeneral.add(new IntSetting.Builder()
        .name("tracer-alpha").defaultValue(255).sliderMax(500).build());

    private final Setting<Double> tracerWidth = sgGeneral.add(new DoubleSetting.Builder()
        .name("tracer-width").description("Thickness of the tracers in pixels on screen (1 = the thin default line).")
        .defaultValue(1.0).min(1.0).max(16.0).sliderMin(1.0).sliderMax(8.0).build());

    private final Setting<Boolean> coordinateTracers = sgGeneral.add(new BoolSetting.Builder()
        .name("coordinate-tracers")
        .description("Tracers start 4 blocks in front of your head in the world instead of at the camera (good with freecam).")
        .defaultValue(false).build());

    private final Setting<Boolean> onlyUnderY0 = sgGeneral.add(new BoolSetting.Builder()
        .name("only-under-y0").description("Only show blocks below Y 0.").defaultValue(false).build());

    private final Setting<Boolean> pistonIdle = sgGeneral.add(new BoolSetting.Builder()
        .name("idle-piston-color").description("Retracted pistons use a lime color so they stand out from extended ones.")
        .defaultValue(true).build());

    private final Map<G, Setting<Boolean>> typeOn = new EnumMap<>(G.class);
    private final Map<G, Setting<SettingColor>> typeColor = new EnumMap<>(G.class);

    private final Setting<List<Block>> extraBlocks = sgExtra.add(new BlockListSetting.Builder()
        .name("extra-blocks").description("Other blocks to find by scanning chunks (note blocks, beacons, observers...).")
        .defaultValue(new ArrayList<Block>()).build());

    private final Setting<SettingColor> extraColor = sgExtra.add(new ColorSetting.Builder()
        .name("extra-color").defaultValue(new SettingColor(0, 200, 255, 255)).build());

    private record Item(double cx, double cy, double cz, List<double[]> boxes, SettingColor color) {}

    private volatile List<Item> items = List.of();
    private final Map<ChunkPos, List<BlockPos>> extraCache = new ConcurrentHashMap<>();
    private final Set<ChunkPos> extraScanned = ConcurrentHashMap.newKeySet();
    private int extraHash = 0;
    private int tick = 0;
    private int scanX = 0, scanZ = 0;

    public FrostStorageEsp() {
        super(ChuklAddon.RENDER_CATEGORY, "frost-storage-esp",
            "Storage ESP ported from the Frost client: real shapes, per-type colors, tracers.");
        for (G g : G.values()) {
            String n = g.name().toLowerCase().replace('_', '-');
            boolean def = g != G.Dropper;
            typeOn.put(g, sgTypes.add(new BoolSetting.Builder().name(n).defaultValue(def).build()));
            typeColor.put(g, sgTypes.add(new ColorSetting.Builder().name(n + "-color")
                .defaultValue(new SettingColor(g.r, g.g, g.b, 255)).build()));
        }
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() { reset(); }

    private void reset() {
        items = List.of();
        extraCache.clear();
        extraScanned.clear();
        extraHash = 0;
        tick = 0;
    }

    private static G classify(Block b) {
        String p = Registries.BLOCK.getId(b).getPath();
        if (p.endsWith("shulker_box")) return G.Shulker_Box;
        switch (p) {
            case "chest": return G.Chest;
            case "trapped_chest": return G.Trapped_Chest;
            case "ender_chest": return G.Ender_Chest;
            case "spawner": case "trial_spawner": return G.Spawner;
            case "furnace": return G.Furnace;
            case "blast_furnace": return G.Blast_Furnace;
            case "smoker": return G.Smoker;
            case "barrel": return G.Barrel;
            case "dispenser": return G.Dispenser;
            case "dropper": return G.Dropper;
            case "hopper": return G.Hopper;
            case "piston": return G.Piston;
            case "sticky_piston": return G.Sticky_Piston;
            case "crafter": return G.Crafter;
            default: return null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tick++;
        scanExtras();
        if (tick % 4 != 0) return;
        rebuild();
    }

    private void scanExtras() {
        List<Block> sel = extraBlocks.get();
        int h = sel.hashCode();
        if (h != extraHash) {
            extraHash = h;
            extraCache.clear();
            extraScanned.clear();
        }
        if (sel.isEmpty() || tick % 10 != 0) return;

        int vd = Math.max(1, range.get() / 16);
        ChunkPos c = mc.player.getChunkPos();
        Set<Block> targets = new HashSet<>(sel);
        for (int i = 0; i < 2; i++) {
            if (scanX < c.x - vd || scanX > c.x + vd) scanX = c.x - vd;
            if (scanZ < c.z - vd || scanZ > c.z + vd) scanZ = c.z - vd;
            ChunkPos cp = new ChunkPos(scanX, scanZ);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk != null && extraScanned.add(cp)) scanChunk(chunk, cp, targets);
            scanX++;
            if (scanX > c.x + vd) {
                scanX = c.x - vd;
                scanZ++;
                if (scanZ > c.z + vd) scanZ = c.z - vd;
            }
        }
        extraCache.keySet().removeIf(cp -> Math.abs(cp.x - c.x) > vd + 2 || Math.abs(cp.z - c.z) > vd + 2);
        extraScanned.removeIf(cp -> Math.abs(cp.x - c.x) > vd + 2 || Math.abs(cp.z - c.z) > vd + 2);
    }

    private void scanChunk(WorldChunk chunk, ChunkPos cp, Set<Block> targets) {
        List<BlockPos> found = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> targets.contains(st.getBlock()))) continue;
            int sy = bottom + i * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (targets.contains(s.getBlockState(x, y, z).getBlock()))
                    found.add(new BlockPos(cp.getStartX() + x, sy + y, cp.getStartZ() + z));
            }
        }
        if (found.isEmpty()) extraCache.remove(cp);
        else extraCache.put(cp, found);
    }

    private void rebuild() {
        List<Item> out = new ArrayList<>();
        double max = Math.max(16, range.get());
        double maxSq = max * max;
        int vd = Math.max(1, range.get() / 16);
        ChunkPos c = mc.player.getChunkPos();
        Vec3d pp = mc.player.getEyePos();
        Set<Long> seen = new HashSet<>();

        for (int cx = c.x - vd; cx <= c.x + vd; cx++) {
            for (int cz = c.z - vd; cz <= c.z + vd; cz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk != null) {
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos pos = be.getPos();
                        G g = classify(be.getCachedState().getBlock());
                        if (g == null || !typeOn.get(g).get()) continue;
                        add(out, seen, pos, g, null, pp, maxSq);
                    }
                }
                List<BlockPos> ex = extraCache.get(new ChunkPos(cx, cz));
                if (ex != null) for (BlockPos pos : ex) add(out, seen, pos, null, extraColor.get(), pp, maxSq);
            }
        }
        out.sort(Comparator.comparingDouble(i -> {
            double dx = i.cx - pp.x, dy = i.cy - pp.y, dz = i.cz - pp.z;
            return dx * dx + dy * dy + dz * dz;
        }));
        items = out;
    }

    private void add(List<Item> out, Set<Long> seen, BlockPos pos, G g, SettingColor forced, Vec3d pp, double maxSq) {
        if (onlyUnderY0.get() && pos.getY() >= 0) return;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        double dx = cx - pp.x, dy = cy - pp.y, dz = cz - pp.z;
        if (dx * dx + dy * dy + dz * dz > maxSq) return;
        if (!seen.add(pos.asLong())) return;

        BlockState st = mc.world.getBlockState(pos);
        SettingColor col = forced;
        if (g != null) {
            col = typeColor.get(g).get();
            if (pistonIdle.get() && (g == G.Piston || g == G.Sticky_Piston)
                && st.contains(Properties.EXTENDED) && !st.get(Properties.EXTENDED)) col = PISTON_IDLE;
        }

        List<double[]> boxes = new ArrayList<>();
        VoxelShape shape = st.getOutlineShape(mc.world, pos);
        boolean full = g == G.Hopper || shape == null || shape.isEmpty();
        if (full) {
            boxes.add(new double[]{pos.getX() + INSET, pos.getY() + INSET, pos.getZ() + INSET,
                pos.getX() + 1 - INSET, pos.getY() + 1 - INSET, pos.getZ() + 1 - INSET});
        } else {
            for (Box part : shape.getBoundingBoxes()) {
                Box b = part.offset(pos).contract(INSET);
                if (b.maxX > b.minX && b.maxY > b.minY && b.maxZ > b.minZ)
                    boxes.add(new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
            }
        }
        if (boxes.isEmpty()) return;
        out.add(new Item(cx, cy, cz, boxes, col));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        List<Item> list = items;
        if (list.isEmpty()) return;

        if (fill.get()) {
            for (Item it : list) {
                Color f = new Color(it.color.r, it.color.g, it.color.b, Math.max(0, Math.min(255, fillAlpha.get())));
                for (double[] b : it.boxes) {
                    event.renderer.box(b[0], b[1], b[2], b[3], b[4], b[5], f, f, ShapeMode.Sides, 0);
                }
            }
        }

        if (tracers.get()) {
            double sx, sy, sz;
            if (coordinateTracers.get()) {
                Vec3d eye = mc.player.getCameraPosVec(event.tickDelta);
                Vec3d look = mc.player.getRotationVec(event.tickDelta).multiply(4.0);
                sx = eye.x + look.x; sy = eye.y + look.y; sz = eye.z + look.z;
            } else {
                sx = RenderUtils.center.x; sy = RenderUtils.center.y; sz = RenderUtils.center.z;
            }
            int n = Math.min(MAX_TRACERS, list.size());
            for (int i = 0; i < n; i++) {
                Item it = list.get(i);
                Color tc = new Color(it.color.r, it.color.g, it.color.b, Math.max(0, Math.min(255, tracerAlpha.get())));
                double w = tracerWidth.get();
                if (w <= 1.05) {
                    event.renderer.line(sx, sy, sz, it.cx, it.cy, it.cz, tc);
                    continue;
                }
                // thick tracer: a flat ribbon that always faces the camera, with a constant width on screen
                double ex = RenderUtils.center.x, ey = RenderUtils.center.y, ez = RenderUtils.center.z;
                double dx = it.cx - sx, dy = it.cy - sy, dz = it.cz - sz;
                double vx = (sx + it.cx) / 2 - ex, vy = (sy + it.cy) / 2 - ey, vz = (sz + it.cz) / 2 - ez;
                double px = dy * vz - dz * vy, py = dz * vx - dx * vz, pz = dx * vy - dy * vx;
                double pl = Math.sqrt(px * px + py * py + pz * pz);
                if (pl < 1e-9) {
                    event.renderer.line(sx, sy, sz, it.cx, it.cy, it.cz, tc);
                    continue;
                }
                px /= pl; py /= pl; pz /= pl;
                double h0 = w * 0.0008 * Math.sqrt((sx - ex) * (sx - ex) + (sy - ey) * (sy - ey) + (sz - ez) * (sz - ez));
                double h1 = w * 0.0008 * Math.sqrt((it.cx - ex) * (it.cx - ex) + (it.cy - ey) * (it.cy - ey) + (it.cz - ez) * (it.cz - ez));
                event.renderer.quad(sx - px * h0, sy - py * h0, sz - pz * h0, sx + px * h0, sy + py * h0, sz + pz * h0,
                    it.cx + px * h1, it.cy + py * h1, it.cz + pz * h1, it.cx - px * h1, it.cy - py * h1, it.cz - pz * h1, tc);
            }
        }
    }
}
