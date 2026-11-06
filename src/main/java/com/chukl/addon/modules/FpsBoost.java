package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.SimpleOption;

/**
 * FPS Boost, tuned for Android phones running Minecraft through ZalithLauncher 2 with MobileGlues (OpenGL to GLES):
 * those setups are limited by draw calls and by the CPU, not by raw GPU power, so this lowers what the game has to
 * draw and simulate, and (optionally) keeps lowering or raising the view distance to hold a target FPS.
 * Your own settings are restored when the module is turned off.
 */
public class FpsBoost extends Module {
    public enum Preset { Balanced, Aggressive, Potato, Custom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAuto = settings.createGroup("Auto Tune");

    private final Setting<Preset> preset = sgGeneral.add(new EnumSetting.Builder<Preset>()
        .name("preset").description("Balanced keeps the world looking normal, Aggressive and Potato trade looks for FPS. Custom uses the values below.")
        .defaultValue(Preset.Aggressive).build());

    private final Setting<Integer> viewDistance = sgGeneral.add(new IntSetting.Builder()
        .name("view-distance").description("Render distance in chunks (Custom preset).")
        .defaultValue(8).min(2).max(32).sliderMin(3).sliderMax(16).build());

    private final Setting<Integer> simDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance").description("Simulation distance in chunks (Custom preset). Lower = less entity and block ticking.")
        .defaultValue(6).min(5).max(32).sliderMin(5).sliderMax(12).build());

