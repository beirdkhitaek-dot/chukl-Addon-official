package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Krispy Chunk Finder.
 *
 * Reads the light data the server sends with every chunk. A section underground whose whole light array is
 * all-zero or all-15 is unusual, and the chunk it belongs to is flagged. Each flagged chunk gets a plate drawn
 * at the surface, an optional toast and ping sound, and you can have it disconnect you on a flag.
 *
 * Only chunks that arrive after the module is switched on are checked (the light data is not kept after it is
 * read). Light changes right next to your own digging are ignored for a few seconds.
 *
 * Performance: the packets are only queued when they arrive; a small number are classified per tick on the
 * main thread, and a payload is rejected as soon as two different byte values are seen.
 */
public class KrispyChunkFinder extends Module {
    private enum Payload {NORMAL, EMPTY_MASK, ZEROED_PRESENT, UNIFORM_MAX, UNKNOWN}

    private enum Track {SKY, BLOCK}

    private record Pending(long epoch, int chunkX, int chunkZ, LightData data) {}

    private record AlertedSection(long chunkKey, int sectionY, Track track) {}

    private record Flag(ChunkPos chunk, int y) {}

    /** What the last light packet said about each section of one chunk (index 0 and length-1 are the border sections). */
    private static final class Snapshot {
        final int sectionCount;
        final int bottomSection;
        final Payload[] block;
        final Payload[] sky;
        final long[] blockUpdated;
        final long[] skyUpdated;

        Snapshot(int sectionCount, int bottomSection) {
            this.sectionCount = sectionCount;
            this.bottomSection = bottomSection;
            int n = sectionCount + 2;
            block = new Payload[n];
            sky = new Payload[n];
            blockUpdated = new long[n];
            skyUpdated = new long[n];
            Arrays.fill(block, Payload.UNKNOWN);
            Arrays.fill(sky, Payload.UNKNOWN);
        }
    }

