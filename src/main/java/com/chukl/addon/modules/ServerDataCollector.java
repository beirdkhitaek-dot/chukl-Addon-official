package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
import net.minecraft.client.network.PlayerListEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Setting<Boolean> writeOnJoin = sg.add(new BoolSetting.Builder()
        .name("write-on-join").description("Write a line right after you join a world or server.")
        .defaultValue(true).build());

    private final Map<String, Integer> packetCounts = new HashMap<>();
    private long packetTotal = 0;
    private long lastWrite = 0;
    private int lines = 0;
    private boolean pendingJoin = false;
    private int joinDelay = 0;
    private String lastError = "";

    public ServerDataCollector() {
        super(ChuklAddon.CATEGORY, "server-data-collector",
            "Research: writes technical facts about the server (brand, version, chunk radius, tick rate, ping, packet rates) to a file. No player names, chat or positions.");
    }

    @Override
    public void onActivate() {
        packetCounts.clear();
        packetTotal = 0;
        lastWrite = System.currentTimeMillis();
        lines = 0;
        pendingJoin = writeOnJoin.get();
        joinDelay = 60;
        info("Writing to: " + ResearchFiles.folder().getAbsolutePath() + " (server-data-" + ResearchFiles.serverName() + ".jsonl)");
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        packetCounts.clear();
        packetTotal = 0;
        pendingJoin = writeOnJoin.get();
        joinDelay = 60;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!packetStats.get()) return;
        synchronized (packetCounts) {
            packetTotal++;
            packetCounts.merge(event.packet.getClass().getSimpleName(), 1, Integer::sum);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;
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
        return sb.append('}').toString();
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
