package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.scan.BaseScanner;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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
    public enum Style { Box, Outline_Only, Fill_Only, Corners }

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

    private final Setting<Style> style = sgGeneral.add(new EnumSetting.Builder<Style>()
        .name("style").description("Box = fill and outline. Outline_Only and Fill_Only draw one of them. Corners draws only the corner brackets of each box.")
        .defaultValue(Style.Box).build());

    private final Setting<Double> inset = sgGeneral.add(new DoubleSetting.Builder()
        .name("box-size").description("How much of the block the box covers. 1.0 is the full block, smaller shrinks it.")
        .defaultValue(1.0).range(0.2, 1.1).sliderRange(0.2, 1.1).build());

    private final Setting<Double> bracket = sgGeneral.add(new DoubleSetting.Builder()
        .name("corner-length").description("Length of each corner bracket, as part of the box size.")
        .defaultValue(0.3).range(0.1, 0.5).sliderRange(0.1, 0.5)
        .visible(() -> style.get() == Style.Corners).build());

    private final Setting<Boolean> pulse = sgGeneral.add(new BoolSetting.Builder()
        .name("pulse").description("Make the fill slowly fade in and out.").defaultValue(false).build());

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

        Style st = style.get();
        int a = fillAlpha.get();
        if (pulse.get()) {
            double wave = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0);
            a = (int) Math.max(5, a * (0.25 + 0.75 * wave));
        }
        boolean lines = tracers.get();
        ShapeMode mode = switch (st) {
            case Outline_Only -> ShapeMode.Lines;
            case Fill_Only -> ShapeMode.Sides;
            default -> ShapeMode.Both;
        };
        double size = inset.get();
        double pad = (1.0 - size) / 2.0;
        double len = bracket.get() * size;

        Map<BaseScanner.Kind, Color> fills = new EnumMap<>(BaseScanner.Kind.class);
        for (BaseScanner.Kind k : BaseScanner.Kind.values()) {
            SettingColor c = colors.get(k).get();
            fills.put(k, new Color(c.r, c.g, c.b, a));
        }

        for (Item it : list) {
            SettingColor line = colors.get(it.kind()).get();
            double x1 = it.x() + pad, y1 = it.y() + pad, z1 = it.z() + pad;
            double x2 = it.x() + 1 - pad, y2 = it.y() + 1 - pad, z2 = it.z() + 1 - pad;

            if (st == Style.Corners) {
                corners(event, x1, y1, z1, x2, y2, z2, len, line);
            } else {
                event.renderer.box(x1, y1, z1, x2, y2, z2, fills.get(it.kind()), line, mode, 0);
            }
            if (lines) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    it.x() + 0.5, it.y() + 0.5, it.z() + 0.5, line);
            }
        }
    }

    /** Draws only the three short edges that meet at each of the 8 corners. */
    private static void corners(Render3DEvent e, double x1, double y1, double z1, double x2, double y2, double z2, double len, Color c) {
        for (int ix = 0; ix < 2; ix++) {
            for (int iy = 0; iy < 2; iy++) {
                for (int iz = 0; iz < 2; iz++) {
                    double x = ix == 0 ? x1 : x2, y = iy == 0 ? y1 : y2, z = iz == 0 ? z1 : z2;
                    double dx = ix == 0 ? len : -len, dy = iy == 0 ? len : -len, dz = iz == 0 ? len : -len;
                    e.renderer.line(x, y, z, x + dx, y, z, c);
                    e.renderer.line(x, y, z, x, y + dy, z, c);
                    e.renderer.line(x, y, z, x, y, z + dz, c);
                }
            }
        }
    }
}
