package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server Data Collector: for research. Every few seconds it writes one line of technical facts about the server you are
 * on (software brand, version, real chunk radius, tick rate, your ping, packet rates, world border, time ...) to
 * meteor-client/chukl-research/server-data-[server].jsonl. It never records other players' names, chat or positions.
 */
public class ServerDataCollector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> interval = sg.add(new IntSetting.Builder()
        .name("interval-seconds").description("How often a line is written.")
        .defaultValue(30).range(5, 3600).sliderRange(10, 300).build());

    private final Setting<Boolean> packetStats = sg.add(new BoolSetting.Builder()
        .name("packet-stats").description("Include how many packets per second the server sends and the most common packet types.")
        .defaultValue(true).build());

    private final Setting<Boolean> chunkRadius = sg.add(new BoolSetting.Builder()
        .name("chunk-radius").description("Measure the real chunk radius the server sends you (can be smaller than your render distance).")
        .defaultValue(true).build());

    private final Setting<Boolean> anticheat = sg.add(new BoolSetting.Builder()
        .name("anticheat-indicators")
        .description("Passive observation only: counts how often the server moves you back (position corrections), notes anticheat names that appear in server messages (names only, never the text), and records the server's kick reason. Nothing is sent to the server and nothing is probed.")
        .defaultValue(true).build());

    private final Setting<Boolean> hidingCheck = sg.add(new BoolSetting.Builder()
        .name("block-hiding-check")
        .description("Finds out if the server hides ores from you (anti-xray): it looks at how many ores are buried inside rock. Runs quietly in the background.")
        .defaultValue(true).build());

    private final Setting<Boolean> revealTracking = sg.add(new BoolSetting.Builder()
        .name("late-reveal-tracking")
        .description("Counts ores the server shows you later, after the chunk was sent (what anti-xray does when you get close).")
        .defaultValue(true).build());

    private final Setting<Boolean> summaryFile = sg.add(new BoolSetting.Builder()
        .name("summary-file")
        .description("Also write a short, easy-to-read report (server-summary-NAME.txt) next to the data file.")
        .defaultValue(true).build());

    private final Setting<Boolean> writeOnJoin = sg.add(new BoolSetting.Builder()
        .name("write-on-join").description("Write a line right after you join a world or server.")
        .defaultValue(true).build());

    /** Anticheat product names that servers commonly show in messages. Only the matched name is ever stored. */
    private static final String[] AC_NAMES = {
        "nocheatplus", "grim", "vulcan", "matrix", "spartan", "verus", "intave", "themis", "polar", "karhu",
        "negativity", "aac", "anticheat", "anti-cheat", "antixray", "anti-xray", "watchdog", "hawk"
    };

    private static final Set<Block> ORES = new HashSet<>(List.of(
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.ANCIENT_DEBRIS));

    private ExecutorService scanner;
    private final Map<ChunkPos, WorldChunk> scanned = new ConcurrentHashMap<>();
    private final AtomicLong oresTotal = new AtomicLong(), oresBuried = new AtomicLong(), chunksScanned = new AtomicLong(),
        amethystClusters = new AtomicLong(), blockUpdates = new AtomicLong(), oresRevealed = new AtomicLong(), revealedTotal = new AtomicLong();
    private String lastVerdict = "not enough data yet", lastSoftware = "", lastSummaryServer = "";
    private int lastNear = -1, lastFar = -1, lastNearLow = 0, lastFarLow = 0, lastRadius = 0, lastCorrections = 0;
    private double lastBuriedPct = -1;
    private final Map<String, Integer> packetCounts = new HashMap<>();
    private final Map<String, Integer> acMentions = new HashMap<>();
    private int corrections = 0;
    private int sinceJoinTicks = 0;
    private long packetTotal = 0;
    private long lastWrite = 0;
    private int lines = 0;
    private boolean pendingJoin = false;
    private int joinDelay = 0;
    private String lastError = "";

    public ServerDataCollector() {
        super(ChuklAddon.CATEGORY, "server-data-collector",
            "Research: writes technical facts about the server (brand, version, chunk radius, tick rate, ping, packet rates, passive anticheat indicators) to a file. No player names, chat text or positions.");
    }

    @Override
    public void onActivate() {
        packetCounts.clear();
        acMentions.clear();
        corrections = 0;
        sinceJoinTicks = 0;
        packetTotal = 0;
        lastWrite = System.currentTimeMillis();
        lines = 0;
        pendingJoin = writeOnJoin.get();
        joinDelay = 60;
        scanner = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ServerData-Scan"); t.setDaemon(true); return t; });
        resetBlockStats();
        info("Writing to: " + ResearchFiles.folder().getAbsolutePath() + " (server-data-" + ResearchFiles.serverName() + ".jsonl)");
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) scanner.shutdownNow();
        scanner = null;
    }

    private void resetBlockStats() {
        scanned.clear();
        oresTotal.set(0); oresBuried.set(0); chunksScanned.set(0); amethystClusters.set(0);
        blockUpdates.set(0); oresRevealed.set(0); revealedTotal.set(0);
        lastVerdict = "not enough data yet";
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        resetBlockStats();
        sinceJoinTicks = 0;
        synchronized (acMentions) { acMentions.clear(); corrections = 0; }
        packetCounts.clear();
        packetTotal = 0;
        pendingJoin = writeOnJoin.get();
        joinDelay = 60;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        String name = event.packet.getClass().getSimpleName();
        if (anticheat.get()) {
            // the server teleporting you after the join sequence = it corrected your movement
            if (event.packet instanceof PlayerPositionLookS2CPacket && sinceJoinTicks > 200) {
                synchronized (acMentions) { corrections++; }
            }
            if (event.packet instanceof DisconnectS2CPacket d) {
                String reason = d.reason() == null ? "" : d.reason().getString();
                if (reason.length() > 200) reason = reason.substring(0, 200);
                ResearchFiles.appendLine("server-data-" + ResearchFiles.serverName() + ".jsonl",
                    "{\"type\":\"kick\",\"time\":" + ResearchFiles.json(Instant.now().toString())
                        + ",\"reason\":" + ResearchFiles.json(reason) + "}");
            }
        }
        if (revealTracking.get()) {
            if (event.packet instanceof BlockUpdateS2CPacket b) {
                blockUpdates.incrementAndGet();
                if (ORES.contains(b.getState().getBlock())) { oresRevealed.incrementAndGet(); revealedTotal.incrementAndGet(); }
            } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket d) {
                d.visitUpdates((pos, state) -> {
                    blockUpdates.incrementAndGet();
                    if (ORES.contains(state.getBlock())) { oresRevealed.incrementAndGet(); revealedTotal.incrementAndGet(); }
                });
            }
        }
        if (!packetStats.get()) return;
        synchronized (packetCounts) {
            packetTotal++;
            packetCounts.merge(name, 1, Integer::sum);
        }
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!anticheat.get()) return;
        String text = event.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
        for (String n : AC_NAMES) {
            if (text.contains(n)) synchronized (acMentions) { acMentions.merge(n, 1, Integer::sum); }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;
        sinceJoinTicks++;
        long now = System.currentTimeMillis();
        if (pendingJoin) {
            if (--joinDelay > 0) return; // give the server a moment to send the world and chunks
            pendingJoin = false;
            write(now);
            return;
        }
        if (now - lastWrite >= interval.get() * 1000L) write(now);
    }

    private void write(long now) {
        double seconds = Math.max(1.0, (now - lastWrite) / 1000.0);
        lastWrite = now;
        try {
            String line = buildLine(seconds);
            String err = ResearchFiles.appendLine("server-data-" + ResearchFiles.serverName() + ".jsonl", line);
            if (!err.isEmpty()) {
                if (!err.equals(lastError)) warning(err);
                lastError = err;
            } else {
                lastError = "";
                lines++;
            }
        } catch (Throwable t) {
            warning("Could not collect data: " + t.getMessage());
        }
    }

    private String buildLine(double seconds) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"time\":").append(ResearchFiles.json(Instant.now().toString()));
        var entry = mc.getCurrentServerEntry();
        sb.append(",\"server\":").append(ResearchFiles.json(entry != null ? entry.address : "singleplayer"));
        sb.append(",\"brand\":").append(ResearchFiles.json(mc.getNetworkHandler().getBrand()));
        sb.append(",\"clientVersion\":").append(ResearchFiles.json(SharedConstants.getGameVersion().name()));
        sb.append(",\"dimension\":").append(ResearchFiles.json(mc.world.getRegistryKey().getValue().toString()));
        sb.append(",\"difficulty\":").append(ResearchFiles.json(mc.world.getDifficulty().getName()));
        sb.append(",\"timeOfDay\":").append(mc.world.getTimeOfDay());
        sb.append(",\"tickRate\":").append(String.format(java.util.Locale.ROOT, "%.2f", TickRate.INSTANCE.getTickRate()));

        PlayerListEntry me = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        if (me != null) sb.append(",\"pingMs\":").append(me.getLatency());
        sb.append(",\"playersOnline\":").append(mc.getNetworkHandler().getPlayerList().size());

        sb.append(",\"clientViewDistance\":").append(mc.options.getViewDistance().getValue());
        sb.append(",\"clientSimulationDistance\":").append(mc.options.getSimulationDistance().getValue());
        if (chunkRadius.get()) sb.append(",\"realChunkRadius\":").append(measureRadius());

        if (anticheat.get()) {
            int corr;
            Map<String, Integer> mentions;
            synchronized (acMentions) {
                corr = corrections;
                mentions = new HashMap<>(acMentions);
                corrections = 0;
                acMentions.clear();
            }
            sb.append(",\"positionCorrections\":").append(corr);
            sb.append(",\"anticheatNamesSeen\":{");
            int k = 0;
            for (Map.Entry<String, Integer> e : mentions.entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append(ResearchFiles.json(e.getKey())).append(':').append(e.getValue());
            }
            sb.append('}');
        }

        var border = mc.world.getWorldBorder();
        sb.append(",\"borderSize\":").append(String.format(java.util.Locale.ROOT, "%.1f", border.getSize()));
        sb.append(",\"borderCenterX\":").append(String.format(java.util.Locale.ROOT, "%.1f", border.getCenterX()));
        sb.append(",\"borderCenterZ\":").append(String.format(java.util.Locale.ROOT, "%.1f", border.getCenterZ()));

        if (packetStats.get()) {
            long total;
            List<Map.Entry<String, Integer>> top;
            synchronized (packetCounts) {
                total = packetTotal;
                top = new ArrayList<>(packetCounts.entrySet());
                packetTotal = 0;
                packetCounts.clear();
            }
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            sb.append(",\"packetsPerSecond\":").append(String.format(java.util.Locale.ROOT, "%.1f", total / seconds));
            sb.append(",\"topPackets\":{");
            for (int i = 0; i < Math.min(8, top.size()); i++) {
                if (i > 0) sb.append(',');
                sb.append(ResearchFiles.json(top.get(i).getKey())).append(':').append(top.get(i).getValue());
            }
            sb.append('}');
        }
        appendBlockFacts(sb, seconds);
        return sb.append('}').toString();
    }

    private static String softwareGuess(String brand) {
        String b = brand == null ? "" : brand.toLowerCase(java.util.Locale.ROOT);
        String[] names = {"folia", "purpur", "pufferfish", "leaf", "paper", "spigot", "bukkit", "velocity", "bungee", "waterfall", "fabric", "forge", "neoforge", "quilt", "vanilla"};
        for (String n : names) if (b.contains(n)) return n;
        return "unknown (custom brand)";
    }

    /** Block hiding check, late reveals, Y levels and the plain-language summary. */
    private void appendBlockFacts(StringBuilder sb, double seconds) {
        lastSoftware = softwareGuess(mc.getNetworkHandler().getBrand());
        sb.append(",\"softwareGuess\":").append(ResearchFiles.json(lastSoftware));

        if (hidingCheck.get()) {
            scheduleScans();
            long total = oresTotal.get(), buried = oresBuried.get();
            lastBuriedPct = total > 0 ? 100.0 * buried / total : -1;
            if (total < 30) lastVerdict = "not enough data yet (" + total + " ores seen so far, walk around for a while)";
            else if (lastBuriedPct < 15) lastVerdict = "HIDES buried ores: anti-xray is on, so ores deep in rock are not sent to you";
            else if (lastBuriedPct < 45) lastVerdict = "maybe hides some ores, or this area is full of caves";
            else lastVerdict = "no hiding seen: buried ores are sent normally";
            sb.append(",\"blockHiding\":{\"oresSeen\":").append(total)
                .append(",\"buriedPercent\":").append(String.format(java.util.Locale.ROOT, "%.1f", lastBuriedPct))
                .append(",\"chunksChecked\":").append(chunksScanned.get())
                .append(",\"verdict\":").append(ResearchFiles.json(lastVerdict)).append('}');
        }
        if (revealTracking.get()) {
            long upd = blockUpdates.getAndSet(0), rev = oresRevealed.getAndSet(0);
            sb.append(",\"blockUpdatesPerSecond\":").append(String.format(java.util.Locale.ROOT, "%.1f", upd / seconds));
            sb.append(",\"oresRevealedByUpdates\":").append(rev);
        }
        // does the server send the full height far away too?
        var manager = mc.world.getChunkManager();
        var c = mc.player.getChunkPos();
        lastRadius = measureRadius();
        int[] near = sections(manager.getWorldChunk(c.x, c.z));
        int[] far = {0, 0};
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int cx = c.x + d[0] * lastRadius, cz = c.z + d[1] * lastRadius;
            if (manager.isChunkLoaded(cx, cz)) { far = sections(manager.getWorldChunk(cx, cz)); break; }
        }
        lastNear = near[0]; lastNearLow = near[1]; lastFar = far[0]; lastFarLow = far[1];
        sb.append(",\"nonEmptySectionsNear\":").append(near[0]).append(",\"nonEmptySectionsFar\":").append(far[0]);
        sb.append(",\"lowestBlockYNear\":").append(near[1]).append(",\"lowestBlockYFar\":").append(far[1]);
        if (summaryFile.get()) writeSummary();
    }

    /** {number of sections that contain blocks, lowest Y that contains blocks}. */
    private static int[] sections(WorldChunk chunk) {
        if (chunk == null) return new int[]{0, 0};
        ChunkSection[] arr = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord(), n = 0, low = Integer.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null || arr[i].isEmpty()) continue;
            n++;
            low = Math.min(low, (bottom + i) * 16);
        }
        return new int[]{n, low == Integer.MAX_VALUE ? 0 : low};
    }

    private void scheduleScans() {
        ExecutorService ex = scanner;
        if (ex == null || mc.world == null) return;
        var manager = mc.world.getChunkManager();
        ChunkPos c = mc.player.getChunkPos();
        List<WorldChunk> todo = new ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (!manager.isChunkLoaded(c.x + dx, c.z + dz)) continue;
                WorldChunk ch = manager.getWorldChunk(c.x + dx, c.z + dz);
                if (ch == null || scanned.get(ch.getPos()) == ch) continue;
                todo.add(ch);
            }
        }
        todo.sort((a, b) -> Integer.compare(dist2(a.getPos(), c), dist2(b.getPos(), c)));
        ClientWorld world = mc.world;
        for (int i = 0; i < Math.min(12, todo.size()); i++) {
            WorldChunk ch = todo.get(i);
            scanned.put(ch.getPos(), ch);
            ex.execute(() -> {
                try { scanChunk(world, ch); } catch (Throwable ignored) {}
            });
        }
        if (scanned.size() > 600) scanned.keySet().removeIf(p -> !manager.isChunkLoaded(p.x, p.z));
    }

    private static int dist2(ChunkPos a, ChunkPos b) {
        return (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z);
    }

    /** Counts ores in the middle of the chunk and how many of them are completely surrounded by blocks. */
    private void scanChunk(ClientWorld world, WorldChunk chunk) {
        ChunkSection[] arr = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        int sx = chunk.getPos().getStartX(), sz = chunk.getPos().getStartZ();
        BlockPos.Mutable p = new BlockPos.Mutable();
        long total = 0, buried = 0, am = 0;
        for (int i = 0; i < arr.length; i++) {
            ChunkSection sec = arr[i];
            if (sec == null || sec.isEmpty()) continue;
            if (!sec.hasAny(s -> ORES.contains(s.getBlock()) || s.getBlock() == Blocks.AMETHYST_CLUSTER)) continue;
            int by0 = (bottom + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        Block b = sec.getBlockState(x, y, z).getBlock();
                        if (b == Blocks.AMETHYST_CLUSTER) { am++; continue; }
                        if (!ORES.contains(b)) continue;
                        if (x < 1 || x > 14 || z < 1 || z > 14) continue; // neighbours outside the chunk may not be loaded
                        total++;
                        boolean open = false;
                        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                            p.set(sx + x, by0 + y, sz + z).move(d);
                            BlockState n = world.getBlockState(p);
                            if (n.isAir() || !n.getFluidState().isEmpty()) { open = true; break; }
                        }
                        if (!open) buried++;
                    }
                }
            }
        }
        oresTotal.addAndGet(total);
        oresBuried.addAndGet(buried);
        amethystClusters.addAndGet(am);
        chunksScanned.incrementAndGet();
    }

    private void writeSummary() {
        try {
            var entry = mc.getCurrentServerEntry();
            String server = entry != null ? entry.address : "singleplayer";
            StringBuilder t = new StringBuilder();
            t.append("Server summary: ").append(server).append('\n');
            t.append("Updated: ").append(Instant.now()).append("\n\n");
            t.append("Software: ").append(mc.getNetworkHandler().getBrand()).append("  (looks like: ").append(lastSoftware).append(")\n");
            t.append("Game version: ").append(SharedConstants.getGameVersion().name()).append('\n');
            t.append("Players online: ").append(mc.getNetworkHandler().getPlayerList().size()).append('\n');
            t.append("Server speed: ").append(String.format(java.util.Locale.ROOT, "%.1f", TickRate.INSTANCE.getTickRate())).append(" ticks per second (20 is perfect)\n\n");
            t.append("How far the server sends chunks: ").append(lastRadius).append(" chunks  (your render distance is ")
                .append(mc.options.getViewDistance().getValue()).append(")\n");
            t.append("Full height sent near you: ").append(lastNear).append(" sections, lowest block Y ").append(lastNearLow).append('\n');
            t.append("Full height sent far away: ").append(lastFar).append(" sections, lowest block Y ").append(lastFarLow);
            if (lastFar >= 0 && lastNear > 0 && lastFar < lastNear - 2) t.append("  <- the server cuts the height far away");
            t.append("\n\n");
            t.append("Does it hide ores? ").append(lastVerdict).append('\n');
            t.append("  ores seen: ").append(oresTotal.get()).append(", buried inside rock: ")
                .append(lastBuriedPct < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.0f%%", lastBuriedPct))
                .append(" (a normal server shows about 50% to 90%), chunks checked: ").append(chunksScanned.get()).append('\n');
            t.append("  amethyst clusters seen: ").append(amethystClusters.get()).append('\n');
            t.append("  ores shown to you later by block updates: ").append(revealedTotal.get()).append('\n');
            t.append("  note: a server that adds FAKE ores cannot be told apart from a normal one by this check.\n");
            java.nio.file.Files.writeString(new java.io.File(ResearchFiles.folder(), "server-summary-" + ResearchFiles.serverName() + ".txt").toPath(), t.toString());
        } catch (Throwable ignored) {
        }
    }

    /** The farthest chunk (in chunks, square distance) that is loaded around you. */
    private int measureRadius() {
        var manager = mc.world.getChunkManager();
        var c = mc.player.getChunkPos();
        int max = Math.min(64, Math.max(mc.options.getViewDistance().getValue() + 4, 12));
        int far = 0;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d <= far) continue;
                if (manager.isChunkLoaded(c.x + dx, c.z + dz)) far = d;
            }
        }
        return far;
    }

    @Override
    public String getInfoString() {
        return lines + " lines";
    }
}
