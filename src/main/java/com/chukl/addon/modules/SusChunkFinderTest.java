package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Finds probable base locations from plant/amethyst growth and buried rotated deepslate.
 *
 * Works the same way as the Krypton module: every chunk is scanned ONCE when its data arrives (and once
 * for the chunks already loaded when you turn the module on). Chunks that still contain things that
 * have not finished growing "heat up" all chunks around them. A chunk that is hot, is not covered by
 * fully grown plants, and has at least 3 loaded neighbors is marked as sus.
 *
 * Speed: chunks are scanned in parallel on several background threads, sections without anything
 * interesting are skipped, and the sus list refreshes every other tick.
 */
public class SusChunkFinderTest extends Module {
    private record ScanResult(int notGrown, boolean hasGrown, Set<BlockPos> deepslate, int ugScore, int ugX, int ugZ, int natMask) {}

    public enum RenderMode { Best_Chunks, All_Chunks }

    public enum MarkerStyle { Outline, Solid, Light_Beam, Pulse_Beam, Rainbow_Beam, Lightning }

    public enum ChunkStyle { Energy_Orb, Column, Orb_And_Column }

    public enum YMode { Fixed, Follow_Player }

    public enum ServerProfile { Auto, Standard, DonutFolia }

    public enum EspScope { Best_Chunks, Flagged_Chunks, All_Chunks }

    /** The brightest lit blocks of one chunk, for the light ESP: x,y,z triplets and their block light level. */
    private record LitCells(int[] xyz, byte[] lvl) {}

    private record Zone(double centerX, double centerZ, int size, int maxHeat, int level) {}

    private record Opts(boolean kelp, boolean caveVines, boolean vines, boolean amethyst, boolean folia,
                        boolean bamboo, boolean cocoa, boolean beeNest, boolean deepslate,
                        Map<Block, Integer> ug, int ugMin, int ugMax, Map<Block, Integer> nat) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgUg = settings.createGroup("Underground");
    private final SettingGroup sgAdapt = settings.createGroup("Adaptive");
    private final SettingGroup sgLight = settings.createGroup("Light (experimental)");
    private final SettingGroup sgHud = settings.createGroup("Target HUD");

