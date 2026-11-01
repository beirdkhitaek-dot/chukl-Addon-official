package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;

import java.util.HashSet;
import java.util.Set;

/**
 * Grenade Callout: plays a sound ("Fire in the hole!") when a throwable leaves your hand.
 * The sound is a .wav file you import yourself (put it in meteor-client/chukl-visual/ or give a full path).
 */
public class GrenadeCallout extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThrowables = settings.createGroup("Throwables");

    private final Setting<String> soundFile = sgGeneral.add(new StringSetting.Builder()
        .name("sound-file")
        .description("Name of your .wav inside meteor-client/chukl-visual (with or without .wav), or a full path.")
        .defaultValue("fire_in_the_hole.wav")
        .build());

    private final Setting<Double> volume = sgGeneral.add(new DoubleSetting.Builder()
        .name("volume").description("Sound volume.")
        .defaultValue(1.0).min(0.0).max(1.0).sliderMax(1.0).build());

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks").description("Minimum ticks between callouts so spamming items does not stack the sound.")
        .defaultValue(20).min(0).sliderMax(100).build());

    private final Setting<Boolean> othersToo = sgGeneral.add(new BoolSetting.Builder()
        .name("other-players").description("Also call out throwables thrown by other players near you.")
        .defaultValue(false).build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("other-range").description("How close another player's throw must be.")
        .defaultValue(30).min(5).sliderMax(100)
        .visible(() -> othersToo.get()).build());

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message").description("Also print a short message in chat.")
        .defaultValue(false).build());

    private final Setting<Boolean> snowball = sgThrowables.add(new BoolSetting.Builder().name("snowball").defaultValue(true).build());
    private final Setting<Boolean> egg = sgThrowables.add(new BoolSetting.Builder().name("egg").defaultValue(true).build());
    private final Setting<Boolean> pearl = sgThrowables.add(new BoolSetting.Builder().name("ender-pearl").defaultValue(true).build());
    private final Setting<Boolean> splash = sgThrowables.add(new BoolSetting.Builder().name("splash-potion").defaultValue(true).build());
    private final Setting<Boolean> lingering = sgThrowables.add(new BoolSetting.Builder().name("lingering-potion").defaultValue(true).build());
    private final Setting<Boolean> xpBottle = sgThrowables.add(new BoolSetting.Builder().name("experience-bottle").defaultValue(false).build());
    private final Setting<Boolean> wind = sgThrowables.add(new BoolSetting.Builder().name("wind-charge").defaultValue(false).build());
    private final Setting<Boolean> trident = sgThrowables.add(new BoolSetting.Builder().name("trident").defaultValue(false).build());

    private final Set<Integer> seen = new HashSet<>();
    private int lastPlayed = -1000;
    private int age = 0;
    private boolean warned = false;

    public GrenadeCallout() {
        super(ChuklAddon.VISUAL_CATEGORY, "grenade-callout",
            "Plays a custom .wav sound (like 'Fire in the hole!') when you throw a grenade-type item.");
    }

    @Override
    public void onActivate() {
        seen.clear();
        age = 0;
        warned = false;
        info("Put your .wav in: " + ChuklSound.folders().get(0).getAbsolutePath() + " then press Refresh in the module settings.");
    }

    private boolean wanted(String path) {
        switch (path) {
            case "snowball": return snowball.get();
            case "egg": return egg.get();
            case "ender_pearl": return pearl.get();
            case "splash_potion": return splash.get();
            case "lingering_potion": return lingering.get();
            case "experience_bottle": return xpBottle.get();
            case "wind_charge": return wind.get();
            case "trident": return trident.get();
            default: return false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        age++;
        Set<Integer> now = new HashSet<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ProjectileEntity p)) continue;
            int id = e.getId();
            now.add(id);
            if (seen.contains(id)) continue;
            if (!wanted(Registries.ENTITY_TYPE.getId(e.getType()).getPath())) continue;

            Entity owner = p.getOwner();
            boolean mine = owner == mc.player;
            if (!mine) {
                if (!othersToo.get() || owner == null) continue;
                if (owner.distanceTo(mc.player) > range.get()) continue;
            }
            if (age - lastPlayed < cooldown.get()) continue;
            lastPlayed = age;
            callout();
        }
        seen.clear();
        seen.addAll(now);
    }

    private void callout() {
        if (chat.get()) info("Fire in the hole!");
        float vol = volume.get().floatValue();
        String name = soundFile.get();
        Thread t = new Thread(() -> {
            String err = ChuklSound.play(name, vol);
            if (!err.isEmpty()) mc.execute(() -> {
                if (mc.player != null) mc.player.playSound(SoundEvents.ENTITY_TNT_PRIMED, vol, 1.0F);
                if (!warned) { warned = true; info(err); }
            });
        }, "GrenadeCallout-Sound");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        return SoundWidget.make(theme, soundFile, () -> volume.get().floatValue(), this::info);
    }
}
