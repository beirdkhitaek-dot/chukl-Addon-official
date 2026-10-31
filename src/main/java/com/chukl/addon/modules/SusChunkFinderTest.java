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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Finds probable base locations from plant/amethyst growth and buried rotated deepslate.
 *
 * Works the same way as the Krypton module: every chunk is scanned ONCE when its data arrives (and once
 * for the chunks already loaded when you turn the module on). Chunks that still contain things that
 * have not finished growing "heat up" all chunks around them. A chunk that is hot, is not covered by
 * fully grown plants, and has at least 3 loaded neighbors is marked as sus.
 *
 * Speed: chunks are scanned in parallel on several background threads, sections without anything
 * interesting are skipped, and the sus list refreshes every other tick.
 */
public class SusChunkFinderTest extends Module {
    private record ScanResult(int notGrown, boolean hasGrown, Set<BlockPos> deepslate, int ugScore, int ugX, int ugZ) {}

    public enum RenderMode { Best_Chunks, All_Chunks }

    public enum MarkerStyle { Outline, Solid, Light_Beam, Pulse_Beam, Rainbow_Beam, Lightning }

    public enum ChunkStyle { Energy_Orb, Column, Orb_And_Column }

    public enum YMode { Fixed, Follow_Player }

    private record Zone(double centerX, double centerZ, int size, int maxHeat, int level) {}

    private record Opts(boolean kelp, boolean caveVines, boolean vines, boolean amethyst, boolean folia,
                        boolean bamboo, boolean cocoa, boolean beeNest, boolean deepslate,
                        Map<Block, Integer> ug, int ugMin, int ugMax) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgUg = settings.createGroup("Underground");

