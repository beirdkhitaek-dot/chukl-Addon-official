package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.cs16.Cs16Hooks;
import com.chukl.addon.cs16.MdlModel;
import com.chukl.addon.cs16.MdlRasterizer;
import com.chukl.addon.cs16.MdlWorldMesh;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
 * with the knife's own draw / idle / slash / stab animations. It is drawn behind the HUD (a mixin into the in-game HUD,
 * with a fallback to the normal overlay event). In third person and on other players the p_knife (or w_knife) model
 * is drawn in the world, attached to the hand; when a Player Model module is active it attaches to that model instead.
 */
public class Cs16Knife extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgView = settings.createGroup("View");
    private final SettingGroup sgPerf = settings.createGroup("Performance");
    private final SettingGroup sgWorld = settings.createGroup("Third Person");

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
        .description("Also replace axes. Swords use the primary attack (slash), axes use the secondary attack (stab), like Counter-Strike.")
        .defaultValue(true).build());

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


    private final Setting<Boolean> thirdPerson = sgWorld.add(new BoolSetting.Builder()
        .name("third-person").description("Draw the knife in your hand when you look at yourself in third person.")
        .defaultValue(true).build());

    private final Setting<Boolean> otherPlayers = sgWorld.add(new BoolSetting.Builder()
        .name("other-players").description("Draw the knife in the hand of other players who hold a sword or axe.")
        .defaultValue(true).build());

    private final Setting<String> worldModel = sgWorld.add(new StringSetting.Builder()
        .name("world-model")
        .description("Model for third person and other players (p_knife.mdl, w_knife.mdl or any knife .mdl) in the cs16-knives folder. Empty = automatic: p_<skin>, then p_knife, then w_knife.")
        .defaultValue("").build());

    private final Setting<Double> worldScale = sgWorld.add(new DoubleSetting.Builder()
        .name("world-scale").description("Size of the knife in blocks per model unit. Counter-Strike's own size is about 0.025.")
        .defaultValue(0.035).min(0.01).max(0.2).sliderMin(0.02).sliderMax(0.08).build());

    private final Setting<Double> worldBrightness = sgWorld.add(new DoubleSetting.Builder()
        .name("world-brightness").description("Brightness of the knife in the world.")
        .defaultValue(1.3).min(0.5).max(2.5).sliderMin(0.6).sliderMax(2.0).build());

    private final Setting<Double> worldForward = sgWorld.add(new DoubleSetting.Builder()
        .name("world-offset-forward").description("Move the knife forward (blocks).")
        .defaultValue(0.0).min(-1).max(1).sliderMin(-0.5).sliderMax(0.5).build());

    private final Setting<Double> worldRight = sgWorld.add(new DoubleSetting.Builder()
        .name("world-offset-right").description("Move the knife to the side of the holding hand (blocks).")
        .defaultValue(0.0).min(-1).max(1).sliderMin(-0.5).sliderMax(0.5).build());

    private final Setting<Double> worldUp = sgWorld.add(new DoubleSetting.Builder()
        .name("world-offset-up").description("Move the knife up (blocks).")
        .defaultValue(0.0).min(-1).max(1).sliderMin(-0.5).sliderMax(0.5).build());

    private final Setting<Double> worldPitch = sgWorld.add(new DoubleSetting.Builder()
        .name("world-pitch").description("Tilt the knife up (positive) or down (negative), in degrees.")
        .defaultValue(0.0).min(-90).max(90).sliderMin(-45).sliderMax(45).build());

    private final Setting<Boolean> worldSwing = sgWorld.add(new BoolSetting.Builder()
        .name("world-swing").description("Swing the knife when the player attacks.")
        .defaultValue(true).build());

    private final Setting<Integer> worldDistance = sgWorld.add(new IntSetting.Builder()
        .name("world-distance").description("Players farther than this many blocks keep the vanilla item.")
        .defaultValue(48).min(8).max(128).sliderMin(16).sliderMax(96).build());

    // ---- loaded model state
    private volatile MdlModel model;
    private volatile String loadedName = null;
    private volatile String loadError = null;
    private volatile boolean loading = false;
    private int idleSeq = -1, drawSeq = -1;
    // Counter-Strike: attack 1 (primary) = slash, attack 2 (secondary) = stab. Swords use attack 1, axes attack 2.
    private int[] slashHit = new int[0], slashMiss = new int[0], stabHit = new int[0], stabMiss = new int[0];

    // ---- world (third person / other players) model
    private volatile MdlWorldMesh wmesh;
    private volatile MdlModel wmodel;
    private volatile float[][] wpose;
    private volatile int wHandBone = -1;
    private final Color tmpColor = new Color(255, 255, 255, 255);
    private volatile long hudHookNanos = 0;

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
                String ln = x.getName().toLowerCase(Locale.ROOT);
                // p_ and w_ models are the third-person/world versions, not view models
                if (x.isFile() && ln.endsWith(".mdl") && !ln.startsWith("p_") && !ln.startsWith("w_")) names.add(x.getName());
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
        installBundledDefaults();
        info("Put .mdl knife models in: " + folders().get(0).getAbsolutePath());
        reloadSkin();
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        freeTexture();
        model = null;
        wmesh = null;
        wmodel = null;
        loadedName = null;
    }

    /** Copies models bundled inside the addon (assets/chukl-addon/cs16/) to the user's folder, but only when it has no skins yet. */
    private void installBundledDefaults() {
        try {
            if (!listSkins().isEmpty()) return;
            File dir = folders().get(0);
            int copied = 0;
            for (String name : new String[]{"v_knife.mdl", "p_knife.mdl", "w_knife.mdl"}) {
                try (java.io.InputStream in = Cs16Knife.class.getResourceAsStream("/assets/chukl-addon/cs16/" + name)) {
                    if (in == null) continue;
                    java.nio.file.Files.copy(in, new File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            if (copied > 0) info("Installed " + copied + " bundled knife model(s) to " + dir.getAbsolutePath());
        } catch (Throwable t) {
            warning("Could not install the bundled knife models: " + t.getMessage());
        }
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
                loadWorldModel(f.getName());
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
        List<Integer> sh = new ArrayList<>(), sm = new ArrayList<>(), th = new ArrayList<>(), tm = new ArrayList<>(), any = new ArrayList<>();
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
            if (l.contains("stab")) {
                // attack 2: stab (hit) and stab_miss
                (missy ? tm : th).add(i);
            } else if (l.contains("midslash")) {
                sh.add(i); // attack 1 that hits
            } else if (l.contains("slash") || l.contains("attack")) {
                sm.add(i); // attack 1 that misses
            }
        }
        if (idle < 0) {
            for (int i = 0; i < m.seqs.length; i++) {
                if (m.seqs[i].usable() && m.seqs[i].looping()) { idle = i; break; }
            }
        }
        if (idle < 0) {
            for (int i = 0; i < m.seqs.length; i++) if (m.seqs[i].usable()) { idle = i; break; }
        }
        // a model without one of the four swings borrows the nearest one
        if (sh.isEmpty()) sh = new ArrayList<>(sm);
        if (sm.isEmpty()) sm = new ArrayList<>(sh);
        if (th.isEmpty()) th = new ArrayList<>(tm);
        if (tm.isEmpty()) tm = new ArrayList<>(th);
        if (sh.isEmpty() && sm.isEmpty()) { sh = new ArrayList<>(th); sm = new ArrayList<>(tm); }
        if (th.isEmpty() && tm.isEmpty()) { th = new ArrayList<>(sh); tm = new ArrayList<>(sm); }
        if (sh.isEmpty() && sm.isEmpty()) { sh = new ArrayList<>(any); sm = new ArrayList<>(any); th = new ArrayList<>(any); tm = new ArrayList<>(any); }
        idleSeq = idle;
        drawSeq = draw;
        slashHit = sh.stream().mapToInt(Integer::intValue).toArray();
        slashMiss = sm.stream().mapToInt(Integer::intValue).toArray();
        stabHit = th.stream().mapToInt(Integer::intValue).toArray();
        stabMiss = tm.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Loads the p_/w_ model used in third person and on other players, and bakes it for drawing in the world. */
    private void loadWorldModel(String viewName) {
        wmesh = null;
        wmodel = null;
        List<String> tries = new ArrayList<>();
        String forced = worldModel.get().trim();
        if (!forced.isEmpty()) tries.add(forced);
        else {
            String base = viewName.toLowerCase(Locale.ROOT).replaceFirst("\\.mdl$", "").replaceFirst("^v_", "");
            tries.add("p_" + base);
            tries.add("p_knife");
            tries.add("w_knife");
        }
        for (String t : tries) {
            File f = findSkin(t);
            if (f == null) continue;
            try {
                MdlModel pm = MdlModel.load(f.toPath());
                float[][] pose = pm.pose(0, 0f, true);
                MdlWorldMesh mesh = MdlWorldMesh.bake(pm, pose, 500);
                if (mesh.count == 0) continue;
                int hand = -1;
                for (int i = 0; i < pm.bones.length; i++) {
                    String bn = pm.bones[i].name == null ? "" : pm.bones[i].name.toLowerCase(Locale.ROOT);
                    if (bn.contains("r hand") && !bn.contains("finger")) { hand = i; break; }
                }
                wpose = pose;
                wHandBone = hand;
                wmodel = pm;
                wmesh = mesh;
                info("Third person model: " + f.getName() + " (" + mesh.count + " triangles).");
                return;
            } catch (Throwable ex) {
                warning("Could not load " + f.getName() + ": " + ex.getMessage());
            }
        }
        info("No p_knife / w_knife model found next to your knife skins, so third person keeps the vanilla item.");
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
        boolean axe = mc.player.getMainHandStack().isIn(ItemTags.AXES);
        // Counter-Strike: attack 1 = slash (swords), attack 2 = stab (axes)
        int[] list = axe ? (hit ? stabHit : stabMiss) : (hit ? slashHit : slashMiss);
        if (list.length == 0) list = axe ? (hit ? stabMiss : stabHit) : (hit ? slashMiss : slashHit);
        if (list.length == 0) list = axe ? slashMiss : stabMiss;
        if (list.length == 0) return;
        int seq = list[attackCounter++ % list.length];
        play(seq, false);
    }

    /** Called from the in-game HUD mixin before the HUD is drawn, so the knife ends up behind it. */
    public static void drawBehindHud(DrawContext context) {
        Cs16Knife k = INSTANCE;
        if (k == null || !k.isActive()) return;
        k.hudHookNanos = System.nanoTime();
        k.drawKnife(context);
    }

    /** Fallback: if the HUD hook never runs (a different game version), draw on top of the HUD like before. */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (System.nanoTime() - hudHookNanos < 500_000_000L) return;
        drawKnife(event.drawContext);
    }

    private void drawKnife(DrawContext ctx) {
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
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, 0, 0, 0f, 0f, sw, sh, texW, texH, texW, texH, 0xFFFFFFFF);
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

    // ------------------------------------------------------------------ third person and other players

    /** True when this player's hand gets the world knife (shared by the drawing and the vanilla-item hiding). */
    private boolean worldTarget(LivingEntity e) {
        if (!(e instanceof PlayerEntity p) || mc.player == null || wmesh == null || model == null) return false;
        if (p == mc.player) {
            if (!thirdPerson.get() || mc.options.getPerspective().isFirstPerson()) return false;
        } else if (!otherPlayers.get()) return false;
        if (p.isSpectator()) return false;
        double d = worldDistance.get();
        if (mc.player.squaredDistanceTo(p) > d * d) return false;
        return isReplaceable(p.getMainHandStack());
    }

    /** Used by the render-state mixin: true when the vanilla item in this player's main hand should not be drawn. */
    public static boolean shouldHideWorld(LivingEntity e) {
        Cs16Knife k = INSTANCE;
        return k != null && k.isActive() && k.worldTarget(e);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        MdlWorldMesh w = wmesh;
        if (w == null || mc.world == null || mc.player == null) return;
        int drawn = 0;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!worldTarget(p)) continue;
            drawWorldKnife(event, w, p, event.tickDelta);
            if (++drawn >= 24) break; // keeps crowded servers smooth
        }
    }

    private void drawWorldKnife(Render3DEvent e, MdlWorldMesh w, PlayerEntity p, float td) {
        float bright = worldBrightness.get().floatValue();
        double scale = worldScale.get();
        double ox, oy, oz;
        double[] F = new double[3], L = new double[3], U = new double[3];
        float[] skin = null;
        float gx = 0, gy = 0, gz = 0;

        Cs16Hooks.RigProvider rp = Cs16Hooks.rigProvider;
        Cs16Hooks.Rig rig = rp == null ? null : rp.rigFor(p, td);
        if (rig != null && rig.model != null && rig.pose != null && wHandBone >= 0) {
            // A player model is drawn: the knife follows that model's right hand, like Counter-Strike's p_knife
            int hb = -1;
            for (int i = 0; i < rig.model.bones.length; i++) {
                String bn = rig.model.bones[i].name;
                if (bn != null && bn.equalsIgnoreCase(wmodel.bones[wHandBone].name)) { hb = i; break; }
            }
            if (hb >= 0) {
                skin = MdlWorldMesh.mul(rig.pose[hb], MdlWorldMesh.invert(wpose[wHandBone]));
                double yaw = Math.toRadians(rig.yawDegrees);
                F[0] = -Math.sin(yaw); F[1] = 0; F[2] = Math.cos(yaw);
                L[0] = Math.cos(yaw); L[1] = 0; L[2] = Math.sin(yaw);
                U[1] = 1;
                ox = rig.x; oy = rig.y; oz = rig.z;
                scale = rig.scale;
                emitWorld(e, w, skin, ox, oy, oz, F, L, U, scale, 0, 0, 0, bright);
                return;
            }
        }

        // vanilla player model: hold the knife where the vanilla hand holds the item
        Vec3d pos = p.getLerpedPos(td);
        double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(td, p.lastBodyYaw, p.bodyYaw));
        double[] f0 = {-Math.sin(yaw), 0, Math.cos(yaw)};
        double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        double side = p.getMainArm() == Arm.LEFT ? -1 : 1;
        double hx = pos.x + right[0] * (0.32 + worldRight.get()) * side + f0[0] * (0.30 + worldForward.get());
        double hz = pos.z + right[2] * (0.32 + worldRight.get()) * side + f0[2] * (0.30 + worldForward.get());
        double hy = pos.y + (p.isSneaking() ? 0.78 : 0.98) + worldUp.get();

        double tilt = worldPitch.get() - p.getPitch(td) * 0.4;
        if (worldSwing.get()) {
            float sw = p.getHandSwingProgress(td);
            if (sw > 0) tilt -= 70.0 * Math.sin(Math.PI * Math.sqrt(sw));
        }
        double th = Math.toRadians(tilt);
        double c = Math.cos(th), sn = Math.sin(th);
        F[0] = f0[0] * c; F[1] = sn; F[2] = f0[2] * c;
        U[0] = -f0[0] * sn; U[1] = c; U[2] = -f0[2] * sn;
        L[0] = -right[0]; L[1] = 0; L[2] = -right[2];
        emitWorld(e, w, null, hx, hy, hz, F, L, U, scale, w.gripX, w.gripY, w.gripZ, bright);
    }

    /** Draws the baked triangles. Model space (x forward, y left, z up) maps to the world through F, L and U. */
    private void emitWorld(Render3DEvent e, MdlWorldMesh w, float[] skin, double ox, double oy, double oz,
                           double[] F, double[] L, double[] U, double s, float gx, float gy, float gz, float bright) {
        double[] pt = new double[9];
        for (int i = 0; i < w.count; i++) {
            for (int k = 0; k < 3; k++) {
                float x = w.pos[i * 9 + k * 3], y = w.pos[i * 9 + k * 3 + 1], z = w.pos[i * 9 + k * 3 + 2];
                if (skin != null) {
                    float nx = skin[0] * x + skin[1] * y + skin[2] * z + skin[3];
                    float ny = skin[4] * x + skin[5] * y + skin[6] * z + skin[7];
                    float nz = skin[8] * x + skin[9] * y + skin[10] * z + skin[11];
                    x = nx; y = ny; z = nz;
                }
                x -= gx; y -= gy; z -= gz;
                pt[k * 3] = ox + s * (F[0] * x + L[0] * y + U[0] * z);
                pt[k * 3 + 1] = oy + s * (F[1] * x + L[1] * y + U[1] * z);
                pt[k * 3 + 2] = oz + s * (F[2] * x + L[2] * y + U[2] * z);
            }
            int col = w.argb[i];
            tmpColor.set(Math.min(255, (int) (((col >> 16) & 0xFF) * bright)), Math.min(255, (int) (((col >> 8) & 0xFF) * bright)),
                Math.min(255, (int) ((col & 0xFF) * bright)), 255);
            e.renderer.quad(pt[0], pt[1], pt[2], pt[3], pt[4], pt[5], pt[6], pt[7], pt[8], pt[6], pt[7], pt[8], tmpColor);
        }
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WLabel label = list.add(theme.label("Import: put .mdl knife models in the cs16-knives folder (its full path is printed in chat when you press Refresh), then press Refresh.", 360)).widget();
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
            label.set("No .mdl models found yet. Press Refresh to see the folder path in chat.");
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
