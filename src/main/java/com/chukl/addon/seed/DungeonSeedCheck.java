package com.chukl.addon.seed;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Tells whether a dungeon (monster room) spawner stands exactly where the world seed puts one.
 *
 * How Minecraft places a dungeon (1.18+ placed feature "monster_room" / "monster_room_deep", decoration step
 * UNDERGROUND_STRUCTURES = 3): per chunk it seeds a legacy (java.util.Random compatible) generator with
 *   a = nextLong() | 1, b = nextLong() | 1 (generator seeded with the world seed)
 *   populationSeed = ((chunkX * 16) * a + (chunkZ * 16) * b) ^ worldSeed
 *   featureSeed    = populationSeed + featureIndex + 10000 * step
 * and then makes 10 attempts. Each attempt draws x = nextInt(16), z = nextInt(16), y = uniform(minY, maxY); the room
 * origin (where the spawner ends up) is that position. An attempt that finds no valid room still draws nextInt(2)
 * twice (room width and length) before giving up, so with "all earlier attempts failed" the position of every attempt is
 * known exactly. If an earlier attempt succeeded, the draws it used depend on the room it built, so a SECOND dungeon in
 * the same chunk cannot be predicted - callers treat that case as "unknown", never as proof of a player.
 *
 * Reference for the formulas: the public seed-finding libraries (SeedFinding mc_core ChunkRand.setPopulationSeed /
 * setDecoratorSeed, MIT) and the vanilla WorldgenRandom.setDecorationSeed / setFeatureSeed algorithm; hube12's
 * decorator salt notes confirm "salt = index + 10000 * step" (monster_room is in step 3).
 *
 * The feature index inside the step depends on how Minecraft sorts all biome features, and the exact height range is a
 * detail that is easy to get slightly wrong, so nothing is hard coded: the check tries every plausible index and height
 * range and only starts judging after one of them explains at least 2 real dungeon spawners. Until then it says
 * UNKNOWN, so it can never wrongly flag anything by itself.
 */
public final class DungeonSeedCheck {
    public enum Verdict { MATCH, MISMATCH, UNKNOWN }

    /** Plain-language state for the chat and the module info line. */
    public enum State { NO_SEED, LEARNING, CALIBRATED, WRONG_SEED }

    private static final int STEP = 3; // GenerationStep.Decoration.UNDERGROUND_STRUCTURES
    private static final int ATTEMPTS_NORMAL = 10; // monster_room: count 10
    private static final int ATTEMPTS_DEEP = 4;    // monster_room_deep: count 4
    private static final int MAX_INDEX = 40;
    private static final int NEEDED = 2;       // spawners that must agree before judging
    private static final int GIVE_UP_AT = 10;  // observed spawners without any agreement = seed looks wrong
    private static final int MAX_OBS = 24;

    /** Height ranges (min, max inclusive) tried for the normal room (y >= 0). First one is vanilla 1.18+. */
    private static final int[][] NORMAL_RANGES = {{0, 319}, {0, 320}, {0, 255}};
    /** Height ranges tried for the deep room (y < 0). First one is vanilla 1.18+ (aboveBottom(6) .. -1). */
    private static final int[][] DEEP_RANGES = {{-58, -1}, {-64, -1}, {-60, -1}};

    /** One calibrated combination: feature index + height range. */
    private record Combo(int index, int minY, int maxY, int attempts) {}

    private boolean hasSeed;
    private long seed;

    private final List<BlockPos> normalObs = new ArrayList<>();
    private final List<BlockPos> deepObs = new ArrayList<>();
    private final Set<BlockPos> seen = new HashSet<>();
    private Combo normal;
    private Combo deep;
    private State state = State.NO_SEED;

