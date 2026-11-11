package com.chukl.addon.scan;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared scanner behind Chukl Chunk Finder and Prime Chunk Finder (v1.7).
 *
 * It never walks the world and never uses a thread: every tick it looks at a few of the chunks the game has already
 * loaded and reads what is cheap to read, namely the block entities (chests, barrels, shulkers...) and, only when a
 * section's palette says it could contain one, a short list of blocks that nature does not place underground
 * (torches, signs, beds...). Each chunk ends up with one score. Everything runs on the game thread in a small
 * per-tick budget, so it cannot stall a frame.
 */
public final class BaseScanner {
    /** What was found in one chunk. score is the weighted evidence, lowestY the deepest thing that counted. */
    public record Evidence(int cx, int cz, double score, int storage, int special, int unnatural, int lowestY) {
        public int blockX() { return cx * 16 + 8; }
        public int blockZ() { return cz * 16 + 8; }
    }

    public enum Kind { CHEST, TRAPPED_CHEST, BARREL, SHULKER, ENDER_CHEST, HOPPER, FURNACE, DISPENSER }

    private static final Set<Block> UNNATURAL = Set.of(
        Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH,
        Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN,
        Blocks.CRAFTING_TABLE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN
    );

    private static boolean isUnnatural(BlockState st) {
        return UNNATURAL.contains(st.getBlock()) || st.isIn(BlockTags.ALL_SIGNS) || st.isIn(BlockTags.BEDS);
    }

    /** The storage type of a block, or null when it is not one we track. */
    public static Kind kindOf(Block b) {
        if (b == Blocks.CHEST) return Kind.CHEST;
        if (b == Blocks.TRAPPED_CHEST) return Kind.TRAPPED_CHEST;
        if (b == Blocks.BARREL) return Kind.BARREL;
        if (b == Blocks.ENDER_CHEST) return Kind.ENDER_CHEST;
        if (b == Blocks.HOPPER) return Kind.HOPPER;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return Kind.FURNACE;
        if (b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.CRAFTER) return Kind.DISPENSER;
        if (b instanceof ShulkerBoxBlock) return Kind.SHULKER;
        return null;
    }

    /** How much one block of this kind says "a player lives here". Natural loot chests are common, so chests count least. */
    private static double weight(Kind k) {
        return switch (k) {
            case CHEST -> 1.0;
            case TRAPPED_CHEST -> 2.0;
            case BARREL -> 2.0;
            case SHULKER -> 4.0;
            case ENDER_CHEST -> 3.0;
            case HOPPER -> 3.0;
            case FURNACE -> 1.0;
            case DISPENSER -> 2.0;
        };
    }

    /** Bases are dug in, so deep things count more and surface things (which you can simply see) count less. */
    private static double depthMultiplier(int y) {
        if (y < 0) return 1.5;
        if (y < 40) return 1.2;
        if (y < 64) return 0.8;
        return 0.4;
    }

    private final Map<Long, Evidence> evidence = new HashMap<>();
    private final Map<Long, Long> scannedAt = new HashMap<>();
    private final List<Long> order = new ArrayList<>();
    private int cursor;
    private int orderTimer;
    private int version;
    private int snapshotVersion = -1;
    private List<Evidence> snapshot = List.of();

    public void clear() {
        evidence.clear();
        scannedAt.clear();
        order.clear();
        cursor = 0;
        orderTimer = 0;
        version++;
    }

    private static long key(int cx, int cz) { return ChunkPos.toLong(cx, cz); }

    /**
     * @param radius        chunks around the player to look at
     * @param budget        chunks to (re)scan this tick
     * @param rescanMs      how old a chunk's result may get before it is looked at again
     * @param unnaturalMaxY only blocks below this Y count as "not natural"
     */
    public void tick(ClientWorld world, ChunkPos center, int radius, int budget, long rescanMs, int unnaturalMaxY) {
        if (--orderTimer <= 0) {
            orderTimer = 20;
            rebuildOrder(world, center, radius);
        }
        if (order.isEmpty()) return;

        long now = System.currentTimeMillis();
        int done = 0;
        for (int step = 0; step < order.size() && done < budget; step++) {
            if (cursor >= order.size()) cursor = 0;
            long k = order.get(cursor++);
            Long last = scannedAt.get(k);
            if (last != null && now - last < rescanMs) continue;

            int cx = ChunkPos.getPackedX(k);
            int cz = ChunkPos.getPackedZ(k);
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) continue;

            scannedAt.put(k, now);
            Evidence e = scan(chunk, cx, cz, unnaturalMaxY);
            Evidence old = e == null ? evidence.remove(k) : evidence.put(k, e);
            if (e != null ? !e.equals(old) : old != null) version++;
            done++;
        }
    }

    private void rebuildOrder(ClientWorld world, ChunkPos center, int radius) {
        order.clear();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x + dx, cz = center.z + dz;
                if (world.getChunkManager().getWorldChunk(cx, cz) != null) order.add(key(cx, cz));
            }
        }
        order.sort(Comparator.comparingInt(k -> {
            int dx = ChunkPos.getPackedX(k) - center.x, dz = ChunkPos.getPackedZ(k) - center.z;
            return dx * dx + dz * dz;
        }));
        cursor = 0;

        int keep = radius + 2;
        boolean removed = evidence.keySet().removeIf(k ->
            Math.abs(ChunkPos.getPackedX(k) - center.x) > keep || Math.abs(ChunkPos.getPackedZ(k) - center.z) > keep);
        scannedAt.keySet().removeIf(k ->
            Math.abs(ChunkPos.getPackedX(k) - center.x) > keep || Math.abs(ChunkPos.getPackedZ(k) - center.z) > keep);
        if (removed) version++;
    }

    private Evidence scan(WorldChunk chunk, int cx, int cz, int unnaturalMaxY) {
        double score = 0;
        int storage = 0, special = 0, unnatural = 0;
        int lowest = Integer.MAX_VALUE;

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            Kind kind = kindOf(be.getCachedState().getBlock());
            if (kind == null) continue;
            int y = be.getPos().getY();
            score += weight(kind) * depthMultiplier(y);
            storage++;
            if (kind != Kind.CHEST && kind != Kind.FURNACE) special++;
            if (y < lowest) lowest = y;
        }

        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        for (int i = 0; i < sections.length && unnatural < 40; i++) {
            ChunkSection s = sections[i];
            int sectionY = bottom + i * 16;
            if (s == null || s.isEmpty() || sectionY > unnaturalMaxY) continue;
            if (!s.hasAny(BaseScanner::isUnnatural)) continue;
            for (int y = 0; y < 16 && unnatural < 40; y++) {
                if (sectionY + y > unnaturalMaxY) break;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (isUnnatural(s.getBlockState(x, y, z))) {
                            unnatural++;
                            if (sectionY + y < lowest) lowest = sectionY + y;
                        }
                    }
                }
            }
        }
        score += Math.min(unnatural, 40) * 0.25;

        if (score <= 0) return null;
        return new Evidence(cx, cz, score, storage, special, unnatural, lowest == Integer.MAX_VALUE ? 0 : lowest);
    }

    /** All chunks with evidence, rebuilt only when something changed. Read this on the game thread. */
    public List<Evidence> snapshot() {
        if (snapshotVersion != version) {
            snapshot = List.copyOf(evidence.values());
            snapshotVersion = version;
        }
        return snapshot;
    }

    public Evidence at(int cx, int cz) { return evidence.get(key(cx, cz)); }
}
