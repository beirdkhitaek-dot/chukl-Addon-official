package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seed Finder (ore pattern recorder, structure analyzer and a seed checker). A world seed decides where every ore vein generates, so the exact shape
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

    private final SettingGroup sgStruct = settings.createGroup("Structures");
    private final SettingGroup sgSolve = settings.createGroup("Solver");

    private final Setting<Boolean> structures = sgStruct.add(new BoolSetting.Builder()
        .name("analyze-structures").description("Also record structures (temples, igloos, monuments, outposts, strongholds, villages, nether fortresses ...) recognised from their blocks, to structures-<server>.jsonl. These are the input for the solver.")
        .defaultValue(true).build());

    private final Setting<String> candidates = sgSolve.add(new StringSetting.Builder()
        .name("candidate-seeds").description("Seeds to test, separated by commas or spaces. Numbers are used as they are, any other text is turned into a seed the way Minecraft does.")
        .defaultValue("").build());

    private final Setting<Boolean> useSearch = sgSolve.add(new BoolSetting.Builder()
        .name("search-range").description("Also test every number from search-from for search-million million seeds. This only finds seeds that happen to be inside that range, it can not find any seed.")
        .defaultValue(false).build());

    private final Setting<String> searchFrom = sgSolve.add(new StringSetting.Builder()
        .name("search-from").description("First number to test.")
        .defaultValue("0").visible(() -> useSearch.get()).build());

    private final Setting<Integer> searchMillion = sgSolve.add(new IntSetting.Builder()
        .name("search-million").description("How many million numbers to test. A phone does very roughly 2 to 5 million per second per thread.")
        .defaultValue(100).range(1, 100000).sliderRange(10, 2000).visible(() -> useSearch.get()).build());

    private final Setting<Integer> threads = sgSolve.add(new IntSetting.Builder()
        .name("solver-threads").description("Threads used by the range search. Fewer = cooler phone.")
        .defaultValue(2).range(1, 6).sliderRange(1, 4).visible(() -> useSearch.get()).build());

    private final Setting<Boolean> runSolver = sgSolve.add(new BoolSetting.Builder()
        .name("run-solver").description("Turn this on to start the solver with the structures recorded for this server. It switches itself off again. Turn the module off to stop a running search.")
        .defaultValue(false).build());

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
    private final Set<String> structSeen = new HashSet<>();
    private int structsWritten = 0;
    private final List<String> structBuffer = new ArrayList<>();
    private volatile boolean solving = false;
    private volatile boolean cancel = false;
    private volatile long tested = 0;
    private volatile long totalToTest = 0;
    private final List<Thread> workers = new ArrayList<>();

    public SeedFinder() {
        super(ChuklAddon.CATEGORY, "seed-finder",
            "Research: records ore veins and recognised structures from the chunks you load to files, and checks candidate seeds (or a number range) against the structures you found.");
    }

    @Override
    public void onActivate() {
        done.clear();
        queue.clear();
        buffer.clear();
        chunksScanned = 0;
        veinsWritten = 0;
        structsWritten = 0;
        structSeen.clear();
        cancel = false;
        ticks = 0;
        dimension = "";
        fileName = "";
        info("Recording ore veins to: " + ResearchFiles.folder().getAbsolutePath() + " (ore-veins-" + ResearchFiles.serverName() + ".jsonl)");
        info("Walk through chunks nobody has mined yet. Mined ores are missing from the data.");
    }

    @Override
    public void onDeactivate() {
        cancel = true;
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
        if (runSolver.get()) {
            runSolver.set(false);
            startSolver();
        }

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

        if (buffer.size() >= 50 || structBuffer.size() >= 5 || (ticks % 200 == 0 && (!buffer.isEmpty() || !structBuffer.isEmpty()))) flush();
    }

    private void scan(WorldChunk chunk, ChunkPos cp) {
        if (structures.get()) scanStructures(chunk, cp);
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

    // ---------- structure analyzer ----------

    private void scanStructures(WorldChunk chunk, ChunkPos cp) {
        boolean nether = dimension.endsWith("the_nether");
        if (!nether && !dimension.endsWith("overworld")) return;
        Map<Block, int[]> found = new HashMap<>(); // block -> {count, x, y, z} of the first one
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection sec = sections[i];
            if (sec == null || sec.isEmpty() || !sec.hasAny(st -> SeedStructures.ALL.contains(st.getBlock()))) continue;
            int sy = bottom + i * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block b = sec.getBlockState(x, y, z).getBlock();
                if (!SeedStructures.ALL.contains(b)) continue;
                int[] f = found.get(b);
                if (f == null) found.put(b, new int[]{1, cp.getStartX() + x, sy + y, cp.getStartZ() + z});
                else f[0]++;
            }
        }
        if (found.isEmpty()) return;
        for (SeedStructures.Sig sig : SeedStructures.SIGS) {
            if (sig.nether() != nether) continue;
            int[] anchor = null;
            boolean all = true;
            StringBuilder counts = new StringBuilder();
            for (Block b : sig.required()) {
                int[] f = found.get(b);
                if (f == null) { all = false; break; }
                if (anchor == null) anchor = f;
                if (counts.length() > 0) counts.append(',');
                counts.append(ResearchFiles.json(b.getTranslationKey())).append(':').append(f[0]);
            }
            if (!all || anchor == null) continue;
            if (!structSeen.add(sig.kind() + "@" + cp.x + "," + cp.z)) continue;
            structBuffer.add("{\"type\":\"structure\",\"kind\":" + ResearchFiles.json(sig.kind())
                + ",\"dimension\":" + ResearchFiles.json(dimension)
                + ",\"cx\":" + cp.x + ",\"cz\":" + cp.z
                + ",\"x\":" + anchor[1] + ",\"y\":" + anchor[2] + ",\"z\":" + anchor[3]
                + ",\"blocks\":{" + counts + "}}");
            structsWritten++;
        }
    }

    // ---------- solver ----------

    private static final Pattern P_STRUCT = Pattern.compile("\"type\":\"structure\",\"kind\":\"([a-z_]+)\",\"dimension\":\"([^\"]+)\",\"cx\":(-?\\d+),\"cz\":(-?\\d+)");

    /** Structures recorded for this server so far (this session and earlier ones), merged per structure. */
    private List<SeedStructures.Obs> loadObservations() {
        flush();
        List<SeedStructures.Obs> raw = new ArrayList<>();
        File f = new File(ResearchFiles.folder(), "structures-" + ResearchFiles.serverName() + ".jsonl");
        for (File file : new File[]{f}) {
            if (!file.exists()) continue;
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.contains("\"type\":\"structure\"")) continue;
                    Matcher m = P_STRUCT.matcher(line);
                    if (!m.find() || !m.group(2).endsWith("overworld")) continue;
                    if (SeedStructures.place(m.group(1)) == null) continue;
                    raw.add(new SeedStructures.Obs(m.group(1), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
                }
            } catch (Exception e) {
                warning("Could not read " + file.getName() + ": " + e.getMessage());
            }
        }
        return SeedStructures.merge(raw);
    }

    private static long parseSeed(String t) {
        String s = t.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode(); // how Minecraft turns a text seed into a number
        }
    }

    private void startSolver() {
        if (solving) {
            info("The solver is already running. Turn the module off to stop it.");
            return;
        }
        List<SeedStructures.Obs> obs = loadObservations();
        if (obs.size() < 3) {
            warning("The solver needs at least 3 structures of the kinds desert temple, jungle temple, igloo, swamp hut, outpost or monument. Found " + obs.size()
                + ". Explore with the module on (it writes structures-<server>.jsonl) and try again.");
            return;
        }
        info("Solver: " + obs.size() + " structures to explain.");
        for (SeedStructures.Obs o : obs) info("  " + o.kind() + " near chunk " + (int) Math.round(o.cx()) + ", " + (int) Math.round(o.cz()));

        List<Long> cand = new ArrayList<>();
        for (String part : candidates.get().split("[,;\\s]+")) if (!part.isBlank()) cand.add(parseSeed(part));
        long from = 0;
        long count = 0;
        if (useSearch.get()) {
            from = parseSeed(searchFrom.get());
            count = searchMillion.get() * 1_000_000L;
        }
        if (cand.isEmpty() && count == 0) {
            warning("Nothing to test. Put seeds in candidate-seeds, or turn on search-range.");
            return;
        }

        solving = true;
        cancel = false;
        tested = 0;
        totalToTest = cand.size() + count;
        workers.clear();

        final long fromF = from, countF = count;
        final int nThreads = Math.max(1, threads.get());
        Thread master = new Thread(() -> runSolver(obs, cand, fromF, countF, nThreads), "chukl-seed-solver");
        master.setDaemon(true);
        master.start();
    }

    private void runSolver(List<SeedStructures.Obs> obs, List<Long> cand, long from, long count, int nThreads) {
        long startMs = System.currentTimeMillis();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        int need = Math.max(3, (int) Math.ceil(obs.size() * 0.8));
        try {
            SeedStructures.Lcg rnd = new SeedStructures.Lcg();
            int[] tmp = new int[2];
            for (long seed : cand) {
                int sc = SeedStructures.score(rnd, tmp, obs, seed);
                tested++;
                results.add("candidate " + seed + " explains " + sc + " of " + obs.size());
                if (sc >= need) mc.execute(() -> info("Seed " + seed + " explains " + sc + " of " + obs.size() + " structures. This is a strong match."));
            }

            if (count > 0 && !cancel) {
                List<Thread> ts = new ArrayList<>();
                long per = count / nThreads;
                for (int t = 0; t < nThreads; t++) {
                    final long a = from + per * t;
                    final long b = t == nThreads - 1 ? from + count : a + per;
                    Thread w = new Thread(() -> searchRange(obs, a, b, need, results), "chukl-seed-solver-" + t);
                    w.setDaemon(true);
                    ts.add(w);
                    w.start();
                }
                for (Thread w : ts) w.join();
            }
        } catch (Throwable t) {
            mc.execute(() -> warning("Solver stopped: " + t.getMessage()));
        } finally {
            solving = false;
            long sec = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
            long n = tested;
            boolean stopped = cancel;
            List<String> copy;
            synchronized (results) { copy = new ArrayList<>(results); }
            long strong = copy.stream().filter(r -> r.startsWith("match")).count();
            ResearchFiles.appendLines("seed-solver-" + ResearchFiles.serverName() + ".txt", copy);
            mc.execute(() -> info("Solver " + (stopped ? "stopped" : "finished") + ": tested " + n + " seeds in " + sec + " s, "
                + strong + " strong matches. Details in seed-solver-" + ResearchFiles.serverName() + ".txt"));
        }
    }

    private void searchRange(List<SeedStructures.Obs> obs, long from, long to, int need, List<String> results) {
        SeedStructures.Lcg rnd = new SeedStructures.Lcg();
        int[] tmp = new int[2];
        int total = obs.size();
        long local = 0;
        for (long seed = from; seed < to; seed++) {
            if (cancel) break;
            // quick reject: a real seed explains (nearly) all of them, so stop as soon as too many have failed
            int miss = 0, maxMiss = total - need;
            boolean ok = true;
            for (int i = 0; i < total; i++) {
                if (!SeedStructures.explains(rnd, tmp, obs.get(i), seed) && ++miss > maxMiss) { ok = false; break; }
            }
            if (ok) {
                final long s = seed;
                results.add("match " + s + " (explains at least " + need + " of " + total + ")");
                mc.execute(() -> info("Match: seed " + s + " explains at least " + need + " of " + total + " structures."));
            }
            if ((++local & 0xFFFFF) == 0) { tested += 0x100000; }
        }
        tested += local & 0xFFFFF;
    }

    private void flush() {
        if (!structBuffer.isEmpty()) {
            String e2 = ResearchFiles.appendLines("structures-" + ResearchFiles.serverName() + ".jsonl", new ArrayList<>(structBuffer));
            structBuffer.clear();
            if (!e2.isEmpty() && !e2.equals(lastError)) warning(e2);
        }
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
        if (solving) return "solving " + (totalToTest == 0 ? 0 : (int) (100.0 * tested / totalToTest)) + "%";
        return chunksScanned + " chunks, " + veinsWritten + " veins, " + structsWritten + " structures";
    }
}
