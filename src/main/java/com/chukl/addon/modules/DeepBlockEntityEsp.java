package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.utils.LineOfSight;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockDataSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.ESPBlockData;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Highlights block entities (chests, barrels, spawners, furnaces, etc.) below a
 * configurable Y level (default Y15). Each block you add gets its own shape mode,
 * colors and tracer. Blocks you haven't added use the default config if
 * "highlight-unlisted" is on.
 *
 * Custom Blocks: also highlight ANY block you add (ores, obsidian, whatever),
 * not just block entities, each with its own colors and tracer.
 */
public class DeepBlockEntityEsp extends Module {
    private static final int MAX_CUSTOM_FOUND = 5000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCustom = settings.createGroup("Custom Blocks");

    // --- Block entities (unchanged behavior) ---

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only highlight block entities below this Y level.")
        .defaultValue(15)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Boolean> seeThroughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Draw block entities through walls. When off, only ones in your line of sight are drawn.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> highlightUnlisted = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-unlisted")
        .description("Also highlight block entities you haven't added below, using the default config.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ESPBlockData> defaultBlockConfig = sgGeneral.add(new GenericSetting.Builder<ESPBlockData>()
        .name("default-block-config")
        .description("Default shape, colors and tracer.")
        .defaultValue(new ESPBlockData(
            ShapeMode.Both,
            new SettingColor(255, 170, 0, 255),
            new SettingColor(255, 170, 0, 40),
            true,
            new SettingColor(255, 170, 0, 150)
        ))
        .build()
    );

    private final Setting<Map<Block, ESPBlockData>> blockConfigs = sgGeneral.add(new BlockDataSetting.Builder<ESPBlockData>()
        .name("block-configs")
        .description("Pick blocks and set a custom color/tracer for each one.")
        .defaultData(defaultBlockConfig)
        .build()
    );

    // --- Custom blocks (any block, not just block entities) ---

    private final Setting<ESPBlockData> customDefaultConfig = sgCustom.add(new GenericSetting.Builder<ESPBlockData>()
        .name("custom-default-config")
        .description("Default shape, colors and tracer for newly added custom blocks.")
        .defaultValue(new ESPBlockData(
            ShapeMode.Both,
            new SettingColor(60, 255, 120, 255),
            new SettingColor(60, 255, 120, 40),
            false,
            new SettingColor(60, 255, 120, 150)
        ))
        .build()
    );

    private final Setting<Map<Block, ESPBlockData>> customBlocks = sgCustom.add(new BlockDataSetting.Builder<ESPBlockData>()
        .name("custom-blocks")
        .description("Add ANY block here (ores, obsidian, ...) and set its own color/tracer.")
        .defaultData(customDefaultConfig)
        .build()
    );

    private final Setting<Integer> customRadius = sgCustom.add(new IntSetting.Builder()
        .name("custom-scan-radius")
        .description("Chunks around you to scan for custom blocks.")
        .defaultValue(3)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> customMaxY = sgCustom.add(new IntSetting.Builder()
        .name("custom-max-y")
        .description("Only highlight custom blocks below this Y level.")
        .defaultValue(15)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private record Found(BlockPos pos, Block block) {}

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile List<Found> found = List.of();
    private ExecutorService executor;
    private int timer;

    public DeepBlockEntityEsp() {
        super(ChuklAddon.CATEGORY, "deep-block-entity-esp", "Highlights block entities below a set Y level, with per-block colors and tracers. Also supports custom blocks.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DeepBlockEntityEsp-Scan");
            t.setDaemon(true);
            return t;
        });
        found = List.of();
        scanning.set(false);
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        found = List.of();
        scanning.set(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        // Nothing to scan for
        if (customBlocks.get().isEmpty()) {
            found = List.of();
            return;
        }

        if (--timer > 0) return;
        timer = 60; // 3 seconds

        if (!scanning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;
        int radius = customRadius.get();
        int lowY = world.getBottomY();
        int highY = customMaxY.get() - 1;
        Set<Block> targets = new HashSet<>(customBlocks.get().keySet());

        executor.execute(() -> {
            try {
                found = scanCustom(world, chunkX, chunkZ, radius, lowY, highY, targets);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Deep Block Entity ESP custom scan failed", t);
            } finally {
                scanning.set(false);
            }
        });
    }

    /** Runs on the background thread. */
    private static List<Found> scanCustom(ClientWorld world, int centerChunkX, int centerChunkZ, int radius,
                                          int lowY, int highY, Set<Block> targets) {
        List<Found> result = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
                net.minecraft.world.chunk.WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
                int bottom = chunk.getBottomSectionCoord();

                int bx = cx << 4, bz = cz << 4;

                for (int i = 0; i < sections.length; i++) {
                    int secY = (bottom + i) << 4;
                    if (secY + 15 < lowY || secY > highY) continue;
                    // palette check: a layer that holds none of the target blocks is skipped without looking at its blocks
                    net.minecraft.world.chunk.ChunkSection sec = sections[i];
                    if (sec == null || sec.isEmpty() || !sec.hasAny(st -> targets.contains(st.getBlock()))) continue;

                    int y0 = Math.max(lowY, secY), y1 = Math.min(highY, secY + 15);
                    for (int x = bx; x < bx + 16; x++) {
                        for (int z = bz; z < bz + 16; z++) {
                            for (int y = y0; y <= y1; y++) {
                                Block block = sec.getBlockState(x & 15, y & 15, z & 15).getBlock();
                                if (!targets.contains(block)) continue;

                                result.add(new Found(new BlockPos(x, y, z), block));
                                if (result.size() >= MAX_CUSTOM_FOUND) return result;
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;

        // ---- Block entities (same as before) ----
        int limit = maxY.get();
        Map<Block, ESPBlockData> configs = blockConfigs.get();

        for (BlockEntity blockEntity : Utils.blockEntities()) {
            BlockPos pos = blockEntity.getPos();
            if (pos.getY() >= limit) continue;
            if (!seeThroughWalls.get() && !LineOfSight.canSeeBlock(pos)) continue;

            Block block = blockEntity.getCachedState().getBlock();
            ESPBlockData data = configs.get(block);

            if (data == null) {
                if (!highlightUnlisted.get()) continue;
                data = defaultBlockConfig.get();
            }

            event.renderer.box(pos, data.sideColor, data.lineColor, data.shapeMode, 0);

            if (data.tracer) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    data.tracerColor
                );
            }
        }

        // ---- Custom blocks ----
        List<Found> list = found;
        if (list.isEmpty()) return;

        Map<Block, ESPBlockData> custom = customBlocks.get();

        for (Found f : list) {
            ESPBlockData data = custom.get(f.block());
            if (data == null) continue;

            BlockPos pos = f.pos();
            if (!seeThroughWalls.get() && !LineOfSight.canSeeBlock(pos)) continue;

            event.renderer.box(pos, data.sideColor, data.lineColor, data.shapeMode, 0);

            if (data.tracer) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    data.tracerColor
                );
            }
        }
    }
}
