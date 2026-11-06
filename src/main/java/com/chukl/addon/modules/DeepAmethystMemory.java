package com.chukl.addon.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side memory of grown amethyst clusters, shared by the Sus Chunk modules.
 *
 * It only ever uses blocks the server has already sent to your game (the client's own world copy). It sends no
 * packets and asks the server for nothing. A cluster is remembered the moment its layer arrives, and keeps being drawn
 * after you walk away or go back up. It is forgotten when you change world or when you can see that the block is gone
 * (you mined it).
 */
final class DeepAmethystMemory {
    static final String NOTICE = "Deep layers not loaded here, dig below Y 30 to load them";

    private final Map<ChunkPos, Set<BlockPos>> found = new ConcurrentHashMap<>();
    private volatile ClientWorld world;
    private List<ChunkPos> sorted;
    private ChunkPos sortedFor;
    private int sortedSize;
    private long sortedAt;

    /** Remember clusters found in a chunk. The memory starts empty again when the world changes. */
    void remember(ClientWorld w, ChunkPos cp, Collection<BlockPos> blocks) {
        if (w == null || blocks == null || blocks.isEmpty()) return;
        if (world != w) { found.clear(); world = w; }
        found.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet()).addAll(blocks);
    }

    void clearIfNewWorld(ClientWorld w) {
        if (world != w) { found.clear(); world = w; }
    }

    int count() {
        int n = 0;
        for (Set<BlockPos> s : found.values()) n += s.size();
        return n;
    }

    int chunks() {
        return found.size();
    }

    /** Draws boxes on every remembered cluster in the Y range, nearest first, through walls. */
    void render(Render3DEvent event, MinecraftClient mc, int minY, int maxY, Color fill, int cap) {
        if (found.isEmpty() || mc.world == null || mc.player == null) return;
        clearIfNewWorld(mc.world);
        int lo = Math.min(minY, maxY), hi = Math.max(minY, maxY);
        ChunkPos here = mc.player.getChunkPos();
        // sorting is the expensive part, so it is redone only when you change chunk, a chunk is added, or every half second
        long now = System.currentTimeMillis();
        List<ChunkPos> chunks = sorted;
        if (chunks == null || !here.equals(sortedFor) || sortedSize != found.size() || now - sortedAt > 500) {
            chunks = new ArrayList<>(found.keySet());
            chunks.sort(Comparator.comparingInt((ChunkPos c) ->
                (c.x - here.x) * (c.x - here.x) + (c.z - here.z) * (c.z - here.z)));
            sorted = chunks; sortedFor = here; sortedSize = found.size(); sortedAt = now;
        }
        var manager = mc.world.getChunkManager();
        int drawn = 0;
        for (ChunkPos cp : chunks) {
            Set<BlockPos> set = found.get(cp);
            if (set == null) continue;
            WorldChunk chunk = manager.isChunkLoaded(cp.x, cp.z) ? manager.getWorldChunk(cp.x, cp.z) : null;
            for (BlockPos p : set) {
                if (p.getY() < lo || p.getY() > hi) continue;
                if (chunk != null && wasMined(chunk, p)) { set.remove(p); continue; }
                if (drawn >= cap) return;
                event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    fill, fill, ShapeMode.Both, 0);
                drawn++;
            }
            if (set.isEmpty()) found.remove(cp);
        }
    }

    /** True only when the layer has real blocks and this one is no longer a cluster. An empty layer proves nothing. */
    private static boolean wasMined(WorldChunk chunk, BlockPos p) {
        ChunkSection[] arr = chunk.getSectionArray();
        int idx = (p.getY() >> 4) - chunk.getBottomSectionCoord();
        if (idx < 0 || idx >= arr.length || arr[idx] == null || arr[idx].isEmpty()) return false;
        BlockState s = chunk.getBlockState(p);
        return s.getBlock() != Blocks.AMETHYST_CLUSTER;
    }

    /**
     * True when the chunk you stand in has no blocks at all in the deep layers (below Y16) of the checked range,
     * which means the server has not sent them to you yet.
     */
    static boolean deepLayersMissing(MinecraftClient mc, int minY, int maxY) {
        if (mc.world == null || mc.player == null) return false;
        int lo = Math.min(minY, maxY), hi = Math.min(Math.max(minY, maxY), 15);
        if (lo > hi) return false;
        ChunkPos c = mc.player.getChunkPos();
        var manager = mc.world.getChunkManager();
        if (!manager.isChunkLoaded(c.x, c.z)) return false;
        WorldChunk chunk = manager.getWorldChunk(c.x, c.z);
        if (chunk == null) return false;
        ChunkSection[] arr = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        boolean any = false;
        for (int i = 0; i < arr.length; i++) {
            int base = (bottom + i) * 16;
            if (base + 15 < lo || base > hi) continue;
            any = true;
            if (arr[i] != null && !arr[i].isEmpty()) return false;
        }
        return any;
    }

    /** Static text near the top of the screen. Reads only what is already in the client's memory. */
    static void drawNotice(Render2DEvent event, MinecraftClient mc) {
        int w = mc.textRenderer.getWidth(NOTICE);
        int x = (mc.getWindow().getScaledWidth() - w) / 2;
        event.drawContext.drawTextWithShadow(mc.textRenderer, NOTICE, x, 36, 0xFFFF5555);
    }
}
