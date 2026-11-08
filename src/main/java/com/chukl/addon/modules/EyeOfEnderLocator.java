package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Finds the stronghold by triangulation: every thrown eye of ender flies in a straight line toward it, so two throws
 * from different spots cross exactly there. Watches the eye entities itself, so there is nothing to type or measure.
 */
public class EyeOfEnderLocator extends Module {
    private static final int MAX_THROWS = 6;
    private static final double SAME_SPOT = 3.0;      // a new throw this close to an old one replaces it (a retake)
    private static final double MIN_FLIGHT = 4.0;     // blocks an eye must fly before its direction can be trusted
    private static final double ANGLE_NOISE = 0.0008; // radians, used for the "about +/- N blocks" figure

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-messages")
        .description("Tell you in chat where the stronghold is after each throw.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLines = sgGeneral.add(new BoolSetting.Builder()
        .name("show-in-world")
        .description("Draw a line along each throw, and a tall beam with a square where the stronghold probably is.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the lines and the beam.")
        .defaultValue(new SettingColor(190, 90, 255, 255))
        .visible(showLines::get)
        .build()
    );

    /** One finished throw: where it started and the direction it flew (length 1, on the ground plane). */
    private record Throw(double ox, double oy, double oz, double dx, double dz) {}

    /** An eye that is still in the air. */
    private static class Flight {
        double ox, oy, oz;
        double lx, lz;
        int lastSeen;
    }

    private final List<Throw> throwsList = new ArrayList<>();
    private final Map<Integer, Flight> flights = new HashMap<>();
    private ClientWorld lastWorld;
    private int tickCount;
    private boolean warnedDimension;

    private boolean hasEstimate;
    private double estX, estZ, estErr;

    public EyeOfEnderLocator() {
        super(ChuklAddon.CATEGORY, "eye-of-ender-locator",
            "Finds the stronghold: throw an eye of ender, walk a bit sideways, throw another. It tells you where they cross.");
    }

