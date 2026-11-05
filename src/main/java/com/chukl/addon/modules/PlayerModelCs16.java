package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.cs16.Cs16Hooks;
import com.chukl.addon.cs16.MdlAtlas;
import com.chukl.addon.cs16.MdlModel;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
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
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Player Model (CS16): draws players with Counter-Strike 1.6 player models (.mdl) that you import, animated like the game:
 * the legs play the gait animation (idle, walk, run, crouch, jump, swim) at the speed the player really moves, the upper body
 * plays the weapon animation of the held item (aim, attack) and follows where the player looks (pitch blends), and a dying
 * player plays a death animation. The model is drawn through the game's own entity rendering, so it has real textures,
 * lighting and the vanilla name tag. The CS16 Knife attaches p_knife to this model's right hand.
 */
public class PlayerModelCs16 extends Module {
    public enum ApplyTo { Everyone, Others_Only, Self_Only }

    public enum Weapon {
        Auto("auto"), Knife("knife"), Pistol("onehanded"), Rifle("rifle"), Carbine("carbine"), MP5("mp5"), Shotgun("shotgun"),
        M249("m249"), AK47("ak47"), C4("c4"), Dual_Pistols("dualpistols"), Grenade("grenade");
        final String key;
        Weapon(String key) { this.key = key; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAnim = settings.createGroup("Animation");

    private final Setting<String> model = sgGeneral.add(new StringSetting.Builder()
        .name("model")
        .description("Player model in meteor-client/chukl-visual/cs16-players (for example sas, or sas/sas). Empty = the first one found.")
        .defaultValue("").build());

    private final Setting<String> selfModel = sgGeneral.add(new StringSetting.Builder()
        .name("own-model")
        .description("A different model for yourself in third person. Empty = the same as everyone.")
        .defaultValue("").build());

    private final Setting<Boolean> randomPerPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("random-per-player")
        .description("Give every player one of your imported models (always the same one for the same player).")
        .defaultValue(false).build());

    private final Setting<ApplyTo> applyTo = sgGeneral.add(new EnumSetting.Builder<ApplyTo>()
        .name("apply-to").description("Who gets the model.")
        .defaultValue(ApplyTo.Everyone).build());

    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Size of the model (1 = Counter-Strike's own size, where the eyes are as high as in Minecraft).")
        .defaultValue(1.0).min(0.5).max(1.6).sliderMin(0.7).sliderMax(1.3).build());

    private final Setting<Integer> distance = sgGeneral.add(new IntSetting.Builder()
        .name("distance").description("Players farther than this many blocks keep the vanilla model (saves performance).")
        .defaultValue(64).min(8).max(160).sliderMin(16).sliderMax(128).build());

    private final Setting<Boolean> hideArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-vanilla-armor").description("Hide vanilla armor, cape and head items (they would float around the CS model).")
        .defaultValue(true).build());

    private final Setting<Weapon> weaponPose = sgAnim.add(new EnumSetting.Builder<Weapon>()
        .name("weapon-pose")
        .description("Which weapon animation the upper body plays. Auto: sword/axe/trident = knife, bow/crossbow = rifle, other items = pistol, empty hand = knife.")
        .defaultValue(Weapon.Auto).build());

    private final Setting<Double> attackSpeed = sgAnim.add(new DoubleSetting.Builder()
        .name("attack-speed").description("Speed of the attack animation (Minecraft swings are quick, so this is above 1).")
        .defaultValue(2.0).min(0.5).max(5.0).sliderMin(1).sliderMax(4).build());

    private final Setting<Boolean> deathAnimation = sgAnim.add(new BoolSetting.Builder()
        .name("death-animation").description("Play a Counter-Strike death animation when a player dies.")
        .defaultValue(true).build());

    private final Setting<Boolean> invertPitch = sgAnim.add(new BoolSetting.Builder()
        .name("invert-pitch").description("Turn on if the upper body looks up when the player looks down.")
        .defaultValue(false).build());

    private final Setting<Double> bodyTurn = sgAnim.add(new DoubleSetting.Builder()
        .name("facing-offset").description("Turns the model around its vertical axis, in degrees, if a model faces the wrong way.")
        .defaultValue(0.0).min(-180).max(180).sliderMin(-180).sliderMax(180).build());

