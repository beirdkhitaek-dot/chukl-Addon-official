package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;

import java.util.List;

/** Kill Bell: plays a sound (default: the Half-Life bell) when a player you were fighting dies. */
public class KillBell extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> soundFile = sg.add(new StringSetting.Builder()
        .name("sound-file")
        .description("A .wav in meteor-client/chukl-visual (name with or without .wav). bell1.wav is built in.")
        .defaultValue("bell1.wav").build());

    private final Setting<Double> volume = sg.add(new DoubleSetting.Builder()
        .name("volume").defaultValue(1.0).min(0.0).sliderMax(1.0).build());

    private final Setting<Integer> window = sg.add(new IntSetting.Builder()
        .name("kill-window-ticks").description("A death counts as your kill if you hit the player within this many ticks.")
        .defaultValue(80).min(5).sliderMax(400).build());

    private final Setting<Boolean> anyDeath = sg.add(new BoolSetting.Builder()
        .name("any-player-death").description("Ring for every player that dies near you, not just your kills.")
        .defaultValue(false).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat-message").description("Print the name of the player.").defaultValue(false).build());

    private final KillTracker tracker = new KillTracker();
    private int tick = 0;
    private boolean warned = false;

    public KillBell() {
        super(ChuklAddon.VISUAL_CATEGORY, "kill-bell", "Plays a bell sound (Half-Life style) when you kill a player. Custom .wav supported.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        return SoundWidget.make(theme, soundFile, () -> volume.get().floatValue(), this::info);
    }

    @Override
    public void onActivate() {
        tracker.clear();
        tick = 0;
        warned = false;
        info("Sounds folder: " + ChuklSound.folders().get(0).getAbsolutePath());
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof PlayerEntity) tracker.hit(event.entity.getId(), tick);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        tick++;
        List<PlayerEntity> dead = tracker.deaths(mc.world, mc.player, tick, window.get(), anyDeath.get());
        for (PlayerEntity p : dead) {
            if (chat.get()) info(p.getName().getString() + " died.");
            ring();
        }
    }

    private void ring() {
        float vol = volume.get().floatValue();
        String name = soundFile.get();
        Thread t = new Thread(() -> {
            String err = ChuklSound.play(name, vol);
            if (!err.isEmpty()) mc.execute(() -> {
                if (mc.player != null) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, vol, 0.6F);
                if (!warned) { warned = true; info(err); }
            });
        }, "KillBell-Sound");
        t.setDaemon(true);
        t.start();
    }
}