    // --- General (same list and order as Krypton) ---

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Chunk radius around growing plants that is considered 'currently loaded' and ignored. 0 = only the chunk the plants are in, 1 = 3x3 chunks, 2 = 5x5.")
        .defaultValue(4)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Minimum number of unfinished growing things needed to mark a chunk as sus.")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> smartAdjustment = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-adjustment")
        .description("Smart mode: groups nearby sus chunks into zones, drops lone weak chunks, rates each zone (low/medium/high confidence) and draws stronger zones brighter. Only draws chunks inside your render distance. Never changes your sensitivity or simulation distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noiseFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("noise-filter")
        .description("Smart mode: a single sus chunk with nobody else around is only shown if its heat is at least double your sensitivity. Removes most false positives.")
        .defaultValue(true)
        .visible(smartAdjustment::get)
        .build()
    );

    private final Setting<Boolean> zoneMarker = sgGeneral.add(new BoolSetting.Builder()
        .name("zone-marker")
        .description("Draws a tall beam marker so you can spot it from far away: at every zone center (All Chunks) or at your best dig spots (Best Chunks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyZones = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-zones")
        .description("Smart mode: prints the coordinates in chat when a new high-confidence zone is found.")
        .defaultValue(false)
        .visible(smartAdjustment::get)
        .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity (10-255) of the chunk fill and the deepslate boxes.")
        .defaultValue(81)
        .range(10, 255)
        .sliderRange(10, 255)
        .build()
    );

    private final Setting<Boolean> kelp = sgGeneral.add(new BoolSetting.Builder()
        .name("kelp").description("Use kelp growth.").defaultValue(false).build());

    private final Setting<Boolean> caveVines = sgGeneral.add(new BoolSetting.Builder()
        .name("cave-vines").description("Use cave vine growth.").defaultValue(false).build());

    private final Setting<Boolean> vines = sgGeneral.add(new BoolSetting.Builder()
        .name("vines").description("Use vine growth.").defaultValue(false).build());

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst").description("Use amethyst bud and cluster growth.").defaultValue(true).build());

    private final Setting<Boolean> bamboo = sgGeneral.add(new BoolSetting.Builder()
        .name("bamboo").description("Use bamboo growth.").defaultValue(false).build());

    private final Setting<Boolean> cocoa = sgGeneral.add(new BoolSetting.Builder()
        .name("cocoa").description("Use cocoa pod growth.").defaultValue(false).build());

    private final Setting<Boolean> beeNest = sgGeneral.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Bee nests with honey mean someone loaded the chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotatedDeepslate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Detect rotated deepslate buried between Y 0 and 60.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-info")
        .description("Every 2 seconds, prints the numbers behind the chunk you are standing in (heat, tracked, loaded neighbors). Use it to compare with Krypton.")
        .defaultValue(false)
        .build()
    );

    // --- Render ---

    private final Setting<ShapeMode> chunkShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("chunk-shape-mode")
        .description("How sus chunks are drawn. Sides = filled flat square, Lines = outline only, Both = filled with outline.")
        .defaultValue(ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> chunkSideColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-fill-color")
        .description("Fill color of sus chunks (opacity comes from the Alpha setting).")
        .defaultValue(new SettingColor(70, 150, 255, 110))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> chunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-outline-color")
        .description("Outline color of sus chunks.")
        .defaultValue(new SettingColor(130, 200, 255, 220))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<Integer> chunkY = sgRender.add(new IntSetting.Builder()
        .name("chunk-y")
        .description("Y level the orb, pillar and flat layer are drawn at. Any level from -64 to 320.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Double> chunkThickness = sgRender.add(new DoubleSetting.Builder()
        .name("chunk-thickness")
        .description("Thickness of the chunk box in blocks. Small = flat square, larger = tall column.")
        .defaultValue(0.1)
        .range(0.05, 128)
        .sliderRange(0.05, 16)
        .build()
    );

    private final Setting<Integer> chunkInset = sgRender.add(new IntSetting.Builder()
        .name("chunk-inset")
        .description("Shrinks each chunk square by this many blocks on every side, so the layer is smaller and neighbors don't merge into one big sheet.")
        .defaultValue(0)
        .range(0, 7)
        .sliderRange(0, 7)
        .build()
    );

    private final Setting<MarkerStyle> markerStyle = sgRender.add(new EnumSetting.Builder<MarkerStyle>()
        .name("marker-style")
        .description("Look of the pillar. Lightning = crackling electric bolts, Light Beam = glowing beam, Pulse Beam = beam that breathes, Rainbow Beam = color-cycling beam, Solid = filled column, Outline = thin lines.")
        .defaultValue(MarkerStyle.Lightning)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Color of the zone pillar (ignored by Rainbow Beam).")
        .defaultValue(new SettingColor(70, 150, 255, 255))
        .visible(() -> zoneMarker.get() && markerStyle.get() != MarkerStyle.Rainbow_Beam)
        .build()
    );

    private final Setting<Double> markerWidth = sgRender.add(new DoubleSetting.Builder()
        .name("marker-width")
        .description("Thickness of the pillar core in blocks. Bigger = easier to see from far away.")
        .defaultValue(1.5)
        .range(0.25, 16)
        .sliderRange(0.25, 8)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerHeight = sgRender.add(new IntSetting.Builder()
        .name("marker-height")
        .description("How tall the pillar is, in blocks above the chunk layer. Higher + brighter zones get extra height on top of this.")
        .defaultValue(96)
        .range(16, 384)
        .sliderRange(16, 320)
        .visible(() -> zoneMarker.get())
        .build()
    );

    private final Setting<Integer> markerGlow = sgRender.add(new IntSetting.Builder()
        .name("marker-glow-layers")
        .description("Soft glow layers around the beam (0 = none). Each layer is one extra box per zone, so keep it low on weak devices.")
        .defaultValue(3)
        .range(0, 5)
        .sliderRange(0, 5)
        .visible(() -> zoneMarker.get()
            && (markerStyle.get() == MarkerStyle.Light_Beam || markerStyle.get() == MarkerStyle.Pulse_Beam || markerStyle.get() == MarkerStyle.Rainbow_Beam))
        .build()
    );

    private final Setting<Double> markerSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("marker-animation-speed")
        .description("Speed of the pulse / rainbow animation.")
        .defaultValue(1.0)
        .range(0.1, 5)
        .sliderRange(0.1, 3)
        .visible(() -> zoneMarker.get()
            && (markerStyle.get() == MarkerStyle.Pulse_Beam || markerStyle.get() == MarkerStyle.Rainbow_Beam))
        .build()
    );

    private final Setting<SettingColor> deepslateColor = sgRender.add(new ColorSetting.Builder()
        .name("deepslate-color")
        .description("Outline color of suspicious rotated deepslate blocks.")
        .defaultValue(new SettingColor(0, 255, 255, 160))
        .visible(rotatedDeepslate::get)
        .build()
    );

    private final Setting<Integer> deepslateDistance = sgRender.add(new IntSetting.Builder()
        .name("deepslate-render-distance")
        .description("Only draw suspicious deepslate within this many blocks.")
        .defaultValue(64)
        .range(16, 256)
        .sliderRange(16, 192)
        .visible(rotatedDeepslate::get)
        .build()
    );

    // --- Test: best-chunk rendering ---

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Best Chunks = draw only the top chunk(s) as a tall dig-down column (Y range from the Underground group) with a dig shaft and beam. All Chunks = the old flat layer for every sus chunk.")
        .defaultValue(RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Integer> chunksShown = sgRender.add(new IntSetting.Builder()
        .name("chunks-shown")
        .description("How many of the best chunks to draw. 1 = just the single best chunk.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> announceBest = sgRender.add(new BoolSetting.Builder()
        .name("announce-best")
        .description("Print the coordinates of the best dig spot in chat when it changes.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> verifyChunk = sgRender.add(new BoolSetting.Builder()
        .name("verify-chunk")
        .description("When a best chunk shows up, re-read its real chunk data (and every chunk that contributes to it) and show the true scan progress. "
            + "If the fresh data no longer supports it, it is marked a false positive and the next best chunk takes its place.")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> verifyPercent = sgRender.add(new BoolSetting.Builder()
        .name("verify-percent")
        .description("Show the scan percentage above the hotbar while verifying. It disappears when the scan is finished.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && verifyChunk.get())
        .build()
    );

    private final Setting<ChunkStyle> chunkStyle = sgRender.add(new EnumSetting.Builder<ChunkStyle>()
        .name("chunk-style")
        .description("How the best chunk looks. Energy Orb = glowing orb of light with electric arcs, Column = the dig-range column, Orb And Column = both.")
        .defaultValue(ChunkStyle.Energy_Orb)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Double> orbSize = sgRender.add(new DoubleSetting.Builder()
        .name("orb-size")
        .description("Radius of the energy orb in blocks.")
        .defaultValue(3.0)
        .range(0.5, 24)
        .sliderRange(0.5, 12)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<Integer> orbArcs = sgRender.add(new IntSetting.Builder()
        .name("orb-arcs")
        .description("Number of electric arcs crackling out of the orb (0 = none).")
        .defaultValue(7)
        .range(0, 20)
        .sliderRange(0, 16)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<Double> orbSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("orb-speed")
        .description("How fast the orb spins and pulses.")
        .defaultValue(1.0)
        .range(0.1, 5)
        .sliderRange(0.1, 3)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && chunkStyle.get() != ChunkStyle.Column)
        .build()
    );

    private final Setting<YMode> yMode = sgRender.add(new EnumSetting.Builder<YMode>()
        .name("y-mode")
        .description("Fixed = use chunk-y. Follow Player = stay at your own height plus y-offset.")
        .defaultValue(YMode.Fixed)
        .build()
    );

    private final Setting<Integer> yOffset = sgRender.add(new IntSetting.Builder()
        .name("y-offset")
        .description("Blocks above (or below, if negative) your own height.")
        .defaultValue(0)
        .range(-64, 128)
        .sliderRange(-32, 64)
        .visible(() -> yMode.get() == YMode.Follow_Player)
        .build()
    );

    private final Setting<Boolean> showShaft = sgRender.add(new BoolSetting.Builder()
        .name("dig-shaft")
        .description("Draw the one-block dig shaft from the surface down to the bottom of the dig range.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your crosshair to the best chunk.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer line.")
        .defaultValue(new SettingColor(100, 200, 255, 220))
        .visible(() -> renderMode.get() == RenderMode.Best_Chunks && tracers.get())
        .build()
    );

    // --- Test: adaptive to the server ---

    private final Setting<Boolean> adaptive = sgAdapt.add(new BoolSetting.Builder()
        .name("adaptive-mode")
        .description("Adapt to the server you are on: detect its type, learn how far it really sends chunks, keep the simulation distance inside that, and tune the sensitivity to how noisy the server is. Off = behave exactly like the settings say.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ServerProfile> serverProfile = sgAdapt.add(new EnumSetting.Builder<ServerProfile>()
        .name("server-profile")
        .description("Auto = pick from the server brand (DonutFolia is detected automatically). Standard = normal vanilla / Paper / Spigot behavior. DonutFolia = amethyst handling for DonutFolia servers.")
        .defaultValue(ServerProfile.Auto)
        .visible(adaptive::get)
        .build()
    );

    private final Setting<Boolean> autoSensitivity = sgAdapt.add(new BoolSetting.Builder()
        .name("auto-sensitivity")
        .description("Raises the sensitivity a little when far too many chunks get flagged (a server full of farms and builds) and lowers it back when almost nothing is flagged. It never goes below your own sensitivity.")
        .defaultValue(true)
        .visible(adaptive::get)
        .build()
    );

    private final Setting<Integer> maxBoost = sgAdapt.add(new IntSetting.Builder()
        .name("max-sensitivity-boost")
        .description("Most the sensitivity may be raised above your own setting.")
        .defaultValue(10)
        .range(1, 20)
        .sliderRange(1, 20)
        .visible(() -> adaptive.get() && autoSensitivity.get())
        .build()
    );

    private final Setting<Boolean> announceAdapt = sgAdapt.add(new BoolSetting.Builder()
        .name("announce-adaptation")
        .description("Say in chat what the module adapted to (server brand, chunk radius, simulation distance, sensitivity).")
        .defaultValue(true)
        .visible(adaptive::get)
        .build()
    );

    // --- Test: light ESP + deep light signal (experimental) ---

    private final Setting<Boolean> lightEsp = sgLight.add(new BoolSetting.Builder()
        .name("light-esp")
        .description("EXPERIMENTAL: draws the lit blocks underground as colored boxes (brighter light = brighter box), so torches, lanterns and lamps of a hidden base stand out. Only the brightest blocks of each chunk are kept, and only the chunks of the chosen scope are drawn, so it stays light on weak devices. Overworld only.")
        .defaultValue(false)
        .build()
    );

    private final Setting<EspScope> espScope = sgLight.add(new EnumSetting.Builder<EspScope>()
        .name("scope")
        .description("Best Chunks = only the chunk(s) the module picked. Flagged Chunks = every flagged chunk. All Chunks = every loaded chunk that has lit blocks (nearest first).")
        .defaultValue(EspScope.Best_Chunks)
        .visible(lightEsp::get)
        .build()
    );

    private final Setting<Integer> espMinLight = sgLight.add(new IntSetting.Builder()
        .name("min-light-level")
        .description("Lowest block light level that is drawn. 1 = everything lit, higher = only blocks close to a light source.")
        .defaultValue(1)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(lightEsp::get)
        .build()
    );

    private final Setting<SettingColor> espColor = sgLight.add(new ColorSetting.Builder()
        .name("light-color")
        .description("Color of the brightest light. Dimmer light fades toward dark.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .visible(lightEsp::get)
        .build()
    );

    private final Setting<Integer> espAlpha = sgLight.add(new IntSetting.Builder()
        .name("light-alpha")
        .description("Opacity of the light boxes.")
        .defaultValue(80)
        .range(1, 255)
        .sliderRange(1, 255)
        .visible(lightEsp::get)
        .build()
    );

    private final Setting<Integer> espMaxBoxes = sgLight.add(new IntSetting.Builder()
        .name("max-boxes")
        .description("Most light boxes drawn at once (nearest chunks first). Lower = smoother on weak devices.")
        .defaultValue(400)
        .range(50, 3000)
        .sliderRange(50, 1500)
        .visible(lightEsp::get)
        .build()
    );

    private final Setting<Boolean> hudOn = sgHud.add(new BoolSetting.Builder()
        .name("target-hud").description("Show details about the sus chunk you are looking at.")
        .defaultValue(true).build());

    private final Setting<Integer> hudOpacity = sgHud.add(new IntSetting.Builder()
        .name("hud-opacity").description("Panel background opacity in percent.")
        .defaultValue(70).min(0).sliderMax(100).build());

    private final Setting<Double> hudSize = sgHud.add(new DoubleSetting.Builder()
        .name("hud-size").description("Panel size.")
        .defaultValue(1.0).min(0.3).sliderMax(3.0).build());

    private final Setting<Integer> hudDistance = sgHud.add(new IntSetting.Builder()
        .name("hud-distance").description("How far away a chunk can be and still show its panel.")
        .defaultValue(200).min(16).sliderMax(500).build());

    private final Setting<Integer> hudOffX = sgHud.add(new IntSetting.Builder()
        .name("hud-offset-x").defaultValue(0).sliderRange(-300, 300).build());

    private final Setting<Integer> hudOffY = sgHud.add(new IntSetting.Builder()
        .name("hud-offset-y").defaultValue(0).sliderRange(-300, 300).build());

    private final Setting<Boolean> lightIgnoreNatural = sgLight.add(new BoolSetting.Builder()
        .name("light-ignore-natural")
        .description("Ignore light that a natural source can explain (lava, magma, glow lichen, glow berries, amethyst, shroomlight, "
            + "caves with lava) and light inside natural structures. Only light that is brighter than any nearby natural source counts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> deepSignal = sgLight.add(new BoolSetting.Builder()
        .name("deep-light-signal")
        .description("EXPERIMENTAL: counts lit blocks in the deep range as underground evidence. Very little is lit down there naturally, so a few lit blocks point to torches or lamps. Chunks within one chunk of lava are skipped, because lava lights its surroundings by itself. Overworld only.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> deepMinLight = sgLight.add(new IntSetting.Builder()
        .name("deep-min-light")
        .description("Block light level a deep block needs to count. About 6 lit blocks = 1 point toward the underground threshold (up to 40).")
        .defaultValue(8)
        .range(1, 15)
        .sliderRange(1, 15)
        .visible(deepSignal::get)
        .build()
    );

    private final Setting<Integer> deepMinY = sgLight.add(new IntSetting.Builder()
        .name("deep-min-y")
        .description("Bottom of the Y range used by the light ESP and the deep light signal.")
        .defaultValue(-63)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(() -> lightEsp.get() || deepSignal.get())
        .build()
    );

    private final Setting<Integer> deepMaxY = sgLight.add(new IntSetting.Builder()
        .name("deep-max-y")
        .description("Top of the Y range used by the light ESP and the deep light signal.")
        .defaultValue(-30)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(() -> lightEsp.get() || deepSignal.get())
        .build()
    );

    // --- Test: underground detection ---

    private final Setting<Boolean> ugDetect = sgUg.add(new BoolSetting.Builder()
        .name("underground-detection")
        .description("Scan the underground height range for blocks that do not generate there naturally (chests, furnaces, beds, ...). Chunks with enough of them are flagged and ranked higher.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> ugMinY = sgUg.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y scanned and the bottom of the dig column.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Integer> ugMaxY = sgUg.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y scanned and the top of the dig column.")
        .defaultValue(29)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Integer> ugThreshold = sgUg.add(new IntSetting.Builder()
        .name("threshold")
        .description("Underground score a chunk needs to be flagged on its own. Storage = 3 points per block, utility and redstone = 2, lighting and building = 1.")
        .defaultValue(10)
        .range(1, 200)
        .sliderRange(1, 60)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugFlagsChunks = sgUg.add(new BoolSetting.Builder()
        .name("flag-chunks")
        .description("Flag chunks that reach the threshold even when there is no plant/amethyst growth heat. Off = underground blocks only raise the ranking of chunks that are already sus.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ignoreNatural = sgUg.add(new BoolSetting.Builder()
        .name("ignore-natural-structures")
        .description("Ignore underground evidence inside natural structures (mineshafts, strongholds, dungeons, ancient cities, trial chambers, amethyst geodes) and the chunks right next to them, so their chests, spawners, torches and rails do not flag a chunk. A chunk with three times the threshold is still flagged, in case a base sits inside one. Overworld only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lightBox = sgUg.add(new BoolSetting.Builder()
        .name("light-box-detection")
        .description("Check the underground light data for lit areas shaped like a box. A sealed room with torches or lamps is lit as a clean rectangle, a natural cave is lit as an irregular blob. Rooms with a doorway still count. Overworld only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lightMin = sgUg.add(new IntSetting.Builder()
        .name("min-light")
        .description("Block light level that counts as lit. 1 = anything lit at all.")
        .defaultValue(1)
        .range(1, 14)
        .sliderRange(1, 14)
        .visible(lightBox::get)
        .build()
    );

    private final Setting<Integer> boxMinSize = sgUg.add(new IntSetting.Builder()
        .name("box-min-size")
        .description("Smallest width and depth, in blocks, of a lit rectangle that counts as a box.")
        .defaultValue(3)
        .range(2, 12)
        .sliderRange(2, 10)
        .visible(lightBox::get)
        .build()
    );

    private final Setting<Integer> boxShape = sgUg.add(new IntSetting.Builder()
        .name("box-shape-match")
        .description("How much of a lit area has to be one clean rectangle, in percent. Higher = stricter, fewer false positives.")
        .defaultValue(75)
        .range(50, 100)
        .sliderRange(50, 100)
        .visible(lightBox::get)
        .build()
    );

    private final Setting<Integer> boxLayers = sgUg.add(new IntSetting.Builder()
        .name("box-min-layers")
        .description("How many Y levels in a row-ish the box shape must show up on (a room is usually 3 or 4 blocks tall). Each level is worth 5 points toward the threshold.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 8)
        .visible(lightBox::get)
        .build()
    );

    private final Setting<Boolean> ugStorage = sgUg.add(new BoolSetting.Builder()
        .name("storage")
        .description("Chests, trapped chests, barrels, ender chests, shulker boxes.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugUtility = sgUg.add(new BoolSetting.Builder()
        .name("utility")
        .description("Furnaces, crafting/enchanting/smithing tables, anvils, beacons, hoppers, droppers, dispensers, brewing stands, beds.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugRedstone = sgUg.add(new BoolSetting.Builder()
        .name("redstone")
        .description("Pistons, observers, repeaters, comparators, redstone lamps, TNT, note blocks.")
        .defaultValue(true)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugLighting = sgUg.add(new BoolSetting.Builder()
        .name("lighting")
        .description("Torches, lanterns, glowstone, sea lanterns. Mineshafts have torches too, so this can give false positives.")
        .defaultValue(false)
        .visible(ugDetect::get)
        .build()
    );

    private final Setting<Boolean> ugBuilding = sgUg.add(new BoolSetting.Builder()
        .name("building")
        .description("Glass, obsidian, scaffolding and metal/gem storage blocks. Obsidian also forms naturally where lava meets water.")
        .defaultValue(false)
        .visible(ugDetect::get)
        .build()
    );

    // --- Data (only touched by the single scan thread, read by the main thread) ---

    private final Map<ChunkPos, Integer> heatmap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> tracked = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> growthCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Boolean> fullyGrown = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> usedSimDist = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> chunkDeepslate = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> deepslateSet = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> ugScores = new ConcurrentHashMap<>();
    private final Map<ChunkPos, BlockPos> ugTargets = new ConcurrentHashMap<>();
    private volatile Set<ChunkPos> ugFlagged = Set.of();
    private final Map<ChunkPos, Integer> natMask = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> scannedAt = new ConcurrentHashMap<>();
    private final Set<ChunkPos> lightChecked = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Integer> lightScores = new ConcurrentHashMap<>();
    private volatile Set<ChunkPos> naturalZone = Set.of();
    private final Map<ChunkPos, Integer> deepScores = new ConcurrentHashMap<>();
    private final Set<ChunkPos> lavaChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, LitCells> litCells = new ConcurrentHashMap<>();
    private int observedRadius = -1;
    private long observedChangedAt;
    private int sensOffset;
    private long lastSensChangeAt;
    private long lastAnnounceAt;
    private String lastAdaptMsg = "";
    private volatile List<ChunkPos> bestChunks = List.of();

    // anti-xray / hidden underground probe
    private volatile boolean blocksHidden = false;
    private volatile String hiddenKind = "";
    private final Set<ChunkPos> probed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger probeOres = new AtomicInteger();
    private final AtomicInteger probeEmbedded = new AtomicInteger();
    private final AtomicInteger probeSolid = new AtomicInteger();
    private final AtomicInteger probeChunks = new AtomicInteger();

    // verification (real progress: sections actually read / sections that will be read)
    private final Set<ChunkPos> rejected = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> confirmed = ConcurrentHashMap.newKeySet();
    private volatile ChunkPos verifying = null;
    private final AtomicInteger verifyDone = new AtomicInteger();
    private volatile int verifyTotal = 0;
    private volatile int verifyUnits = 0;
    private volatile int verifyUnitsDone = 0;
    private ChunkPos lastAnnounced;
    private int lastUgSig;
    private int ugMaskCache = -1;
    private Map<Block, Integer> ugMapCache = Map.of();

    // ---- underground block table (built once from the block registry) ----
    private static final int[] UG_WEIGHTS = {3, 2, 2, 1, 1}; // storage, utility, redstone, lighting, building
    private static Map<Block, int[]> ugInfo;

    // ---- natural structure signature blocks: mineshaft, stronghold, dungeon, ancient city, trial chamber, geode ----
    private static final int[] NAT_THRESH = {10, 8, 8, 10, 1, 10};
    private static Map<Block, Integer> natInfo;

    private static int natGroupOf(String p) {
        switch (p) {
            case "rail": case "oak_fence": case "dark_oak_fence": case "cobweb":
                return 0;
            case "mossy_stone_bricks": case "cracked_stone_bricks": case "chiseled_stone_bricks":
            case "end_portal_frame": case "end_portal":
                return 1;
            case "mossy_cobblestone": case "spawner":
                return 2;
            case "sculk": case "sculk_vein": case "sculk_sensor": case "calibrated_sculk_sensor":
            case "sculk_shrieker": case "sculk_catalyst": case "reinforced_deepslate":
                return 3;
            case "trial_spawner": case "vault":
                return 4;
            case "amethyst_block": case "budding_amethyst": case "amethyst_cluster":
            case "small_amethyst_bud": case "medium_amethyst_bud": case "large_amethyst_bud":
                return 5;
            default:
                if (p.startsWith("infested_") && p.contains("stone_bricks")) return 1;
                return -1;
        }
    }

    private static synchronized Map<Block, Integer> natInfo() {
        if (natInfo != null) return natInfo;
        Map<Block, Integer> m = new java.util.HashMap<>();
        for (Block b : Registries.BLOCK) {
            int g = natGroupOf(Registries.BLOCK.getId(b).getPath());
            if (g >= 0) m.put(b, g);
        }
        natInfo = m;
        return m;
    }

    private static int ugGroupOf(String p) {
        switch (p) {
            case "chest": case "trapped_chest": case "barrel": case "ender_chest":
                return 0;
            case "furnace": case "blast_furnace": case "smoker": case "crafting_table": case "enchanting_table":
            case "smithing_table": case "anvil": case "chipped_anvil": case "damaged_anvil": case "beacon":
            case "hopper": case "dispenser": case "dropper": case "brewing_stand": case "grindstone":
            case "cartography_table": case "fletching_table": case "loom":
                return 1;
            case "piston": case "sticky_piston": case "observer": case "redstone_lamp": case "repeater":
            case "comparator": case "tnt": case "note_block":
                return 2;
            case "torch": case "wall_torch": case "soul_torch": case "soul_wall_torch": case "redstone_torch":
            case "redstone_wall_torch": case "lantern": case "soul_lantern": case "sea_lantern": case "glowstone":
                return 3;
            case "obsidian": case "glass": case "glass_pane": case "tinted_glass": case "scaffolding":
            case "iron_block": case "gold_block": case "diamond_block": case "emerald_block": case "netherite_block":
            case "copper_block": case "lapis_block": case "coal_block": case "redstone_block":
                return 4;
            default:
                if (p.endsWith("shulker_box")) return 0;
                if (p.endsWith("_bed")) return 1;
                return -1;
        }
    }

    private static synchronized Map<Block, int[]> ugInfo() {
        if (ugInfo != null) return ugInfo;
        Map<Block, int[]> m = new java.util.HashMap<>();
        for (Block b : Registries.BLOCK) {
            int g = ugGroupOf(Registries.BLOCK.getId(b).getPath());
            if (g >= 0) m.put(b, new int[]{g, UG_WEIGHTS[g]});
        }
        ugInfo = m;
        return m;
    }

    /** Blocks (with their weights) for the groups that are switched on. Rebuilt only when the groups change. */
    private Map<Block, Integer> ugBlocks() {
        int mask = 0;
        if (ugDetect.get()) {
            if (ugStorage.get()) mask |= 1;
            if (ugUtility.get()) mask |= 2;
            if (ugRedstone.get()) mask |= 4;
            if (ugLighting.get()) mask |= 8;
            if (ugBuilding.get()) mask |= 16;
        }
        if (mask != ugMaskCache) {
            Map<Block, Integer> m = new java.util.HashMap<>();
            if (mask != 0) {
                for (Map.Entry<Block, int[]> e : ugInfo().entrySet()) {
                    if ((mask & (1 << e.getValue()[0])) != 0) m.put(e.getKey(), e.getValue()[1]);
                }
            }
            ugMapCache = m;
            ugMaskCache = mask;
        }
        return ugMapCache;
    }

    /** Changes whenever a setting that affects scanning changes, so every loaded chunk gets scanned again. */
    private int ugSignature() {
        return java.util.Objects.hash(ugDetect.get(), ugMinY.get(), ugMaxY.get(), ugStorage.get(), ugUtility.get(),
            ugRedstone.get(), ugLighting.get(), ugBuilding.get(), ignoreNatural.get(), lightBox.get(), lightMin.get(),
            boxMinSize.get(), boxShape.get(), boxLayers.get(), lightEsp.get(), espMinLight.get(), deepSignal.get(),
            deepMinLight.get(), deepMinY.get(), deepMaxY.get());
    }

    private volatile Map<ChunkPos, Integer> susChunks = Map.of();
    /** Smart mode: confidence (0 low, 1 medium, 2 high) of the zone each sus chunk belongs to. */
    private volatile Map<ChunkPos, Integer> susLevel = Map.of();
    private volatile List<Zone> zones = List.of();
    private final Set<Long> notifiedZones = new java.util.HashSet<>();
    private volatile List<BlockPos> deepslateBlocks = List.of();
    private ExecutorService[] executors;
    private ClientWorld lastWorld;
    private int timer;
    private final Map<ChunkPos, WorldChunk> seenChunks = new java.util.HashMap<>();

    public SusChunkFinderTest() {
        super(ChuklAddon.CATEGORY, "sus-chunk-finder-test", "TEST copy of Sus Chunk Finder: renders only the best chunk(s) as a dig-down column + beam, and scans Y29 to -64 for player-made blocks.");
    }

    @Override
    public void onActivate() {
        restart();
    }

    @Override
    public void onDeactivate() {
        stopExecutors();
        lastWorld = null;
        clearData();
    }

    private void stopExecutors() {
        if (executors != null) {
            for (ExecutorService e : executors) e.shutdownNow();
        }
        executors = null;
    }

    private void restart() {
        stopExecutors();

        // One single-thread executor per worker. A given chunk always goes to the same worker, so two
        // scans of the same chunk can never run at once, while different chunks scan in parallel.
        int count = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        executors = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            int id = i;
            executors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SusChunkFinderTest-Scan-" + id);
                t.setDaemon(true);
                return t;
            });
        }

        clearData();
        lastWorld = mc.world;
        timer = 0;
        lastUgSig = ugSignature();
    }

    private void clearData() {
        heatmap.clear();
        tracked.clear();
        growthCounts.clear();
        fullyGrown.clear();
        usedSimDist.clear();
        chunkDeepslate.clear();
        loadedChunks.clear();
        seenChunks.clear();
        deepslateSet.clear();
        susChunks = Map.of();
        susLevel = Map.of();
        zones = List.of();
        notifiedZones.clear();
        deepslateBlocks = List.of();
        ugScores.clear();
        ugTargets.clear();
        natMask.clear();
        scannedAt.clear();
        lightChecked.clear();
        lightScores.clear();
        deepScores.clear();
        lavaChunks.clear();
        litCells.clear();
        naturalZone = Set.of();
        observedRadius = -1;
        observedChangedAt = System.currentTimeMillis();
        sensOffset = 0;
        lastSensChangeAt = System.currentTimeMillis();
        lastAdaptMsg = "";
        ugFlagged = Set.of();
        bestChunks = List.of();
        lastAnnounced = null;
        rejected.clear();
        confirmed.clear();
        probed.clear();
        probeOres.set(0);
        probeEmbedded.set(0);
        probeSolid.set(0);
        probeChunks.set(0);
        blocksHidden = false;
        hiddenKind = "";
        verifying = null;
        verifyTotal = 0;
        verifyDone.set(0);
    }

    // ---------- scanning ----------

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;
        ServerProfile profile = serverProfile.get();
        if (adaptive.get() && profile != ServerProfile.Auto) return profile == ServerProfile.DonutFolia;
        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private Opts opts() {
        return new Opts(kelp.get(), caveVines.get(), vines.get(), amethyst.get(), amethyst.get() && isDonutFolia(),
            bamboo.get(), cocoa.get(), beeNest.get(), rotatedDeepslate.get(),
            isOverworld(mc.world) ? ugBlocks() : Map.of(), Math.min(ugMinY.get(), ugMaxY.get()), Math.max(ugMinY.get(), ugMaxY.get()),
            isOverworld(mc.world) && ignoreNatural.get() ? natInfo() : Map.of());
    }

    /** Your setting, but never more than the chunk radius this server really sends (once that radius has settled). */
    private int effectiveSimDist() {
        int base = simulationDistance.get();
        if (adaptive.get() && observedRadius > 0 && System.currentTimeMillis() - observedChangedAt > 3000) {
            return Math.min(base, observedRadius);
        }
        return base;
    }

    /** Your sensitivity, plus what auto-sensitivity added for this server. Smart Adjustment itself never changes it. */
    private int effectiveSensitivity() {
        return sensitivity.get() + (adaptive.get() && autoSensitivity.get() ? sensOffset : 0);
    }

    /** Learns how noisy the server is: too many flagged chunks raise the sensitivity, almost none lower it again. */
    private void adaptSensitivity(int flagged) {
        if (!adaptive.get() || !autoSensitivity.get()) {
            sensOffset = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSensChangeAt < 15000) return;

        int loaded = loadedChunks.size();
        if (loaded < 40) return;

        double ratio = flagged / (double) loaded;
        if (ratio > 0.05 && sensOffset < maxBoost.get()) {
            sensOffset++;
            lastSensChangeAt = now;
        } else if (ratio < 0.004 && sensOffset > 0) {
            sensOffset--;
            lastSensChangeAt = now;
        } else if (sensOffset > maxBoost.get()) {
            sensOffset = maxBoost.get();
        }
    }

    private void announceAdaptation() {
        if (!adaptive.get() || !announceAdapt.get()) return;
        if (observedRadius <= 0 || loadedChunks.size() < 20) return;

        long now = System.currentTimeMillis();
        if (now - observedChangedAt < 5000 || now - lastAnnounceAt < 30000) return; // wait until things settle

        String brand = mc.getNetworkHandler() == null ? "?" : String.valueOf(mc.getNetworkHandler().getBrand());
        String msg = "Adapted to this server (" + brand + (isDonutFolia() ? ", DonutFolia mode" : "") + "): chunk radius "
            + observedRadius + ", simulation distance " + effectiveSimDist() + ", sensitivity " + effectiveSensitivity()
            + (sensOffset > 0 ? " (your " + sensitivity.get() + " + " + sensOffset + " for a noisy server)" : "");
        if (msg.equals(lastAdaptMsg)) return;

        lastAdaptMsg = msg;
        lastAnnounceAt = now;
        info(msg);
    }

    /**
     * Looks for chunks that just loaded (or were sent again by the server, which creates a new chunk
     * object) and scans each one once. Checked every tick, so a chunk is scanned the moment it arrives.
     */
    private void scanNewChunks() {
        if (mc.world == null || mc.player == null) return;

        var manager = mc.world.getChunkManager();
        ChunkPos center = mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue() + (smartAdjustment.get() ? 0 : 2);
        int farthest = 0;

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!manager.isChunkLoaded(cx, cz)) continue;
                farthest = Math.max(farthest, Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)));

                WorldChunk chunk = manager.getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkPos pos = new ChunkPos(cx, cz);
                if (seenChunks.get(pos) != chunk) {
                    seenChunks.put(pos, chunk);
                    queue(chunk);
                }
            }
        }

        // The farthest chunk the server actually sent us is the real radius (servers can cap your render distance)
        if (farthest > observedRadius) {
            observedRadius = farthest;
            observedChangedAt = System.currentTimeMillis();
        }

        // Forget unloaded chunks so they get scanned again when they come back
        seenChunks.keySet().removeIf(pos -> !manager.isChunkLoaded(pos.x, pos.z));
    }

    private void queue(WorldChunk chunk) {
        ExecutorService[] pool = executors;
        if (pool == null || chunk == null || mc.world == null) return;

        ClientWorld world = mc.world;
        Opts o = opts();
        int simDist = effectiveSimDist();

        ChunkPos pos = chunk.getPos();
        ExecutorService worker = pool[Math.floorMod(pos.x * 31 + pos.z, pool.length)];

        worker.execute(() -> {
            try {
                updateChunk(world, chunk, o, simDist);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Sus Chunk Finder scan failed", t);
            }
        });
    }

    private static void forEachNeighbor(ChunkPos cp, int dist, Consumer<ChunkPos> action) {
        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                action.accept(new ChunkPos(cp.x + dx, cp.z + dz));
            }
        }
    }

    /** Background thread. Removes what this chunk contributed last time, scans it again, adds the new result. */
    private void updateChunk(ClientWorld world, WorldChunk chunk, Opts o, int simDist) {
        if (world != mc.world) return;

        ChunkPos cp = chunk.getPos();
        loadedChunks.add(cp);

        int prevSim = usedSimDist.getOrDefault(cp, simDist);
        int prevGrowth = growthCounts.getOrDefault(cp, 0);
        boolean prevFull = Boolean.TRUE.equals(fullyGrown.get(cp));
        Set<BlockPos> prevDeepslate = chunkDeepslate.remove(cp);
        ugScores.remove(cp);
        ugTargets.remove(cp);
        natMask.remove(cp);
        lightScores.remove(cp);
        lightChecked.remove(cp);
        deepScores.remove(cp);
        lavaChunks.remove(cp);
        litCells.remove(cp);

        if (prevGrowth > 0) {
            forEachNeighbor(cp, prevSim, n -> heatmap.compute(n, (k, v) -> {
                int value = (v == null ? 0 : v) - prevGrowth;
                return value <= 0 ? null : value;
            }));
        }

        if (prevFull) {
            forEachNeighbor(cp, prevSim, n -> tracked.compute(n, (k, v) -> {
                if (v == null) return null;
                return v - 1 <= 0 ? null : v - 1;
            }));
        }

        if (prevDeepslate != null && !prevDeepslate.isEmpty()) deepslateSet.removeAll(prevDeepslate);

        ScanResult result = scanChunk(world, chunk, cp, o);

        growthCounts.put(cp, result.notGrown());
        fullyGrown.put(cp, result.hasGrown());
        usedSimDist.put(cp, simDist);

        if (result.hasGrown()) forEachNeighbor(cp, simDist, n -> tracked.merge(n, 1, Integer::sum));
        if (result.notGrown() > 0) forEachNeighbor(cp, simDist, n -> heatmap.merge(n, result.notGrown(), Integer::sum));

        if (!result.deepslate().isEmpty()) {
            chunkDeepslate.put(cp, result.deepslate());
            deepslateSet.addAll(result.deepslate());
        }

        if (result.natMask() != 0) natMask.put(cp, result.natMask());
        scannedAt.put(cp, System.currentTimeMillis());

        if (result.ugScore() > 0) {
            ugScores.put(cp, result.ugScore());
            ugTargets.put(cp, new BlockPos(result.ugX(), 0, result.ugZ()));
        }
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isCluster(BlockState s) {
        return s.getBlock() == Blocks.AMETHYST_CLUSTER;
    }

    private ScanResult scanChunk(ClientWorld world, WorldChunk chunk, ChunkPos cp, Opts o) {
        return scanChunk(world, chunk, cp, o, null);
    }

    /** progress (if given) is called once for every section that has been fully read. */
    private ScanResult scanChunk(ClientWorld world, WorldChunk chunk, ChunkPos cp, Opts o, Runnable progress) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();

        // Palette check: sections without any of these blocks are skipped entirely.
        boolean useAmethystBlocks = o.amethyst() && !o.folia();
        Predicate<BlockState> growthFilter = s -> {
            Block b = s.getBlock();
            return (o.kelp() && b == Blocks.KELP)
                || (o.caveVines() && b == Blocks.CAVE_VINES)
                || (o.vines() && b == Blocks.VINE)
                || (useAmethystBlocks && (b == Blocks.AMETHYST_CLUSTER || isBud(s)))
                || (o.bamboo() && b == Blocks.BAMBOO)
                || (o.cocoa() && b == Blocks.COCOA)
                || (o.beeNest() && b == Blocks.BEE_NEST);
        };
        Predicate<BlockState> deepslateFilter = s -> s.getBlock() == Blocks.DEEPSLATE;
        Map<Block, Integer> ug = o.ug();
        Predicate<BlockState> ugFilter = s -> ug.containsKey(s.getBlock());
        Map<Block, Integer> nat = o.nat();
        Predicate<BlockState> natFilter = s -> nat.containsKey(s.getBlock());
        int[] natCnt = new int[6];

        int notGrown = 0;
        int grown = 0;
        boolean foundBuds = false;
        boolean foundClusters = false;
        Set<BlockPos> deepslate = new HashSet<>();
        int ugScore = 0;
        long ugSumX = 0, ugSumZ = 0;
        BlockPos.Mutable other = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            if (progress != null && i > 0) progress.run();
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (bottomSection + i) << 4;

            if (o.folia()) {
                if (!foundBuds && section.hasAny(SusChunkFinderTest::isBud)) foundBuds = true;
                if (!foundClusters && section.hasAny(SusChunkFinderTest::isCluster)) foundClusters = true;
            }

            boolean scanGrowth = section.hasAny(growthFilter);
            boolean scanDeepslate = o.deepslate()
                && sectionMinY + 15 >= 0 && sectionMinY <= 60
                && section.hasAny(deepslateFilter);

            boolean scanUg = !ug.isEmpty()
                && sectionMinY + 15 >= o.ugMin() && sectionMinY <= o.ugMax()
                && section.hasAny(ugFilter);

            boolean scanNat = !nat.isEmpty()
                && sectionMinY + 15 >= -64 && sectionMinY <= 80
                && section.hasAny(natFilter);

            if (!scanGrowth && !scanDeepslate && !scanUg && !scanNat) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        int bx = startX + x;
                        int by = sectionMinY + y;
                        int bz = startZ + z;

                        if (scanGrowth) {
                            if (o.kelp() && block == Blocks.KELP && state.contains(Properties.AGE_25)) {
                                boolean waterAbove = world.getBlockState(other.set(bx, by + 1, bz)).isOf(Blocks.WATER);
                                if (state.get(Properties.AGE_25) != 25 && waterAbove) grown++;
                                else notGrown++;

                            } else if (o.caveVines() && block == Blocks.CAVE_VINES && state.contains(Properties.AGE_25)) {
                                boolean airBelow = world.getBlockState(other.set(bx, by - 1, bz)).isAir();
                                if (state.get(Properties.AGE_25) != 25 && airBelow) grown++;
                                else notGrown++;

                            } else if (o.vines() && block == Blocks.VINE) {
                                BlockState below = world.getBlockState(other.set(bx, by - 1, bz));
                                if (!below.isOf(Blocks.VINE)) {
                                    if (!below.isAir()) {
                                        notGrown++;
                                    } else if (state.get(Properties.NORTH) || state.get(Properties.EAST)
                                        || state.get(Properties.SOUTH) || state.get(Properties.WEST)) {
                                        grown++;
                                    } else {
                                        notGrown++;
                                    }
                                }

                            } else if (useAmethystBlocks && block == Blocks.AMETHYST_CLUSTER) {
                                notGrown++;

                            } else if (useAmethystBlocks && isBud(state) && state.contains(Properties.FACING)) {
                                Direction facing = state.get(Properties.FACING);
                                BlockPos parent = other.set(bx, by, bz).offset(facing.getOpposite());
                                if (world.getBlockState(parent).isOf(Blocks.BUDDING_AMETHYST)) grown++;
                                else notGrown++;

                            } else if (o.bamboo() && block == Blocks.BAMBOO && state.contains(Properties.STAGE)) {
                                BlockState above = world.getBlockState(other.set(bx, by + 1, bz));
                                if (!above.isOf(Blocks.BAMBOO)) {
                                    if (state.get(Properties.STAGE) == 1) notGrown++;
                                    else if (above.isAir()) grown++;
                                }

                            } else if (o.cocoa() && block == Blocks.COCOA && state.contains(Properties.AGE_2)) {
                                if (state.get(Properties.AGE_2) == 2) notGrown++;
                                else grown++;

                            } else if (o.beeNest() && block == Blocks.BEE_NEST && state.contains(Properties.HONEY_LEVEL)) {
                                if (state.get(Properties.HONEY_LEVEL) == 5) notGrown++;
                                else grown++;
                            }
                        }

                        if (scanNat) {
                            Integer ng = nat.get(block);
                            if (ng != null) natCnt[ng]++;
                        }

                        if (scanUg && by >= o.ugMin() && by <= o.ugMax()) {
                            Integer w = ug.get(block);
                            if (w != null) {
                                ugScore += w;
                                ugSumX += (long) bx * w;
                                ugSumZ += (long) bz * w;
                            }
                        }

                        if (scanDeepslate && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)
                            && state.get(Properties.AXIS) != Direction.Axis.Y && by >= 0 && by <= 60) {

                            boolean buried = true;
                            for (Direction dir : Direction.values()) {
                                if (world.getBlockState(other.set(bx, by, bz).offset(dir)).isAir()) {
                                    buried = false;
                                    break;
                                }
                            }
                            if (buried) deepslate.add(new BlockPos(bx, by, bz));
                        }
                    }
                }
            }
        }

        if (progress != null && sections.length > 0) progress.run();

        if (o.folia()) {
            if (foundBuds) grown++;
            else if (foundClusters) notGrown++;
        }

        int ugX = ugScore > 0 ? (int) Math.round(ugSumX / (double) ugScore) : 0;
        int ugZ = ugScore > 0 ? (int) Math.round(ugSumZ / (double) ugScore) : 0;
        int mask = 0;
        for (int g = 0; g < natCnt.length; g++) {
            if (natCnt[g] >= NAT_THRESH[g]) mask |= 1 << g;
        }
        return new ScanResult(notGrown, grown > 0, deepslate, Math.min(ugScore, 200), ugX, ugZ, mask);
    }

    // ---------- main thread ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executors == null) return;

        // New world / dimension: start over, like turning the module off and on
        if (mc.world != lastWorld) {
            restart();
            return;
        }

        if (ugSignature() != lastUgSig) { // an underground setting changed: rescan everything
            restart();
            return;
        }

        scanNewChunks();
        probeHidden();

        if (debugInfo.get() && mc.player.age % 40 == 0) printDebug();
        if (mc.player.age % 100 == 0) announceAdaptation();

        if (--timer > 0) return;
        timer = 2; // refresh the sus list every other tick

        ClientWorld world = mc.world;
        int minSensitivity = effectiveSensitivity();
        Map<ChunkPos, Integer> sus = new java.util.HashMap<>();

        for (Map.Entry<ChunkPos, Integer> entry : heatmap.entrySet()) {
            ChunkPos pos = entry.getKey();
            if (entry.getValue() < minSensitivity) continue;
            if (tracked.containsKey(pos)) continue;
            if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;

            int neighbors = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (loadedChunks.contains(new ChunkPos(pos.x + dx, pos.z + dz))) neighbors++;
                }
            }

            if (neighbors >= 3) sus.put(pos, entry.getValue());
        }
        adaptSensitivity(sus.size());

        // chunks that belong to (or touch) a natural structure
        Set<ChunkPos> zone = new HashSet<>();
        if (ignoreNatural.get()) {
            for (ChunkPos c : natMask.keySet()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) zone.add(new ChunkPos(c.x + dx, c.z + dz));
                }
            }
        }
        naturalZone = zone;

        // underground light: analyse a chunk a few seconds after it was scanned, once its light data has arrived
        if ((lightBox.get() || lightEsp.get() || deepSignal.get() || (blocksHidden && adaptive.get())) && isOverworld(world)) {
            long now = System.currentTimeMillis();
            int budget = 4;
            for (Map.Entry<ChunkPos, Long> e : scannedAt.entrySet()) {
                if (budget <= 0) break;
                if (now - e.getValue() < 3000) continue;
                ChunkPos cp = e.getKey();
                if (lightChecked.contains(cp)) continue;
                WorldChunk lightChunk = seenChunks.get(cp);
                if (lightChunk == null) continue;
                lightChecked.add(cp);
                queueLight(world, lightChunk);
                budget--;
            }
        }
        scannedAt.keySet().removeIf(c -> !seenChunks.containsKey(c));

        Set<ChunkPos> ugStrong = new HashSet<>();
        if ((ugDetect.get() || lightBox.get() || deepSignal.get() || (blocksHidden && adaptive.get())) && ugFlagsChunks.get()) {
            int thr = ugThreshold.get();
            Set<ChunkPos> keys = new HashSet<>(ugScores.keySet());
            keys.addAll(lightScores.keySet());
            keys.addAll(deepScores.keySet());
            for (ChunkPos pos : keys) {
                int score = effUg(pos);
                if (score < thr) continue;
                if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;
                sus.merge(pos, score, Integer::max);
                ugStrong.add(pos);
            }
        }
        ugFlagged = ugStrong;
        if (verifyChunk.get()) sus.keySet().removeAll(rejected);

        if (smartAdjustment.get()) applySmartMode(sus, minSensitivity);
        else {
            susLevel = Map.of();
            zones = List.of();
        }
        susChunks = sus;
        bestChunks = pickBest(sus);
        updateVerification(world);

        if (rotatedDeepslate.get()) {
            deepslateSet.removeIf(p -> !world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4));
            deepslateBlocks = new ArrayList<>(deepslateSet);
        } else {
            deepslateBlocks = List.of();
        }
    }

    /**
     * Smart mode. Groups sus chunks that touch (or are one chunk apart) into zones, removes lone weak chunks,
     * and rates every zone: high = 4+ chunks or very hot, medium = 2+ chunks or hot, low = everything else.
     */
    private void applySmartMode(Map<ChunkPos, Integer> sus, int sens) {
        Map<ChunkPos, Integer> level = new java.util.HashMap<>();
        List<Zone> found = new ArrayList<>();
        Set<ChunkPos> seen = new HashSet<>();
        boolean filter = noiseFilter.get();

        for (ChunkPos start : new ArrayList<>(sus.keySet())) {
            if (!seen.add(start)) continue;

            List<ChunkPos> members = new ArrayList<>();
            ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                ChunkPos c = queue.poll();
                members.add(c);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        ChunkPos n = new ChunkPos(c.x + dx, c.z + dz);
                        if (sus.containsKey(n) && seen.add(n)) queue.add(n);
                    }
                }
            }

            int max = 0;
            double wx = 0, wz = 0, total = 0;
            for (ChunkPos c : members) {
                int heat = sus.get(c);
                max = Math.max(max, heat);
                wx += (c.x * 16 + 8) * (double) heat;
                wz += (c.z * 16 + 8) * (double) heat;
                total += heat;
            }

            if (filter && members.size() == 1 && max < sens * 2 && !ugFlagged.contains(members.get(0))) {
                for (ChunkPos c : members) sus.remove(c); // lone weak chunk = probably noise
                continue;
            }

            int lvl = (members.size() >= 4 || max >= sens * 4) ? 2 : (members.size() >= 2 || max >= sens * 2) ? 1 : 0;
            for (ChunkPos c : members) level.put(c, lvl);
            found.add(new Zone(wx / total, wz / total, members.size(), max, lvl));
        }

        susLevel = level;
        zones = found;

        if (notifyZones.get()) {
            for (Zone z : found) {
                if (z.level() < 2) continue;
                long key = ChunkPos.toLong((int) Math.floor(z.centerX() / 64), (int) Math.floor(z.centerZ() / 64));
                if (notifiedZones.add(key)) {
                    info("High-confidence sus zone at " + (int) z.centerX() + ", " + (int) z.centerZ()
                        + " (" + z.size() + " chunk" + (z.size() == 1 ? "" : "s") + ", heat " + z.maxHeat() + ")");
                }
            }
        }
    }

    private static boolean isOverworld(ClientWorld world) {
        return world != null && world.getRegistryKey() == World.OVERWORLD;
    }

    /**
     * Underground evidence for a chunk: blocks that do not generate naturally plus box-shaped lit rooms.
     * Inside (or next to) a natural structure it counts for nothing, unless it is far above the threshold.
     */
    private int effUg(ChunkPos c) {
        int deep = deepScores.getOrDefault(c, 0);
        if (deep > 0 && lavaNear(c)) deep = 0; // lava lights its surroundings by itself
        int blockScore = blocksHidden && adaptive.get() ? 0 : ugScores.getOrDefault(c, 0); // fake/hidden blocks cannot be trusted
        int score = blockScore + lightScores.getOrDefault(c, 0) + deep;
        if (score <= 0) return 0;
        if (ignoreNatural.get() && naturalZone.contains(c) && score < ugThreshold.get() * 3) return 0;
        return score;
    }

    private boolean lavaNear(ChunkPos c) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (lavaChunks.contains(new ChunkPos(c.x + dx, c.z + dz))) return true;
            }
        }
        return false;
    }

    private record LightOpts(boolean box, int minLight, int minSize, int ratio, int minLayers, int ugMin, int ugMax,
                             boolean esp, int espMin, boolean deep, int deepMin, int deepMinY, int deepMaxY, boolean natural) {}

    private void queueLight(ClientWorld world, WorldChunk chunk) {
        ExecutorService[] pool = executors;
        if (pool == null) return;

        boolean force = blocksHidden && adaptive.get();
        LightOpts o = new LightOpts(lightBox.get() || force, lightMin.get(), boxMinSize.get(), boxShape.get(), boxLayers.get(),
            Math.min(ugMinY.get(), ugMaxY.get()), Math.max(ugMinY.get(), ugMaxY.get()),
            lightEsp.get(), espMinLight.get(), deepSignal.get() || force, deepMinLight.get(),
            Math.min(deepMinY.get(), deepMaxY.get()), Math.max(deepMinY.get(), deepMaxY.get()), lightIgnoreNatural.get());

        ChunkPos pos = chunk.getPos();
        pool[Math.floorMod(pos.x * 31 + pos.z, pool.length)].execute(() -> {
            try {
                lightScan(world, chunk, o);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Sus Chunk Finder Test light scan failed", t);
            }
        });
    }

    /** Light level a natural block gives off (0 = not a natural light source). */
    private static int natLight(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.LAVA) return 15;
        if (b == Blocks.MAGMA_BLOCK) return 3;
        if (b == Blocks.GLOW_LICHEN) return 7;
        if (b == Blocks.SHROOMLIGHT || b == Blocks.GLOWSTONE || b == Blocks.FIRE) return 15;
        if (b == Blocks.SOUL_FIRE) return 10;
        if (b == Blocks.CRYING_OBSIDIAN) return 10;
        if (b == Blocks.SCULK_CATALYST) return 6;
        if (b == Blocks.AMETHYST_CLUSTER) return 5;
        if (b == Blocks.LARGE_AMETHYST_BUD) return 4;
        if (b == Blocks.MEDIUM_AMETHYST_BUD) return 2;
        if (b == Blocks.SMALL_AMETHYST_BUD) return 1;
        if ((b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT) && s.contains(Properties.BERRIES) && s.get(Properties.BERRIES)) return 14;
        return 0;
    }

    /** Natural light sources in this chunk and its 8 neighbours: {x, y, z, level}. */
    private List<int[]> gatherEmitters(ClientWorld world, ChunkPos cp, int lo, int hi) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                WorldChunk c = world.getChunkManager().getWorldChunk(cp.x + dx, cp.z + dz);
                if (c == null) continue;
                ChunkSection[] sections = c.getSectionArray();
                int bottom = c.getBottomY();
                for (int i = 0; i < sections.length; i++) {
                    int sy = bottom + i * 16;
                    if (sy + 15 < lo - 15 || sy > hi + 15) continue;
                    ChunkSection sec = sections[i];
                    if (sec == null || sec.isEmpty() || !sec.hasAny(st -> natLight(st) > 0)) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int e = natLight(sec.getBlockState(x, y, z));
                        if (e > 0 && out.size() < 3000) out.add(new int[]{c.getPos().getStartX() + x, sy + y, c.getPos().getStartZ() + z, e});
                    }
                }
            }
        }
        return out;
    }

    /** True when a natural source close enough could have produced this light level. */
    private static boolean explainedByNature(int x, int y, int z, int level, List<int[]> emitters) {
        for (int[] e : emitters) {
            int d = Math.abs(e[0] - x) + Math.abs(e[1] - y) + Math.abs(e[2] - z);
            if (e[3] - d >= level) return true;
        }
        return false;
    }

    private static boolean isOre(Block b) {
        return b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE || b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE
            || b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE || b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE
            || b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE || b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE
            || b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE || b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE;
    }

    /**
     * Anti-xray probe. Samples a few chunks and measures the ores below Y16:
     * - ores that never sit buried (no air touching them) = the server only sends exposed ores (hidden underground),
     * - far too many ores = the server mixes fake ores into the hidden blocks.
     * In both cases block based underground detection cannot be trusted, so light signals take over.
     */
    private void probeHidden() {
        if (!adaptive.get() || mc.world == null || mc.player == null || executors == null) return;
        if (mc.player.age % 20 != 0 || probeChunks.get() >= 20 || !isOverworld(mc.world)) return;

        ChunkPos here = mc.player.getChunkPos();
        ClientWorld world = mc.world;
        for (ChunkPos cp : loadedChunks) {
            if (Math.max(Math.abs(cp.x - here.x), Math.abs(cp.z - here.z)) > 6) continue;
            if (!probed.add(cp)) continue;
            WorldChunk chunk = world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk == null) continue;
            executors[Math.floorMod(cp.x * 31 + cp.z, executors.length)].execute(() -> {
                try {
                    probeChunk(world, chunk);
                } catch (Throwable ignored) {
                }
            });
            break; // one chunk per second keeps it light
        }
    }

    private void probeChunk(ClientWorld world, WorldChunk chunk) {
        if (world != mc.world) return;
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomY();
        ChunkPos cp = chunk.getPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int ores = 0, embedded = 0, solid = 0;
        for (int i = 0; i < sections.length; i++) {
            int sy = bottom + i * 16;
            if (sy > 15 || sy + 15 < -64) continue;
            ChunkSection sec = sections[i];
            if (sec == null || sec.isEmpty()) continue;
            for (int y = 0; y < 16; y++) {
                int by = sy + y;
                if (by > 15) continue;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState st = sec.getBlockState(x, y, z);
                    if (st.isAir()) continue;
                    solid++;
                    if (!isOre(st.getBlock())) continue;
                    ores++;
                    boolean touchesAir = false;
                    for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                        if (world.getBlockState(m.set(cp.getStartX() + x, by, cp.getStartZ() + z).offset(d)).isAir()) { touchesAir = true; break; }
                    }
                    if (!touchesAir) embedded++;
                }
            }
        }
        probeOres.addAndGet(ores);
        probeEmbedded.addAndGet(embedded);
        probeSolid.addAndGet(solid);
        int n = probeChunks.incrementAndGet();
        if (n < 12) return;

        int totalOres = probeOres.get(), totalSolid = Math.max(1, probeSolid.get());
        double embeddedRatio = totalOres == 0 ? 0 : probeEmbedded.get() / (double) totalOres;
        double density = totalOres / (double) totalSolid;
        String kind = "";
        if (totalSolid > 200000 && totalOres < 20) kind = "all underground ores hidden";
        else if (totalOres >= 20 && embeddedRatio < 0.10) kind = "only air-exposed ores are sent (anti-xray)";
        else if (density > 0.06) kind = "fake ores mixed in (anti-xray)";
        boolean hidden = !kind.isEmpty();
        if (hidden != blocksHidden) {
            blocksHidden = hidden;
            hiddenKind = kind;
            String msg = hidden
                ? "This server hides underground blocks: " + kind + ". Block based underground detection is switched off here and light detection takes over."
                : "";
            if (hidden) mc.execute(() -> info(msg));
        }
    }

    /**
     * One pass over the block light of the chunk. From it we get:
     * - box layers: levels where the lit cells are mostly one clean rectangle (a sealed, lit room),
     * - the deep light signal: how many blocks in the deep range are lit (and whether lava is the reason),
     * - the light ESP cells: the brightest lit blocks of the deep range.
     */
    private void lightScan(ClientWorld world, WorldChunk chunk, LightOpts o) {
        if (world != mc.world) return;

        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        if (o.box()) {
            lo = Math.min(lo, o.ugMin());
            hi = Math.max(hi, o.ugMax());
        }
        if (o.esp() || o.deep()) {
            lo = Math.min(lo, o.deepMinY());
            hi = Math.max(hi, o.deepMaxY());
        }
        if (lo > hi) return;

        ChunkPos cp = chunk.getPos();
        int startX = cp.getStartX(), startZ = cp.getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        boolean[][] lit = new boolean[16][16];
        List<int[]> emitters = o.natural() ? gatherEmitters(world, cp, lo, hi) : List.of();

        int layers = 0;
        long sumX = 0, sumZ = 0, cells = 0;
        int deepCells = 0;
        boolean lava = false;

        final int cap = 256;
        int[][] bucket = o.esp() ? new int[16][cap * 3] : null;
        int[] bucketCount = new int[16];

        for (int y = Math.max(lo, -64); y <= Math.min(hi, 319); y++) {
            boolean inBox = o.box() && y >= o.ugMin() && y <= o.ugMax();
            boolean inDeep = (o.esp() || o.deep()) && y >= o.deepMinY() && y <= o.deepMaxY();

            int n = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int l = world.getLightLevel(LightType.BLOCK, m.set(startX + x, y, startZ + z));
                    if (l > 0 && !emitters.isEmpty() && explainedByNature(startX + x, y, startZ + z, l, emitters)) l = 0;

                    if (inBox) {
                        boolean on = l >= o.minLight();
                        lit[x][z] = on;
                        if (on) n++;
                    }

                    if (inDeep && l > 0) {
                        if (o.deep()) {
                            if (l >= o.deepMin()) deepCells++;
                            if (!lava && l >= 15 && world.getBlockState(m).isOf(Blocks.LAVA)) lava = true;
                        }
                        if (o.esp() && l >= o.espMin() && bucketCount[l] < cap) {
                            int k = bucketCount[l]++;
                            bucket[l][k * 3] = startX + x;
                            bucket[l][k * 3 + 1] = y;
                            bucket[l][k * 3 + 2] = startZ + z;
                        }
                    }
                }
            }

            // too little light = nothing to see, nearly the whole chunk lit = lava lake or open cavern, not a room
            if (!inBox || n < o.minSize() * o.minSize() || n > 200) continue;

            int[] rect = maxRect(lit); // {area, width, depth} of the biggest fully lit rectangle
            if (rect[1] < o.minSize() || rect[2] < o.minSize()) continue;
            if (rect[0] * 100 < n * o.ratio()) continue;

            layers++;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (!lit[x][z]) continue;
                    sumX += startX + x;
                    sumZ += startZ + z;
                    cells++;
                }
            }
        }

        // box-shaped lit rooms
        int boxScore = o.box() && layers >= o.minLayers() ? Math.min(60, layers * 5) : 0;
        if (boxScore > 0 && cells > 0) {
            lightScores.put(cp, boxScore);
            ugTargets.putIfAbsent(cp, new BlockPos((int) (sumX / cells), 0, (int) (sumZ / cells)));
        } else {
            lightScores.remove(cp);
        }

        // deep light signal
        int deepScore = o.deep() ? Math.min(40, deepCells / 6) : 0;
        if (deepScore > 0) deepScores.put(cp, deepScore);
        else deepScores.remove(cp);
        if (o.deep()) {
            if (lava) lavaChunks.add(cp);
            else lavaChunks.remove(cp);
        }

        // light ESP: keep the brightest cells, up to the cap
        if (o.esp()) {
            int[] xyz = new int[cap * 3];
            byte[] lv = new byte[cap];
            int k = 0;
            for (int l = 15; l >= 1 && k < cap; l--) {
                for (int i = 0; i < bucketCount[l] && k < cap; i++) {
                    xyz[k * 3] = bucket[l][i * 3];
                    xyz[k * 3 + 1] = bucket[l][i * 3 + 1];
                    xyz[k * 3 + 2] = bucket[l][i * 3 + 2];
                    lv[k] = (byte) l;
                    k++;
                }
            }
            if (k > 0) litCells.put(cp, new LitCells(java.util.Arrays.copyOf(xyz, k * 3), java.util.Arrays.copyOf(lv, k)));
            else litCells.remove(cp);
        }
    }

    /** Biggest rectangle made only of true cells: {area, width, depth}. */
    private static int[] maxRect(boolean[][] grid) {
        int[] h = new int[16];
        int bestArea = 0, bestW = 0, bestD = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) h[z] = grid[x][z] ? h[z] + 1 : 0;
            for (int i = 0; i < 16; i++) {
                int minH = Integer.MAX_VALUE;
                for (int j = i; j < 16; j++) {
                    minH = Math.min(minH, h[j]);
                    if (minH == 0) break;
                    int width = j - i + 1;
                    int area = width * minH;
                    if (area > bestArea) {
                        bestArea = area;
                        bestW = width;
                        bestD = minH;
                    }
                }
            }
        }
        return new int[]{bestArea, bestW, bestD};
    }

    /** The Y level everything is drawn at: the fixed chunk-y, or your own height plus the offset. */
    private double baseY() {
        if (yMode.get() == YMode.Follow_Player && mc.player != null) return mc.player.getY() + yOffset.get();
        return chunkY.get();
    }

    private double rank(ChunkPos c, Map<ChunkPos, Integer> levels) {
        return heatmap.getOrDefault(c, 0) + effUg(c) + 5.0 * levels.getOrDefault(c, 0);
    }

    /** Picks the best chunk(s) to dig: most evidence first, nearest first on ties. Keeps the old pick unless something beats it. */
    private List<ChunkPos> pickBest(Map<ChunkPos, Integer> sus) {
        if (sus.isEmpty() || mc.player == null) return List.of();

        int view = mc.options.getViewDistance().getValue();
        ChunkPos here = mc.player.getChunkPos();
        Map<ChunkPos, Integer> levels = susLevel;

        List<ChunkPos> list = new ArrayList<>(sus.keySet());
        list.removeIf(c -> Math.max(Math.abs(c.x - here.x), Math.abs(c.z - here.z)) > view);
        if (list.isEmpty()) return List.of();

        list.sort(java.util.Comparator
            .comparingDouble((ChunkPos c) -> -rank(c, levels))
            .thenComparingInt(c -> (c.x - here.x) * (c.x - here.x) + (c.z - here.z) * (c.z - here.z)));

        ChunkPos prev = lastAnnounced;
        if (prev != null && list.contains(prev) && rank(prev, levels) >= rank(list.get(0), levels)) {
            list.remove(prev);
            list.add(0, prev); // no flip-flopping between equal chunks while you walk around
        }

        List<ChunkPos> out = new ArrayList<>(list.subList(0, Math.min(chunksShown.get(), list.size())));

        ChunkPos top = out.get(0);
        if (!top.equals(lastAnnounced)) {
            lastAnnounced = top;
            if (announceBest.get() && renderMode.get() == RenderMode.Best_Chunks) {
                BlockPos t = digTarget(top);
                info("Best dig spot: X " + t.getX() + "  Z " + t.getZ() + "  (chunk " + top.x + ", " + top.z
                    + ", score " + (int) rank(top, levels) + ", underground " + effUg(top) + ")");
            }
        }
        return out;
    }

    // ---------- target HUD ----------

    @EventHandler
    private void onHud(Render2DEvent event) {
        if (!hudOn.get() || mc.player == null || mc.world == null || mc.options.hudHidden) return;
        Map<ChunkPos, Integer> sus = susChunks;
        if (sus.isEmpty()) return;

        ChunkPos cp = SusHud.lookedChunk(sus.keySet(), hudDistance.get(), -64, 320);
        if (cp == null) return;

        int need = Math.max(1, effectiveSensitivity());
        int heat = heatmap.getOrDefault(cp, 0);
        int lvl = susLevel.getOrDefault(cp, 0);
        int ug = blocksHidden && adaptive.get() ? 0 : ugScores.getOrDefault(cp, 0);
        int lb = lightScores.getOrDefault(cp, 0), dp = deepScores.getOrDefault(cp, 0);
        boolean nat = naturalZone.contains(cp) || natMask.containsKey(cp);
        boolean ok = confirmed.contains(cp);

        // confidence: how much independent evidence points at this chunk
        double conf = 15;
        if (heat >= need) conf += Math.min(30, 30.0 * heat / (need * 3.0));
        if (ug > 0) conf += Math.min(22, 22.0 * ug / (ugThreshold.get() * 2.0));
        if (lb > 0) conf += 15;
        if (dp > 0) conf += 8;
        if (lvl >= 2) conf += 8;
        if (ok) conf += 15;
        if (nat) conf -= 40;
        int pct = (int) Math.max(5, Math.min(99, Math.round(conf)));

        List<SusHud.Row> rows = new ArrayList<>();
        boolean best = bestChunks.contains(cp);
        rows.add(SusHud.Row.of("Suspicious chunk " + cp.x + ", " + cp.z + (best ? "   * best pick" : ""), SusHud.CYAN));
        SusHud.Row v = SusHud.verdict(pct, nat);
        rows.add(SusHud.Row.of(v.text, v.color));
        rows.add(new SusHud.Row("Confidence " + pct + "%", SusHud.GREY, pct));

        rows.add(SusHud.Row.of("Why it was flagged:", SusHud.GREY));
        boolean any = false;
        if (heat >= need) { rows.add(SusHud.Row.of(" + Plants stopped growing here (" + heat + ", needs " + need + ")", SusHud.WHITE)); any = true; }
        if (ug > 0) { rows.add(SusHud.Row.of(" + Player-made blocks underground (score " + ug + ")", SusHud.WHITE)); any = true; }
        if (lb > 0) { rows.add(SusHud.Row.of(" + Lit rectangular room underground", SusHud.WHITE)); any = true; }
        if (dp > 0) { rows.add(SusHud.Row.of(" + Odd light deep underground" + (lavaNear(cp) ? " (lava near, weaker)" : ""), SusHud.WHITE)); any = true; }
        if (!any) rows.add(SusHud.Row.of(" + Nearby chunks show activity", SusHud.WHITE));
        if (nat) rows.add(SusHud.Row.of(" - Looks like a natural structure", SusHud.RED));
        if (blocksHidden) rows.add(SusHud.Row.of(" ! Server hides underground blocks, using light instead", SusHud.ORANGE));

        BlockPos t = digTarget(cp);
        double dist = Math.hypot(cp.getStartX() + 8 - mc.player.getX(), cp.getStartZ() + 8 - mc.player.getZ());
        rows.add(SusHud.Row.of("", SusHud.GREY));
        if (verifying != null && verifying.equals(cp) && verifyTotal > 0)
            rows.add(SusHud.Row.of("Checking it again... " + Math.min(99, (int) (verifyDone.get() * 100L / verifyTotal)) + "%", SusHud.YELLOW));
        else if (ok) rows.add(SusHud.Row.of("Double-checked: still looks real", SusHud.GREEN));
        rows.add(SusHud.Row.of("Next: dig down at X " + t.getX() + "  Z " + t.getZ(), SusHud.CYAN));
        rows.add(SusHud.Row.of((int) dist + " blocks away", SusHud.GREY));

        SusHud.draw(event.drawContext, rows, hudSize.get(), hudOpacity.get(), hudOffX.get(), hudOffY.get());
    }

    // ---------- verification ----------

    /** Main thread: starts a verification of the top chunk when needed, and shows the real progress. */
    private void updateVerification(ClientWorld world) {
        if (!verifyChunk.get() || renderMode.get() != RenderMode.Best_Chunks) {
            verifying = null;
            return;
        }
        ChunkPos cur = verifying;
        if (cur != null) {
            int total = verifyTotal;
            if (verifyPercent.get() && total > 0 && mc.inGameHud != null) {
                int pct = Math.min(99, (int) (verifyDone.get() * 100L / total));
                mc.inGameHud.setOverlayMessage(Text.literal("Verifying chunk " + cur.x + ", " + cur.z + ":  " + pct + "%  ("
                    + verifyUnitsDone + "/" + verifyUnits + " chunks read)"), false);
            }
            return;
        }
        List<ChunkPos> best = bestChunks;
        if (best.isEmpty()) return;
        ChunkPos top = best.get(0);
        if (confirmed.contains(top) || rejected.contains(top)) return;
        startVerify(world, top);
    }

    private void startVerify(ClientWorld world, ChunkPos cp) {
        Opts o = opts();
        int sim = effectiveSimDist();
        int need = effectiveSensitivity();
        int ugThr = ugThreshold.get();

        List<WorldChunk> reads = new ArrayList<>();
        List<ChunkPos> poses = new ArrayList<>();
        int total = 0;
        WorldChunk self = world.getChunkManager().getWorldChunk(cp.x, cp.z);
        if (self == null) return;
        reads.add(self);
        poses.add(cp);
        total += self.getSectionArray().length;
        for (int dx = -sim; dx <= sim; dx++) {
            for (int dz = -sim; dz <= sim; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkPos n = new ChunkPos(cp.x + dx, cp.z + dz);
                if (growthCounts.getOrDefault(n, 0) <= 0) continue; // only chunks that actually gave this one heat
                WorldChunk wc = world.getChunkManager().getWorldChunk(n.x, n.z);
                if (wc == null) continue;
                reads.add(wc);
                poses.add(n);
                total += wc.getSectionArray().length;
            }
        }

        verifying = cp;
        verifyDone.set(0);
        verifyTotal = Math.max(1, total);
        verifyUnits = reads.size();
        verifyUnitsDone = 0;

        Thread t = new Thread(() -> {
            try {
                int growth = 0;
                ScanResult selfRes = null;
                for (int i = 0; i < reads.size(); i++) {
                    if (mc.world != world) return;
                    ScanResult r = scanChunk(world, reads.get(i), poses.get(i), o, verifyDone::incrementAndGet);
                    if (i == 0) selfRes = r;
                    growth += r.notGrown();
                    verifyUnitsDone = i + 1;
                }
                if (mc.world != world || selfRes == null) return;

                int freshUg = selfRes.ugScore() + lightScores.getOrDefault(cp, 0) + deepScores.getOrDefault(cp, 0);
                boolean natural = ignoreNatural.get() && (selfRes.natMask() != 0 || naturalZone.contains(cp)) && freshUg < ugThr * 3;
                boolean growthOk = growth >= need;
                boolean ugOk = ugFlagsChunks.get() && freshUg >= ugThr && !natural;
                boolean ok = !natural && (growthOk || ugOk);

                String reason = ok
                    ? (growthOk ? "growth evidence " + growth + " (needs " + need + ")" : "underground evidence " + freshUg + " (needs " + ugThr + ")")
                    : natural ? "it is a natural structure"
                    : "fresh data shows growth " + growth + " (needs " + need + ") and underground " + freshUg + " (needs " + ugThr + ")";

                if (ok) confirmed.add(cp);
                else rejected.add(cp);

                mc.execute(() -> {
                    if (mc.world != world) return;
                    verifying = null;
                    verifyTotal = 0;
                    if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(Text.empty(), false); // percentage goes away
                    if (ok) info("Verified chunk " + cp.x + ", " + cp.z + ": " + reason + ".");
                    else info("False positive: chunk " + cp.x + ", " + cp.z + " dropped - " + reason + ". Moving to the next best chunk.");
                });
            } catch (Throwable ex) {
                ChuklAddon.LOG.error("Sus Chunk Finder verify failed", ex);
                mc.execute(() -> {
                    verifying = null;
                    verifyTotal = 0;
                    confirmed.add(cp); // do not loop on an error
                });
            }
        }, "SusChunkFinderTest-Verify");
        t.setDaemon(true);
        t.start();
    }

    /** Where to dig: the middle of the underground blocks found in the chunk, or the chunk center if there are none. */
    private BlockPos digTarget(ChunkPos c) {
        BlockPos t = ugTargets.get(c);
        return t != null ? t : new BlockPos(c.getStartX() + 8, 0, c.getStartZ() + 8);
    }

    /** Used by GoodRoad: dig spots of all currently flagged chunks. */
    public List<BlockPos> getSusTargets() {
        List<BlockPos> out = new ArrayList<>();
        for (ChunkPos c : susChunks.keySet()) out.add(digTarget(c));
        return out;
    }

    /** Used by GoodRoad: dig spots of the current best chunks. */
    public List<BlockPos> getBestTargets() {
        List<BlockPos> out = new ArrayList<>();
        for (ChunkPos c : bestChunks) out.add(digTarget(c));
        return out;
    }

    /** Light ESP: boxes on the brightest lit blocks, colored by light level (bright = full color, dim = dark). */
    private void renderLightEsp(Render3DEvent event) {
        if (litCells.isEmpty()) return;

        ChunkPos here = mc.player.getChunkPos();
        List<ChunkPos> chunks;
        switch (espScope.get()) {
            case Best_Chunks:
                chunks = new ArrayList<>(bestChunks);
                break;
            case Flagged_Chunks:
                chunks = new ArrayList<>(susChunks.keySet());
                break;
            default:
                chunks = new ArrayList<>(litCells.keySet());
                break;
        }
        if (espScope.get() != EspScope.Best_Chunks) {
            chunks.sort(java.util.Comparator.comparingInt((ChunkPos c) ->
                (c.x - here.x) * (c.x - here.x) + (c.z - here.z) * (c.z - here.z)));
        }

        SettingColor base = espColor.get();
        int a = Math.max(1, Math.min(255, espAlpha.get()));
        int cap = espMaxBoxes.get();
        int drawn = 0;

        for (ChunkPos cp : chunks) {
            LitCells cells = litCells.get(cp);
            if (cells == null) continue;
            if (lightIgnoreNatural.get() && naturalZone.contains(cp)) continue; // natural structure light

            for (int i = 0; i < cells.lvl().length; i++) {
                if (drawn >= cap) return;

                double t = Math.max(0.0, Math.min(1.0, (cells.lvl()[i] - 1) / 14.0));
                Color c = new Color(
                    (int) Math.round((base.r - 19) * t + 19),
                    (int) Math.round((base.g - 19) * t + 19),
                    (int) Math.round((base.b - 50) * t + 50),
                    a
                );

                int x = cells.xyz()[i * 3], y = cells.xyz()[i * 3 + 1], z = cells.xyz()[i * 3 + 2];
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, c, c, ShapeMode.Sides, 0);
                drawn++;
            }
        }
    }

    private void renderBest(Render3DEvent event) {
        List<ChunkPos> best = bestChunks;
        if (best.isEmpty()) return;

        ChunkStyle style = chunkStyle.get();
        boolean orb = style != ChunkStyle.Column;
        boolean column = style != ChunkStyle.Energy_Orb;

        int yMin = Math.min(ugMinY.get(), ugMaxY.get());
        int yMax = Math.max(ugMinY.get(), ugMaxY.get());
        int inset = chunkInset.get();
        SettingColor fill = chunkSideColor.get();
        SettingColor line = chunkLineColor.get();
        SettingColor mark = markerColor.get();
        ShapeMode mode = chunkShapeMode.get();
        double y0 = baseY();
        double shaftTop = Math.max(y0, yMax) + 1;

        List<double[]> beams = new ArrayList<>();
        for (int i = 0; i < best.size(); i++) {
            ChunkPos cp = best.get(i);
            double ox = cp.getStartX() + 8.0, oz = cp.getStartZ() + 8.0;

            if (column) {
                // the chunk, from the bottom of the dig range up to the top of it
                Color side = new Color(fill.r, fill.g, fill.b, alpha.get());
                event.renderer.box(
                    cp.getStartX() + inset, yMin, cp.getStartZ() + inset,
                    cp.getStartX() + 16 - inset, yMax + 1, cp.getStartZ() + 16 - inset,
                    side, line, mode, 0
                );
            }

            if (orb) renderOrb(event, ox, y0, oz, orbSize.get(), cp.x * 31 + cp.z);

            if (showShaft.get()) {
                // the dig shaft: one block wide, from the surface down to the bottom
                BlockPos t = digTarget(cp);
                Color shaft = new Color(mark.r, mark.g, mark.b, 150);
                Color shaftLine = new Color(255, 255, 255, 220);
                event.renderer.box(t.getX(), yMin, t.getZ(), t.getX() + 1, shaftTop, t.getZ() + 1, shaft, shaftLine, ShapeMode.Both, 0);
            }

            if (tracers.get()) {
                SettingColor tc = tracerColor.get();
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    ox, y0, oz,
                    new Color(tc.r, tc.g, tc.b, tc.a)
                );
            }

            beams.add(new double[]{ox, oz, i == 0 ? 2 : 1});
        }

        if (zoneMarker.get()) renderMarkers(event, beams);
    }

    /** A circle of line segments around (cx,cy,cz) in the plane spanned by the unit vectors u and v. */
    private void ring(Render3DEvent event, double cx, double cy, double cz, double r,
                      double ux, double uy, double uz, double vx, double vy, double vz, Color color) {
        final int n = 20;
        double px = cx + r * ux, py = cy + r * uy, pz = cz + r * uz;
        for (int i = 1; i <= n; i++) {
            double a = i * (Math.PI * 2 / n);
            double c = Math.cos(a), s = Math.sin(a);
            double x = cx + r * (c * ux + s * vx);
            double y = cy + r * (c * uy + s * vy);
            double z = cz + r * (c * uz + s * vz);
            event.renderer.line(px, py, pz, x, y, z, color);
            px = x; py = y; pz = z;
        }
    }

    /** A glowing, spinning orb of light with white-hot core and crackling electric arcs. */
    private void renderOrb(Render3DEvent event, double cx, double cy, double cz, double r, int seed) {
        double t = (System.currentTimeMillis() % 1000000L) / 1000.0 * orbSpeed.get();
        double pulse = 0.85 + 0.15 * Math.sin(t * 4.0);
        double spin = t * 1.2;
        double cs = Math.cos(spin), sn = Math.sin(spin);

        SettingColor base = markerColor.get();
        int cr = base.r, cg = base.g, cb = base.b;

        // soft glow: fainter, slightly bigger rings around the orb
        for (int g = 1; g <= markerGlow.get(); g++) {
            double rr = r * (1.0 + 0.18 * g);
            Color halo = new Color(cr, cg, cb, (int) (Math.max(10, 110 / (g + 0.5)) * pulse));
            ring(event, cx, cy, cz, rr, 1, 0, 0, 0, 0, 1, halo);
            ring(event, cx, cy, cz, rr, cs, 0, sn, 0, 1, 0, halo);
            ring(event, cx, cy, cz, rr, -sn, 0, cs, 0, 1, 0, halo);
        }

        // the sphere: equator, two spinning meridians and two latitude rings
        Color shell = new Color(Math.min(255, cr + 90), Math.min(255, cg + 90), Math.min(255, cb + 90), (int) (235 * pulse));
        ring(event, cx, cy, cz, r, 1, 0, 0, 0, 0, 1, shell);
        ring(event, cx, cy, cz, r, cs, 0, sn, 0, 1, 0, shell);
        ring(event, cx, cy, cz, r, -sn, 0, cs, 0, 1, 0, shell);
        double lat = r * 0.6, latR = r * 0.8;
        ring(event, cx, cy + lat, cz, latR, 1, 0, 0, 0, 0, 1, shell);
        ring(event, cx, cy - lat, cz, latR, 1, 0, 0, 0, 0, 1, shell);

        // white-hot core
        double core = r * 0.2 * (0.9 + 0.2 * pulse);
        event.renderer.box(cx - core, cy - core, cz - core, cx + core, cy + core, cz + core,
            new Color(255, 255, 255, 240), new Color(190, 225, 255, 255), ShapeMode.Both, 0);

        // electric arcs: jagged lines that jump to a new random shape ~11 times a second
        int arcs = orbArcs.get();
        long slice = System.currentTimeMillis() / 90;
        Color bolt = new Color(225, 242, 255, 235);
        for (int a = 0; a < arcs; a++) {
            java.util.Random rnd = new java.util.Random(slice * 7919L + a * 104729L + seed);
            double theta = rnd.nextDouble() * Math.PI * 2;
            double dy = rnd.nextDouble() * 2 - 1;
            double rr = Math.sqrt(1 - dy * dy);
            double dx = rr * Math.cos(theta), dz = rr * Math.sin(theta);
            double len = r * (1.3 + rnd.nextDouble() * 0.9);
            double px = cx, py = cy, pz = cz;
            final int segs = 6;
            for (int sg = 1; sg <= segs; sg++) {
                double f = sg / (double) segs;
                double jit = r * 0.2 * (1.0 - f * 0.5);
                double x = cx + dx * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                double y = cy + dy * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                double z = cz + dz * len * f + (rnd.nextDouble() - 0.5) * 2 * jit;
                event.renderer.line(px, py, pz, x, y, z, bolt);
                px = x; py = y; pz = z;
            }
        }
    }

    private void printDebug() {
        ChunkPos cp = mc.player.getChunkPos();
        int heat = heatmap.getOrDefault(cp, 0);
        int cover = tracked.getOrDefault(cp, 0);
        int neighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (loadedChunks.contains(new ChunkPos(cp.x + dx, cp.z + dz))) neighbors++;
            }
        }

        String brand = mc.getNetworkHandler() == null ? "?" : String.valueOf(mc.getNetworkHandler().getBrand());
        info("Server " + brand + ", folia mode " + isDonutFolia() + ", render distance " + mc.options.getViewDistance().getValue()
            + ", sim distance " + effectiveSimDist() + ", chunks tracked " + loadedChunks.size());
        info("Chunk " + cp.x + ", " + cp.z + ": heat " + heat + " (needs " + effectiveSensitivity() + "), covered by grown plants "
            + cover + ", loaded neighbors " + neighbors + " (needs 3), own growing count " + growthCounts.getOrDefault(cp, 0)
            + ", flagged " + susChunks.containsKey(cp));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        boolean best = renderMode.get() == RenderMode.Best_Chunks;
        if (best) renderBest(event);
        if (lightEsp.get()) renderLightEsp(event);

        Map<ChunkPos, Integer> chunks = best ? Map.<ChunkPos, Integer>of() : susChunks;
        if (!chunks.isEmpty()) {
            double y = baseY();
            double thickness = chunkThickness.get();
            int inset = chunkInset.get();
            SettingColor fill = chunkSideColor.get();
            SettingColor line = chunkLineColor.get();
            ShapeMode mode = chunkShapeMode.get();
            int baseAlpha = alpha.get();
            boolean smart = smartAdjustment.get();
            int view = mc.options.getViewDistance().getValue();
            ChunkPos here = mc.player.getChunkPos();
            int sens = Math.max(1, effectiveSensitivity());
            Map<ChunkPos, Integer> levels = susLevel;

            for (Map.Entry<ChunkPos, Integer> entry : chunks.entrySet()) {
                ChunkPos cp = entry.getKey();

                int a = baseAlpha;
                if (smart) {
                    if (Math.max(Math.abs(cp.x - here.x), Math.abs(cp.z - here.z)) > view) continue;

                    // Hotter chunks draw stronger: 40% opacity at the threshold up to 100% at 4x the threshold
                    double strength = Math.min(1.0, 0.4 + 0.6 * (entry.getValue() / (double) (sens * 4)));
                    // Zones with more evidence draw brighter: low 60%, medium 80%, high 100%
                    int lvl = levels.getOrDefault(cp, 0);
                    strength *= 0.6 + 0.2 * lvl;
                    a = Math.max(10, (int) (baseAlpha * strength));
                }

                Color side = new Color(fill.r, fill.g, fill.b, a);

                event.renderer.box(
                    cp.getStartX() + inset, y, cp.getStartZ() + inset,
                    cp.getStartX() + 16 - inset, y + thickness, cp.getStartZ() + 16 - inset,
                    side, line, mode, 0
                );
            }
        }

        if (!best && zoneMarker.get()) {
            int zview = mc.options.getViewDistance().getValue();
            ChunkPos zhere = mc.player.getChunkPos();
            List<double[]> pts = new ArrayList<>();
            for (Zone z : zones) {
                if (Math.max(Math.abs((int) Math.floor(z.centerX() / 16) - zhere.x),
                    Math.abs((int) Math.floor(z.centerZ() / 16) - zhere.z)) > zview) continue;
                pts.add(new double[]{z.centerX(), z.centerZ(), z.level()});
            }
            renderMarkers(event, pts);
        }

        if (rotatedDeepslate.get()) {
            List<BlockPos> blocks = deepslateBlocks;
            if (blocks.isEmpty()) return;

            SettingColor base = deepslateColor.get();
            Color color = new Color(base.r, base.g, base.b, alpha.get());
            double maxDistSq = (double) deepslateDistance.get() * deepslateDistance.get();
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            int drawn = 0;

            for (BlockPos pos : blocks) {
                double dx = pos.getX() + 0.5 - px, dy = pos.getY() + 0.5 - py, dz = pos.getZ() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

                event.renderer.box(pos, color, color, ShapeMode.Lines, 0);

                if (++drawn >= 500) break; // keeps weak devices smooth
            }
        }
    }

    private void renderMarkers(Render3DEvent event, List<double[]> pts) {
        MarkerStyle style = markerStyle.get();
        double bottom = baseY();
        double baseHalf = markerWidth.get() / 2.0;
        int glow = markerGlow.get();
        double t = (System.currentTimeMillis() % 100000L) / 1000.0 * markerSpeed.get();

        SettingColor mc0 = markerColor.get();
        int r = mc0.r, g = mc0.g, b = mc0.b;
        if (style == MarkerStyle.Rainbow_Beam) {
            double hue = (t * 0.15) % 1.0;
            // plain HSV->RGB (no java.awt, so it is safe on Android/Zalith)
            double h6 = hue * 6.0;
            int sector = (int) h6;
            double f = h6 - sector;
            int v = 255, p0 = (int) (255 * 0.15), q = (int) (255 * (1 - 0.85 * f)), t2 = (int) (255 * (1 - 0.85 * (1 - f)));
            switch (sector % 6) {
                case 0 -> { r = v; g = t2; b = p0; }
                case 1 -> { r = q; g = v; b = p0; }
                case 2 -> { r = p0; g = v; b = t2; }
                case 3 -> { r = p0; g = q; b = v; }
                case 4 -> { r = t2; g = p0; b = v; }
                default -> { r = v; g = p0; b = q; }
            }
        }

        // Pulse breathes between 55% and 100% brightness
        double pulse = (style == MarkerStyle.Pulse_Beam) ? 0.775 + 0.225 * Math.sin(t * 3.0) : 1.0;

        for (double[] pt : pts) {
            int lvl = (int) pt[2];
            double top = bottom + markerHeight.get() + lvl * 16;
            double half = baseHalf + lvl * 0.25; // stronger zones get a fatter pillar
            double cx = pt[0], cz = pt[1];

            if (style == MarkerStyle.Outline) {
                Color line = new Color(r, g, b, Math.max(120, alpha.get()));
                event.renderer.box(cx - half, bottom, cz - half, cx + half, top, cz + half, line, line, ShapeMode.Lines, 0);
                continue;
            }

            if (style == MarkerStyle.Lightning) {
                // thin glowing core plus three crackling bolts that re-roll ~12 times a second
                Color lcore = new Color(r, g, b, (int) (150 * pulse));
                double lh = Math.max(0.2, half * 0.35);
                event.renderer.box(cx - lh, bottom, cz - lh, cx + lh, top, cz + lh, lcore, lcore, ShapeMode.Sides, 0);

                long slice = System.currentTimeMillis() / 80;
                Color boltC = new Color(Math.min(255, r + 110), Math.min(255, g + 110), Math.min(255, b + 110), 235);
                int segs = Math.max(8, (int) ((top - bottom) / 3));
                double spread = half + 0.6;
                for (int k = 0; k < 3; k++) {
                    java.util.Random rnd = new java.util.Random(slice * 31L + k * 977L + (long) (cx * 13 + cz * 7));
                    double px = cx, py = bottom, pz = cz;
                    for (int sg = 1; sg <= segs; sg++) {
                        double y2 = bottom + (top - bottom) * sg / segs;
                        double x2 = sg == segs ? cx : cx + (rnd.nextDouble() - 0.5) * 2 * spread;
                        double z2 = sg == segs ? cz : cz + (rnd.nextDouble() - 0.5) * 2 * spread;
                        event.renderer.line(px, py, pz, x2, y2, z2, boltC);
                        px = x2; py = y2; pz = z2;
                    }
                }
                continue;
            }

            // Bright core
            int coreA = (int) Math.min(255, (style == MarkerStyle.Solid ? 170 : 230) * pulse);
            Color core = new Color(r, g, b, coreA);
            event.renderer.box(cx - half, bottom, cz - half, cx + half, top, cz + half,
                core, new Color(255, 255, 255, (int) (200 * pulse)),
                style == MarkerStyle.Solid ? ShapeMode.Both : ShapeMode.Sides, 0);

            if (style == MarkerStyle.Solid) continue;

            // Soft glow halos: wider, fainter boxes layered around the core
            for (int i = 1; i <= glow; i++) {
                double h = half + i * (0.35 + baseHalf * 0.5);
                int a = (int) (Math.max(6, 90 / (i + 0.5)) * pulse);
                Color halo = new Color(r, g, b, a);
                event.renderer.box(cx - h, bottom, cz - h, cx + h, top, cz + h, halo, halo, ShapeMode.Sides, 0);
            }
        }
    }
}