    private final Setting<Boolean> handItems = sgAnim.add(new BoolSetting.Builder()
        .name("items-in-hand").description("Draw held items in the CS model's hand and make them follow its animation.")
        .defaultValue(true).build());

    private final Setting<Double> itemScale = sgAnim.add(new DoubleSetting.Builder()
        .name("item-scale").description("Size of held items in the CS hand.")
        .defaultValue(1.0).min(0.3).max(2.5).sliderMin(0.5).sliderMax(1.5).build());

    private final Setting<Double> itemSide = sgAnim.add(new DoubleSetting.Builder()
        .name("item-offset-side").description("Move held items sideways in the hand (blocks).")
        .defaultValue(0.0).min(-0.5).max(0.5).sliderMin(-0.3).sliderMax(0.3).build());

    private final Setting<Double> itemUp = sgAnim.add(new DoubleSetting.Builder()
        .name("item-offset-up").description("Move held items along the arm (blocks, positive = toward the shoulder).")
        .defaultValue(0.0).min(-0.5).max(0.5).sliderMin(-0.3).sliderMax(0.3).build());

    private final Setting<Double> itemForward = sgAnim.add(new DoubleSetting.Builder()
        .name("item-offset-forward").description("Move held items forward from the hand (blocks).")
        .defaultValue(0.0).min(-0.5).max(0.5).sliderMin(-0.3).sliderMax(0.3).build());

    // ---- model slots
    private static final class Slot {
        final String key;
        volatile MdlModel model;
        volatile MdlAtlas atlas;
        volatile boolean failed;
        Texture texture;
        float[][] ref;
        int rightHand = -2, leftHand = -2;
        Identifier texId;
        final Map<String, Integer> seq = new HashMap<>();
        Slot(String key) { this.key = key; }
        int seq(String name) { Integer i = seq.get(name); return i == null ? -1 : i; }
        boolean ready() { return texture != null && model != null && atlas != null; }
    }

    // ---- per-player animation state (render thread)
    private static final class State {
        long last, seen;
        double lx, lz;
        boolean hasPos;
        float speed;
        float airT;
        float gaitPhase;
        String gaitName = "";
        float aimPhase;
        float shootT = -1;
        float prevSwing;
        float deathT;
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Map<Integer, State> states = new HashMap<>();
    private final Map<Integer, Cs16Hooks.Rig> rigs = new HashMap<>();
    private final Set<ItemRenderState> handled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private ExecutorService executor;
    private int idCounter = 0;
    private int frameCounter = 0;
    private String lastMessage = "";

    private static PlayerModelCs16 INSTANCE;
    private static final float CS_BLOCKS_PER_UNIT = 0.0253f; // Counter-Strike's eyes are 64 units up, Minecraft's are 1.62 blocks
    private static final float FOOT = 36f;                   // the model origin is 36 units above the floor

    public PlayerModelCs16() {
        super(ChuklAddon.VISUAL_CATEGORY, "cs16-player-model",
            "Draws players as Counter-Strike 1.6 player models (.mdl you import) with CS animations: walk, run, crouch, jump, swim, weapon poses, attacks and deaths.");
        INSTANCE = this;
    }

    // ------------------------------------------------------------------ folders

    public static List<File> folders() {
        Set<File> set = new LinkedHashSet<>();
        for (File f : ChuklSound.folders()) set.add(new File(f, "cs16-players"));
        File first = set.iterator().next();
        first.mkdirs();
        return new ArrayList<>(set);
    }

    /** Model keys like "sas/sas" or "militia". Skips texture-only companion files (gignT.mdl next to gign.mdl). */
    public static List<String> listModels() {
        Set<String> keys = new LinkedHashSet<>();
        for (File dir : folders()) {
            scan(dir, "", keys, 1);
        }
        return new ArrayList<>(keys);
    }

    private static void scan(File dir, String prefix, Set<String> keys, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            String ln = n.toLowerCase(Locale.ROOT);
            if (f.isDirectory() && depth > 0) { scan(f, prefix + n + "/", keys, depth - 1); continue; }
            if (!f.isFile() || !ln.endsWith(".mdl")) continue;
            String base = n.substring(0, n.length() - 4);
            if (ln.endsWith("t.mdl") && new File(dir, base.substring(0, base.length() - 1) + ".mdl").isFile()) continue; // texture file
            keys.add(prefix + base);
        }
    }

