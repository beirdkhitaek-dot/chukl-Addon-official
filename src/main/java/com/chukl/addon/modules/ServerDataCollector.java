package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.PathManagers;
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
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;

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

    private final Setting<Boolean> autoRun = sg.add(new BoolSetting.Builder()
        .name("auto-dig-run")
        .description("Automatic test run: digs straight down with Baritone and stops every 16 blocks to record what the server sends you at each depth. Turn it on and leave your character alone. Needs Baritone (it comes with Meteor). Do not use it near lava or where digging is not allowed. It stops by itself if your health gets low.")
        .defaultValue(false).build());

    private final Setting<Integer> digTo = sg.add(new IntSetting.Builder()
        .name("dig-down-to")
        .description("How deep the automatic run goes (Y level). -56 is just above bedrock.")
        .defaultValue(-56).range(-60, 200).sliderRange(-60, 60)
        .visible(autoRun::get).build());

    private final Setting<Integer> waitSeconds = sg.add(new IntSetting.Builder()
        .name("wait-seconds")
        .description("How long to stay at each stop, so the server has time to send you the chunks. 20 is a good value.")
        .defaultValue(20).range(5, 120).sliderRange(10, 60)
        .visible(autoRun::get).build());

    private final Setting<Boolean> returnToStart = sg.add(new BoolSetting.Builder()
        .name("return-to-start")
        .description("When the run is finished, walk back up to where you started.")
        .defaultValue(true)
        .visible(autoRun::get).build());

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

    // automatic dig-down run
    private enum Auto { OFF, START_WAIT, DIGGING, WAITING, RETURNING }
    private Auto auto = Auto.OFF;
    private boolean autoFinished = false;
    private final List<Integer> stops = new ArrayList<>();
    private final List<String> digResults = new ArrayList<>();
    private int stopIndex = 0, autoTicks = 0, stuckTicks = 0, retries = 0;
    private BlockPos startPos = BlockPos.ORIGIN;
    private final AtomicLong oresDeep = new AtomicLong(), oresDeepBuried = new AtomicLong();
    private final Map<String, Integer> payloadChannels = new ConcurrentHashMap<>();
    private final Map<Block, AtomicLong> oreMix = new ConcurrentHashMap<>();
    private int lastPlayersLoaded = 0, lastEntitiesLoaded = 0, maxPlayerDistance = 0, maxEntityDistance = 0;
    private int lastPlayersOnline = 0;

    private static String packetName(net.minecraft.network.packet.Packet<?> p) {
        try { return p.getPacketType().id().toString(); } catch (Throwable t) { return p.getClass().getSimpleName(); }
    }

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
        auto = Auto.OFF;
        autoFinished = false;
        digResults.clear();
        info("Writing to: " + ResearchFiles.folder().getAbsolutePath() + " (server-data-" + ResearchFiles.serverName() + ".jsonl)");
    }

    @Override
    public void onDeactivate() {
        if (auto != Auto.OFF) abortAuto("Module turned off.");
        if (scanner != null) scanner.shutdownNow();
        scanner = null;
    }

    private void resetBlockStats() {
        scanned.clear();
        oreMix.clear();
        payloadChannels.clear();
        oresDeep.set(0); oresDeepBuried.set(0);
        oresTotal.set(0); oresBuried.set(0); chunksScanned.set(0); amethystClusters.set(0);
        blockUpdates.set(0); oresRevealed.set(0); revealedTotal.set(0);
        lastVerdict = "not enough data yet";
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        if (auto != Auto.OFF) { auto = Auto.OFF; autoFinished = true; stops.clear(); }
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
        String name = packetName(event.packet);
        if (anticheat.get()) {
            // plugin channels the server talks on (anticheats and other plugins often register their own)
            if (event.packet instanceof CustomPayloadS2CPacket cp) {
                try { payloadChannels.merge(cp.payload().getId().id().toString(), 1, Integer::sum); } catch (Throwable ignored) {}
            }
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
        autoTick();
        if (now - lastWrite >= interval.get() * 1000L) write(now);
    }

    // ---------------------------------------------------------------- automatic dig-down run

    private void autoTick() {
        if (!autoRun.get()) {
            if (auto != Auto.OFF) abortAuto("Auto run turned off.");
            autoFinished = false; // turning the setting off and on again starts a new run
            return;
        }
        if (auto == Auto.OFF) {
            if (!autoFinished) startAuto();
            return;
        }
        // safety: low health stops everything
        if (mc.player.getHealth() <= 8.0f && auto != Auto.RETURNING) {
            abortAuto("Health is low (" + (int) mc.player.getHealth() + "), stopped for safety.");
            return;
        }
        autoTicks++;
        switch (auto) {
            case START_WAIT -> {
                if (autoTicks >= waitSeconds.get() * 20) { autoTicks = 0; stuckTicks = 0; retries = 0; beginDig(); }
            }
            case DIGGING -> {
                int target = stops.get(stopIndex);
                if (mc.player.getBlockY() <= target + 1) {
                    PathManagers.get().stop();
                    auto = Auto.WAITING;
                    autoTicks = 0;
                    return;
                }
                if (!PathManagers.get().isPathing()) {
                    if (++stuckTicks > 60) {
                        stuckTicks = 0;
                        if (++retries > 3) { abortAuto("Baritone got stuck at Y " + mc.player.getBlockY() + " (could not dig further)."); return; }
                        beginDig();
                    }
                } else stuckTicks = 0;
                if (autoTicks > 20 * 120) abortAuto("Taking too long to reach Y " + target + ".");
            }
            case WAITING -> {
                if (autoTicks >= waitSeconds.get() * 20) {
                    recordStop(stops.get(stopIndex));
                    stopIndex++;
                    autoTicks = 0; stuckTicks = 0; retries = 0;
                    if (stopIndex < stops.size()) beginDig();
                    else finishAuto();
                }
            }
            case RETURNING -> {
                if (!PathManagers.get().isPathing()) {
                    if (++stuckTicks > 100 || mc.player.getBlockY() >= startPos.getY() - 1) endAuto("Back at the start. Auto run finished.");
                } else stuckTicks = 0;
                if (autoTicks > 20 * 300) endAuto("Stopped walking back (took too long). Auto run finished.");
            }
            default -> {}
        }
    }

    private void startAuto() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            warning("Auto run needs Baritone and it was not found. Turn the setting off or install Baritone.");
            autoFinished = true;
            return;
        }
        startPos = mc.player.getBlockPos();
        stops.clear();
        digResults.clear();
        int floor = Math.floorDiv(startPos.getY() - 1, 16) * 16;
        for (int y = floor; y > digTo.get(); y -= 16) stops.add(y);
        stops.add(digTo.get());
        stopIndex = 0; autoTicks = 0; stuckTicks = 0; retries = 0;
        auto = Auto.START_WAIT;
        info("Auto run started at Y " + startPos.getY() + ": " + stops.size() + " stops down to Y " + digTo.get()
            + ". First I record where you stand (" + waitSeconds.get() + "s), then I dig down. Do not move.");
    }

    private void beginDig() {
        BlockPos here = mc.player.getBlockPos();
        int target = stops.get(stopIndex);
        auto = Auto.DIGGING;
        // straight down from the start point, so the run always digs the same shaft
        PathManagers.get().moveTo(new BlockPos(startPos.getX(), target, startPos.getZ()));
        if (stopIndex == 0 && retries == 0) info("Digging down to Y " + target + " ...");
    }

    private void finishAuto() {
        recordSummaryLine();
        if (returnToStart.get()) {
            auto = Auto.RETURNING;
            autoTicks = 0; stuckTicks = 0;
            PathManagers.get().moveTo(startPos);
            info("Dig run done. Walking back up.");
        } else endAuto("Auto run finished. Baritone stopped, you are at Y " + mc.player.getBlockY() + ".");
    }

    private void endAuto(String message) {
        PathManagers.get().stop();
        auto = Auto.OFF;
        autoFinished = true;
        stops.clear();
        info(message + " Data is in " + ResearchFiles.folder().getAbsolutePath());
    }

    private void abortAuto(String why) {
        PathManagers.get().stop();
        warning("Auto run stopped: " + why);
        if (mc.player != null && mc.world != null && !digResults.isEmpty()) recordSummaryLine();
        auto = Auto.OFF;
        autoFinished = true;
        stops.clear();
    }

    /** One line per stop: where the player is and which parts of the world the server sent at this depth. */
    private void recordStop(int target) {
        try {
            int y = mc.player.getBlockY();
            String map = sectionMap(mc.world.getChunkManager().getWorldChunk(mc.player.getChunkPos().x, mc.player.getChunkPos().z));
            int loaded = 0, withDeep = 0;
            var manager = mc.world.getChunkManager();
            var c = mc.player.getChunkPos();
            int r = Math.max(2, Math.min(6, lastRadius > 0 ? lastRadius : 6));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!manager.isChunkLoaded(c.x + dx, c.z + dz)) continue;
                    loaded++;
                    if (sections(manager.getWorldChunk(c.x + dx, c.z + dz))[1] < 16) withDeep++;
                }
            }
            long deep = oresDeep.get(), deepBuried = oresDeepBuried.get();
            StringBuilder sb = new StringBuilder("{\"type\":\"dig-step\"");
            sb.append(",\"time\":").append(ResearchFiles.json(Instant.now().toString()));
            sb.append(",\"stop\":").append(stopIndex + 1).append(",\"of\":").append(stops.size());
            sb.append(",\"targetY\":").append(target).append(",\"playerY\":").append(y);
            sb.append(",\"waitedSeconds\":").append(waitSeconds.get());
            sb.append(",\"loadedChunksAround\":").append(loaded);
            sb.append(",\"chunksWithBlocksBelowY16\":").append(withDeep);
            sb.append(",\"sectionMapHere\":").append(ResearchFiles.json(map));
            sb.append(",\"oresBelowY16Seen\":").append(deep).append(",\"oresBelowY16Buried\":").append(deepBuried);
            sb.append(",\"pingMs\":").append(mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()) != null
                ? mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()).getLatency() : -1);
            sb.append('}');
            ResearchFiles.appendLine("server-data-" + ResearchFiles.serverName() + ".jsonl", sb.toString());
            digResults.add("Y " + y + " (stop at " + target + "): " + withDeep + " of " + loaded + " chunks around you have blocks below Y16; your chunk: " + map);
        } catch (Throwable t) {
            warning("Could not record stop: " + t.getMessage());
        }
    }

    private void recordSummaryLine() {
        if (summaryFile.get()) writeSummary();
    }

    /** Your chunk from the bottom up. # = the server sent blocks in that 16-block layer, . = empty. */
    private static String sectionMap(WorldChunk chunk) {
        if (chunk == null) return "";
        ChunkSection[] arr = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append((bottom + i) * 16).append(arr[i] == null || arr[i].isEmpty() ? ":." : ":#");
        }
        return sb.toString();
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
        lastPlayersOnline = mc.getNetworkHandler().getPlayerList().size();
        sb.append(",\"playersOnline\":").append(lastPlayersOnline);
        sb.append(",\"playerY\":").append(mc.player.getBlockY());
        // anti-ESP check: only totals and the farthest distance are kept, never names or positions
        int pl = 0, en = 0, farP = 0, farE = 0;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            int d = (int) e.distanceTo(mc.player);
            if (e instanceof PlayerEntity) { pl++; farP = Math.max(farP, d); }
            en++; farE = Math.max(farE, d);
        }
        lastPlayersLoaded = pl; lastEntitiesLoaded = en; maxPlayerDistance = farP; maxEntityDistance = farE;
        sb.append(",\"otherPlayersLoaded\":").append(pl).append(",\"farthestPlayerBlocks\":").append(farP);
        sb.append(",\"otherEntitiesLoaded\":").append(en).append(",\"farthestEntityBlocks\":").append(farE);

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
            sb.append(",\"pluginChannelsSeen\":{");
            int q = 0;
            for (Map.Entry<String, Integer> e : payloadChannels.entrySet()) {
                if (q++ > 0) sb.append(',');
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

    private boolean fakeOres = false;

    private String oreMixJson() {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<Block, AtomicLong> e : oreMix.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(ResearchFiles.json(Registries.BLOCK.getId(e.getKey()).getPath())).append(':').append(e.getValue().get());
        }
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
            // ancient debris does not generate in the overworld, so seeing it there means the server adds fake ores
            AtomicLong debris = oreMix.get(Blocks.ANCIENT_DEBRIS);
            fakeOres = debris != null && debris.get() > 0 && "minecraft:overworld".equals(mc.world.getRegistryKey().getValue().toString());
            if (fakeOres) lastVerdict = "FAKE ORES likely: ancient debris was seen in the overworld, where it never generates (anti-xray adds decoys)";
            sb.append(",\"blockHiding\":{\"oresSeen\":").append(total)
                .append(",\"buriedPercent\":").append(String.format(java.util.Locale.ROOT, "%.1f", lastBuriedPct))
                .append(",\"chunksChecked\":").append(chunksScanned.get())
                .append(",\"oresBelowY16\":").append(oresDeep.get()).append(",\"oresBelowY16Buried\":").append(oresDeepBuried.get())
                .append(",\"fakeOresLikely\":").append(fakeOres)
                .append(",\"oreMix\":").append(oreMixJson())
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
        sb.append(",\"sectionMapHere\":").append(ResearchFiles.json(sectionMap(manager.getWorldChunk(c.x, c.z))));
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
        long total = 0, buried = 0, am = 0, deepT = 0, deepB = 0;
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
                        oreMix.computeIfAbsent(b, k -> new AtomicLong()).incrementAndGet();
                        if (x < 1 || x > 14 || z < 1 || z > 14) continue; // neighbours outside the chunk may not be loaded
                        total++;
                        boolean isDeep = by0 + y < 16;
                        if (isDeep) deepT++;
                        boolean open = false;
                        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                            p.set(sx + x, by0 + y, sz + z).move(d);
                            BlockState n = world.getBlockState(p);
                            if (n.isAir() || !n.getFluidState().isEmpty()) { open = true; break; }
                        }
                        if (!open) { buried++; if (isDeep) deepB++; }
                    }
                }
            }
        }
        oresDeep.addAndGet(deepT);
        oresDeepBuried.addAndGet(deepB);
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
            t.append("  ores below Y16: ").append(oresDeep.get()).append(" (buried: ").append(oresDeepBuried.get()).append(")\n");
            t.append("  ore types seen: ").append(oreMixJson()).append('\n');
            t.append("  fake ores: ").append(fakeOres ? "LIKELY (ancient debris seen in the overworld)"
                : "no sign. Decoy ores of normal types cannot be told apart from real ones by this check.").append("\n\n");
            t.append("Anti-ESP (what the server shows you of other players and mobs):\n");
            t.append("  players online: ").append(lastPlayersOnline).append(", other players loaded around you: ").append(lastPlayersLoaded)
                .append(", farthest one: ").append(maxPlayerDistance).append(" blocks\n");
            t.append("  other entities loaded: ").append(lastEntitiesLoaded).append(", farthest: ").append(maxEntityDistance).append(" blocks\n\n");
            t.append("Plugin channels the server uses: ").append(payloadChannels.isEmpty() ? "none seen" : payloadChannels.toString()).append('\n');
            synchronized (acMentions) {
                t.append("Anticheat names seen in messages (latest sample): ").append(acMentions.isEmpty() ? "none" : acMentions.keySet().toString()).append('\n');
            }
            if (!digResults.isEmpty()) {
                t.append("\nAuto dig-down run:\n");
                for (String s : digResults) t.append("  ").append(s).append('\n');
            }
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