    /** Minecraft style: a number is the seed, any other text becomes its hash code, blank = unknown. */
    public static Long parseSeed(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return (long) t.hashCode();
        }
    }

    /** Sets (or clears, with null) the world seed. Forgets everything that was learned. */
    public void setSeed(Long value) {
        normalObs.clear();
        deepObs.clear();
        seen.clear();
        normal = null;
        deep = null;
        if (value == null) {
            hasSeed = false;
            seed = 0;
            state = State.NO_SEED;
        } else {
            hasSeed = true;
            seed = value;
            state = State.LEARNING;
        }
    }

    public State state() {
        return state;
    }

    public boolean hasSeed() {
        return hasSeed;
    }

    /** One short line for the module info text, or null. */
    public String shortStatus() {
        switch (state) {
            case LEARNING: return "seed: learning";
            case CALIBRATED: return "seed: ok";
            case WRONG_SEED: return "seed: wrong?";
            default: return null;
        }
    }

    /**
     * Tell the check about a spawner that really looks like a natural dungeon (cobblestone room, dungeon mob).
     * Returns a chat message when the state changed (calibrated, or seed looks wrong), otherwise null.
     */
    public String observe(BlockPos pos) {
        if (!hasSeed) return null;
        BlockPos p = pos.toImmutable();
        if (!seen.add(p)) return null;

        State before = state;
        boolean isDeep = p.getY() < 0;
        List<BlockPos> list = isDeep ? deepObs : normalObs;
        if (list.size() >= MAX_OBS) return null;
        list.add(p);

        boolean wasCalibrated = (isDeep ? deep : normal) != null;
        if (!wasCalibrated) {
            Combo best = findCombo(list, isDeep ? DEEP_RANGES : NORMAL_RANGES);
            if (best != null) {
                if (isDeep) deep = best; else normal = best;
            }
        }

        if (normal != null || deep != null) {
            state = State.CALIBRATED;
        } else if (state == State.WRONG_SEED) {
            // stay on "wrong" until something finally agrees
        } else if (normalObs.size() + deepObs.size() >= GIVE_UP_AT) {
            state = State.WRONG_SEED;
        }

        if (state == before) return null;
        int n = normalObs.size() + deepObs.size();
        if (state == State.CALIBRATED) {
            return "Seed check: calibrated with " + n + " natural spawners. Spawners are now judged by the world seed.";
        }
        if (state == State.WRONG_SEED) {
            return "Seed check: the seed looks wrong - " + n + " natural-looking dungeon spawners do not match it. "
                + "Check the world-seed setting. Until it matches, spawners are judged by their surroundings only.";
        }
        return null;
    }

    /** MATCH = stands where the seed puts a dungeon. MISMATCH = does not. UNKNOWN = not calibrated for this height. */
    public Verdict verdict(BlockPos pos) {
        if (!hasSeed || state != State.CALIBRATED) return Verdict.UNKNOWN;
        Combo c = pos.getY() < 0 ? deep : normal;
        if (c == null) return Verdict.UNKNOWN;
        return matches(seed, c, pos) ? Verdict.MATCH : Verdict.MISMATCH;
    }

    // ---------- the generator ----------

    /** Best combination that explains at least NEEDED of the observed spawners, or null. */
    private Combo findCombo(List<BlockPos> obs, int[][] ranges) {
        if (obs.size() < NEEDED) return null;
        Combo best = null;
        int bestHits = 0;
        for (int[] r : ranges) {
            for (int index = 0; index <= MAX_INDEX; index++) {
                Combo c = new Combo(index, r[0], r[1], r[1] < 0 ? ATTEMPTS_DEEP : ATTEMPTS_NORMAL);
                int hits = 0;
                for (BlockPos p : obs) if (matches(seed, c, p)) hits++;
                if (hits > bestHits) {
                    bestHits = hits;
                    best = c;
                }
            }
        }
        return bestHits >= NEEDED ? best : null;
    }

    /** True when one of the (all earlier attempts failed) dungeon attempts of the spawner's chunk lands on pos. */
    static boolean matches(long worldSeed, Combo c, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;

        Random rnd = new Random(worldSeed);
        long a = rnd.nextLong() | 1L;
        long b = rnd.nextLong() | 1L;
        long population = ((long) originX * a + (long) originZ * b) ^ worldSeed;
        rnd.setSeed(population + (long) c.index() + 10000L * STEP);

        int span = c.maxY() - c.minY() + 1;
        for (int attempt = 0; attempt < c.attempts(); attempt++) {
            int x = originX + rnd.nextInt(16);
            int z = originZ + rnd.nextInt(16);
            int y = rnd.nextInt(span) + c.minY();
            if (x == pos.getX() && z == pos.getZ() && y == pos.getY()) return true;
            rnd.nextInt(2); // room width draw of a failed attempt
            rnd.nextInt(2); // room length draw of a failed attempt
        }
        return false;
    }

    /** Test hook: the 10 predicted positions of a chunk for a combination (index, minY, maxY). */
    public static List<int[]> predict(long worldSeed, int chunkX, int chunkZ, int index, int minY, int maxY) {
        List<int[]> out = new ArrayList<>();
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        Random rnd = new Random(worldSeed);
        long a = rnd.nextLong() | 1L;
        long b = rnd.nextLong() | 1L;
        long population = ((long) originX * a + (long) originZ * b) ^ worldSeed;
        rnd.setSeed(population + (long) index + 10000L * STEP);
        int span = maxY - minY + 1;
        int attempts = maxY < 0 ? ATTEMPTS_DEEP : ATTEMPTS_NORMAL;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = originX + rnd.nextInt(16);
            int z = originZ + rnd.nextInt(16);
            int y = rnd.nextInt(span) + minY;
            out.add(new int[] {x, y, z});
            rnd.nextInt(2);
            rnd.nextInt(2);
        }
        return out;
    }
}
