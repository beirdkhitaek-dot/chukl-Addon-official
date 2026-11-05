package com.chukl.addon.cs16;

import net.minecraft.entity.LivingEntity;

/**
 * Meeting point between the CS16 modules. The CS16 Knife draws a third-person knife on your vanilla player model by
 * default. When a Player Model module is on, it registers a {@link RigProvider} so the knife is attached to that
 * model's hand bone instead (exactly how Counter-Strike attaches p_knife to a player model), and vanilla items keep
 * working because the knife only takes over a hand that holds a sword or axe.
 */
public final class Cs16Hooks {
    private Cs16Hooks() {}

    /** The skeleton of one entity right now, in model units, plus where it stands in the world. */
    public static final class Rig {
        /** Player model (its bones are used by name, for example "Bip01 R Hand"). */
        public MdlModel model;
        /** Current bone matrices of the model (3x4, row-major), like {@link MdlModel#pose}. */
        public float[][] pose;
        /** Where the model origin (its feet) is in the world, the yaw it faces (Minecraft degrees) and its size in blocks per model unit. */
        public double x, y, z, yawDegrees, scale = 0.025;
    }

    /** Implemented by the Player Model module. */
    public interface RigProvider {
        /** The rig used to draw this entity, or null when the entity is drawn with the vanilla model. */
        Rig rigFor(LivingEntity entity, float tickDelta);
    }

    /** Null while no Player Model module is active. */
    public static volatile RigProvider rigProvider;

    /** When the vanilla renderer last drew the local player's body (third person and any freecam, never first person). */
    public static volatile long ownRenderNanos;
}
