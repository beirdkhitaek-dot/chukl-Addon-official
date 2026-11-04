package com.chukl.addon.modules;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structure data for the Seed Finder: how to recognise a structure from the blocks of a chunk (the analyzer) and how a
 * world seed decides where a structure of that kind may start (the solver). The placement numbers (spacing, separation,
 * salt) are the Java Edition 1.18+ values written from memory. They have not been checked against a world with a known
 * seed, so treat solver results as unconfirmed until one is.
 */
final class SeedStructures {
    private SeedStructures() {}

    /** What the analyzer looks for: a structure is reported when every required block is present in the same chunk. */
    record Sig(String kind, boolean nether, Block... required) {}

    static final List<Sig> SIGS = List.of(
        new Sig("desert_pyramid", false, Blocks.CHISELED_SANDSTONE, Blocks.TNT, Blocks.STONE_PRESSURE_PLATE),
        new Sig("jungle_temple", false, Blocks.MOSSY_COBBLESTONE, Blocks.TRIPWIRE_HOOK),
        new Sig("igloo", false, Blocks.SNOW_BLOCK, Blocks.RED_BED, Blocks.WHITE_CARPET),
        new Sig("swamp_hut", false, Blocks.CAULDRON, Blocks.CRAFTING_TABLE, Blocks.SPRUCE_STAIRS),
        new Sig("ocean_monument", false, Blocks.PRISMARINE_BRICKS, Blocks.SEA_LANTERN, Blocks.DARK_PRISMARINE),
        new Sig("pillager_outpost", false, Blocks.DARK_OAK_LOG, Blocks.COBBLESTONE, Blocks.DARK_OAK_PLANKS, Blocks.WHITE_WOOL),
        new Sig("stronghold", false, Blocks.END_PORTAL_FRAME),
        new Sig("ancient_city", false, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST, Blocks.SOUL_LANTERN),
        new Sig("trial_chambers", false, Blocks.TRIAL_SPAWNER),
        new Sig("mineshaft", false, Blocks.RAIL, Blocks.OAK_FENCE, Blocks.OAK_PLANKS),
        new Sig("village", false, Blocks.BELL),
        new Sig("nether_fortress", true, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE),
        new Sig("bastion", true, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE),
        new Sig("ruined_portal", true, Blocks.CRYING_OBSIDIAN, Blocks.NETHERRACK)
    );

    /** Every distinct block any signature needs. */
    static final Set<Block> ALL = new HashSet<>();
    static {
        for (Sig s : SIGS) for (Block b : s.required()) ALL.add(b);
    }

    /** Placement of one kind: a region of {@code spacing} chunks holds one start, at least {@code separation} chunks from the next. */
    record Place(int spacing, int separation, int salt, boolean triangular, int tolerance) {}

    /** Kinds the solver uses: compact structures whose start chunk is close to where their blocks are. */
    static Place place(String kind) {
        return switch (kind) {
            case "desert_pyramid" -> new Place(32, 8, 14357617, false, 2);
            case "jungle_temple" -> new Place(32, 8, 14357619, false, 2);
            case "igloo" -> new Place(32, 8, 14357618, false, 2);
            case "swamp_hut" -> new Place(32, 8, 14357620, false, 2);
            case "pillager_outpost" -> new Place(32, 8, 165745296, false, 3);
            case "ocean_monument" -> new Place(32, 5, 10387313, true, 4);
            default -> null;
        };
    }

    /** A tiny copy of java.util.Random's generator, so the solver can try millions of seeds without allocating. */
    static final class Lcg {
        private long s;

        void setSeed(long seed) { s = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1); }

        private int next(int bits) {
            s = (s * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            return (int) (s >>> (48 - bits));
        }

        int nextInt(int bound) {
            int r = next(31);
            int m = bound - 1;
            if ((bound & m) == 0) return (int) ((bound * (long) r) >> 31);
            for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) { /* retry */ }
            return r;
        }
    }

    /** The chunk where the structure of region (rx, rz) starts for this world seed. Result goes to out[0]=x, out[1]=z. */
    static void startChunk(Lcg rnd, Place p, long worldSeed, int rx, int rz, int[] out) {
        rnd.setSeed(rx * 341873128712L + rz * 132897987541L + worldSeed + p.salt());
        int range = p.spacing() - p.separation();
        int ox, oz;
        if (p.triangular()) {
            ox = (rnd.nextInt(range) + rnd.nextInt(range)) / 2;
            oz = (rnd.nextInt(range) + rnd.nextInt(range)) / 2;
        } else {
            ox = rnd.nextInt(range);
            oz = rnd.nextInt(range);
        }
        out[0] = rx * p.spacing() + ox;
        out[1] = rz * p.spacing() + oz;
    }

    /** One structure the analyzer saw: kind and where (chunk coordinates, averaged over the chunks it covers). */
    record Obs(String kind, double cx, double cz) {}

    /** How many observations a candidate seed explains. */
    static int score(Lcg rnd, int[] tmp, List<Obs> obs, long seed) {
        int hit = 0;
        for (Obs o : obs) if (explains(rnd, tmp, o, seed)) hit++;
        return hit;
    }

    static boolean explains(Lcg rnd, int[] tmp, Obs o, long seed) {
        Place p = place(o.kind());
        if (p == null) return false;
        int tol = p.tolerance();
        int x0 = Math.floorDiv((int) Math.floor(o.cx()) - tol, p.spacing()), x1 = Math.floorDiv((int) Math.ceil(o.cx()) + tol, p.spacing());
        int z0 = Math.floorDiv((int) Math.floor(o.cz()) - tol, p.spacing()), z1 = Math.floorDiv((int) Math.ceil(o.cz()) + tol, p.spacing());
        for (int rx = x0; rx <= x1; rx++) {
            for (int rz = z0; rz <= z1; rz++) {
                startChunk(rnd, p, seed, rx, rz, tmp);
                if (Math.abs(tmp[0] - o.cx()) <= tol + 0.5 && Math.abs(tmp[1] - o.cz()) <= tol + 0.5) return true;
            }
        }
        return false;
    }

    /** Merges observations of the same kind that are close together (one structure covers several chunks). */
    static List<Obs> merge(List<Obs> in) {
        List<double[]> sum = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (Obs o : in) {
            boolean joined = false;
            for (int i = 0; i < kinds.size(); i++) {
                if (!kinds.get(i).equals(o.kind())) continue;
                double[] a = sum.get(i);
                if (Math.abs(a[0] / a[2] - o.cx()) <= 5 && Math.abs(a[1] / a[2] - o.cz()) <= 5) {
                    a[0] += o.cx(); a[1] += o.cz(); a[2]++;
                    joined = true;
                    break;
                }
            }
            if (!joined) {
                kinds.add(o.kind());
                sum.add(new double[]{o.cx(), o.cz(), 1});
            }
        }
        List<Obs> out = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            double[] a = sum.get(i);
            out.add(new Obs(kinds.get(i), a[0] / a[2], a[1] / a[2]));
        }
        return out;
    }
}