    private final Setting<Double> entityDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("entity-distance").description("How far entities are drawn, 0.5 to 5 (Custom preset).")
        .defaultValue(0.6).min(0.5).max(5.0).sliderMin(0.5).sliderMax(2.0).build());

    private final Setting<Boolean> particlesMin = sgGeneral.add(new BoolSetting.Builder()
        .name("minimal-particles").description("Fewer particles (Custom preset).").defaultValue(true).build());

    private final Setting<Boolean> noClouds = sgGeneral.add(new BoolSetting.Builder()
        .name("no-clouds").description("Turn clouds off (Custom preset).").defaultValue(true).build());

    private final Setting<Boolean> noShadows = sgGeneral.add(new BoolSetting.Builder()
        .name("no-entity-shadows").description("Skip the round shadow under entities (Custom preset).").defaultValue(true).build());

    private final Setting<Boolean> noBob = sgGeneral.add(new BoolSetting.Builder()
        .name("no-view-bobbing").description("Turn off view bobbing.").defaultValue(true).build());

    private final Setting<Boolean> lowerBlend = sgGeneral.add(new BoolSetting.Builder()
        .name("no-biome-blend").description("Biome blend 0 (sharper biome borders, cheaper chunk building).").defaultValue(true).build());

    private final Setting<Boolean> auto = sgAuto.add(new BoolSetting.Builder()
        .name("auto-tune").description("Lower the view distance when FPS is under the target and raise it again when there is room.")
        .defaultValue(true).build());

    private final Setting<Integer> targetFps = sgAuto.add(new IntSetting.Builder()
        .name("target-fps").description("The FPS you want to hold.").defaultValue(40).min(15).max(120).sliderMin(20).sliderMax(90).build());

    private final Setting<Integer> minView = sgAuto.add(new IntSetting.Builder()
        .name("min-view-distance").description("Auto tune never goes under this.").defaultValue(4).min(2).max(16).sliderMin(2).sliderMax(10).build());

    private final Setting<Integer> checkSeconds = sgAuto.add(new IntSetting.Builder()
        .name("check-seconds").description("How often auto tune looks at the FPS (changing the view distance reloads chunks, so do not go too low).")
        .defaultValue(6).min(3).max(30).sliderMin(3).sliderMax(15).build());

    // values before the module changed them
    private Integer oView, oSim, oBlend;
    private Double oEntity;
    private Object oParticles;
    private CloudRenderMode oClouds;
    private Boolean oShadows, oBob;

    private int baseView = 8;
    private int curView = 8;
    private int timer;
    private int lowTicks, highTicks;

    public FpsBoost() {
        super(ChuklAddon.VISUAL_CATEGORY, "fps-boost",
            "Raises FPS on Android (ZalithLauncher 2 with MobileGlues): lower view and simulation distance, fewer particles and entities, optional auto tune that holds a target FPS.");
    }

    @Override
    public void onActivate() {
        var o = mc.options;
        oView = o.getViewDistance().getValue();
        oSim = o.getSimulationDistance().getValue();
        oBlend = o.getBiomeBlendRadius().getValue();
        oEntity = o.getEntityDistanceScaling().getValue();
        oParticles = o.getParticles().getValue();
        oClouds = o.getCloudRenderMode().getValue();
        oShadows = o.getEntityShadows().getValue();
        oBob = o.getBobView().getValue();
        apply();
        info("FPS Boost on (" + preset.get() + "): view distance " + baseView + ". Your old settings come back when you turn it off.");
    }

    @Override
    public void onDeactivate() {
        var o = mc.options;
        if (o == null || oView == null) return;
        o.getViewDistance().setValue(oView);
        o.getSimulationDistance().setValue(oSim);
        o.getBiomeBlendRadius().setValue(oBlend);
        o.getEntityDistanceScaling().setValue(oEntity);
        restore((SimpleOption) o.getParticles(), oParticles);
        o.getCloudRenderMode().setValue(oClouds);
        o.getEntityShadows().setValue(oShadows);
        o.getBobView().setValue(oBob);
        oView = null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(SimpleOption opt, String name) {
        Object cur = opt.getValue();
        if (!(cur instanceof Enum<?> e)) return;
        for (Object c : e.getDeclaringClass().getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(name)) { opt.setValue(c); return; }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restore(SimpleOption opt, Object value) {
        if (value != null) opt.setValue(value);
    }

    private void apply() {
        var o = mc.options;
        int view, sim;
        double ent;
        boolean part, clouds, shadows, bob, blend;
        switch (preset.get()) {
            case Balanced -> { view = 10; sim = 8; ent = 0.8; part = true; clouds = false; shadows = true; bob = noBob.get(); blend = false; }
            case Aggressive -> { view = 8; sim = 6; ent = 0.6; part = true; clouds = true; shadows = false; bob = true; blend = true; }
            case Potato -> { view = 5; sim = 5; ent = 0.5; part = true; clouds = true; shadows = false; bob = true; blend = true; }
            default -> { view = viewDistance.get(); sim = simDistance.get(); ent = entityDistance.get(); part = particlesMin.get(); clouds = noClouds.get(); shadows = noShadows.get(); bob = noBob.get(); blend = lowerBlend.get(); }
        }
        baseView = view;
        curView = view;
        o.getViewDistance().setValue(view);
        o.getSimulationDistance().setValue(sim);
        o.getEntityDistanceScaling().setValue(ent);
        if (part) setEnum((SimpleOption) o.getParticles(), "MINIMAL");
        if (clouds) o.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        if (shadows) o.getEntityShadows().setValue(false);
        if (bob) o.getBobView().setValue(false);
        if (blend) o.getBiomeBlendRadius().setValue(0);
        timer = 0;
        lowTicks = highTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || !auto.get() || oView == null) return;
        if (++timer < checkSeconds.get() * 20) return;
        timer = 0;
        int fps = mc.getCurrentFps();
        int target = targetFps.get();
        // wait for two low (or two high) readings in a row so a short stutter does not change anything
        if (fps < target - 3) { lowTicks++; highTicks = 0; }
        else if (fps > target + 15) { highTicks++; lowTicks = 0; }
        else { lowTicks = highTicks = 0; }

        if (lowTicks >= 2 && curView > minView.get()) {
            curView--;
            mc.options.getViewDistance().setValue(curView);
            lowTicks = 0;
        } else if (highTicks >= 3 && curView < baseView) {
            curView++;
            mc.options.getViewDistance().setValue(curView);
            highTicks = 0;
        }
    }

    @Override
    public String getInfoString() {
        return preset.get() + " " + curView + "c";
    }
}