    private static final int LIGHT_ARRAY_BYTES = 2048;
    private static final int DRAIN_BUDGET = 256;          // light packets handled per tick
    private static final int MAX_SNAPSHOTS = 8192;
    private static final int MAX_FLAGS = 512;
    private static final int MAX_ALERTED = 100_000;
    private static final long DIG_IGNORE_MS = 4000L;
    private static final double DIG_RADIUS_SQ = 64.0;     // 8 blocks
    private static final int MIN_FLAG_Y = 21;
    private static final double PLATE_LIFT = 1.002;
    private static final SoundEvent PING = SoundEvent.of(Identifier.of("minecraft", "entity.experience_orb.pickup"));

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> advancementNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("advancement-notifications")
        .description("Shows an advancement-style toast with the coordinates when a chunk is flagged.")
        .defaultValue(true).build());

    private final Setting<Boolean> soundPing = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Plays a ping when a chunk is flagged.")
        .defaultValue(true).build());

    private final Setting<Double> soundVolume = sgGeneral.add(new DoubleSetting.Builder()
        .name("sound-volume")
        .description("Volume of the ping (0-100).")
        .defaultValue(100.0).range(0, 100).sliderRange(0, 100)
        .visible(soundPing::get).build());

    private final Setting<Boolean> kickOnFlag = sgGeneral.add(new BoolSetting.Builder()
        .name("kick-on-flag")
        .description("Disconnects you from the server the moment a chunk is flagged. Turns itself off afterwards, and turns off Auto Reconnect so you are not put straight back in.")
        .defaultValue(false).build());

    private final Setting<Double> plateSize = sgRender.add(new DoubleSetting.Builder()
        .name("plate-size")
        .description("Width of the plate drawn for a flagged chunk, in blocks (a chunk is 16).")
        .defaultValue(16.0).range(1, 64).sliderRange(1, 64).build());

    private final Setting<Double> plateThickness = sgRender.add(new DoubleSetting.Builder()
        .name("plate-thickness")
        .description("Thickness of the plate in blocks.")
        .defaultValue(0.15).range(0.01, 4).sliderRange(0.01, 4).build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color of the plate.")
        .defaultValue(new SettingColor(0, 255, 70, 75)).build());

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline color of the plate.")
        .defaultValue(new SettingColor(0, 255, 70, 220)).build());

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Only draw plates within this many chunks of you.")
        .defaultValue(24).range(2, 64).sliderRange(2, 64).build());

    /** Written by the network thread, read by the main thread. */
    private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong epoch = new AtomicLong();

    // Main thread only
    private final Map<Long, Snapshot> snapshots = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Snapshot> eldest) {
            return size() > MAX_SNAPSHOTS;
        }
    };
    private final Set<Long> alertedChunks = new HashSet<>();
    private final Set<AlertedSection> alertedSections = new HashSet<>();
    private final Map<Long, Flag> flags = new LinkedHashMap<>();
    private final Map<Long, Long> recentDigs = new HashMap<>();
    private ClientWorld trackedWorld;
    private long stamp;

    public KrispyChunkFinder() {
        super(ChuklAddon.CATEGORY, "krispy-chunk-finder",
            "Krispy's Chunk Finder: flags chunks whose underground light data is all-zero or all-15 and marks them on the surface.");
    }

    @Override
    public void onActivate() {
        epoch.incrementAndGet();
        queue.clear();
        clearData();
        trackedWorld = mc.world;
    }

    @Override
    public void onDeactivate() {
        epoch.incrementAndGet();
        queue.clear();
        clearData();
        trackedWorld = null;
    }

    private void clearData() {
        snapshots.clear();
        alertedChunks.clear();
        alertedSections.clear();
        flags.clear();
        recentDigs.clear();
        stamp = 0;
    }

    // ---------- network thread: only queue the light data ----------

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;

        // Everything after a join/respawn packet belongs to the new world, everything queued before it does not
        if (packet instanceof GameJoinS2CPacket || packet instanceof PlayerRespawnS2CPacket) {
            epoch.incrementAndGet();
            queue.clear();
            return;
        }

        if (packet instanceof ChunkDataS2CPacket chunk) {
            offer(chunk.getChunkX(), chunk.getChunkZ(), chunk.getLightData());
        } else if (packet instanceof LightUpdateS2CPacket light) {
            offer(light.getChunkX(), light.getChunkZ(), light.getData());
        }
    }

    private void offer(int chunkX, int chunkZ, LightData data) {
        if (data != null) queue.offer(new Pending(epoch.get(), chunkX, chunkZ, data));
    }

    // ---------- main thread ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (mc.world != trackedWorld) {
            clearData();
            trackedWorld = mc.world;
        }

        int sections = mc.world.countVerticalSections();
        int bottom = mc.world.getBottomSectionCoord();
        long currentEpoch = epoch.get();

        int handled = 0;
        Pending p;
        while (handled < DRAIN_BUDGET && (p = queue.poll()) != null) {
            handled++;
            if (p.epoch() == currentEpoch) process(p, sections, bottom);
        }

        if (alertedSections.size() > MAX_ALERTED) {
            alertedSections.clear();
            alertedChunks.clear();
        }
    }

    /** Remembers where you dig, so the light changes you cause yourself are not flagged. */
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || event.pos == null) return;
        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(event.pos)) <= DIG_RADIUS_SQ) {
            recentDigs.put(ChunkPos.toLong(event.pos.getX() >> 4, event.pos.getZ() >> 4), System.currentTimeMillis());
        }
    }

    private boolean nearRecentDig(int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        recentDigs.values().removeIf(t -> now - t > DIG_IGNORE_MS);

        for (long key : recentDigs.keySet()) {
            if (Math.abs(ChunkPos.getPackedX(key) - chunkX) <= 1 && Math.abs(ChunkPos.getPackedZ(key) - chunkZ) <= 1) return true;
        }
        return false;
    }

    // ---------- light classifier ----------

    private void process(Pending p, int sections, int bottom) {
        long key = ChunkPos.toLong(p.chunkX(), p.chunkZ());

        Snapshot snap = snapshots.get(key);
        if (snap == null || snap.sectionCount != sections || snap.bottomSection != bottom) {
            snap = new Snapshot(sections, bottom);
            snapshots.put(key, snap);
        }

        long thisStamp = ++stamp;
        try {
            applyTrack(p.data().getInitedBlock(), p.data().getUninitedBlock(), p.data().getBlockNibbles(), snap.block, snap.blockUpdated, thisStamp);
            applyTrack(p.data().getInitedSky(), p.data().getUninitedSky(), p.data().getSkyNibbles(), snap.sky, snap.skyUpdated, thisStamp);
        } catch (Throwable t) {
            ChuklAddon.LOG.error("Krispy Chunk Finder: light classifier failed on chunk " + p.chunkX() + "," + p.chunkZ(), t);
            return;
        }

        // Only sections that start below Y1 are looked at (underground)
        for (int i = 1; i <= snap.sectionCount; i++) {
            int sectionY = (snap.bottomSection + i - 1) * 16;
            if (sectionY >= 1) break;

            if (snap.skyUpdated[i] == thisStamp) check(Track.SKY, p.chunkX(), p.chunkZ(), key, sectionY, snap.sky[i]);
            if (snap.blockUpdated[i] == thisStamp) check(Track.BLOCK, p.chunkX(), p.chunkZ(), key, sectionY, snap.block[i]);
        }
    }

    private static void applyTrack(BitSet inited, BitSet uninited, List<byte[]> nibbles, Payload[] types, long[] updated, long thisStamp) {
        int next = 0;
        for (int i = 0; i < types.length; i++) {
            if (inited.get(i)) {
                byte[] data = next < nibbles.size() ? nibbles.get(next) : null;
                next++;
                types[i] = classify(data);
                updated[i] = thisStamp;
            } else if (uninited.get(i)) {
                types[i] = Payload.EMPTY_MASK;
                updated[i] = thisStamp;
            }
        }
    }

    private static Payload classify(byte[] data) {
        if (data == null || data.length != LIGHT_ARRAY_BYTES) return Payload.UNKNOWN;

        boolean allZero = true;
        boolean allMax = true;
        for (byte b : data) {
            if (b != 0) allZero = false;
            if (b != -1) allMax = false;
            if (!allZero && !allMax) return Payload.NORMAL;
        }
        return allZero ? Payload.ZEROED_PRESENT : Payload.UNIFORM_MAX;
    }

    private void check(Track track, int chunkX, int chunkZ, long key, int sectionY, Payload payload) {
        if (payload != Payload.ZEROED_PRESENT && payload != Payload.UNIFORM_MAX) return;

        // Alert once per section, and only once per chunk
        if (alertedSections.add(new AlertedSection(key, sectionY, track)) && alertedChunks.add(key)) {
            onChunkFound(chunkX, chunkZ);
        }
    }

    // ---------- flags ----------

    private void onChunkFound(int chunkX, int chunkZ) {
        if (nearRecentDig(chunkX, chunkZ)) return;

        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int y = mc.world.isChunkLoaded(chunkX, chunkZ)
            ? Math.max(MIN_FLAG_Y, mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z))
            : MIN_FLAG_Y;

        flag(new BlockPos(x, y, z));
    }

    private void flag(BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        if (flags.putIfAbsent(chunk.toLong(), new Flag(chunk, Math.max(MIN_FLAG_Y, pos.getY()))) != null) return;

        if (flags.size() > MAX_FLAGS) {
            Iterator<Long> oldest = flags.keySet().iterator();
            oldest.next();
            oldest.remove();
        }

        if (advancementNotifications.get()) {
            SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("Krispy Chunk Finder"), Text.literal("Flagged X: " + pos.getX() + " Z: " + pos.getZ()));
        }

        if (soundPing.get()) {
            float volume = (float) (soundVolume.get() / 100.0);
            if (mc.player != null) mc.player.playSound(PING, volume, 1.0F);
        }

        if (kickOnFlag.get() && mc.getNetworkHandler() != null) {
            kickOnFlag.set(false);

            AutoReconnect reconnect = Modules.get().get(AutoReconnect.class);
            if (reconnect != null && reconnect.isActive()) reconnect.toggle();

            mc.getNetworkHandler().getConnection().disconnect(Text.literal(
                "Kicked - Krispy Chunk Finder flagged X: " + pos.getX() + " Z: " + pos.getZ()
                    + "\nKick On Flag was automatically turned OFF."));
        }
    }

    // ---------- render ----------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (flags.isEmpty() || mc.player == null) return;

        double half = plateSize.get() / 2.0;
        double thickness = plateThickness.get();
        int range = renderDistance.get();
        ChunkPos here = mc.player.getChunkPos();
        SettingColor fill = fillColor.get();
        SettingColor line = outlineColor.get();

        for (Flag f : flags.values()) {
            if (Math.abs(f.chunk().x - here.x) > range || Math.abs(f.chunk().z - here.z) > range) continue;

            double cx = f.chunk().getCenterX();
            double cz = f.chunk().getCenterZ();
            double y = f.y() + PLATE_LIFT;

            event.renderer.box(cx - half, y, cz - half, cx + half, y + thickness, cz + half, fill, line, ShapeMode.Both, 0);
        }
    }
}
