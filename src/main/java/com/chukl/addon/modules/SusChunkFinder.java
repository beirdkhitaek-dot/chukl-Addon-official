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
import net.minecraft.client.world.ClientWorld;
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
public class SusChunkFinder extends Module {
    private record ScanResult(int notGrown, boolean hasGrown, Set<BlockPos> deepslate) {}

    public enum MarkerStyle { Outline, Solid, Light_Beam, Pulse_Beam, Rainbow_Beam }

    private record Zone(double centerX, double centerZ, int size, int maxHeat, int level) {}

    private record Opts(boolean kelp, boolean caveVines, boolean vines, boolean amethyst, boolean folia,
                        boolean bamboo, boolean cocoa, boolean beeNest, boolean deepslate) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

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
        .description("Smart mode: draws a tall marker at the center of every zone so you can spot it from far away.")
        .defaultValue(true)
        .visible(smartAdjustment::get)
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
        .defaultValue(new SettingColor(255, 0, 0, 110))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> chunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-outline-color")
        .description("Outline color of sus chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<Integer> chunkY = sgRender.add(new IntSetting.Builder()
        .name("chunk-y")
        .description("Height the chunk square is drawn at.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(-64, 128)
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
        .defaultValue(2)
        .range(0, 7)
        .sliderRange(0, 7)
        .build()
    );

    private final Setting<MarkerStyle> markerStyle = sgRender.add(new EnumSetting.Builder<MarkerStyle>()
        .name("marker-style")
        .description("Look of the zone pillar. Outline = old thin lines, Solid = filled column, Light Beam = glowing beam of light, Pulse Beam = beam that breathes, Rainbow Beam = color-cycling beam.")
        .defaultValue(MarkerStyle.Light_Beam)
        .visible(() -> smartAdjustment.get() && zoneMarker.get())
        .build()
    );

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Color of the zone pillar (ignored by Rainbow Beam).")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(() -> smartAdjustment.get() && zoneMarker.get() && markerStyle.get() != MarkerStyle.Rainbow_Beam)
        .build()
    );

    private final Setting<Double> markerWidth = sgRender.add(new DoubleSetting.Builder()
        .name("marker-width")
        .description("Thickness of the pillar core in blocks. Bigger = easier to see from far away.")
        .defaultValue(1.5)
        .range(0.25, 16)
        .sliderRange(0.25, 8)
        .visible(() -> smartAdjustment.get() && zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerHeight = sgRender.add(new IntSetting.Builder()
        .name("marker-height")
        .description("How tall the pillar is, in blocks above the chunk layer. Higher + brighter zones get extra height on top of this.")
        .defaultValue(96)
        .range(16, 384)
        .sliderRange(16, 320)
        .visible(() -> smartAdjustment.get() && zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerGlow = sgRender.add(new IntSetting.Builder()
        .name("marker-glow-layers")
        .description("Soft glow layers around the beam (0 = none). Each layer is one extra box per zone, so keep it low on weak devices.")
        .defaultValue(3)
        .range(0, 5)
        .sliderRange(0, 5)
        .visible(() -> smartAdjustment.get() && zoneMarker.get()
            && (markerStyle.get() == MarkerStyle.Light_Beam || markerStyle.get() == MarkerStyle.Pulse_Beam || markerStyle.get() == MarkerStyle.Rainbow_Beam))
        .build()
    );

    private final Setting<Double> markerSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("marker-animation-speed")
        .description("Speed of the pulse / rainbow animation.")
        .defaultValue(1.0)
        .range(0.1, 5)
        .sliderRange(0.1, 3)
        .visible(() -> smartAdjustment.get() && zoneMarker.get()
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

    // --- Data (only touched by the single scan thread, read by the main thread) ---

    private final Map<ChunkPos, Integer> heatmap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> tracked = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> growthCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Boolean> fullyGrown = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> usedSimDist = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> chunkDeepslate = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> deepslateSet = ConcurrentHashMap.newKeySet();

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

    public SusChunkFinder() {
        super(ChuklAddon.CATEGORY, "sus-chunk-finder", "Finds probable base locations using plant/amethyst growth and rotated deepslate.");
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
                Thread t = new Thread(r, "SusChunkFinder-Scan-" + id);
                t.setDaemon(true);
                return t;
            });
        }

        clearData();
        lastWorld = mc.world;
        timer = 0;
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
    }

    // ---------- scanning ----------

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;
        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private Opts opts() {
        return new Opts(kelp.get(), caveVines.get(), vines.get(), amethyst.get(), amethyst.get() && isDonutFolia(),
            bamboo.get(), cocoa.get(), beeNest.get(), rotatedDeepslate.get());
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

        int notGrown = 0;
        int grown = 0;
        boolean foundBuds = false;
        boolean foundClusters = false;
        Set<BlockPos> deepslate = new HashSet<>();
        BlockPos.Mutable other = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (bottomSection + i) << 4;

            if (o.folia()) {
                if (!foundBuds && section.hasAny(SusChunkFinder::isBud)) foundBuds = true;
                if (!foundClusters && section.hasAny(SusChunkFinder::isCluster)) foundClusters = true;
            }

            boolean scanGrowth = section.hasAny(growthFilter);
            boolean scanDeepslate = o.deepslate()
                && sectionMinY + 15 >= 0 && sectionMinY <= 60
                && section.hasAny(deepslateFilter);

            if (!scanGrowth && !scanDeepslate) continue;

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

        return new ScanResult(notGrown, grown > 0, deepslate);
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
        if (smartAdjustment.get()) applySmartMode(sus, minSensitivity);
        else {
            susLevel = Map.of();
            zones = List.of();
        }
        susChunks = sus;

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

            if (filter && members.size() == 1 && max < sens * 2) {
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

        Map<ChunkPos, Integer> chunks = susChunks;
        if (!chunks.isEmpty()) {
            double y = chunkY.get();
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

        if (smartAdjustment.get() && zoneMarker.get()) {
            renderMarkers(event);
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

    private void renderMarkers(Render3DEvent event) {
        MarkerStyle style = markerStyle.get();
        int view = mc.options.getViewDistance().getValue();
        ChunkPos here = mc.player.getChunkPos();
        double bottom = chunkY.get();
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

        for (Zone z : zones) {
            if (Math.max(Math.abs((int) Math.floor(z.centerX() / 16) - here.x),
                Math.abs((int) Math.floor(z.centerZ() / 16) - here.z)) > view) continue;

            double top = bottom + markerHeight.get() + z.level() * 16;
            double half = baseHalf + z.level() * 0.25; // stronger zones get a fatter pillar
            double cx = z.centerX(), cz = z.centerZ();

            if (style == MarkerStyle.Outline) {
                Color line = new Color(r, g, b, Math.max(120, alpha.get()));
                event.renderer.box(cx - half, bottom, cz - half, cx + half, top, cz + half, line, line, ShapeMode.Lines, 0);
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
