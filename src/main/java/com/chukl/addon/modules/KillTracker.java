package com.chukl.addon.modules;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Remembers who you hit and reports players that died shortly after. */
final class KillTracker {
    private final Map<Integer, Integer> lastHit = new HashMap<>();
    private final Map<Integer, Integer> counted = new HashMap<>();

    void hit(int entityId, int tick) {
        lastHit.put(entityId, tick);
    }

    void clear() {
        lastHit.clear();
        counted.clear();
    }

    /** Players that are newly dead. With anyDeath every death counts, otherwise only ones you hit within windowTicks. */
    List<PlayerEntity> deaths(ClientWorld world, PlayerEntity self, int tick, int windowTicks, boolean anyDeath) {
        List<PlayerEntity> out = new ArrayList<>();
        for (PlayerEntity p : world.getPlayers()) {
            if (p == self) continue;
            if (!(p.isDead() || p.getHealth() <= 0.0F || p.deathTime > 0)) continue;
            int id = p.getId();
            if (counted.containsKey(id)) continue;
            Integer h = lastHit.get(id);
            boolean mine = h != null && tick - h <= windowTicks;
            if (!anyDeath && !mine) continue;
            counted.put(id, tick);
            out.add(p);
        }
        lastHit.values().removeIf(t -> tick - t > 1200);
        counted.values().removeIf(t -> tick - t > 200);
        return out;
    }
}
