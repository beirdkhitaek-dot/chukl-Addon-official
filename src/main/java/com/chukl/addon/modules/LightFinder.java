package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.utils.LineOfSight;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds underground block light (torches, lanterns, glowstone...), which often means a hidden base.
 *
 * Blocks mode: every lit block near you gets an outline whose color goes from dark to bright yellow
 * with the light level. Only blocks within a set distance are drawn, nearest first, and the count is
 * capped, so it stays smooth on weak devices.
 *
 * Sections mode: one box per 16x16x16 section that contains light. Lightest option, good for scouting
 * a large area.
 */
public class LightFinder extends Module {
    public enum RenderMode {
        Blocks,
        Sections
    }

    private record LitBlock(BlockPos pos, int level, double distSq) {}

    private record LightSection(Box box, int lit, int max) {}

    private record ScanResult(List<LitBlock> blocks, List<LightSection> sections) {}

    private static final int MAX_CANDIDATES = 400000;
    private static final int MAX_LOS_CHECKS = 3000;   // nearest first; each check is a ray cast on the game thread
    private static final int MAX_DRAWN = 6000;        // most blocks drawn per frame

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General ---

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Chunks around you to scan. Lower is smoother.")
        .defaultValue(8)
        .range(1, 32)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only look for light below this Y level.")
        .defaultValue(0)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> minBrightness = sgGeneral.add(new IntSetting.Builder()
        .name("min-brightness")
        .description("Ignore light dimmer than this (1-15). Raise it to ignore faint spill from far away light sources.")
        .defaultValue(1)
        .range(1, 15)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Seconds between scans (it scans less often while you stand still).")
        .defaultValue(3)
        .range(1, 30)
        .sliderRange(1, 15)
        .build()
    );

