package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;

import java.io.File;
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
        .description("Name of your .wav file inside the meteor-client/chukl-visual folder, or a full path. Empty = built-in fallback beep.")
        .defaultValue("fire_in_the_hole.wav")
        .onChanged(v -> clip = null)
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
    private volatile Object clip; // javax.sound Clip, kept as Object so a missing sound API cannot break class loading

    public GrenadeCallout() {
        super(ChuklAddon.VISUAL_CATEGORY, "grenade-callout",
            "Plays a custom .wav sound (like 'Fire in the hole!') when you throw a grenade-type item.");
    }

    private static File folder() {
        File f = new File(MeteorClient.FOLDER, "chukl-visual");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    @Override
    public void onActivate() {
        seen.clear();
        age = 0;
        warned = false;
        clip = null;
        File dir = folder();
        info("Put your .wav in: " + dir.getAbsolutePath() + " and set sound-file to its name.");
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
        Thread t = new Thread(() -> {
            if (!playWav(vol)) fallback(vol);
        }, "GrenadeCallout-Sound");
        t.setDaemon(true);
        t.start();
    }

    private void fallback(float vol) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.playSound(SoundEvents.ENTITY_TNT_PRIMED, vol, 1.0F);
            if (!warned) {
                warned = true;
                info("Could not play your .wav (missing file, bad format, or no audio support). Use a 16-bit PCM .wav. Folder: " + folder().getAbsolutePath());
            }
        });
    }

    private boolean playWav(float vol) {
        try {
            String name = soundFile.get().trim();
            if (name.isEmpty()) return false;
            File f = new File(name);
            if (!f.isAbsolute()) f = new File(folder(), name);
            if (!f.isFile()) return false;

            javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(f);
            javax.sound.sampled.Clip c = javax.sound.sampled.AudioSystem.getClip();
            c.open(in);
            if (c.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                javax.sound.sampled.FloatControl g = (javax.sound.sampled.FloatControl) c.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(Math.max(0.0001, vol)));
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
            }
            c.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                    try { c.close(); } catch (Throwable ignored) {}
                }
            });
            c.start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
