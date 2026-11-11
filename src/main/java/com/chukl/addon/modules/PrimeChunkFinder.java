package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.scan.BaseScanner;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;

import java.util.List;

/**
 * Prime Chunk Finder (v1.7): the strict one. It adds up each chunk with its 8 neighbours and shows only the ONE best
 * spot, and only when the evidence is well above a high bar. Fewer results, much fewer false alarms.
 */
public class PrimeChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How many chunks around you to look at. Only chunks the game has loaded can be checked.")
        .defaultValue(12).range(2, 32).sliderRange(2, 32).build());

    private final Setting<Double> primeMin = sgGeneral.add(new DoubleSetting.Builder()
        .name("prime-min")
        .description("Evidence needed in a 3x3 chunk area before it counts as prime. Higher is stricter.")
        .defaultValue(14.0).range(4.0, 120.0).sliderRange(4.0, 60.0).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("unnatural-max-y")
        .description("Torches, signs, beds and similar only count below this Y level.")
        .defaultValue(30).range(-64, 320).sliderRange(-64, 100).build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Tell you in chat when the prime chunk appears or moves.")
        .defaultValue(true).build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer").description("Draw a line from you to the prime chunk.").defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Color of the prime chunk marker.")
        .defaultValue(new SettingColor(255, 60, 200, 255)).build());

    private final BaseScanner scanner = new BaseScanner();
    private ClientWorld lastWorld;
    private BaseScanner.Evidence prime;
    private double primeSum;
    private int announcedCx = Integer.MIN_VALUE, announcedCz = Integer.MIN_VALUE;
    private int refreshTimer;

    public PrimeChunkFinder() {
        super(ChuklAddon.CATEGORY, "prime-chunk-finder",
            "The strict base finder: shows only the single best 3x3 chunk area, and only when the evidence is strong. Few false alarms.");
    }

    @Override
    public void onActivate() { reset(); lastWorld = mc.world; }

    @Override
    public void onDeactivate() { reset(); }

    private void reset() {
        scanner.clear();
        prime = null;
        primeSum = 0;
        announcedCx = Integer.MIN_VALUE;
        announcedCz = Integer.MIN_VALUE;
    }

    @Override
    public String getInfoString() {
        return prime == null ? null : strength();
    }

    private String strength() {
        double r = primeSum / primeMin.get();
        if (r >= 4) return "Very strong";
        if (r >= 2) return "Strong";
        return "Likely";
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) { lastWorld = mc.world; reset(); }

        scanner.tick(mc.world, mc.player.getChunkPos(), radius.get(), 3, 8000, maxY.get());

        if (--refreshTimer > 0) return;
        refreshTimer = 10;

        List<BaseScanner.Evidence> list = scanner.snapshot();
        BaseScanner.Evidence best = null;
        double bestSum = 0;
        for (BaseScanner.Evidence e : list) {
            double sum = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BaseScanner.Evidence n = scanner.at(e.cx() + dx, e.cz() + dz);
                    if (n != null) sum += n.score();
                }
            }
            if (sum > bestSum) { bestSum = sum; best = e; }
        }

        if (best == null || bestSum < primeMin.get()) {
            prime = null;
            primeSum = 0;
            return;
        }
        prime = best;
        primeSum = bestSum;

        boolean moved = Math.abs(best.cx() - announcedCx) > 1 || Math.abs(best.cz() - announcedCz) > 1;
        if (moved) {
            announcedCx = best.cx();
            announcedCz = best.cz();
            if (notify.get()) {
                info("Prime chunk (" + strength() + "): X " + best.blockX() + ", Z " + best.blockZ()
                    + ". Evidence " + Math.round(bestSum) + " in the 3x3 area around it, deepest Y " + best.lowestY() + ".");
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        BaseScanner.Evidence p = prime;
        if (p == null || mc.world == null || mc.player == null) return;

        SettingColor c = color.get();
        double x0 = (p.cx() - 1) * 16 + 0.5, z0 = (p.cz() - 1) * 16 + 0.5;
        double y = Math.floor(mc.player.getY()) + 0.02;
        event.renderer.box(x0, y, z0, x0 + 47, y + 0.05, z0 + 47, new Color(c.r, c.g, c.b, 40), c, ShapeMode.Both, 0);

        double bottom = mc.world.getBottomY();
        event.renderer.line(p.blockX(), bottom, p.blockZ(), p.blockX(), bottom + mc.world.getHeight(), p.blockZ(), c);

        if (tracer.get()) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                p.blockX(), y + 1, p.blockZ(), new Color(c.r, c.g, c.b, 140));
        }
    }
}
