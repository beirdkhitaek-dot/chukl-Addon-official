package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.scan.BaseScanner;
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
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Storage ESP (v1.7): boxes around chests, barrels, shulkers, ender chests, hoppers, furnaces and dispensers, one color
 * per type. The list is rebuilt a few times a second and capped, so a huge storage hall cannot hurt FPS.
 */
public class ChuklStorageEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTypes = settings.createGroup("Types");
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Only show storage this many blocks away.")
        .defaultValue(96).range(16, 256).sliderRange(16, 256).build());

    private final Setting<Boolean> underground = sgGeneral.add(new BoolSetting.Builder()
        .name("underground-only").description("Only show storage below the Y level set next.")
        .defaultValue(false).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y to show when underground-only is on.")
        .defaultValue(40).range(-64, 320).sliderRange(-64, 128)
        .visible(underground::get).build());

    private final Setting<Integer> minGroup = sgGeneral.add(new IntSetting.Builder()
        .name("min-per-chunk").description("Only show storage in chunks that have at least this many. 3 or more hides lone natural chests.")
        .defaultValue(1).range(1, 20).sliderRange(1, 10).build());

    private final Setting<ShapeMode> shape = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the boxes are drawn.").defaultValue(ShapeMode.Both).build());

    private final Setting<Integer> maxBoxes = sgGeneral.add(new IntSetting.Builder()
        .name("max-boxes").description("Never draw more than this many boxes at once. The closest ones win.")
        .defaultValue(1500).range(100, 6000).sliderRange(100, 4000).build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line to each box. Use with a small range.").defaultValue(false).build());

    private final Setting<Integer> fillAlpha = sgColors.add(new IntSetting.Builder()
        .name("fill-alpha").description("How see-through the fill is.")
        .defaultValue(45).range(5, 255).sliderRange(5, 255).build());

    private final Map<BaseScanner.Kind, Setting<Boolean>> on = new EnumMap<>(BaseScanner.Kind.class);
    private final Map<BaseScanner.Kind, Setting<SettingColor>> colors = new EnumMap<>(BaseScanner.Kind.class);

    private record Item(double x, double y, double z, BaseScanner.Kind kind, double dist) {}

    private List<Item> items = List.of();
    private int timer;

    public ChuklStorageEsp() {
        super(ChuklAddon.RENDER_CATEGORY, "chukl-storage-esp",
            "Boxes around chests, barrels, shulkers, ender chests, hoppers, furnaces and dispensers. One color per type, capped so it stays smooth.");
        for (BaseScanner.Kind k : BaseScanner.Kind.values()) {
            String n = k.name().toLowerCase().replace('_', '-');
            on.put(k, sgTypes.add(new BoolSetting.Builder()
                .name(n).description("Show " + n.replace('-', ' ') + ".")
                .defaultValue(true).build()));
            colors.put(k, sgColors.add(new ColorSetting.Builder()
                .name(n + "-color").description("Color of " + n.replace('-', ' ') + ".")
                .defaultValue(defaultColor(k)).build()));
        }
    }

    private static SettingColor defaultColor(BaseScanner.Kind k) {
        return switch (k) {
            case CHEST -> new SettingColor(255, 170, 40, 255);
            case TRAPPED_CHEST -> new SettingColor(255, 60, 40, 255);
            case BARREL -> new SettingColor(160, 110, 60, 255);
            case SHULKER -> new SettingColor(190, 90, 255, 255);
            case ENDER_CHEST -> new SettingColor(60, 220, 190, 255);
            case HOPPER -> new SettingColor(170, 170, 170, 255);
            case FURNACE -> new SettingColor(120, 120, 140, 255);
            case DISPENSER -> new SettingColor(80, 160, 255, 255);
        };
    }

    @Override
    public void onActivate() { items = List.of(); timer = 0; }

    @Override
    public void onDeactivate() { items = List.of(); }

    @Override
    public String getInfoString() {
        return items.isEmpty() ? null : Integer.toString(items.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (--timer > 0) return;
        timer = 8;

        double r = range.get();
        double rSq = r * r;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        boolean deepOnly = underground.get();
        int limitY = maxY.get();

        List<BlockEntity> all = new ArrayList<>();
        for (BlockEntity be : Utils.blockEntities()) all.add(be);

        // chunk counts for min-per-chunk
        Map<Long, Integer> perChunk = new java.util.HashMap<>();
        int need = minGroup.get();
        if (need > 1) {
            for (BlockEntity be : all) {
                if (BaseScanner.kindOf(be.getCachedState().getBlock()) == null) continue;
                perChunk.merge(net.minecraft.util.math.ChunkPos.toLong(be.getPos()), 1, Integer::sum);
            }
        }

        List<Item> out = new ArrayList<>();
        for (BlockEntity be : all) {
            BaseScanner.Kind kind = BaseScanner.kindOf(be.getCachedState().getBlock());
            if (kind == null || !on.get(kind).get()) continue;
            BlockPos pos = be.getPos();
            if (deepOnly && pos.getY() > limitY) continue;
            double dx = pos.getX() + 0.5 - px, dy = pos.getY() + 0.5 - py, dz = pos.getZ() + 0.5 - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d > rSq) continue;
            if (need > 1 && perChunk.getOrDefault(net.minecraft.util.math.ChunkPos.toLong(pos), 0) < need) continue;
            out.add(new Item(pos.getX(), pos.getY(), pos.getZ(), kind, d));
        }
        out.sort(java.util.Comparator.comparingDouble(Item::dist));
        int cap = maxBoxes.get();
        items = out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        List<Item> list = items;
        if (list.isEmpty()) return;

        int a = fillAlpha.get();
        boolean lines = tracers.get();
        ShapeMode mode = shape.get();
        Map<BaseScanner.Kind, Color> fills = new EnumMap<>(BaseScanner.Kind.class);
        for (BaseScanner.Kind k : BaseScanner.Kind.values()) {
            SettingColor c = colors.get(k).get();
            fills.put(k, new Color(c.r, c.g, c.b, a));
        }

        for (Item it : list) {
            SettingColor line = colors.get(it.kind()).get();
            event.renderer.box(it.x(), it.y(), it.z(), it.x() + 1, it.y() + 1, it.z() + 1, fills.get(it.kind()), line, mode, 0);
            if (lines) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    it.x() + 0.5, it.y() + 0.5, it.z() + 0.5, line);
            }
        }
    }
}
