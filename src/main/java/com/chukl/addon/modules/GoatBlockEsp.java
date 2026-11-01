package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Goat Block ESP: highlights the blocks you pick, only inside the Y range you choose.
 * Boxes and tracers each choose their own color source: the block's own color, or one custom color.
 */
public class GoatBlockEsp extends Module {
    public enum ColorMode { Block_Color, Custom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBoxes = settings.createGroup("Boxes");
    private final SettingGroup sgTracers = settings.createGroup("Tracers");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Blocks to highlight.")
        .defaultValue(new ArrayList<>(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS)))
        .build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y to check.")
        .defaultValue(-64).sliderRange(-64, 320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y to check.")
        .defaultValue(64).sliderRange(-64, 320).build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("How far away (in blocks) to look.")
        .defaultValue(64).sliderMax(256).build());

    private final Setting<Integer> maxBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks").description("Most blocks drawn at once (nearest first). Lower is lighter for phones.")
        .defaultValue(1500).sliderMax(10000).build());

    // Boxes
    private final Setting<ColorMode> boxColorMode = sgBoxes.add(new EnumSetting.Builder<ColorMode>()
        .name("box-color-mode").description("Block_Color = each block uses its own color. Custom = the color below.")
        .defaultValue(ColorMode.Block_Color).build());

    private final Setting<SettingColor> boxColor = sgBoxes.add(new ColorSetting.Builder()
        .name("box-custom-color").defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(() -> boxColorMode.get() == ColorMode.Custom).build());

    private final Setting<ShapeMode> shapeMode = sgBoxes.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());

    private final Setting<Integer> fillAlpha = sgBoxes.add(new IntSetting.Builder()
        .name("fill-alpha").defaultValue(70).sliderMax(500).build());

    private final Setting<Integer> lineAlpha = sgBoxes.add(new IntSetting.Builder()
        .name("line-alpha").defaultValue(255).sliderMax(500).build());

    // Tracers (separate color choice)
    private final Setting<Boolean> tracers = sgTracers.add(new BoolSetting.Builder()
        .name("tracers").description("Lines from you to each block.").defaultValue(true).build());

    private final Setting<ColorMode> tracerColorMode = sgTracers.add(new EnumSetting.Builder<ColorMode>()
        .name("tracer-color-mode").description("Block_Color = each tracer uses its block's color. Custom = the color below.")
        .defaultValue(ColorMode.Block_Color).visible(() -> tracers.get()).build());

    private final Setting<SettingColor> tracerColor = sgTracers.add(new ColorSetting.Builder()
        .name("tracer-custom-color").defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> tracers.get() && tracerColorMode.get() == ColorMode.Custom).build());

    private final Setting<Integer> tracerAlpha = sgTracers.add(new IntSetting.Builder()
        .name("tracer-alpha").defaultValue(220).sliderMax(500).visible(() -> tracers.get()).build());

    private final Setting<Integer> maxTracers = sgTracers.add(new IntSetting.Builder()
        .name("max-tracers").description("Only the nearest blocks get a tracer.")
        .defaultValue(100).sliderMax(500).visible(() -> tracers.get()).build());

    private record Item(BlockPos pos, int rgb, double dist) {}

    private final Map<ChunkPos, List<BlockPos>> cache = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> scannedAt = new ConcurrentHashMap<>();
    private volatile List<Item> items = List.of();
    private int tick = 0;
    private int lastSig = 0;

    public GoatBlockEsp() {
        super(ChuklAddon.RENDER_CATEGORY, "goat-block-esp",
            "Highlights the blocks you pick inside a Y range. Boxes and tracers can each use block colors or a custom color.");
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() { reset(); }

    private void reset() {
        cache.clear();
        scannedAt.clear();
        items = List.of();
        tick = 0;
        lastSig = 0;
    }

    private int signature() {
        return blocks.get().hashCode() * 31 + Math.min(minY.get(), maxY.get()) * 7 + Math.max(minY.get(), maxY.get());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tick++;

        int sig = signature();
        if (sig != lastSig) { // selection or Y range changed: scan again
            lastSig = sig;
            cache.clear();
            scannedAt.clear();
        }
        if (blocks.get().isEmpty()) { items = List.of(); return; }

        if (tick % 3 == 0) scanSome();
        if (tick % 4 == 0) rebuild();
    }

    private void scanSome() {
        int vd = Math.max(1, Math.max(16, range.get()) / 16);
        ChunkPos c = mc.player.getChunkPos();
        long now = System.currentTimeMillis();
        long stale = 8000L; // chunks are scanned again every 8 seconds to catch mined or placed blocks
        Set<Block> targets = new HashSet<>(blocks.get());
        int budget = 3;

        // nearest chunks first: walk rings outward
        for (int r = 0; r <= vd && budget > 0; r++) {
            for (int dx = -r; dx <= r && budget > 0; dx++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    ChunkPos cp = new ChunkPos(c.x + dx, c.z + dz);
                    Long t = scannedAt.get(cp);
                    if (t != null && now - t < stale) continue;
                    WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
                    if (chunk == null) continue;
                    scanChunk(chunk, cp, targets);
                    scannedAt.put(cp, now);
                    budget--;
                }
            }
        }
        cache.keySet().removeIf(cp -> Math.abs(cp.x - c.x) > vd + 2 || Math.abs(cp.z - c.z) > vd + 2);
        scannedAt.keySet().removeIf(cp -> Math.abs(cp.x - c.x) > vd + 2 || Math.abs(cp.z - c.z) > vd + 2);
    }

    private void scanChunk(WorldChunk chunk, ChunkPos cp, Set<Block> targets) {
        int lo = Math.min(minY.get(), maxY.get());
        int hi = Math.max(minY.get(), maxY.get());
        List<BlockPos> found = new ArrayList<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            int sy = bottom + i * 16;
            if (sy + 15 < lo || sy > hi) continue; // section outside the Y range: skipped entirely
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> targets.contains(st.getBlock()))) continue;
            for (int y = 0; y < 16; y++) {
                int by = sy + y;
                if (by < lo || by > hi) continue;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (targets.contains(s.getBlockState(x, y, z).getBlock()))
                        found.add(new BlockPos(cp.getStartX() + x, by, cp.getStartZ() + z));
                }
            }
        }
        if (found.isEmpty()) cache.remove(cp);
        else cache.put(cp, found);
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    private static int mapRgb(Block b) {
        try {
            return b.getDefaultMapColor().color;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    private void rebuild() {
        Set<Block> targets = new HashSet<>(blocks.get());
        Vec3d pp = mc.player.getEyePos();
        double rr = Math.max(16, range.get());
        double maxSq = rr * rr;
        List<Item> out = new ArrayList<>();

        for (List<BlockPos> list : cache.values()) {
            for (BlockPos pos : list) {
                double dx = pos.getX() + 0.5 - pp.x, dy = pos.getY() + 0.5 - pp.y, dz = pos.getZ() + 0.5 - pp.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d > maxSq) continue;
                Block b = mc.world.getBlockState(pos).getBlock();
                if (!targets.contains(b)) continue; // mined or changed since the scan
                out.add(new Item(pos.toImmutable(), mapRgb(b), d));
            }
        }
        out.sort(Comparator.comparingDouble(Item::dist));
        int cap = Math.max(1, maxBlocks.get());
        if (out.size() > cap) out = new ArrayList<>(out.subList(0, cap));
        items = out;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        List<Item> list = items;
        if (list.isEmpty()) return;

        boolean customBox = boxColorMode.get() == ColorMode.Custom;
        SettingColor cb = boxColor.get();
        for (Item it : list) {
            int r, g, b;
            if (customBox) { r = cb.r; g = cb.g; b = cb.b; }
            else { r = (it.rgb >> 16) & 255; g = (it.rgb >> 8) & 255; b = it.rgb & 255; }
            Color side = new Color(r, g, b, clamp255(fillAlpha.get()));
            Color line = new Color(r, g, b, clamp255(lineAlpha.get()));
            event.renderer.box(it.pos, side, line, shapeMode.get(), 0);
        }

        if (tracers.get()) {
            boolean customT = tracerColorMode.get() == ColorMode.Custom;
            SettingColor ct = tracerColor.get();
            int n = Math.max(0, Math.min(maxTracers.get(), list.size()));
            for (int i = 0; i < n; i++) {
                Item it = list.get(i);
                int r, g, b;
                if (customT) { r = ct.r; g = ct.g; b = ct.b; }
                else { r = (it.rgb >> 16) & 255; g = (it.rgb >> 8) & 255; b = it.rgb & 255; }
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    it.pos.getX() + 0.5, it.pos.getY() + 0.5, it.pos.getZ() + 0.5,
                    new Color(r, g, b, clamp255(tracerAlpha.get())));
            }
        }
    }
}
