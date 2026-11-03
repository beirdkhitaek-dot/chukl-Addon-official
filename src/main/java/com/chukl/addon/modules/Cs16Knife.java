package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.cs16.MdlModel;
import com.chukl.addon.cs16.MdlRasterizer;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.BufferUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CS16 Knife: replaces the sword you hold (first person) with a Counter-Strike 1.6 knife view model.
 * Skins are GoldSrc .mdl view models that you import yourself (meteor-client/chukl-visual/cs16-knives/).
 * The model is drawn by a small software renderer on a background thread and shown as a screen overlay,
 * with the knife's own draw / idle / slash / stab animations.
 */
public class Cs16Knife extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgView = settings.createGroup("View");
    private final SettingGroup sgPerf = settings.createGroup("Performance");

    private final Setting<String> skin = sgGeneral.add(new StringSetting.Builder()
        .name("skin")
        .description("Name of a .mdl knife model inside meteor-client/chukl-visual/cs16-knives (with or without .mdl). Empty = the first one found.")
        .defaultValue("")
        .build());

    private final Setting<Boolean> hitAnimations = sgGeneral.add(new BoolSetting.Builder()
        .name("hit-animations")
        .description("Use the hit swing (midslash / stab) when your crosshair is on an entity and the miss swing (slash) otherwise.")
        .defaultValue(true).build());

    private final Setting<Boolean> includeAxes = sgGeneral.add(new BoolSetting.Builder()
        .name("include-axes")
        .description("Also replace axes, not only swords.")
        .defaultValue(false).build());

    private final Setting<Boolean> leftHanded = sgGeneral.add(new BoolSetting.Builder()
        .name("left-handed")
        .description("Mirror the knife to the left side.")
        .defaultValue(false).build());

    private final Setting<Double> animSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("Speed of the knife animations.")
        .defaultValue(1.0).min(0.25).max(3.0).sliderMax(2.0).build());

    private final Setting<Double> zoom = sgView.add(new DoubleSetting.Builder()
        .name("zoom")
        .description("Size of the view model (1 = like Counter-Strike).")
        .defaultValue(1.0).min(0.4).max(2.5).sliderMin(0.5).sliderMax(2.0).build());

    private final Setting<Double> moveRight = sgView.add(new DoubleSetting.Builder()
        .name("offset-right").description("Move the model right (negative = left), in model units.")
        .defaultValue(0.0).min(-20).max(20).sliderMin(-10).sliderMax(10).build());

    private final Setting<Double> moveUp = sgView.add(new DoubleSetting.Builder()
        .name("offset-up").description("Move the model up (negative = down), in model units.")
        .defaultValue(0.0).min(-20).max(20).sliderMin(-10).sliderMax(10).build());

    private final Setting<Double> moveForward = sgView.add(new DoubleSetting.Builder()
        .name("offset-forward").description("Move the model away from you (negative = closer), in model units.")
        .defaultValue(0.0).min(-10).max(30).sliderMin(-5).sliderMax(15).build());

    private final Setting<Double> brightness = sgView.add(new DoubleSetting.Builder()
        .name("brightness").description("Brightness of the model.")
        .defaultValue(1.0).min(0.4).max(1.6).sliderMin(0.5).sliderMax(1.5).build());

    private final Setting<Double> renderScale = sgPerf.add(new DoubleSetting.Builder()
        .name("render-scale")
        .description("Resolution of the knife image relative to the screen. Lower = faster (good for phones), higher = sharper.")
        .defaultValue(0.5).min(0.2).max(1.0).sliderMin(0.25).sliderMax(1.0).build());

    private final Setting<Integer> maxFps = sgPerf.add(new IntSetting.Builder()
        .name("max-fps")
        .description("How many times per second the knife image is redrawn.")
        .defaultValue(30).min(10).max(60).sliderMin(15).sliderMax(60).build());

    // ---- loaded model state
    private volatile MdlModel model;
    private volatile String loadedName = null;
    private volatile String loadError = null;
    private volatile boolean loading = false;
    private int idleSeq = -1, drawSeq = -1;
    private int[] hitSeqs = new int[0], missSeqs = new int[0];

    // ---- animation state (main thread)
    private int curSeq = -1;
    private boolean curLoop = true;
    private long seqStart = 0;
    private int attackCounter = 0;
    private float lastSwing = 0;
    private boolean wasHolding = false;
    private int lastSlot = -1;
    private ItemStack lastStack = ItemStack.EMPTY;

    // ---- rendering state
    private ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean frameReady = false;
    private int[] pixels;
    private MdlRasterizer rasterizer;
    private Texture texture;
    private Identifier textureId;
    private int texW, texH, idCounter = 0;
    private long lastSubmit = 0;

    private static Cs16Knife INSTANCE;

    public Cs16Knife() {
        super(ChuklAddon.VISUAL_CATEGORY, "cs16-knife",
            "Replaces the sword in your hand with a Counter-Strike 1.6 knife model that you import (.mdl).");
        INSTANCE = this;
    }

    // ------------------------------------------------------------------ folders and skins

    /** Folders where knife models are looked for, best first. */
    public static List<File> folders() {
        Set<File> set = new LinkedHashSet<>();
        for (File f : ChuklSound.folders()) set.add(new File(f, "cs16-knives"));
        File first = set.iterator().next();
        first.mkdirs();
        return new ArrayList<>(set);
    }

    public static List<String> listSkins() {
        Set<String> names = new LinkedHashSet<>();
        for (File dir : folders()) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            java.util.Arrays.sort(files);
            for (File x : files) {
                if (x.isFile() && x.getName().toLowerCase(Locale.ROOT).endsWith(".mdl")) names.add(x.getName());
            }
        }
        return new ArrayList<>(names);
    }

    private static File findSkin(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) {
            List<String> all = listSkins();
            if (all.isEmpty()) return null;
            n = all.get(0);
        }
        File abs = new File(n);
        if (abs.isAbsolute() && abs.isFile()) return abs;
        String lower = n.toLowerCase(Locale.ROOT);
        for (File dir : folders()) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File x : files) {
                if (!x.isFile()) continue;
                String xn = x.getName().toLowerCase(Locale.ROOT);
                if (xn.equals(lower) || xn.equals(lower + ".mdl")) return x;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Cs16Knife-Render");
            t.setDaemon(true);
            return t;
        });
        busy.set(false);
        frameReady = false;
        loadedName = null;
        curSeq = -1;
        lastStack = ItemStack.EMPTY;
        wasHolding = false;
        info("Put .mdl knife models in: " + folders().get(0).getAbsolutePath());
        reloadSkin();
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        freeTexture();
        model = null;
        loadedName = null;
    }

    private void freeTexture() {
        if (texture != null) {
            try { texture.close(); } catch (Throwable ignored) {}
        }
        texture = null;
        textureId = null;
        rasterizer = null;
        pixels = null;
    }

    /** Loads the selected skin on the background thread. */
    public void reloadSkin() {
        if (loading || executor == null) return;
        String wanted = skin.get();
        File f = findSkin(wanted);
        if (f == null) {
            model = null;
            loadedName = wanted;
            loadError = listSkins().isEmpty()
                ? "No .mdl models found. Put them in " + folders().get(0).getAbsolutePath()
                : "Skin '" + wanted + "' not found. Found: " + String.join(", ", listSkins());
            warning(loadError);
            return;
        }
        loading = true;
        executor.submit(() -> {
            try {
                MdlModel m = MdlModel.load(f.toPath());
                classify(m);
                model = m;
                loadError = null;
                curSeq = -1;
                lastStack = ItemStack.EMPTY; // plays the draw animation again
                info("Loaded " + f.getName() + " (" + m.bones.length + " bones, " + m.seqs.length + " animations).");
            } catch (Throwable t) {
                model = null;
                loadError = "Could not load " + f.getName() + ": " + t.getMessage();
                warning(loadError);
            } finally {
                loadedName = wanted;
                loading = false;
            }
        });
    }

    private void classify(MdlModel m) {
        int idle = -1, draw = -1;
        List<Integer> hit = new ArrayList<>(), miss = new ArrayList<>(), any = new ArrayList<>();
        for (int i = 0; i < m.seqs.length; i++) {
            MdlModel.Seq s = m.seqs[i];
            if (!s.usable()) continue;
            String l = s.label.toLowerCase(Locale.ROOT).trim();
            if (l.contains("draw")) { if (draw < 0) draw = i; continue; }
            if (l.startsWith("idle")) { if (idle < 0) idle = i; continue; }
            if (l.contains("charge") || l.contains("inspect") || l.contains("reload") || l.contains("holster")) continue;
            if (s.numFrames < 6) continue;
            any.add(i);
            boolean missy = l.contains("miss") || l.contains("fail");
            if (l.contains("midslash") || (l.contains("stab") && !missy)) hit.add(i);
            else if (l.contains("slash") || l.contains("stab") || l.contains("attack")) miss.add(i);
        }
        if (idle < 0) {
            for (int i = 0; i < m.seqs.length; i++) {
                if (m.seqs[i].usable() && m.seqs[i].looping()) { idle = i; break; }
            }
        }
        if (idle < 0) {
            for (int i = 0; i < m.seqs.length; i++) if (m.seqs[i].usable()) { idle = i; break; }
        }
        if (hit.isEmpty()) hit = new ArrayList<>(miss);
        if (miss.isEmpty()) miss = new ArrayList<>(hit);
        if (hit.isEmpty()) { hit = new ArrayList<>(any); miss = new ArrayList<>(any); }
        idleSeq = idle;
        drawSeq = draw;
        hitSeqs = hit.stream().mapToInt(Integer::intValue).toArray();
        missSeqs = miss.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ hooks

    private boolean isReplaceable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SWORDS) || (includeAxes.get() && stack.isIn(ItemTags.AXES));
    }

    /** Used by the held item mixin: true when the vanilla first-person sword should not be drawn. */
    public static boolean shouldHide(ItemStack stack) {
        Cs16Knife k = INSTANCE;
        if (k == null || !k.isActive() || k.model == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !client.options.getPerspective().isFirstPerson()) return false;
        return k.isReplaceable(stack);
    }

    private void play(int seq, boolean loop) {
        if (seq < 0 || model == null) return;
        curSeq = seq;
        curLoop = loop;
        seqStart = System.nanoTime();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (loadedName != null && !loadedName.equals(skin.get()) && !loading) reloadSkin();
        MdlModel m = model;
        if (m == null) return;

        ItemStack held = mc.player.getMainHandStack();
        boolean holding = isReplaceable(held);
        int slot = mc.player.getInventory().getSelectedSlot();
        if (holding && (!wasHolding || slot != lastSlot || held.getItem() != lastStack.getItem() || curSeq < 0)) {
            if (drawSeq >= 0) play(drawSeq, false);
            else play(idleSeq, true);
        }
        wasHolding = holding;
        lastSlot = slot;
        lastStack = held.copy();
        if (!holding) return;

        float swing = mc.player.getHandSwingProgress(1.0f);
        if (swing > 0 && (lastSwing <= 0 || swing < lastSwing)) startAttack();
        lastSwing = swing;
    }

    private void startAttack() {
        boolean hit = hitAnimations.get() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY;
        int[] list = hit ? hitSeqs : missSeqs;
        if (list.length == 0) list = hit ? missSeqs : hitSeqs;
        if (list.length == 0) return;
        int seq = list[attackCounter++ % list.length];
        play(seq, false);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        MdlModel m = model;
        if (m == null || executor == null || mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null || mc.options.hudHidden) return;
        if (!mc.options.getPerspective().isFirstPerson()) return;
        if (!isReplaceable(mc.player.getMainHandStack())) return;
        if (curSeq < 0) play(idleSeq >= 0 ? idleSeq : 0, true);

        ensureTarget();
        if (texture == null) return;

        long now = System.nanoTime();

        // upload a finished frame
        if (frameReady) {
            upload(pixels);
            frameReady = false;
            busy.set(false);
        }

        // advance the animation
        MdlModel.Seq s = m.seqs[curSeq];
        float fps = s.fps > 0 ? s.fps : 30f;
        float frame = (now - seqStart) / 1.0e9f * fps * animSpeed.get().floatValue();
        if (!curLoop && frame >= s.numFrames - 1) {
            play(idleSeq >= 0 ? idleSeq : curSeq, true);
            s = m.seqs[curSeq];
            fps = s.fps > 0 ? s.fps : 30f;
            frame = 0;
        }

        // ask the render thread for a new image
        long interval = 1_000_000_000L / Math.max(1, maxFps.get());
        if (!busy.get() && now - lastSubmit >= interval) {
            lastSubmit = now;
            busy.set(true);
            final int seq = curSeq;
            final float fr = frame;
            final boolean wrap = curLoop;
            final MdlRasterizer.Params p = new MdlRasterizer.Params();
            p.zoom = zoom.get().floatValue();
            p.moveRight = moveRight.get().floatValue();
            p.moveUp = moveUp.get().floatValue();
            p.moveForward = moveForward.get().floatValue();
            p.mirror = leftHanded.get();
            p.brightness = brightness.get().floatValue();
            final MdlRasterizer r = rasterizer;
            final int[] buf = pixels;
            try {
                executor.submit(() -> {
                    try {
                        r.render(m, m.pose(seq, fr, wrap), buf, p);
                        frameReady = true;
                    } catch (Throwable t) {
                        busy.set(false);
                    }
                });
            } catch (Throwable t) {
                busy.set(false);
            }
        }

        if (textureId != null) {
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            event.drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, 0, 0, 0f, 0f, sw, sh, texW, texH, texW, texH, 0xFFFFFFFF);
        }
    }

    /** Makes sure the texture and the renderer match the screen size and the render-scale setting. */
    private void ensureTarget() {
        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        int w = Math.max(160, (int) (fw * renderScale.get())), h = Math.max(90, (int) (fh * renderScale.get()));
        if (texture != null && w == texW && h == texH) return;
        busy.set(false);
        frameReady = false;
        freeTexture();
        texW = w;
        texH = h;
        pixels = new int[w * h];
        rasterizer = new MdlRasterizer(w, h);
        try {
            texture = new Texture(w, h, TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
            textureId = Identifier.of("chukl-addon", "cs16_knife_" + (idCounter++));
            mc.getTextureManager().registerTexture(textureId, texture);
        } catch (Throwable t) {
            warning("Could not create the knife texture: " + t.getMessage());
            texture = null;
            textureId = null;
        }
    }

    private void upload(int[] argb) {
        ByteBuffer bb = BufferUtils.createByteBuffer(argb.length * 4);
        IntBuffer ib = bb.asIntBuffer();
        for (int p : argb) ib.put((p & 0xFF00FF00) | ((p << 16) & 0x00FF0000) | ((p >> 16) & 0xFF));
        bb.rewind();
        texture.upload(bb);
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WLabel label = list.add(theme.label("Import: put .mdl knife models in " + folders().get(0).getPath() + ", then press Refresh.")).expandX().widget();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();

        WButton refresh = row.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            List<String> names = listSkins();
            label.set("Found " + names.size() + ": " + String.join(", ", names));
            info("Knife folder: " + folders().get(0).getAbsolutePath());
            info("Found: " + (names.isEmpty() ? "nothing yet" : String.join(", ", names)));
            if (isActive()) reloadSkin();
        };

        WButton prev = row.add(theme.button("Previous skin")).widget();
        prev.action = () -> cycle(label, -1);

        WButton next = row.add(theme.button("Next skin")).widget();
        next.action = () -> cycle(label, 1);
        return list;
    }

    private void cycle(WLabel label, int dir) {
        List<String> names = listSkins();
        if (names.isEmpty()) {
            label.set("No .mdl models found in " + folders().get(0).getPath());
            return;
        }
        String cur = skin.get().trim().toLowerCase(Locale.ROOT);
        int idx = -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).toLowerCase(Locale.ROOT);
            if (n.equals(cur) || n.equals(cur + ".mdl")) { idx = i; break; }
        }
        String pick = names.get(((idx + dir) % names.size() + names.size()) % names.size());
        skin.set(pick);
        label.set("Selected: " + pick);
        info("Skin set to " + pick);
    }

    @Override
    public String getInfoString() {
        MdlModel m = model;
        if (m == null) return loadError != null ? "no skin" : null;
        return loadedName == null || loadedName.isEmpty() ? null : loadedName.replace(".mdl", "");
    }
}
