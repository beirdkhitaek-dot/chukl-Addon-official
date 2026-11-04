package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seed Finder, stage 1 (ore pattern recorder). A world seed decides where every ore vein generates, so the exact shape
 * and position of ore veins is the input a seed solver needs. This stage walks the chunks the server sends you, groups
 * the ore blocks of each chunk into veins and writes every vein (ore type, size, bounds and block positions) to
 * meteor-client/chukl-research/ore-veins-[server].jsonl. The solver that turns this data into a seed is not part of this
 * module yet.
 * Notes: ores that players already mined are missing from the data, and servers with anti-xray can hide or fake ores.
 */
public class SeedFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> perTick = sg.add(new IntSetting.Builder()
        .name("chunks-per-tick").description("How many new chunks are scanned each tick. Lower = lighter for phones.")
        .defaultValue(1).range(1, 8).sliderRange(1, 4).build());

    private final Setting<Integer> minY = sg.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y that is scanned.")
        .defaultValue(-64).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> maxY = sg.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y that is scanned.")
        .defaultValue(320).range(-64, 320).sliderRange(0, 320).build());

    private final Setting<Integer> maxBlocks = sg.add(new IntSetting.Builder()
        .name("max-blocks-per-vein").description("How many block positions are written for one vein (the count is always exact).")
        .defaultValue(64).range(1, 256).sliderRange(8, 128).build());

    private final Setting<Boolean> skipDone = sg.add(new BoolSetting.Builder()
        .name("scan-each-chunk-once").description("Do not scan a chunk again when it unloads and loads back during this session.")
        .defaultValue(true).build());

    private static final Map<Block, String> ORES = new HashMap<>();
    static {
        ORES.put(Blocks.COAL_ORE, "coal");
        ORES.put(Blocks.DEEPSLATE_COAL_ORE, "coal");
        ORES.put(Blocks.IRON_ORE, "iron");
        ORES.put(Blocks.DEEPSLATE_IRON_ORE, "iron");
        ORES.put(Blocks.COPPER_ORE, "copper");
        ORES.put(Blocks.DEEPSLATE_COPPER_ORE, "copper");
        ORES.put(Blocks.GOLD_ORE, "gold");
        ORES.put(Blocks.DEEPSLATE_GOLD_ORE, "gold");
        ORES.put(Blocks.REDSTONE_ORE, "redstone");
        ORES.put(Blocks.DEEPSLATE_REDSTONE_ORE, "redstone");
        ORES.put(Blocks.LAPIS_ORE, "lapis");
        ORES.put(Blocks.DEEPSLATE_LAPIS_ORE, "lapis");
        ORES.put(Blocks.DIAMOND_ORE, "diamond");
        ORES.put(Blocks.DEEPSLATE_DIAMOND_ORE, "diamond");
        ORES.put(Blocks.EMERALD_ORE, "emerald");
        ORES.put(Blocks.DEEPSLATE_EMERALD_ORE, "emerald");
        ORES.put(Blocks.NETHER_GOLD_ORE, "nether_gold");
        ORES.put(Blocks.NETHER_QUARTZ_ORE, "quartz");
        ORES.put(Blocks.ANCIENT_DEBRIS, "ancient_debris");
    }

    private final Set<Long> done = new HashSet<>();
    private final List<ChunkPos> queue = new ArrayList<>();
    private final List<String> buffer = new ArrayList<>();
    private String fileName = "";
    private String dimension = "";
    private int chunksScanned = 0, veinsWritten = 0, ticks = 0;
    private String lastError = "";

    public SeedFinder() {
        super(ChuklAddon.CATEGORY, "seed-finder",
            "Research, stage 1: records ore veins (type, size, positions) from the chunks you load to a file, as input for a seed solver. The solver itself is not included yet.");
    }

    @Override
    public void onActivate() {
        done.clear();
        queue.clear();
        buffer.clear();
        chunksScanned = 0;
        veinsWritten = 0;
        ticks = 0;
        dimension = "";
        fileName = "";
        info("Recording ore veins to: " + ResearchFiles.folder().getAbsolutePath() + " (ore-veins-" + ResearchFiles.serverName() + ".jsonl)");
        info("Walk through chunks nobody has mined yet. Mined ores are missing from the data.");
    }

    @Override
    public void onDeactivate() {
        flush();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        flush();
        done.clear();
        queue.clear();
        dimension = "";
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        ticks++;

        String dim = mc.world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) { // new world or dimension: start a new section in the file
            flush();
            dimension = dim;
            done.clear();
            queue.clear();
            fileName = "ore-veins-" + ResearchFiles.serverName() + ".jsonl";
            var entry = mc.getCurrentServerEntry();
            buffer.add("{\"type\":\"session\",\"time\":" + ResearchFiles.json(Instant.now().toString())
                + ",\"server\":" + ResearchFiles.json(entry != null ? entry.address : "singleplayer")
                + ",\"dimension\":" + ResearchFiles.json(dim)
                + ",\"clientVersion\":" + ResearchFiles.json(SharedConstants.getGameVersion().name())
                + ",\"bottomY\":" + mc.world.getBottomY() + ",\"height\":" + mc.world.getHeight() + "}");
        }

        // find chunks that arrived
        if (ticks % 10 == 0) {
            var manager = mc.world.getChunkManager();
            ChunkPos c = mc.player.getChunkPos();
            int r = mc.options.getViewDistance().getValue();
            // without scan-each-chunk-once, a chunk that unloads is forgotten so it is scanned again when it comes back
            if (!skipDone.get()) done.removeIf(l -> !manager.isChunkLoaded(ChunkPos.getPackedX(l), ChunkPos.getPackedZ(l)));
            Set<ChunkPos> queued = new HashSet<>(queue);
            for (int cx = c.x - r; cx <= c.x + r; cx++) {
                for (int cz = c.z - r; cz <= c.z + r; cz++) {
                    if (!manager.isChunkLoaded(cx, cz)) continue;
                    ChunkPos cp = new ChunkPos(cx, cz);
                    if (done.contains(cp.toLong()) || queued.contains(cp)) continue;
                    queue.add(cp);
                }
            }
            ChunkPos here = c;
            queue.sort(Comparator.comparingInt(p -> (p.x - here.x) * (p.x - here.x) + (p.z - here.z) * (p.z - here.z)));
        }

        int n = perTick.get();
        while (n-- > 0 && !queue.isEmpty()) {
            ChunkPos cp = queue.remove(0);
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk == null) continue;
            done.add(cp.toLong());
            scan(chunk, cp);
            chunksScanned++;
        }

        if (buffer.size() >= 50 || (ticks % 200 == 0 && !buffer.isEmpty())) flush();
    }

    private void scan(WorldChunk chunk, ChunkPos cp) {
        int lo = Math.min(minY.get(), maxY.get()), hi = Math.max(minY.get(), maxY.get());
        // x, y, z packed -> ore kind
        Map<Long, String> ores = new HashMap<>();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            int sy = bottom + i * 16;
            if (sy + 15 < lo || sy > hi) continue;
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> ORES.containsKey(st.getBlock()))) continue;
            for (int y = 0; y < 16; y++) {
                int by = sy + y;
                if (by < lo || by > hi) continue;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    String kind = ORES.get(s.getBlockState(x, y, z).getBlock());
                    if (kind != null) ores.put(pack(x, by, z), kind);
                }
            }
        }
        if (ores.isEmpty()) return;

        // group touching ore blocks of the same kind into veins (26 neighbors, like generated veins)
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, String> e : ores.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            String kind = e.getValue();
            List<Long> vein = new ArrayList<>();
            ArrayDeque<Long> stack = new ArrayDeque<>();
            stack.push(e.getKey());
            seen.add(e.getKey());
            while (!stack.isEmpty()) {
                long p = stack.pop();
                vein.add(p);
                int x = ux(p), y = uy(p), z = uz(p);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                    long q = pack(nx, ny, nz);
                    if (seen.contains(q) || !kind.equals(ores.get(q))) continue;
                    seen.add(q);
                    stack.push(q);
                }
            }
            buffer.add(veinLine(cp, kind, vein));
            veinsWritten++;
        }
    }

    private String veinLine(ChunkPos cp, String kind, List<Long> vein) {
        int minX = 99, minY2 = 9999, minZ = 99, maxX = -1, maxY2 = -9999, maxZ = -1;
        for (long p : vein) {
            minX = Math.min(minX, ux(p)); maxX = Math.max(maxX, ux(p));
            minY2 = Math.min(minY2, uy(p)); maxY2 = Math.max(maxY2, uy(p));
            minZ = Math.min(minZ, uz(p)); maxZ = Math.max(maxZ, uz(p));
        }
        vein.sort(Comparator.comparingLong(Long::longValue));
        StringBuilder sb = new StringBuilder("{\"type\":\"vein\"");
        sb.append(",\"dimension\":").append(ResearchFiles.json(dimension));
        sb.append(",\"cx\":").append(cp.x).append(",\"cz\":").append(cp.z);
        sb.append(",\"ore\":").append(ResearchFiles.json(kind));
        sb.append(",\"count\":").append(vein.size());
        sb.append(",\"minX\":").append(cp.getStartX() + minX).append(",\"minY\":").append(minY2).append(",\"minZ\":").append(cp.getStartZ() + minZ);
        sb.append(",\"maxX\":").append(cp.getStartX() + maxX).append(",\"maxY\":").append(maxY2).append(",\"maxZ\":").append(cp.getStartZ() + maxZ);
        sb.append(",\"blocks\":[");
        int lim = Math.min(maxBlocks.get(), vein.size());
        for (int i = 0; i < lim; i++) {
            long p = vein.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(cp.getStartX() + ux(p)).append(',').append(uy(p)).append(',').append(cp.getStartZ() + uz(p)).append(']');
        }
        return sb.append("]}").toString();
    }

    private void flush() {
        if (buffer.isEmpty() || fileName.isEmpty()) return;
        String err = ResearchFiles.appendLines(fileName, new ArrayList<>(buffer));
        buffer.clear();
        if (!err.isEmpty() && !err.equals(lastError)) warning(err);
        lastError = err;
    }

    // packed position inside a chunk: x (4 bits), z (4 bits), y (offset by 1024, 12 bits)
    private static long pack(int x, int y, int z) { return ((long) (y + 1024) << 8) | ((long) x << 4) | z; }
    private static int ux(long p) { return (int) ((p >> 4) & 15); }
    private static int uz(long p) { return (int) (p & 15); }
    private static int uy(long p) { return (int) (p >> 8) - 1024; }

    @Override
    public String getInfoString() {
        return chunksScanned + " chunks, " + veinsWritten + " veins";
    }
}
