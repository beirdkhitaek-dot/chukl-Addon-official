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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chunk Finder (v1.7): marks every loaded chunk whose storage and "not natural" evidence adds up to a probable base.
 * Colors run from yellow (maybe) to red (very likely). It only reads chunks the game already loaded, a few per tick.
 */
public class ChuklChunkFinder extends Module {
    public enum Where { At_Feet, Fixed_Y }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How many chunks around you to look at. Only chunks the game has loaded can be checked.")
        .defaultValue(12).range(2, 32).sliderRange(2, 32).build());

    private final Setting<Double> minScore = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-score")
        .description("How much evidence a chunk needs to be marked. A chest counts 1, a barrel 2, a shulker 4. Raise it to see fewer chunks.")
        .defaultValue(6.0).range(1.0, 60.0).sliderRange(1.0, 30.0).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("unnatural-max-y")
        .description("Torches, signs, beds and similar only count below this Y level, because on the surface they are normal.")
        .defaultValue(30).range(-64, 320).sliderRange(-64, 100).build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Tell you in chat when a new chunk gets marked.")
        .defaultValue(true).build());

    private final Setting<Where> where = sgRender.add(new EnumSetting.Builder<Where>()
        .name("square-height")
        .description("Draw the squares at your feet, or at a fixed Y.")
        .defaultValue(Where.At_Feet).build());

    private final Setting<Integer> fixedY = sgRender.add(new IntSetting.Builder()
        .name("fixed-y")
        .description("The Y of the squares when square-height is Fixed_Y.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320)
        .visible(() -> where.get() == Where.Fixed_Y).build());

    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder()
        .name("alpha")
        .description("How see-through the colored squares are.")
        .defaultValue(70).range(10, 255).sliderRange(10, 255).build());

    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("strongest-beam")
        .description("Draw a tall line over the single strongest chunk.")
        .defaultValue(true).build());

    private final Setting<SettingColor> weak = sgRender.add(new ColorSetting.Builder()
        .name("weak-color").description("Color of chunks just over the minimum.")
        .defaultValue(new SettingColor(255, 220, 40, 255)).build());

    private final Setting<SettingColor> strong = sgRender.add(new ColorSetting.Builder()
        .name("strong-color").description("Color of the strongest chunks.")
        .defaultValue(new SettingColor(255, 50, 40, 255)).build());

    private final BaseScanner scanner = new BaseScanner();
    private final Set<Long> announced = new HashSet<>();
    private ClientWorld lastWorld;

    public ChuklChunkFinder() {
        super(ChuklAddon.CATEGORY, "chukl-chunk-finder",
            "Marks loaded chunks that probably hold a base: chests, barrels, shulkers, hoppers and things nature does not place underground. Light on FPS, nothing to walk to.");
    }

    @Override
    public void onActivate() {
        scanner.clear();
        announced.clear();
        lastWorld = mc.world;
    }

    @Override
    public void onDeactivate() {
        scanner.clear();
        announced.clear();
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (BaseScanner.Evidence e : scanner.snapshot()) if (e.score() >= minScore.get()) n++;
        return n == 0 ? null : Integer.toString(n);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            scanner.clear();
            announced.clear();
        }
        scanner.tick(mc.world, mc.player.getChunkPos(), radius.get(), 3, 8000, maxY.get());

        if (!notify.get()) return;
        double min = minScore.get();
        for (BaseScanner.Evidence e : scanner.snapshot()) {
            if (e.score() < min) continue;
            if (announced.add(ChunkPos.toLong(e.cx(), e.cz()))) {
                info("Probable base chunk at X " + e.blockX() + ", Z " + e.blockZ() + " (" + e.storage() + " storage, "
                    + e.unnatural() + " unnatural blocks, deepest Y " + e.lowestY() + ").");
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        List<BaseScanner.Evidence> list = scanner.snapshot();
        if (list.isEmpty()) return;

        double min = minScore.get();
        double y = where.get() == Where.Fixed_Y ? fixedY.get() : Math.floor(mc.player.getY()) + 0.02;
        SettingColor a = weak.get(), b = strong.get();
        int al = alpha.get();
        BaseScanner.Evidence best = null;

        for (BaseScanner.Evidence e : list) {
            if (e.score() < min) continue;
            if (best == null || e.score() > best.score()) best = e;

            double t = Math.min(1.0, (e.score() - min) / (min * 3.0));
            int r = (int) (a.r + (b.r - a.r) * t), g = (int) (a.g + (b.g - a.g) * t), bl = (int) (a.b + (b.b - a.b) * t);
            Color side = new Color(r, g, bl, al);
            Color line = new Color(r, g, bl, 255);
            double x0 = e.cx() * 16 + 0.5, z0 = e.cz() * 16 + 0.5;
            event.renderer.box(x0, y, z0, x0 + 15, y + 0.05, z0 + 15, side, line, ShapeMode.Both, 0);
        }

        if (beam.get() && best != null) {
            SettingColor c = strong.get();
            event.renderer.line(best.blockX(), mc.world.getBottomY(), best.blockZ(),
                best.blockX(), mc.world.getBottomY() + mc.world.getHeight(), best.blockZ(), c);
        }
    }
}