    // --- Render ---

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Blocks: outline every lit block near you, colored by brightness. Sections: one box per chunk section with light.")
        .defaultValue(RenderMode.Blocks)
        .build()
    );

    private final Setting<Integer> blockDistance = sgRender.add(new IntSetting.Builder()
        .name("block-render-distance")
        .description("Blocks mode: only draw lit blocks within this many blocks of you.")
        .defaultValue(64)
        .range(8, 512)
        .sliderRange(8, 512)
        .visible(() -> renderMode.get() == RenderMode.Blocks)
        .build()
    );

    private final Setting<Integer> maxBlocks = sgRender.add(new IntSetting.Builder()
        .name("max-blocks")
        .description("Blocks mode: most lit blocks found at once (nearest first). At most 6000 are drawn per frame to keep the game smooth.")
        .defaultValue(20000)
        .range(100, 400000)
        .sliderRange(100, 400000)
        .visible(() -> renderMode.get() == RenderMode.Blocks)
        .build()
    );

    private final Setting<Integer> blockAlpha = sgRender.add(new IntSetting.Builder()
        .name("block-alpha")
        .description("Blocks mode: outline opacity (0-255).")
        .defaultValue(160)
        .range(20, 255)
        .sliderRange(20, 255)
        .visible(() -> renderMode.get() == RenderMode.Blocks)
        .build()
    );

    private final Setting<Integer> minLitBlocks = sgRender.add(new IntSetting.Builder()
        .name("min-lit-blocks")
        .description("Sections mode: lit blocks a section needs before it is drawn.")
        .defaultValue(6)
        .range(1, 4096)
        .sliderRange(1, 500)
        .visible(() -> renderMode.get() == RenderMode.Sections)
        .build()
    );

    private final Setting<Integer> sectionDistance = sgRender.add(new IntSetting.Builder()
        .name("section-render-distance")
        .description("Sections mode: max distance in blocks to draw sections.")
        .defaultValue(160)
        .range(16, 512)
        .sliderRange(16, 320)
        .visible(() -> renderMode.get() == RenderMode.Sections)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Sections mode: how the boxes are rendered.")
        .defaultValue(ShapeMode.Lines)
        .visible(() -> renderMode.get() == RenderMode.Sections)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Sections mode: color of the box sides.")
        .defaultValue(new SettingColor(255, 230, 50, 25))
        .visible(() -> renderMode.get() == RenderMode.Sections)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Sections mode: color of the box outline.")
        .defaultValue(new SettingColor(255, 230, 50, 200))
        .visible(() -> renderMode.get() == RenderMode.Sections)
        .build()
    );

    private final Setting<Boolean> seeThroughWalls = sgRender.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Draw through walls. When off, only what is in your line of sight is drawn.")
        .defaultValue(true)
        .build()
    );

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile List<LitBlock> blocks = List.of();
    private volatile List<LitBlock> visibleBlocks = List.of();
    private volatile List<LightSection> sections = List.of();
    private ExecutorService executor;
    private int timer;
    private int visibilityTimer;
    private double lastScanX = Double.NaN;
    private double lastScanZ = Double.NaN;

    private final Color[] levelColors = new Color[16];
    private int cachedAlpha = -1;

    public LightFinder() {
        super(ChuklAddon.CATEGORY, "light-finder", "Highlights underground light, which can reveal hidden bases.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LightFinder-Scan");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        clearData();
        timer = 0;
        visibilityTimer = 0;
        lastScanX = Double.NaN;
        lastScanZ = Double.NaN;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        clearData();
    }

    private void clearData() {
        blocks = List.of();
        visibleBlocks = List.of();
        sections = List.of();
        scanning.set(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        // Line-of-sight filter for Blocks mode, spread out and capped so it stays cheap
        if (!seeThroughWalls.get() && renderMode.get() == RenderMode.Blocks && --visibilityTimer <= 0) {
            visibilityTimer = 10;

            List<LitBlock> visible = new ArrayList<>();
            int checked = 0;
            for (LitBlock block : blocks) {
                if (checked++ >= MAX_LOS_CHECKS) break;
                if (LineOfSight.canSeeBlock(block.pos())) visible.add(block);
            }
            visibleBlocks = visible;
        }

        if (--timer > 0) return;
        // Standing still: the light around you rarely changes, so scan a third as often
        boolean still = !Double.isNaN(lastScanX) && Math.hypot(mc.player.getX() - lastScanX, mc.player.getZ() - lastScanZ) < 6;
        lastScanX = mc.player.getX();
        lastScanZ = mc.player.getZ();
        timer = scanInterval.get() * 20 * (still ? 3 : 1);

        if (!scanning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int radius = scanRadius.get();
        int top = maxY.get();
        int brightness = minBrightness.get();
        int minLit = minLitBlocks.get();
        boolean blockMode = renderMode.get() == RenderMode.Blocks;
        double maxDist = blockDistance.get();
        int limit = maxBlocks.get();

        executor.execute(() -> {
            try {
                ScanResult result = scan(world, chunkX, chunkZ, radius, top, brightness, minLit,
                    blockMode, px, py, pz, maxDist, limit);
                blocks = result.blocks();
                sections = result.sections();
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Light Finder scan failed", t);
            } finally {
                scanning.set(false);
            }
        });
    }

    /** Background thread: reads the client's block light data one section at a time. */
    private static ScanResult scan(ClientWorld world, int centerChunkX, int centerChunkZ, int radius, int top,
                                   int minBrightness, int minLit, boolean blockMode,
                                   double px, double py, double pz, double maxDist, int limit) {
        List<LitBlock> candidates = new ArrayList<>();
        List<LightSection> sectionList = new ArrayList<>();
        var lightView = world.getLightingProvider().get(LightType.BLOCK);
        int bottomSection = world.getBottomSectionCoord();
        double maxDistSq = maxDist * maxDist;
        double sectionLimit = maxDist + 14; // a section's center can be up to ~14 blocks from its edge
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                for (int sy = bottomSection; (sy << 4) < top; sy++) {
                    int minX = cx << 4, minY = sy << 4, minZ = cz << 4;

                    // Blocks mode: skip sections that are too far away to matter
                    if (blockMode) {
                        double sx = minX + 8 - px, sYd = minY + 8 - py, sz = minZ + 8 - pz;
                        if (sx * sx + sYd * sYd + sz * sz > sectionLimit * sectionLimit) continue;
                    }

                    ChunkNibbleArray array = lightView.getLightSection(ChunkSectionPos.from(cx, sy, cz));
                    if (array == null) continue;

                    int height = Math.min(16, top - minY);
                    int lit = 0;
                    int max = 0;

                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < height; y++) {
                                int level = array.get(x, y, z);
                                if (level < minBrightness) continue;

                                lit++;
                                if (level > max) max = level;

                                if (blockMode && candidates.size() < MAX_CANDIDATES) {
                                    double dx = minX + x + 0.5 - px, dy = minY + y + 0.5 - py, dz = minZ + z + 0.5 - pz;
                                    double distSq = dx * dx + dy * dy + dz * dz;
                                    if (distSq <= maxDistSq) {
                                        candidates.add(new LitBlock(pos.set(minX + x, minY + y, minZ + z).toImmutable(), level, distSq));
                                    }
                                }
                            }
                        }
                    }

                    if (!blockMode && lit >= minLit) {
                        sectionList.add(new LightSection(new Box(minX, minY, minZ, minX + 16, minY + height, minZ + 16), lit, max));
                    }
                }
            }
        }

        if (blockMode) {
            candidates.sort(Comparator.comparingDouble(LitBlock::distSq));
            if (candidates.size() > limit) candidates = new ArrayList<>(candidates.subList(0, limit));
        }

        return new ScanResult(candidates, sectionList);
    }

    /** One color per light level, dark yellow for dim light up to bright yellow for level 15. */
    private Color[] colorsFor(int alpha) {
        if (alpha != cachedAlpha) {
            for (int level = 0; level < 16; level++) {
                int v = (int) (level / 15.0 * 255);
                levelColors[level] = new Color(v, v, 51, alpha);
            }
            cachedAlpha = alpha;
        }
        return levelColors;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        if (renderMode.get() == RenderMode.Blocks) {
            List<LitBlock> list = seeThroughWalls.get() ? blocks : visibleBlocks;
            if (list.isEmpty()) return;

            Color[] colors = colorsFor(blockAlpha.get());

            int drawn = 0;
            for (LitBlock block : list) {
                if (drawn++ >= MAX_DRAWN) break; // keeps weak devices smooth
                Color color = colors[block.level()];
                event.renderer.box(block.pos(), color, color, ShapeMode.Lines, 0);
            }
            return;
        }

        List<LightSection> list = sections;
        if (list.isEmpty()) return;

        double maxDistSq = (double) sectionDistance.get() * sectionDistance.get();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        for (LightSection section : list) {
            Box box = section.box();

            double dx = (box.minX + box.maxX) / 2.0 - px;
            double dy = (box.minY + box.maxY) / 2.0 - py;
            double dz = (box.minZ + box.maxZ) / 2.0 - pz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            if (!seeThroughWalls.get() && !LineOfSight.canSeeBox(box)) continue;

            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );
        }
    }
}