    // --- General (same list and order as Krypton) ---

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Chunk radius around growing plants that is considered 'currently loaded' and ignored. 0 = only the chunk the plants are in, 1 = 3x3 chunks, 2 = 5x5.")
        .defaultValue(4)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Minimum number of unfinished growing things needed to mark a chunk as sus.")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> smartAdjustment = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-adjustment")
        .description("Smart mode: groups nearby sus chunks into zones, drops lone weak chunks, rates each zone (low/medium/high confidence) and draws stronger zones brighter. Only draws chunks inside your render distance. Never changes your sensitivity or simulation distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noiseFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("noise-filter")
        .description("Smart mode: a single sus chunk with nobody else around is only shown if its heat is at least double your sensitivity. Removes most false positives.")
        .defaultValue(true)
        .visible(smartAdjustment::get)
        .build()
    );

    private final Setting<Boolean> zoneMarker = sgGeneral.add(new BoolSetting.Builder()
        .name("zone-marker")
        .description("Draws a tall beam marker so you can spot it from far away: at every zone center (All Chunks) or at your best dig spots (Best Chunks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyZones = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-zones")
        .description("Smart mode: prints the coordinates in chat when a new high-confidence zone is found.")
        .defaultValue(false)
        .visible(smartAdjustment::get)
        .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity (10-255) of the chunk fill and the deepslate boxes.")
        .defaultValue(81)
        .range(10, 255)
        .sliderRange(10, 255)
        .build()
    );

    private final Setting<Boolean> kelp = sgGeneral.add(new BoolSetting.Builder()
        .name("kelp").description("Use kelp growth.").defaultValue(false).build());

    private final Setting<Boolean> caveVines = sgGeneral.add(new BoolSetting.Builder()
        .name("cave-vines").description("Use cave vine growth.").defaultValue(false).build());

    private final Setting<Boolean> vines = sgGeneral.add(new BoolSetting.Builder()
        .name("vines").description("Use vine growth.").defaultValue(false).build());

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst").description("Use amethyst bud and cluster growth.").defaultValue(true).build());

    private final Setting<Boolean> bamboo = sgGeneral.add(new BoolSetting.Builder()
        .name("bamboo").description("Use bamboo growth.").defaultValue(false).build());

    private final Setting<Boolean> cocoa = sgGeneral.add(new BoolSetting.Builder()
        .name("cocoa").description("Use cocoa pod growth.").defaultValue(false).build());

    private final Setting<Boolean> beeNest = sgGeneral.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Bee nests with honey mean someone loaded the chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotatedDeepslate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Detect rotated deepslate buried between Y 0 and 60.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-info")
        .description("Every 2 seconds, prints the numbers behind the chunk you are standing in (heat, tracked, loaded neighbors). Use it to compare with Krypton.")
        .defaultValue(false)
        .build()
    );

    // --- Render ---

    private final Setting<ShapeMode> chunkShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("chunk-shape-mode")
        .description("How sus chunks are drawn. Sides = filled flat square, Lines = outline only, Both = filled with outline.")
        .defaultValue(ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> chunkSideColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-fill-color")
        .description("Fill color of sus chunks (opacity comes from the Alpha setting).")
        .defaultValue(new SettingColor(70, 150, 255, 110))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> chunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-outline-color")
        .description("Outline color of sus chunks.")
        .defaultValue(new SettingColor(130, 200, 255, 220))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<Integer> chunkY = sgRender.add(new IntSetting.Builder()
        .name("chunk-y")
        .description("Y level the orb, pillar and flat layer are drawn at. Any level from -64 to 320.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Double> chunkThickness = sgRender.add(new DoubleSetting.Builder()
        .name("chunk-thickness")
        .description("Thickness of the chunk box in blocks. Small = flat square, larger = tall column.")
        .defaultValue(0.1)
        .range(0.05, 128)
        .sliderRange(0.05, 16)
        .build()
    );

    private final Setting<Integer> chunkInset = sgRender.add(new IntSetting.Builder()
        .name("chunk-inset")
        .description("Shrinks each chunk square by this many blocks on every side, so the layer is smaller and neighbors don't merge into one big sheet.")
        .defaultValue(0)
        .range(0, 7)
        .sliderRange(0, 7)
        .build()
    );

    private final Setting<MarkerStyle> markerStyle = sgRender.add(new EnumSetting.Builder<MarkerStyle>()
        .name("marker-style")
        .description("Look of the pillar. Lightning = crackling electric bolts, Light Beam = glowing beam, Pulse Beam = beam that breathes, Rainbow Beam = color-cycling beam, Solid = filled column, Outline = thin lines.")
        .defaultValue(MarkerStyle.Lightning)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Color of the zone pillar (ignored by Rainbow Beam).")
        .defaultValue(new SettingColor(70, 150, 255, 255))
        .visible(() -> zoneMarker.get() && markerStyle.get() != MarkerStyle.Rainbow_Beam)
        .build()
    );

    private final Setting<Double> markerWidth = sgRender.add(new DoubleSetting.Builder()
        .name("marker-width")
        .description("Thickness of the pillar core in blocks. Bigger = easier to see from far away.")
        .defaultValue(1.5)
        .range(0.25, 16)
        .sliderRange(0.25, 8)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerHeight = sgRender.add(new IntSetting.Builder()
        .name("marker-height")
        .description("How tall the pillar is, in blocks above the chunk layer. Higher + brighter zones get extra height on top of this.")
        .defaultValue(96)
        .range(16, 384)
        .sliderRange(16, 320)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerGlow = sgRender.add(new IntSetting.Builder()
        .name("marker-glow-layers")
        .description("Soft glow layers around the beam (0 = none). Each layer is one extra box per zone, so keep it low on weak devices.")
        .defaultValue(3)
        .range(0, 5)
        .sliderRange(0, 5)
        .visible(() -> zoneMarker.get()
            && (markerStyle.get() == MarkerStyle.Light_Beam || markerStyle.get() == MarkerStyle.Pulse_Beam || markerStyle.get() == MarkerStyle.Rainbow_Beam))
        .build()
    );

    private final Setting<Double> markerSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("marker-animation-speed")
        .description("Speed of the pulse / rainbow animation.")
        .defaultValue(1.0)
        .range(0.1, 5)
        .sliderRange(0.1, 3)
        .visible(() -> zoneMarker.get()
            && (markerStyle.get() == MarkerStyle.Pulse_Beam || markerStyle.get() == MarkerStyle.Rainbow_Beam))
        .build()
    );

    private final Setting<SettingColor> deepslateColor = sgRender.add(new ColorSetting.Builder()
        .name("deepslate-color")
        .description("Outline color of suspicious rotated deepslate blocks.")
        .defaultValue(new SettingColor(0, 255, 255, 160))
        .visible(rotatedDeepslate::get)
        .build()
    );

    private final Setting<Integer> deepslateDistance = sgRender.add(new IntSetting.Builder()
        .name("deepslate-render-distance")
        .description("Only draw suspicious deepslate within this many blocks.")
        .defaultValue(64)
        .range(16, 256)
        .sliderRange(16, 192)
        .visible(rotatedDeepslate::get)
        .build()
    );

    // --- Test: best-chunk rendering ---

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Best Chunks = draw only the top chunk(s) as a tall dig-down column (Y range from the Underground group) with a dig shaft and beam. All Chunks = the old flat layer for every sus chunk.")
        .defaultValue(RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Integer> chunksShown = sgRender.add(new IntSetting.Builder()
        .name("chunks-shown")
        .description("How many of the best chunks to draw. 1 = just the single best chunk.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> announceBest = sgRender.add(new BoolSetting.Builder()
        .name("announce-best")
        .description("Print the coordinates of the best dig spot in chat when it changes.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<ChunkStyle> chunkStyle = sgRender.add(new EnumSetting.Builder<ChunkStyle>()
        .name("chunk-style")
        .description("How the best chunk looks. Energy Orb = glowing orb of light with electric arcs, Column = the dig-range column, Orb And Column = both.")
        .defaultValue(ChunkStyle.Energy_Orb)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Double> orbSize = sgRender.add(new DoubleSetting.Builder()
        .name("orb-size")
        .description("Radius of the energy orb in blocks.")
        .defaultValue(3.0)
        .range(0.5, 24)
        .sliderRange(0.5, 12)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<Integer> orbArcs = sgRender.add(new IntSetting.Builder()
        .name("orb-arcs")
        .description("Number of electric arcs crackling out of the orb (0 = none).")
        .defaultValue(7)
        .range(0, 20)
        .sliderRange(0, 16)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<Double> orbSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("orb-speed")
        .description("How fast the orb spins and pulses.")
        .defaultValue(1.0)
        .range(0.1, 5)
        .sliderRange(0.1, 3)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<YMode> yMode = sgRender.add(new EnumSetting.Builder<YMode>()
        .name("y-mode")
        .description("Fixed = use chunk-y. Follow Player = stay at your own height plus y-offset.")
        .defaultValue(YMode.Fixed)
        .build()
    );

    private final Setting<Integer> yOffset = sgRender.add(new IntSetting.Builder()
        .name("y-offset")
        .description("Blocks above (or below, if negative) your own height.")
        .defaultValue(0)
        .range(-64, 128)
        .sliderRange(-32, 64)
        .visible(() -> yMode.get() == YMode.Follow_Player)
        .build()
    );

    private final Setting<Boolean> showShaft = sgRender.add(new BoolSetting.Builder()
        .name("dig-shaft")
        .description("Draw the one-block dig shaft from the surface down to the bottom of the dig range.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your crosshair to the best chunk.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer line.")
        .defaultValue(new SettingColor(100, 200, 255, 220))
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && tracers.get())
        .build()
    );

    // --- Test: underground detection ---

    private final Setting<Boolean> ugDetect = sgUg.add(new BoolSetting.Builder()
        .name("underground-detection")
        .description("Scan the underground height range for blocks that do not generate there naturally (chests, furnaces, beds, ...). Chunks with enough of them are flagged and ranked higher.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> ugMinY = sgUg.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y scanned and the bottom of the dig column.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Integer> ugMaxY = sgUg.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y scanned and the top of the dig column.")
        .defaultValue(29)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Integer> ugThreshold = sgUg.add(new IntSetting.Builder()
        .name("threshold")
        .description("Underground score a chunk needs to be flagged on its own. Storage = 3 points per block, utility and redstone = 2, lighting and building = 1.")
        .defaultValue(10)
        .range(1, 200)
        .sliderRange(1, 60)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugFlagsChunks = sgUg.add(new BoolSetting.Builder()
        .name("flag-chunks")
        .description("Flag chunks that reach the threshold even when there is no plant/amethyst growth heat. Off = underground blocks only raise the ranking of chunks that are already sus.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugStorage = sgUg.add(new BoolSetting.Builder()
        .name("storage")
        .description("Chests, trapped chests, barrels, ender chests, shulker boxes.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugUtility = sgUg.add(new BoolSetting.Builder()
        .name("utility")
        .description("Furnaces, crafting/enchanting/smithing tables, anvils, beacons, hoppers, droppers, dispensers, brewing stands, beds.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugRedstone = sgUg.add(new BoolSetting.Builder()
        .name("redstone")
        .description("Pistons, observers, repeaters, comparators, redstone lamps, TNT, note blocks.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugLighting = sgUg.add(new BoolSetting.Builder()
        .name("lighting")
        .description("Torches, lanterns, glowstone, sea lanterns. Mineshafts have torches too, so this can give false positives.")
        .defaultValue(false)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugBuilding = sgUg.add(new BoolSetting.Builder()
        .name("building")
        .description("Glass, obsidian, scaffolding and metal/gem storage blocks. Obsidian also forms naturally where lava meets water.")
        .defaultValue(false)
        .visible(ugDetect::get)
        .build()
    );

    // --- Data (only touched by the single scan thread, read by the main thread) ---

    private final Map<ChunkPos, Integer> heatmap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> tracked = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> growthCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Boolean> fullyGrown = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> usedSimDist = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> chunkDeepslate = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> deepslateSet = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> ugScores = new ConcurrentHashMap<>();
    private final Map<ChunkPos, BlockPos> ugTargets = new ConcurrentHashMap<>();
    private volatile Set<ChunkPos> ugFlagged = Set.of();
    private volatile List<ChunkPos> bestChunks = List.of();
    private ChunkPos lastAnnounced;
    private int lastUgSig;
    private int ugMaskCache = -1;
    private Map<Block, Integer> ugMapCache = Map.of();

    // ---- underground block table (built once from the block registry) ----
    private static final int[] UG_WEIGHTS = {3, 2, 2, 1, 1}; // storage, utility, redstone, lighting, building
    private static Map<Block, int[]> ugInfo;

    private static int ugGroupOf(String p) {
        switch (p) {
            case "chest": case "trapped_chest": case "barrel": case "ender_chest":
                return 0;
            case "furnace": case "blast_furnace": case "smoker": case "crafting_table": case "enchanting_table":
            case "smithing_table": case "anvil": case "chipped_anvil": case "damaged_anvil": case "beacon":
            case "hopper": case "dispenser": case "dropper": case "brewing_stand": case "grindstone":
            case "cartography_table": case "fletching_table": case "loom":
                return 1;
            case "piston": case "sticky_piston": case "observer": case "redstone_lamp": case "repeater":
            case "comparator": case "tnt": case "note_block":
                return 2;
            case "torch": case "wall_torch": case "soul_torch": case "soul_wall_torch": case "redstone_torch":
            case "redstone_wall_torch": case "lantern": case "soul_lantern": case "sea_lantern": case "glowstone":
                return 3;
            case "obsidian": case "glass": case "glass_pane": case "tinted_glass": case "scaffolding":
            case "iron_block": case "gold_block": case "diamond_block": case "emerald_block": case "netherite_block":
            case "copper_block": case "lapis_block": case "coal_block": case "redstone_block":
                return 4;
            default:
                if (p.endsWith("shulker_box")) return 0;
                if (p.endsWith("_bed")) return 1;
                return -1;
        }
    }

    private static synchronized Map<Block, int[]> ugInfo() {
        if (ugInfo != null) return ugInfo;
        Map<Block, int[]> m = new java.util.HashMap<>();
        for (Block b : Registries.BLOCK) {
            int g = ugGroupOf(Registries.BLOCK.getId(b).getPath());
            if (g >= 0) m.put(b, new int[]{g, UG_WEIGHTS[g]});
        }
        ugInfo = m;
        return m;
    }

    /** Blocks (with their weights) for the groups that are switched on. Rebuilt only when the groups change. */
    private Map<Block, Integer> ugBlocks() {
        int mask = 0;
        if (ugDetect.get()) {
            if (ugStorage.get()) mask |= 1;
            if (ugUtility.get()) mask |= 2;
            if (ugRedstone.get()) mask |= 4;
            if (ugLighting.get()) mask |= 8;
            if (ugBuilding.get()) mask |= 16;
        }
        if (mask != ugMaskCache) {
            Map<Block, Integer> m = new java.util.HashMap<>();
            if (mask != 0) {
                for (Map.Entry<Block, int[]> e : ugInfo().entrySet()) {
                    if ((mask & (1 << e.getValue()[0])) != 0) m.put(e.getKey(), e.getValue()[1]);
                }
            }
            ugMapCache = m;
            ugMaskCache = mask;
        }
        return ugMapCache;
    }

    /** Changes whenever a setting that affects scanning changes, so every loaded chunk gets scanned again. */
    private int ugSignature() {
        return java.util.Objects.hash(ugDetect.get(), ugMinY.get(), ugMaxY.get(), ugStorage.get(), ugUtility.get(),
            ugRedstone.get(), ugLighting.get(), ugBuilding.get());
    }

    private volatile Map<ChunkPos, Integer> susChunks = Map.of();
    /** Smart mode: confidence (0 low, 1 medium, 2 high) of the zone each sus chunk belongs to. */
    private volatile Map<ChunkPos, Integer> susLevel = Map.of();
    private volatile List<Zone> zones = List.of();
    private final Set<Long> notifiedZones = new java.util.HashSet<>();
    private volatile List<BlockPos> deepslateBlocks = List.of();
    private ExecutorService[] executors;
    private ClientWorld lastWorld;
    private int timer;
    private final Map<ChunkPos, WorldChunk> seenChunks = new java.util.HashMap<>();

    public SusChunkFinderTest() {
        super(ChuklAddon.CATEGORY, "sus-chunk-finder-test", "TEST copy of Sus Chunk Finder: renders only the best chunk(s) as a dig-down column + beam, and scans Y29 to -64 for player-made blocks.");
    }

    @Override
    public void onActivate() {
        restart();
    }

    @Override
    public void onDeactivate() {
        stopExecutors();
        lastWorld = null;
        clearData();
    }

    private void stopExecutors() {
        if (executors != null) {
            for (ExecutorService e : executors) e.shutdownNow();
        }
        executors = null;
    }

    private void restart() {
        stopExecutors();

        // One single-thread executor per worker. A given chunk always goes to the same worker, so two
        // scans of the same chunk can never run at once, while different chunks scan in parallel.
        int count = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        executors = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            int id = i;
            executors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SusChunkFinderTest-Scan-" + id);
                t.setDaemon(true);
                return t;
            });
        }

        clearData();
        lastWorld = mc.world;
        timer = 0;
        lastUgSig = ugSignature();
    }

    private void clearData() {
        heatmap.clear();
        tracked.clear();
        growthCounts.clear();
        fullyGrown.clear();
        usedSimDist.clear();
        chunkDeepslate.clear();
        loadedChunks.clear();
        seenChunks.clear();
        deepslateSet.clear();
        susChunks = Map.of();
        susLevel = Map.of();
        zones = List.of();
        notifiedZones.clear();
        deepslateBlocks = List.of();
        ugScores.clear();
        ugTargets.clear();
        ugFlagged = Set.of();
        bestChunks = List.of();
        lastAnnounced = null;
    }

    // ---------- scanning ----------

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;
        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private Opts opts() {
        return new Opts(kelp.get(), caveVines.get(), vines.get(), amethyst.get(), amethyst.get() && isDonutFolia(),
            bamboo.get(), cocoa.get(), beeNest.get(), rotatedDeepslate.get(),
            ugBlocks(), Math.min(ugMinY.get(), ugMaxY.get()), Math.max(ugMinY.get(), ugMaxY.get()));
    }

    private int effectiveSimDist() {
        return simulationDistance.get();
    }

    /** Your sensitivity is used exactly as set. Smart Adjustment does not change it. */
    private int effectiveSensitivity() {
        return sensitivity.get();
    }

    /**
     * Looks for chunks that just loaded (or were sent again by the server, which creates a new chunk
     * object) and scans each one once. Checked every tick, so a chunk is scanned the moment it arrives.
     */
    private void scanNewChunks() {
        if (mc.world == null || mc.player == null) return;

        var manager = mc.world.getChunkManager();
        ChunkPos center = mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue() + (smartAdjustment.get() ? 0 : 2);

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!manager.isChunkLoaded(cx, cz)) continue;

                WorldChunk chunk = manager.getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkPos pos = new ChunkPos(cx, cz);
                if (seenChunks.get(pos) != chunk) {
                    seenChunks.put(pos, chunk);
                    queue(chunk);
                }
            }
        }

        // Forget unloaded chunks so they get scanned again when they come back
        seenChunks.keySet().removeIf(pos -> !manager.isChunkLoaded(pos.x, pos.z));
    }

    private void queue(WorldChunk chunk) {
        ExecutorService[] pool = executors;
        if (pool == null || chunk == null || mc.world == null) return;

        ClientWorld world = mc.world;
        Opts o = opts();
        int simDist = effectiveSimDist();

        ChunkPos pos = chunk.getPos();
        ExecutorService worker = pool[Math.floorMod(pos.x * 31 + pos.z, pool.length)];

        worker.execute(() -> {
            try {
                updateChunk(world, chunk, o, simDist);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Sus Chunk Finder scan failed", t);
            }
        });
    }

    private static void forEachNeighbor(ChunkPos cp, int dist, Consumer<ChunkPos> action) {
        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                action.accept(new ChunkPos(cp.x + dx, cp.z + dz));
            }
        }
    }

    /** Background thread. Removes what this chunk contributed last time, scans it again, adds the new result. */
    private void updateChunk(ClientWorld world, WorldChunk chunk, Opts o, int simDist) {
        if (world != mc.world) return;

        ChunkPos cp = chunk.getPos();
        loadedChunks.add(cp);

        int prevSim = usedSimDist.getOrDefault(cp, simDist);
        int prevGrowth = growthCounts.getOrDefault(cp, 0);
        boolean prevFull = Boolean.TRUE.equals(fullyGrown.get(cp));
        Set<BlockPos> prevDeepslate = chunkDeepslate.remove(cp);
        ugScores.remove(cp);
        ugTargets.remove(cp);

        if (prevGrowth > 0) {
            forEachNeighbor(cp, prevSim, n -> heatmap.compute(n, (k, v) -> {
                int value = (v == null ? 0 : v) - prevGrowth;
                return value <= 0 ? null : value;
            }));
        }

        if (prevFull) {
            forEachNeighbor(cp, prevSim, n -> tracked.compute(n, (k, v) -> {
                if (v == null) return null;
                return v - 1 <= 0 ? null : v - 1;
            }));
        }

        if (prevDeepslate != null && !prevDeepslate.isEmpty()) deepslateSet.removeAll(prevDeepslate);

        ScanResult result = scanChunk(world, chunk, cp, o);

        growthCounts.put(cp, result.notGrown());
        fullyGrown.put(cp, result.hasGrown());
        usedSimDist.put(cp, simDist);

        if (result.hasGrown()) forEachNeighbor(cp, simDist, n -> tracked.merge(n, 1, Integer::sum));
        if (result.notGrown() > 0) forEachNeighbor(cp, simDist, n -> heatmap.merge(n, result.notGrown(), Integer::sum));

        if (!result.deepslate().isEmpty()) {
            chunkDeepslate.put(cp, result.deepslate());
            deepslateSet.addAll(result.deepslate());
        }

        if (result.ugScore() > 0) {
            ugScores.put(cp, result.ugScore());
            ugTargets.put(cp, new BlockPos(result.ugX(), 0, result.ugZ()));
        }
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isCluster(BlockState s) {
        return s.getBlock() == Blocks.AMETHYST_CLUSTER;
    }

    private ScanResult scanChunk(ClientWorld world, WorldChunk chunk, ChunkPos cp, Opts o) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();

        // Palette check: sections without any of these blocks are skipped entirely.
        boolean useAmethystBlocks = o.amethyst() && !o.folia();
        Predicate<BlockState> growthFilter = s -> {
            Block b = s.getBlock();
            return (o.kelp() && b == Blocks.KELP)
                || (o.caveVines() && b == Blocks.CAVE_VINES)
                || (o.vines() && b == Blocks.VINE)
                || (useAmethystBlocks && (b == Blocks.AMETHYST_CLUSTER || isBud(s)))
                || (o.bamboo() && b == Blocks.BAMBOO)
                || (o.cocoa() && b == Blocks.COCOA)
                || (o.beeNest() && b == Blocks.BEE_NEST);
        };
        Predicate<BlockState> deepslateFilter = s -> s.getBlock() == Blocks.DEEPSLATE;
        Map<Block, Integer> ug = o.ug();
        Predicate<BlockState> ugFilter = s -> ug.containsKey(s.getBlock());

        int notGrown = 0;
        int grown = 0;
        boolean foundBuds = false;
        boolean foundClusters = false;
        Set<BlockPos> deepslate = new HashSet<>();
        int ugScore = 0;
        long ugSumX = 0, ugSumZ = 0;
        BlockPos.Mutable other = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (bottomSection + i) << 4;

            if (o.folia()) {
                if (!foundBuds && section.hasAny(SusChunkFinderTest::isBud)) foundBuds = true;
                if (!foundClusters && section.hasAny(SusChunkFinderTest::isCluster)) foundClusters = true;
            }

            boolean scanGrowth = section.hasAny(growthFilter);
            boolean scanDeepslate = o.deepslate()
                && sectionMinY + 15 >= 0 && sectionMinY <= 60
                && section.hasAny(deepslateFilter);

            boolean scanUg = !ug.isEmpty()
                && sectionMinY + 15 >= o.ugMin() && sectionMinY <= o.ugMax()
                && section.hasAny(ugFilter);

            if (!scanGrowth && !scanDeepslate && !scanUg) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        int bx = startX + x;
                        int by = sectionMinY + y;
                        int bz = startZ + z;

                        if (scanGrowth) {
                            if (o.kelp() && block == Blocks.KELP && state.contains(Properties.AGE_25)) {
                                boolean waterAbove = world.getBlockState(other.set(bx, by + 1, bz)).isOf(Blocks.WATER);
                                if (state.get(Properties.AGE_25) != 25 && waterAbove) grown++;
                                else notGrown++;

                            } else if (o.caveVines() && block == Blocks.CAVE_VINES && state.contains(Properties.AGE_25)) {
                                boolean airBelow = world.getBlockState(other.set(bx, by - 1, bz)).isAir();
                                if (state.get(Properties.AGE_25) != 25 && airBelow) grown++;
                                else notGrown++;

                            } else if (o.vines() && block == Blocks.VINE) {
                                BlockState below = world.getBlockState(other.set(bx, by - 1, bz));
                                if (!below.isOf(Blocks.VINE)) {
                                    if (!below.isAir()) {
                                        notGrown++;
                                    } else if (state.get(Properties.NORTH) || state.get(Properties.EAST)
                                        || state.get(Properties.SOUTH) || state.get(Properties.WEST)) {
                                        grown++;
                                    } else {
                                        notGrown++;
                                    }
                                }

                            } else if (useAmethystBlocks && block == Blocks.AMETHYST_CLUSTER) {
                                notGrown++;

                            } else if (useAmethystBlocks && isBud(state) && state.contains(Properties.FACING)) {
                                Direction facing = state.get(Properties.FACING);
                                BlockPos parent = other.set(bx, by, bz).offset(facing.getOpposite());
                                if (world.getBlockState(parent).isOf(Blocks.BUDDING_AMETHYST)) grown++;
                                else notGrown++;

                            } else if (o.bamboo() && block == Blocks.BAMBOO && state.contains(Properties.STAGE)) {
                                BlockState above = world.getBlockState(other.set(bx, by + 1, bz));
                                if (!above.isOf(Blocks.BAMBOO)) {
                                    if (state.get(Properties.STAGE) == 1) notGrown++;
                                    else if (above.isAir()) grown++;
                                }

                            } else if (o.cocoa() && block == Blocks.COCOA && state.contains(Properties.AGE_2)) {
                                if (state.get(Properties.AGE_2) == 2) notGrown++;
                                else grown++;

                            } else if (o.beeNest() && block == Blocks.BEE_NEST && state.contains(Properties.HONEY_LEVEL)) {
                                if (state.get(Properties.HONEY_LEVEL) == 5) notGrown++;
                                else grown++;
                            }
                        }

                        if (scanUg && by >= o.ugMin() && by <= o.ugMax()) {
                            Integer w = ug.get(block);
                            if (w != null) {
                                ugScore += w;
                                ugSumX += (long) bx * w;
                                ugSumZ += (long) bz * w;
                            }
                        }

                        if (scanDeepslate && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)
                            && state.get(Properties.AXIS) != Direction.Axis.Y && by >= 0 && by <= 60) {

                            boolean buried = true;
                            for (Direction dir : Direction.values()) {
                                if (world.getBlockState(other.set(bx, by, bz).offset(dir)).isAir()) {
                                    buried = false;
                                    break;
                                }
                            }
                            if (buried) deepslate.add(new BlockPos(bx, by, bz));
                        }
                    }
                }
            }
        }

        if (o.folia()) {
            if (foundBuds) grown++;
            else if (foundClusters) notGrown++;
        }

        int ugX = ugScore > 0 ? (int) Math.round(ugSumX / (double) ugScore) : 0;
        int ugZ = ugScore > 0 ? (int) Math.round(ugSumZ / (double) ugScore) : 0;
        return new ScanResult(notGrown, grown > 0, deepslate, Math.min(ugScore, 200), ugX, ugZ);
    }

    // ---------- main thread ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executors == null) return;

        // New world / dimension: start over, like turning the module off and on
        if (mc.world != lastWorld) {
            restart();
            return;
        }

        if (ugSignature() != lastUgSig) { // an underground setting changed: rescan everything
            restart();
            return;
        }

        scanNewChunks();

        if (debugInfo.get() && mc.player.age % 40 == 0) printDebug();

        if (--timer > 0) return;
        timer = 2; // refresh the sus list every other tick

        ClientWorld world = mc.world;
        int minSensitivity = effectiveSensitivity();
        Map<ChunkPos, Integer> sus = new java.util.HashMap<>();

        for (Map.Entry<ChunkPos, Integer> entry : heatmap.entrySet()) {
            ChunkPos pos = entry.getKey();
            if (entry.getValue() < minSensitivity) continue;
            if (tracked.containsKey(pos)) continue;
            if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;

            int neighbors = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (loadedChunks.contains(new ChunkPos(pos.x + dx, pos.z + dz))) neighbors++;
                }
            }

            if (neighbors >= 3) sus.put(pos, entry.getValue());
        }
        Set<ChunkPos> ugStrong = new HashSet<>();
        if (ugDetect.get() && ugFlagsChunks.get()) {
            int thr = ugThreshold.get();
            for (Map.Entry<ChunkPos, Integer> e : ugScores.entrySet()) {
                ChunkPos pos = e.getKey();
                if (e.getValue() < thr) continue;
                if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;
                sus.merge(pos, e.getValue(), Integer::max);
                ugStrong.add(pos);
            }
        }
        ugFlagged = ugStrong;

        if (smartAdjustment.get()) applySmartMode(sus, minSensitivity);
        else {
            susLevel = Map.of();
            zones = List.of();
        }
        susChunks = sus;
        bestChunks = pickBest(sus);

        if (rotatedDeepslate.get()) {
            deepslateSet.removeIf(p -> !world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4));
            deepslateBlocks = new ArrayList<>(deepslateSet);
        } else {
            deepslateBlocks = List.of();
        }
    }

    /**
     * Smart mode. Groups sus chunks that touch (or are one chunk apart) into zones, removes lone weak chunks,
     * and rates every zone: high = 4+ chunks or very hot, medium = 2+ chunks or hot, low = everything else.
     */
    private void applySmartMode(Map<ChunkPos, Integer> sus, int sens) {
        Map<ChunkPos, Integer> level = new java.util.HashMap<>();
        List<Zone> found = new ArrayList<>();
        Set<ChunkPos> seen = new HashSet<>();
        boolean filter = noiseFilter.get();

        for (ChunkPos start : new ArrayList<>(sus.keySet())) {
            if (!seen.add(start)) continue;

            List<ChunkPos> members = new ArrayList<>();
            ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                ChunkPos c = queue.poll();
                members.add(c);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        ChunkPos n = new ChunkPos(c.x + dx, c.z + dz);
                        if (sus.containsKey(n) && seen.add(n)) queue.add(n);
                    }
                }
            }

            int max = 0;
            double wx = 0, wz = 0, total = 0;
            for (ChunkPos c : members) {
                int heat = sus.get(c);
                max = Math.max(max, heat);
                wx += (c.x * 16 + 8) * (double) heat;
                wz += (c.z * 16 + 8) * (double) heat;
                total += heat;
            }

            if (filter && members.size() == 1 && max < sens * 2 && !ugFlagged.contains(members.get(0))) {
                for (ChunkPos c : members) sus.remove(c); // lone weak chunk = probably noise
                continue;
            }

            int lvl = (members.size() >= 4 || max >= sens * 4) ? 2 : (members.size() >= 2 || max >= sens * 2) ? 1 : 0;
            for (ChunkPos c : members) level.put(c, lvl);
            found.add(new Zone(wx / total, wz / total, members.size(), max, lvl));
        }

        susLevel = level;
        zones = found;

        if (notifyZones.get()) {
            for (Zone z : found) {
                if (z.level() < 2) continue;
                long key = ChunkPos.toLong((int) Math.floor(z.centerX() / 64), (int) Math.floor(z.centerZ() / 64));
                if (notifiedZones.add(key)) {
                    info("High-confidence sus zone at " + (int) z.centerX() + ", " + (int) z.centerZ()
                        + " (" + z.size() + " chunk" + (z.size() == 1 ? "" : "s") + ", heat " + z.maxHeat() + ")");
                }
            }
        }
    }

    /** The Y level everything is drawn at: the fixed chunk-y, or your own height plus the offset. */
    private double baseY() {
        if (yMode.get() == YMode.Follow_Player && mc.player != null) return mc.player.getY() + yOffset.get();
        return chunkY.get();
    }

    private double rank(ChunkPos c, Map<ChunkPos, Integer> levels) {
        return heatmap.getOrDefault(c, 0) + ugScores.getOrDefault(c, 0) + 5.0 * levels.getOrDefault(c, 0);
    }

    /** Picks the best chunk(s) to dig: most evidence first, nearest first on ties. Keeps the old pick unless something beats it. */
    private List<ChunkPos> pickBest(Map<ChunkPos, Integer> sus) {
        if (sus.isEmpty() || mc.player == null) return List.of();

        int view = mc.options.getViewDistance().getValue();
        ChunkPos here = mc.player.getChunkPos();
        Map<ChunkPos, Integer> levels = susLevel;

        List<ChunkPos> list = new ArrayList<>(sus.keySet());
        list.removeIf(c -> Math.max(Math.abs(c.x - here.x), Math.abs(c.z - here.z)) > view);
        if (list.isEmpty()) return List.of();

        list.sort(java.util.Comparator
            .comparingDouble((ChunkPos c) -> -rank(c, levels))
            .thenComparingInt(c -> (c.x - here.x) * (c.x - here.x) + (c.z - here.z) * (c.z - here.z)));

        ChunkPos prev = lastAnnounced;
        if (prev != null && list.contains(prev) && rank(prev, levels) >= rank(list.get(0), levels)) {
            list.remove(prev);
            list.add(0, prev); // no flip-flopping between equal chunks while you walk around
        }

        List<ChunkPos> out = new ArrayList<>(list.subList(0, Math.min(chunksShown.get(), list.size())));

        ChunkPos top = out.get(0);
        if (!top.equals(lastAnnounced)) {
            lastAnnounced = top;
            if (announceBest.get() && renderMode.get() == RenderMode.Best_Chunks) {
                BlockPos t = digTarget(top);
                info("Best dig spot: X " + t.getX() + "  Z " + t.getZ() + "  (chunk " + top.x + ", " + top.z
                    + ", score " + (int) rank(top, levels) + ", underground " + ugScores.getOrDefault(top, 0) + ")");
            }
        }
        return out;
    }

    /** Where to dig: the middle of the underground blocks found in the chunk, or the chunk center if there are none. */
    private BlockPos digTarget(ChunkPos c) {
        BlockPos t = ugTargets.get(c);
        return t != null ? t : new BlockPos(c.getStartX() + 8, 0, c.getStartZ() + 8);
    }

    private void renderBest(Render3DEvent event) {
        List<ChunkPos> best = bestChunks;
        if (best.isEmpty()) return;

        ChunkStyle style = chunkStyle.get();
        boolean orb = style != ChunkStyle.Column;
        boolean column = style != ChunkStyle.Energy_Orb;

        int yMin = Math.min(ugMinY.get(), ugMaxY.get());
        int yMax = Math.max(ugMinY.get(), ugMaxY.get());
        int inset = chunkInset.get();
        SettingColor fill = chunkSideColor.get();
        SettingColor line = chunkLineColor.get();
        SettingColor mark = markerColor.get();
        ShapeMode mode = chunkShapeMode.get();
        double y0 = baseY();
        double shaftTop = Math.max(y0, yMax) + 1;

        List<double[]> beams = new ArrayList<>();
        for (int i = 0; i < best.size(); i++) {
            ChunkPos cp = best.get(i);
            double ox = cp.getStartX() + 8.0, oz = cp.getStartZ() + 8.0;

            if (column) {
                // the chunk, from the bottom of the dig range up to the top of it
                Color side = new Color(fill.r, fill.g, fill.b, alpha.get());
                event.renderer.box(
                    cp.getStartX() + inset, yMin, cp.getStartZ() + inset,
                    cp.getStartX() + 16 - inset, yMax + 1, cp.getStartZ() + 16 - inset,
                    side, line, mode, 0
                );
            }

            if (orb) renderOrb(event, ox, y0, oz, orbSize.get(), cp.x * 31 + cp.z);

            if (showShaft.get()) {
                // the dig shaft: one block wide, from the surface down to the bottom
                BlockPos t = digTarget(cp);
                Color shaft = new Color(mark.r, mark.g, mark.b, 150);
                Color shaftLine = new Color(255, 255, 255, 220);
                event.renderer.box(t.getX(), yMin, t.getZ(), t.getX() + 1, shaftTop, t.getZ() + 1, shaft, shaftLine, ShapeMode.Both, 0);
            }

            if (tracers.get()) {
                SettingColor tc = tracerColor.get();
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    ox, y0, oz,
                    new Color(tc.r, tc.g, tc.b, tc.a)
                );
            }

            beams.add(new double[]{ox, oz, i == 0 ? 2 : 1});
        }

        if (zoneMarker.get()) renderMarkers(event, beams);
    }

    /** A circle of line segments around (cx,cy,cz) in the plane spanned by the unit vectors u and v. */
    private void ring(Render3DEvent event, double cx, double cy, double cz, double r,
                      double ux, double uy, double uz, double vx, double vy, double vz, Color color) {
        final int n = 20;
        double px = cx + r * ux, py = cy + r * uy, pz = cz + r * uz;
        for (int i = 1; i <= n; i++) {
            double a = i * (Math.PI * 2 / n);
            double c = Math.cos(a), s = Math.sin(a);
            double x = cx + r * (c * ux + s * vx);
            double y = cy + r * (c * uy + s * vy);
            double z = cz + r * (c * uz + s * vz);
            event.renderer.line(px, py, pz, x, y, z, color);
            px = x; py = y; pz = z;
        }
    }

    /** A glowing, spinning orb of light with white-hot core and crackling electric arcs. */
    private void renderOrb(Render3DEvent event, double cx, double cy, double cz, double r, int seed) {
        double t = (System.currentTimeMillis() % 1000000L) / 1000.0 * orbSpeed.get();
        double pulse = 0.85 + 0.15 * Math.sin(t * 4.0);
        double spin = t * 1.2;
        double cs = Math.cos(spin), sn = Math.sin(spin);

        SettingColor base = markerColor.get();
        int cr = base.r, cg = base.g, cb = base.b;

        // soft glow: fainter, slightly bigger rings around the orb
        for (int g = 1; g <= markerGlow.get(); g++) {
            double rr = r * (1.0 + 0.18 * g);
            Color halo = new Color(cr, cg, cb, (int) (Math.max(10, 110 / (g + 0.5)) * pulse));
            ring(event, cx, cy, cz, rr, 1, 0, 0, 0, 0, 1, halo);
            ring(event, cx, cy, cz, rr, cs, 0, sn, 0, 1, 0, halo);
            ring(event, cx, cy, cz, rr, -sn, 0, cs, 0, 1, 0, halo);
        }

        // the sphere: equator, two spinning meridians and two latitude rings
        Color shell = new Color(Math.min(255, cr + 90), Math.min(255, cg + 90), Math.min(255, cb + 90), (int) (235 * pulse));
        ring(event, cx, cy, cz, r, 1, 0, 0, 0, 0, 1, shell);
        ring(event, cx, cy, cz, r, cs, 0, sn, 0, 1, 0, shell);
        ring(event, cx, cy, cz, r, -sn, 0, cs, 0, 1, 0, shell);
        double lat = r * 0.6, latR = r * 0.8;
        ring(event, cx, cy + lat, cz, latR, 1, 0, 0, 0, 0, 1, shell);
        ring(event, cx, cy - lat, cz, latR, 1, 0, 0, 0, 0, 1, shell);

        // white-hot core
        double core = r * 0.2 * (0.9 + 0.2 * pulse);
        event.renderer.box(cx - core, cy - core, cz - core, cx + core, cy + core, cz + core,
            new Color(255, 255, 255, 240), new Color(190, 225, 255, 255), ShapeMode.Both, 0);

        // electric arcs: jagged lines that jump to a new random shape ~11 times a second
        int arcs = orbArcs.get();
        long slice = System.currentTimeMillis() / 90;
        Color bolt = new Color(225, 242, 255, 235);
        for (int a = 0; a < arcs; a++) {
            java.util.Random rnd = new java.util.Random(slice * 7919L + a * 104729L + seed);
            double theta = rnd.nextDouble() * Math.PI * 2;
            double dy = rnd.nextDouble() * 2 - 1;
            double rr = Math.sqrt(1 - dy * dy);
            double dx = rr * Math.cos(theta), dz = rr * Math.sin(theta);
            double len = r * (1.3 + rnd.nextDouble() * 0.9);
            double px = cx, py = cy, pz = cz;
            final int segs = 6;
            for (int sg = 1; sg <= segs; sg++) {
                double f = sg / (double) segs;
                double jit = r * 0.2 * (1.0 - f * 0.5);
                double x = cx + dx * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                double y = cy + dy * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                double z = cz + dz * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                event.renderer.line(px, py, pz, x, y, z, bolt);
                px = x; py = y; pz = z;
            }
        }
    }

    private void printDebug() {
        ChunkPos cp = mc.player.getChunkPos();
        int heat = heatmap.getOrDefault(cp, 0);
        int cover = tracked.getOrDefault(cp, 0);
        int neighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (loadedChunks.contains(new ChunkPos(cp.x + dx, cp.z + dz))) neighbors++;
            }
        }

        String brand = mc.getNetworkHandler() == null ? "?" : String.valueOf(mc.getNetworkHandler().getBrand());
        info("Server " + brand + ", folia mode " + isDonutFolia() + ", render distance " + mc.options.getViewDistance().getValue()
            + ", sim distance " + effectiveSimDist() + ", chunks tracked " + loadedChunks.size());
        info("Chunk " + cp.x + ", " + cp.z + ": heat " + heat + " (needs " + effectiveSensitivity() + "), covered by grown plants "
            + cover + ", loaded neighbors " + neighbors + " (needs 3), own growing count " + growthCounts.getOrDefault(cp, 0)
            + ", flagged " + susChunks.containsKey(cp));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        boolean best = renderMode.get() == RenderMode.Best_Chunks;
        if (best) renderBest(event);

        Map<ChunkPos, Integer> chunks = best ? Map.<ChunkPos, Integer>of() : susChunks;
        if (!chunks.isEmpty()) {
            double y = baseY();
            double thickness = chunkThickness.get();
            int inset = chunkInset.get();
            SettingColor fill = chunkSideColor.get();
            SettingColor line = chunkLineColor.get();
            ShapeMode mode = chunkShapeMode.get();
            int baseAlpha = alpha.get();
            boolean smart = smartAdjustment.get();
            int view = mc.options.getViewDistance().getValue();
            ChunkPos here = mc.player.getChunkPos();
            int sens = Math.max(1, sensitivity.get());
            Map<ChunkPos, Integer> levels = susLevel;

            for (Map.Entry<ChunkPos, Integer> entry : chunks.entrySet()) {
                ChunkPos cp = entry.getKey();

                int a = baseAlpha;
                if (smart) {
                    if (Math.max(Math.abs(cp.x - here.x), Math.abs(cp.z - here.z)) > view) continue;

                    // Hotter chunks draw stronger: 40% opacity at the threshold up to 100% at 4x the threshold
                    double strength = Math.min(1.0, 0.4 + 0.6 * (entry.getValue() / (double) (sens * 4)));
                    // Zones with more evidence draw brighter: low 60%, medium 80%, high 100%
                    int lvl = levels.getOrDefault(cp, 0);
                    strength *= 0.6 + 0.2 * lvl;
                    a = Math.max(10, (int) (baseAlpha * strength));
                }

                Color side = new Color(fill.r, fill.g, fill.b, a);

                event.renderer.box(
                    cp.getStartX() + inset, y, cp.getStartZ() + inset,
                    cp.getStartX() + 16 - inset, y + thickness, cp.getStartZ() + 16 - inset,
                    side, line, mode, 0
                );
            }
        }

        if (!best && zoneMarker.get()) {
            int zview = mc.options.getViewDistance().getValue();
            ChunkPos zhere = mc.player.getChunkPos();
            List<double[]> pts = new ArrayList<>();
            for (Zone z : zones) {
                if (Math.max(Math.abs((int) Math.floor(z.centerX() / 16) - zhere.x),
                    Math.abs((int) Math.floor(z.centerZ() / 16) - zhere.z)) > zview) continue;
                pts.add(new double[]{z.centerX(), z.centerZ(), z.level()});
            }
            renderMarkers(event, pts);
        }

        if (rotatedDeepslate.get()) {
            List<BlockPos> blocks = deepslateBlocks;
            if (blocks.isEmpty()) return;

            SettingColor base = deepslateColor.get();
            Color color = new Color(base.r, base.g, base.b, alpha.get());
            double maxDistSq = (double) deepslateDistance.get() * deepslateDistance.get();
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            int drawn = 0;

            for (BlockPos pos : blocks) {
                double dx = pos.getX() + 0.5 - px, dy = pos.getY() + 0.5 - py, dz = pos.getZ() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

                event.renderer.box(pos, color, color, ShapeMode.Lines, 0);

                if (++drawn >= 500) break; // keeps weak devices smooth
            }
        }
    }

    private void renderMarkers(Render3DEvent event, List<double[]> pts) {
        MarkerStyle style = markerStyle.get();
        double bottom = baseY();
        double baseHalf = markerWidth.get() / 2.0;
        int glow = markerGlow.get();
        double t = (System.currentTimeMillis() % 100000L) / 1000.0 * markerSpeed.get();

        SettingColor mc0 = markerColor.get();
        int r = mc0.r, g = mc0.g, b = mc0.b;
        if (style == MarkerStyle.Rainbow_Beam) {
            double hue = (t * 0.15) % 1.0;
            // plain HSV->RGB (no java.awt, so it is safe on Android/Zalith)
            double h6 = hue * 6.0;
            int sector = (int) h6;
            double f = h6 - sector;
            int v = 255, p0 = (int) (255 * 0.15), q = (int) (255 * (1 - 0.85 * f)), t2 = (int) (255 * (1 - 0.85 * (1 - f)));
            switch (sector % 6) {
                case 0 -> { r = v; g = t2; b = p0; }
                case 1 -> { r = q; g = v; b = p0; }
                case 2 -> { r = p0; g = v; b = t2; }
                case 3 -> { r = p0; g = q; b = v; }
                case 4 -> { r = t2; g = p0; b = v; }
                default -> { r = v; g = p0; b = q; }
            }
        }

        // Pulse breathes between 55% and 100% brightness
        double pulse = (style == MarkerStyle.Pulse_Beam) ? 0.775 + 0.225 * Math.sin(t * 3.0) : 1.0;

        for (double[] pt : pts) {
            int lvl = (int) pt[2];
            double top = bottom + markerHeight.get() + lvl * 16;
            double half = baseHalf + lvl * 0.25; // stronger zones get a fatter pillar
            double cx = pt[0], cz = pt[1];

            if (style == MarkerStyle.Outline) {
                Color line = new Color(r, g, b, Math.max(120, alpha.get()));
                event.renderer.box(cx - half, bottom, cz - half, cx + half, top, cz + half, line, line, ShapeMode.Lines, 0);
                continue;
            }

            if (style == MarkerStyle.Lightning) {
                // thin glowing core plus three crackling bolts that re-roll ~12 times a second
                Color lcore = new Color(r, g, b, (int) (150 * pulse));
                double lh = Math.max(0.2, half * 0.35);
                event.renderer.box(cx - lh, bottom, cz - lh, cx + lh, top, cz + lh, lcore, lcore, ShapeMode.Sides, 0);

                long slice = System.currentTimeMillis() / 80;
                Color boltC = new Color(Math.min(255, r + 110), Math.min(255, g + 110), Math.min(255, b + 110), 235);
                int segs = Math.max(8, (int) ((top - bottom) / 3));
                double spread = half + 0.6;
                for (int k = 0; k < 3; k++) {
                    java.util.Random rnd = new java.util.Random(slice * 31L + k * 977L + (long) (cx * 13 + cz * 7));
                    double px = cx, py = bottom, pz = cz;
                    for (int sg = 1; sg <= segs; sg++) {
                        double y2 = bottom + (top - bottom) * sg / segs;
                        double x2 = sg == segs ? cx : cx + (rnd.nextDouble() - 0.5) * 2 * spread;
                        double z2 = sg == segs ? cz : cz + (rnd.nextDouble() - 0.5) * 2 * spread;
                        event.renderer.line(px, py, pz, x2, y2, z2, boltC);
                        px = x2; py = y2; pz = z2;
                    }
                }
                continue;
            }

            // Bright core
            int coreA = (int) Math.min(255, (style == MarkerStyle.Solid ? 170 : 230) * pulse);
            Color core = new Color(r, g, b, coreA);
            event.renderer.box(cx - half, bottom, cz - half, cx + half, top, cz + half,
                core, new Color(255, 255, 255, (int) (200 * pulse)),
                style == MarkerStyle.Solid ? ShapeMode.Both : ShapeMode.Sides, 0);

            if (style == MarkerStyle.Solid) continue;

            // Soft glow halos: wider, fainter boxes layered around the core
            for (int i = 1; i <= glow; i++) {
                double h = half + i * (0.35 + baseHalf * 0.5);
                int a = (int) (Math.max(6, 90 / (i + 0.5)) * pulse);
                Color halo = new Color(r, g, b, a);
                event.renderer.box(cx - h, bottom, cz - h, cx + h, top, cz + h, halo, halo, ShapeMode.Sides, 0);
            }
        }
    }
}