    @Override
    public void onActivate() {
        reset();
        lastWorld = mc.world;
        say("Throw an eye of ender, walk a bit sideways (the farther the better), then throw another. I will tell you where the stronghold is.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @Override
    public String getInfoString() {
        if (throwsList.isEmpty()) return null;
        return throwsList.size() + (throwsList.size() == 1 ? " throw" : " throws");
    }

    private void reset() {
        throwsList.clear();
        flights.clear();
        hasEstimate = false;
        warnedDimension = false;
    }

    private void say(String message) {
        if (chat.get()) info(message);
    }

    // --- watching the eyes ---

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (mc.world != lastWorld) { // new world or dimension: old throws mean nothing there
            lastWorld = mc.world;
            reset();
        }
        tickCount++;

        for (Entity e : mc.world.getEntities()) {
            String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            if (!path.equals("eye_of_ender") && !path.contains("ender_eye")) continue;

            int id = e.getId();
            Flight f = flights.get(id);
            if (f == null) {
                f = new Flight();
                f.ox = e.getX();
                f.oy = e.getY();
                f.oz = e.getZ();
                flights.put(id, f);
            }
            f.lx = e.getX();
            f.lz = e.getZ();
            f.lastSeen = tickCount;
        }

        // an eye that vanished has finished its flight (it dropped or broke): read its path
        Iterator<Map.Entry<Integer, Flight>> it = flights.entrySet().iterator();
        while (it.hasNext()) {
            Flight f = it.next().getValue();
            if (f.lastSeen != tickCount) {
                finish(f);
                it.remove();
            }
        }
    }

    private void finish(Flight f) {
        if (mc.world == null || mc.player == null) return;

        if (mc.world.getRegistryKey() != World.OVERWORLD) {
            if (!warnedDimension) {
                warnedDimension = true;
                say("Eyes of ender only point to a stronghold in the Overworld.");
            }
            return;
        }

        double dx = f.lx - f.ox;
        double dz = f.lz - f.oz;
        double len = Math.hypot(dx, dz);
        if (len < MIN_FLIGHT) {
            say("That eye did not fly far enough to read. Throw it again in a clear spot.");
            return;
        }
        dx /= len;
        dz /= len;

        // throwing again from the same spot: keep only the newest
        throwsList.removeIf(t -> Math.hypot(t.ox() - f.ox, t.oz() - f.oz) < SAME_SPOT);
        throwsList.add(new Throw(f.ox, f.oy, f.oz, dx, dz));
        while (throwsList.size() > MAX_THROWS) throwsList.remove(0);

        if (throwsList.size() < 2) {
            say("Throw 1 saved: the stronghold is toward " + compass(dx, dz) + ". Now walk sideways and throw another.");
            hasEstimate = false;
            return;
        }

        solve();
    }

    // --- the math: the point closest to all the throw lines ---

    private void solve() {
        double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
        for (Throw t : throwsList) {
            double m11 = 1 - t.dx() * t.dx();
            double m12 = -t.dx() * t.dz();
            double m22 = 1 - t.dz() * t.dz();
            a11 += m11;
            a12 += m12;
            a22 += m22;
            b1 += m11 * t.ox() + m12 * t.oz();
            b2 += m12 * t.ox() + m22 * t.oz();
        }

        // how different the directions are: sine of the widest angle between two throws
        double maxSin = 0;
        for (int i = 0; i < throwsList.size(); i++) {
            for (int j = i + 1; j < throwsList.size(); j++) {
                Throw p = throwsList.get(i), q = throwsList.get(j);
                maxSin = Math.max(maxSin, Math.abs(p.dx() * q.dz() - p.dz() * q.dx()));
            }
        }

        double det = a11 * a22 - a12 * a12;
        if (maxSin < 1.0e-4 || Math.abs(det) < 1.0e-9) {
            hasEstimate = false;
            say("Both throws point the same way. Walk sideways (not toward or away from it) and throw again.");
            return;
        }

        double x = (a22 * b1 - a12 * b2) / det;
        double z = (a11 * b2 - a12 * b1) / det;

        // the point has to be in FRONT of every throw, otherwise one throw is wrong
        for (Throw t : throwsList) {
            if ((x - t.ox()) * t.dx() + (z - t.oz()) * t.dz() <= 0) {
                hasEstimate = false;
                say("The lines cross behind one of your throws, so one throw is off. Throw again from a new spot.");
                return;
            }
        }

        double dist = Math.hypot(x - mc.player.getX(), z - mc.player.getZ());
        double err = Math.max(8.0, Math.min(9999.0, dist * ANGLE_NOISE / maxSin));

        estX = x;
        estZ = z;
        estErr = err;
        hasEstimate = true;

        String how = err > 150 ? " Walk farther sideways and throw again to make it exact."
            : (throwsList.size() < 3 ? " One more throw from another spot makes it more exact." : "");
        say("Stronghold is near X " + Math.round(x) + ", Z " + Math.round(z)
            + " (about " + Math.round(err) + " blocks either way, " + Math.round(dist) + " blocks "
            + compass(x - mc.player.getX(), z - mc.player.getZ()) + " of you)."
            + " Dig down there; the portal room is usually within about 100 blocks." + how);
    }

    private static String compass(double dx, double dz) {
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        int index = (int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4));
        return names[((index % 8) + 8) % 8];
    }

    // --- drawing ---

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showLines.get() || mc.world == null || mc.player == null) return;

        SettingColor c = color.get();
        Color faint = new Color(c.r, c.g, c.b, 110);

        for (Throw t : throwsList) {
            event.renderer.line(t.ox(), t.oy(), t.oz(), t.ox() + t.dx() * 3500, t.oy(), t.oz() + t.dz() * 3500, faint);
        }

        if (!hasEstimate) return;

        double bottom = mc.world.getBottomY();
        double top = bottom + mc.world.getHeight();
        event.renderer.line(estX, bottom, estZ, estX, top, estZ, c);

        double y = Math.floor(mc.player.getY());
        event.renderer.box(estX - estErr, y, estZ - estErr, estX + estErr, y + 1, estZ + estErr,
            new Color(c.r, c.g, c.b, 25), c, ShapeMode.Lines, 0);

        event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, estX, y + 1, estZ, faint);
    }
}