    private static File findFile(String key) {
        String k = key.trim().replace('\\', '/');
        for (File dir : folders()) {
            File exact = new File(dir, k + ".mdl");
            if (exact.isFile()) return exact;
        }
        String lower = k.toLowerCase(Locale.ROOT);
        for (String candidate : listModels()) {
            String cl = candidate.toLowerCase(Locale.ROOT);
            String baseName = cl.contains("/") ? cl.substring(cl.lastIndexOf('/') + 1) : cl;
            if (cl.equals(lower) || baseName.equals(lower)) {
                for (File dir : folders()) {
                    File f = new File(dir, candidate + ".mdl");
                    if (f.isFile()) return f;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Cs16PlayerModel-Load");
            t.setDaemon(true);
            return t;
        });
        Cs16Hooks.rigProvider = (entity, td) -> {
            Cs16Hooks.Rig r = rigs.get(entity.getId());
            State st = states.get(entity.getId());
            if (r == null || st == null || System.nanoTime() - st.seen > 300_000_000L) return null;
            return r;
        };
        info("Put player models (for example sas/sas.mdl) in: " + folders().get(0).getAbsolutePath());
        List<String> found = listModels();
        if (found.isEmpty()) warning("No player models found yet. Players keep the vanilla model until you add one.");
        else info("Found " + found.size() + " model(s): " + String.join(", ", found));
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        if (Cs16Hooks.rigProvider != null) Cs16Hooks.rigProvider = null;
        for (Slot s : slots.values()) {
            if (s.texture != null) {
                try { s.texture.close(); } catch (Throwable ignored) {}
            }
        }
        slots.clear();
        states.clear();
        rigs.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // textures are created on the game thread, after the background thread has built the image
        for (Slot s : slots.values()) {
            if (s.texture == null && s.atlas != null && !s.failed) createTexture(s);
        }
    }

    private void createTexture(Slot s) {
        MdlAtlas a = s.atlas;
        try {
            Texture t = new Texture(a.width, a.height, TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
            ByteBuffer bb = BufferUtils.createByteBuffer(a.argb.length * 4);
            IntBuffer ib = bb.asIntBuffer();
            for (int p : a.argb) ib.put((p & 0xFF00FF00) | ((p << 16) & 0x00FF0000) | ((p >> 16) & 0xFF));
            bb.rewind();
            t.upload(bb);
            Identifier id = Identifier.of("chukl-addon", "cs16_player_" + (idCounter++));
            mc.getTextureManager().registerTexture(id, t);
            s.texture = t;
            s.texId = id;
            info("Model ready: " + s.key + " (" + a.triCount + " triangles).");
        } catch (Throwable t) {
            s.failed = true;
            warning("Could not create the texture of " + s.key + ": " + t.getMessage());
        }
    }

    private Slot slot(String key) {
        Slot existing = slots.get(key);
        if (existing != null) return existing;
        Slot s = new Slot(key);
        slots.put(key, s);
        ExecutorService ex = executor;
        if (ex == null) return s;
        ex.submit(() -> {
            try {
                File f = findFile(key);
                if (f == null) throw new IllegalStateException("model not found in " + folders().get(0).getAbsolutePath());
                MdlModel m = MdlModel.load(f.toPath());
                for (int i = 0; i < m.seqs.length; i++) {
                    if (m.seqs[i].usable()) s.seq.putIfAbsent(m.seqs[i].label.toLowerCase(Locale.ROOT).trim(), i);
                }
                if (s.seq("idle1") < 0 && s.seq("idle") < 0) throw new IllegalStateException("this is not a player model (no idle1 animation)");
                s.model = m;
                s.atlas = MdlAtlas.build(m);
            } catch (Throwable t) {
                s.failed = true;
                warning("Could not load player model " + key + ": " + t.getMessage());
            }
        });
        return s;
    }

    /** Which model key this player should wear. */
    private String keyFor(PlayerEntity p) {
        List<String> all = listModels();
        if (all.isEmpty()) return null;
        if (p == mc.player && !selfModel.get().isBlank()) return selfModel.get().trim();
        if (randomPerPlayer.get()) return all.get(Math.floorMod(p.getUuid().hashCode(), all.size()));
        String m = model.get().trim();
        return m.isEmpty() ? all.get(0) : m;
    }

    private boolean applies(PlayerEntity p) {
        if (p.isSpectator()) return false;
        ApplyTo a = applyTo.get();
        if (a == ApplyTo.Others_Only && p == mc.player) return false;
        if (a == ApplyTo.Self_Only && p != mc.player) return false;
        double d = distance.get();
        return mc.player.squaredDistanceTo(p) <= d * d;
    }

    // ------------------------------------------------------------------ rendering (called from the entity renderer mixin)

    public static void onRenderEntity(LivingEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        PlayerModelCs16 m = INSTANCE;
        if (m == null || !m.isActive()) return;
        try {
            m.render(state, matrices, queue);
        } catch (Throwable t) {
            // never let a bad model take the game down: fall back to the vanilla model
            if (!String.valueOf(t.getMessage()).equals(m.lastMessage)) {
                m.lastMessage = String.valueOf(t.getMessage());
                m.warning("Player model error: " + t);
            }
        }
    }

    private void render(LivingEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        if (!(state instanceof PlayerEntityRenderState ps) || mc.world == null || mc.player == null) return;
        Entity e = mc.world.getEntityById(ps.id);
        if (!(e instanceof PlayerEntity pe) || !applies(pe)) return;
        String key = keyFor(pe);
        if (key == null) return;
        Slot slot = slot(key);
        if (!slot.ready()) return; // still loading: the vanilla model stays until the CS model is ready

        State st = states.computeIfAbsent(ps.id, k -> new State());
        long now = System.nanoTime();
        float dt = st.last == 0 ? 0.016f : Math.max(0.0005f, Math.min(0.1f, (now - st.last) / 1e9f));
        st.last = now;
        st.seen = now;
        if (++frameCounter % 600 == 0) states.values().removeIf(x -> now - x.seen > 3_000_000_000L);

        float scale = CS_BLOCKS_PER_UNIT * size.get().floatValue();
        float[][] pose = animate(slot, st, ps, pe, dt, scale);

        // the model replaces the vanilla body; held items, name tag and shadow stay vanilla
        ps.invisible = true;
        ps.invisibleToPlayer = true;
        if (hideArmor.get()) {
            ps.capeVisible = false;
            ps.headItemRenderState.clear();
            if (state instanceof BipedEntityRenderState b) {
                b.equippedHeadStack = ItemStack.EMPTY;
                b.equippedChestStack = ItemStack.EMPTY;
                b.equippedLegsStack = ItemStack.EMPTY;
                b.equippedFeetStack = ItemStack.EMPTY;
            }
        }

        MdlAtlas a = slot.atlas;
        double yaw = Math.toRadians(ps.bodyYaw + bodyTurn.get());
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw), lx = (float) Math.cos(yaw), lz = (float) Math.sin(yaw);

        int nv = a.vbone.length;
        float[] pos = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            float[] m = pose[a.vbone[i]];
            float x = a.vpos[i * 3], y = a.vpos[i * 3 + 1], z = a.vpos[i * 3 + 2];
            float mx = m[0] * x + m[1] * y + m[2] * z + m[3];
            float my = m[4] * x + m[5] * y + m[6] * z + m[7];
            float mz = m[8] * x + m[9] * y + m[10] * z + m[11];
            pos[i * 3] = scale * (fx * mx + lx * my);
            pos[i * 3 + 1] = scale * (mz + FOOT);
            pos[i * 3 + 2] = scale * (fz * mx + lz * my);
        }
        int nn = a.nbone.length;
        float[] nrm = new float[nn * 3];
        for (int i = 0; i < nn; i++) {
            float[] m = pose[a.nbone[i]];
            float x = a.npos[i * 3], y = a.npos[i * 3 + 1], z = a.npos[i * 3 + 2];
            float mx = m[0] * x + m[1] * y + m[2] * z;
            float my = m[4] * x + m[5] * y + m[6] * z;
            float mz = m[8] * x + m[9] * y + m[10] * z;
            nrm[i * 3] = fx * mx + lx * my;
            nrm[i * 3 + 1] = mz;
            nrm[i * 3 + 2] = fz * mx + lz * my;
        }

        // rig for attachments (the CS16 Knife puts p_knife in the right hand)
        Cs16Hooks.Rig rig = rigs.computeIfAbsent(ps.id, k -> new Cs16Hooks.Rig());
        rig.model = slot.model;
        rig.pose = pose;
        rig.x = ps.x;
        rig.y = ps.y + FOOT * scale;
        rig.z = ps.z;
        rig.yawDegrees = ps.bodyYaw + bodyTurn.get();
        rig.scale = scale;

        final int light = ps.light;
        final int g = ps.hurt ? 110 : 255;
        queue.submitCustom(matrices, RenderLayers.entityCutoutNoCull(slot.texId), (entry, vc) -> {
            for (int t = 0; t < a.triCount; t++) {
                for (int k = 0; k < 4; k++) {
                    int c = k == 3 ? 2 : k;
                    int v = a.corner[t * 3 + c], n = a.cnorm[t * 3 + c];
                    vc.vertex(entry, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                        .color(255, g, g, 255)
                        .texture(a.uv[t * 6 + c * 2], a.uv[t * 6 + c * 2 + 1])
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(entry, nrm[n * 3], nrm[n * 3 + 1], nrm[n * 3 + 2]);
                }
            }
        });

        if (handItems.get()) {
            if (slot.ref == null) slot.ref = slot.model.poseCs(-1, 0, false, 0.5f, firstSeq(slot, "idle1", "idle"), 0, false);
            if (slot.rightHand == -2) slot.rightHand = findBone(slot.model, "r");
            if (slot.leftHand == -2) slot.leftHand = findBone(slot.model, "l");
            drawHand(slot, ps, pose, true, matrices, queue, fx, fz, lx, lz, scale);
            drawHand(slot, ps, pose, false, matrices, queue, fx, fz, lx, lz, scale);
        }
    }

    private static int findBone(MdlModel m, String side) {
        int best = -1;
        for (int i = 0; i < m.bones.length; i++) {
            String n = m.bones[i].name == null ? "" : m.bones[i].name.toLowerCase(Locale.ROOT).trim();
            if (n.equals("bip01 " + side + " hand")) return i;
            if (n.endsWith(side + " hand") && best < 0) best = i;
        }
        return best;
    }

    /** True once if this item state was drawn in the CS hand, so the vanilla hand renderer skips it. */
    public static boolean takeHandled(ItemRenderState st) {
        PlayerModelCs16 m = INSTANCE;
        return m != null && m.handled.remove(st);
    }

    /**
     * Draws the held item at the CS hand. The item keeps vanilla's own hold (grip, tilt), but the arm frame it hangs from
     * is the hand bone's movement since the idle pose, so it follows every animation.
     */
    private void drawHand(Slot slot, PlayerEntityRenderState ps, float[][] pose, boolean right, MatrixStack matrices,
                          OrderedRenderCommandQueue queue, float fx, float fz, float lx, float lz, float scale) {
        ItemRenderState is = right ? ps.rightHandItemState : ps.leftHandItemState;
        if (is == null || is.isEmpty()) return;
        int hb = right ? slot.rightHand : slot.leftHand;
        if (hb < 0 || hb >= pose.length || hb >= slot.ref.length) return;
        float[] d = com.chukl.addon.cs16.MdlWorldMesh.mul(pose[hb], com.chukl.addon.cs16.MdlWorldMesh.invert(slot.ref[hb]));
        float[] c0 = {d[1], d[5], d[9]}, c1 = {-d[2], -d[6], -d[10]}, c2 = {-d[0], -d[4], -d[8]};
        Matrix3f rot = new Matrix3f(
            fx * c0[0] + lx * c0[1], c0[2], fz * c0[0] + lz * c0[1],
            fx * c1[0] + lx * c1[1], c1[2], fz * c1[0] + lz * c1[1],
            fx * c2[0] + lx * c2[1], c2[2], fz * c2[0] + lz * c2[1]);
        Quaternionf q = new Quaternionf().setFromNormalized(rot);
        float tx = pose[hb][3], ty = pose[hb][7], tz = pose[hb][11];
        float side = right ? 1f : -1f;
        matrices.push();
        matrices.translate(scale * (fx * tx + lx * ty), scale * (tz + FOOT), scale * (fz * tx + lz * ty));
        matrices.multiply(q);
        matrices.translate(side / 16f + itemSide.get().floatValue() * side, -0.625f + itemUp.get().floatValue(), 0.125f + itemForward.get().floatValue());
        float sc = itemScale.get().floatValue();
        matrices.scale(sc, sc, sc);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
        matrices.translate(side / 16f, 0.125f, -0.625f);
        is.render(matrices, queue, ps.light, OverlayTexture.DEFAULT_UV, 0);
        matrices.pop();
        handled.add(is);
    }

    // ------------------------------------------------------------------ Counter-Strike animation logic

    private float[][] animate(Slot slot, State st, PlayerEntityRenderState ps, PlayerEntity pe, float dt, float scale) {
        MdlModel m = slot.model;

        // real speed in blocks per second, smoothed
        if (st.hasPos) {
            float inst = (float) (Math.hypot(ps.x - st.lx, ps.z - st.lz) / dt);
            inst = Math.min(inst, 20f); // a teleport is not a sprint
            st.speed += (inst - st.speed) * Math.min(1f, dt * 8f);
        }
        st.lx = ps.x;
        st.lz = ps.z;
        st.hasPos = true;

        boolean crouch = ps.isInSneakingPose || ps.sneaking;
        boolean dead = deathAnimation.get() && (ps.deathTime > 0 || pe.isDead());
        boolean air = !pe.isOnGround() && !ps.touchingWater && !pe.isClimbing() && !ps.isGliding;
        st.airT = air ? st.airT + dt : 0f;
        boolean moving = st.speed > 0.3f;

        // ---- legs: the gait
        String gait;
        boolean gaitLoop = true;
        if (dead) {
            gait = crouch && slot.seq("crouch_die") >= 0 ? "crouch_die" : "death" + (1 + Math.floorMod(ps.id, 3));
            gaitLoop = false;
        } else if (ps.touchingWater || ps.isSwimming) gait = moving ? "swim" : "treadwater";
        else if (air) gait = st.speed > 5f ? "longjump" : "jump";
        else if (moving) gait = crouch ? "crouchrun" : (pe.isSprinting() || st.speed > 4.9f ? "run" : "walk");
        else gait = crouch ? "crouch_idle" : "idle1";
        int gi = firstSeq(slot, gait, "idle1", "idle");
        MdlModel.Seq gs = m.seqs[gi];
        float gfps = gs.fps > 0 ? gs.fps : 30f;

        if (!gait.equals(st.gaitName)) {
            // keep the stride when walk <-> run, restart other changes
            boolean stride = (gait.equals("walk") || gait.equals("run") || gait.equals("crouchrun")) && (st.gaitName.equals("walk") || st.gaitName.equals("run") || st.gaitName.equals("crouchrun"));
            st.gaitPhase = stride && gs.numFrames > 0 ? st.gaitPhase % gs.numFrames : 0f;
            if (dead) st.deathT = 0f;
            st.gaitName = gait;
        }
        float gframe;
        if (dead) {
            st.deathT += dt;
            gframe = st.deathT * gfps;
            gaitLoop = false;
        } else if (gait.startsWith("jump") || gait.equals("longjump")) {
            gframe = st.airT * gfps;
            gaitLoop = false;
        } else {
            float rate = gfps;
            float ground = gs.groundSpeed();
            if (moving && ground > 1f) {
                float unitsPerSecond = st.speed / scale; // how fast the player really moves, in model units
                rate = gfps * Math.max(0.25f, Math.min(3f, unitsPerSecond / ground));
            }
            st.gaitPhase += dt * rate;
            gframe = st.gaitPhase;
        }

        if (dead) return m.poseCs(-1, 0, false, 0.5f, gi, gframe, false);

        // ---- upper body: the weapon animation of the held item
        String w = weaponKey(pe);
        String prefix = crouch ? "crouch_" : "ref_";
        int aim = firstSeq(slot, prefix + "aim_" + w, "ref_aim_" + w, "ref_aim_knife");
        int shoot = firstSeq(slot, prefix + "shoot_" + w, "ref_shoot_" + w, "ref_shoot_knife");
        if (shoot == gi) shoot = -1;

        if (ps.handSwingProgress > 0 && st.prevSwing <= 0 && shoot >= 0) st.shootT = 0f;
        st.prevSwing = ps.handSwingProgress;

        int upper;
        float uframe;
        boolean uwrap;
        if (st.shootT >= 0 && shoot >= 0) {
            MdlModel.Seq ss = m.seqs[shoot];
            st.shootT += dt * (ss.fps > 0 ? ss.fps : 30f) * attackSpeed.get().floatValue();
            if (st.shootT >= ss.numFrames - 1) st.shootT = -1f;
            upper = shoot;
            uframe = Math.max(0f, st.shootT);
            uwrap = false;
            if (st.shootT < 0) { upper = aim; uframe = 0; uwrap = true; }
        } else {
            MdlModel.Seq as = aim >= 0 ? m.seqs[aim] : null;
            st.aimPhase += dt * (as != null && as.fps > 0 ? as.fps : 12f);
            upper = aim;
            uframe = st.aimPhase;
            uwrap = true;
        }

        // pitch: -90 (up) .. 90 (down) spread over the blends of the aim animations
        float pitch = ps.pitch * (invertPitch.get() ? -1f : 1f);
        MdlModel.Seq us = upper >= 0 ? m.seqs[upper] : null;
        float lo = us != null && us.blendEnd != us.blendStart ? us.blendStart : -90f;
        float hi = us != null && us.blendEnd != us.blendStart ? us.blendEnd : 90f;
        float blend = (pitch - lo) / (hi - lo);
        return m.poseCs(upper, uframe, uwrap, blend, gi, gframe, gaitLoop);
    }

    private int firstSeq(Slot slot, String... names) {
        for (String n : names) {
            int i = slot.seq(n);
            if (i >= 0) return i;
        }
        // last resort: the first usable animation
        for (int i = 0; i < slot.model.seqs.length; i++) if (slot.model.seqs[i].usable()) return i;
        return 0;
    }

    private String weaponKey(PlayerEntity pe) {
        Weapon w = weaponPose.get();
        if (w != Weapon.Auto) return w.key;
        ItemStack s = pe.getMainHandStack();
        if (s.isEmpty()) return "knife";
        if (s.isIn(ItemTags.SWORDS) || s.isIn(ItemTags.AXES) || s.isOf(Items.TRIDENT)) return "knife";
        if (s.isOf(Items.BOW) || s.isOf(Items.CROSSBOW)) return "rifle";
        return "onehanded";
    }

    // ------------------------------------------------------------------ GUI

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WLabel label = list.add(theme.label("Import: put player models (for example sas/sas.mdl) in the cs16-players folder (its full path is printed in chat when you press Refresh), then press Refresh.", 360)).widget();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();

        WButton refresh = row.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            List<String> names = listModels();
            label.set("Found " + names.size() + ": " + String.join(", ", names));
            info("Player model folder: " + folders().get(0).getAbsolutePath());
            info("Found: " + (names.isEmpty() ? "nothing yet" : String.join(", ", names)));
        };
        row.add(theme.button("Previous model")).widget().action = () -> cycle(label, -1);
        row.add(theme.button("Next model")).widget().action = () -> cycle(label, 1);
        return list;
    }

    private void cycle(WLabel label, int dir) {
        List<String> names = listModels();
        if (names.isEmpty()) {
            label.set("No player models found yet. Press Refresh to see the folder path in chat.");
            return;
        }
        String cur = model.get().trim().toLowerCase(Locale.ROOT);
        int idx = -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).toLowerCase(Locale.ROOT);
            if (n.equals(cur) || n.endsWith("/" + cur)) { idx = i; break; }
        }
        String pick = names.get(((idx + dir) % names.size() + names.size()) % names.size());
        model.set(pick);
        label.set("Selected: " + pick);
        info("Model set to " + pick);
    }

    @Override
    public String getInfoString() {
        String m = model.get().trim();
        if (m.isEmpty()) { List<String> l = listModels(); return l.isEmpty() ? "no model" : l.get(0); }
        return m;
    }
}
