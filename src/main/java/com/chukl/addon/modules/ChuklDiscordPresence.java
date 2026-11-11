package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.discordipc.DiscordIPC;
import meteordevelopment.discordipc.RichPresence;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Discord Rich Presence (v1.71): shows "Playing ChuklAddon" on your Discord profile.
 * Needs your own Discord application ID (free, from discord.com/developers). Desktop only: the phone has no Discord pipe.
 */
public class ChuklDiscordPresence extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> appId = sgGeneral.add(new StringSetting.Builder()
        .name("application-id")
        .description("Your Discord application ID (discord.com/developers, New Application, copy the Application ID). Without it nothing is shown.")
        .defaultValue("")
        .build());

    private final Setting<String> details = sgGeneral.add(new StringSetting.Builder()
        .name("top-line")
        .description("The first line under your name. {version} becomes the ChuklAddon version.")
        .defaultValue("Playing ChuklAddon {version}")
        .build());

    private final Setting<String> largeImage = sgGeneral.add(new StringSetting.Builder()
        .name("large-image-key")
        .description("The name of the picture you uploaded under Rich Presence > Art Assets in your Discord application. Empty = none.")
        .defaultValue("")
        .build());

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("show-server")
        .description("Show the server address on your profile. Off by default so you do not give away where you play.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> showTime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-elapsed-time")
        .description("Show how long you have been playing.")
        .defaultValue(true)
        .build());

    private boolean started;
    private long startedAt;
    private String lastState = "";
    private int timer;

    public ChuklDiscordPresence() {
        super(ChuklAddon.CATEGORY, "chukl-discord-presence",
            "Shows that you are playing ChuklAddon on your Discord profile. Needs your own Discord application ID. Desktop only.");
    }

    @Override
    public void onActivate() {
        started = false;
        lastState = "";
        timer = 0;
        long id = parseId(appId.get());
        if (id == 0) {
            warning("Set application-id first: create an application at discord.com/developers and copy its Application ID.");
            return;
        }
        try {
            DiscordIPC.start(id, null);
            started = true;
            startedAt = System.currentTimeMillis() / 1000L;
            push();
        } catch (Throwable t) {
            warning("Could not reach Discord (" + t.getClass().getSimpleName() + "). Is the Discord app open on this computer? It does not work on phones.");
            ChuklAddon.LOG.warn("Discord presence failed", t);
        }
    }

    @Override
    public void onDeactivate() {
        if (!started) return;
        try {
            DiscordIPC.stop();
        } catch (Throwable ignored) {
        }
        started = false;
    }

    @Override
    public String getInfoString() {
        return started ? "on" : null;
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String version() {
        return FabricLoader.getInstance().getModContainer("chukl-addon")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    private String stateText() {
        if (mc.world == null) return "In the menu";
        if (mc.isInSingleplayer()) return "Singleplayer";
        if (showServer.get() && mc.getCurrentServerEntry() != null) return mc.getCurrentServerEntry().address;
        return "Multiplayer";
    }

    private void push() {
        if (!started) return;
        try {
            RichPresence rpc = new RichPresence();
            if (showTime.get()) rpc.setStart(startedAt);
            rpc.setDetails(details.get().replace("{version}", version()).trim());
            lastState = stateText();
            rpc.setState(lastState);
            String key = largeImage.get().trim();
            if (!key.isEmpty()) rpc.setLargeImage(key, "ChuklAddon");
            DiscordIPC.setActivity(rpc);
        } catch (Throwable t) {
            ChuklAddon.LOG.warn("Discord presence update failed", t);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!started || --timer > 0) return;
        timer = 100;
        if (!stateText().equals(lastState)) push();
    }
}
