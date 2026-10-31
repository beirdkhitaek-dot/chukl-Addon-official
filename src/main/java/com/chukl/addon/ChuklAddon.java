package com.chukl.addon;

import com.chukl.addon.modules.DeepBlockEntityEsp;
import com.chukl.addon.modules.DugOutHighlighter;
import com.chukl.addon.modules.LightFinder;
import com.chukl.addon.modules.KrispyChunkFinder;
import com.chukl.addon.modules.SusChunkFinder;
import com.chukl.addon.modules.SusChunkFinderTest;
import com.chukl.addon.modules.VulxtsFreeLook;
import com.chukl.addon.modules.VulxtsFreecam;
import com.chukl.addon.modules.VulxtsSusChunk;
import com.chukl.addon.modules.WaterSusChunk;
import com.chukl.addon.modules.water.AmethystESP;
import com.chukl.addon.modules.water.BedrockVoidESP;
import com.chukl.addon.modules.water.BlockESP;
import com.chukl.addon.modules.water.ChinaHat;
import com.chukl.addon.modules.water.ChunkFinder;
import com.chukl.addon.modules.water.ExtraESP;
import com.chukl.addon.modules.water.FullBright;
import com.chukl.addon.modules.water.FutureDebug;
import com.chukl.addon.modules.water.HoleESP;
import com.chukl.addon.modules.water.JumpCircles;
import com.chukl.addon.modules.water.LightDebug;
import com.chukl.addon.modules.water.MobESP;
import com.chukl.addon.modules.water.PearlESP;
import com.chukl.addon.modules.water.PlayerESP;
import com.chukl.addon.modules.water.ScanOverlay;
import com.chukl.addon.modules.water.SpawnerNotifier;
import com.chukl.addon.modules.water.StorageESP;
import com.chukl.addon.modules.water.TntExplosionMarker;
import com.chukl.addon.modules.water.WaterBlockNotifier;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ChuklAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Chukl");
    public static final Category RENDER_CATEGORY = new Category("Chukl Render");

    @Override
    public void onInitialize() {
        LOG.info("Initializing ChuklAddon");

        Modules.get().add(new DeepBlockEntityEsp());
        Modules.get().add(new DugOutHighlighter());
        Modules.get().add(new SusChunkFinder());
        Modules.get().add(new SusChunkFinderTest());
        Modules.get().add(new LightFinder());
        Modules.get().add(new WaterSusChunk());
        Modules.get().add(new VulxtsSusChunk());
        Modules.get().add(new VulxtsFreecam());
        Modules.get().add(new VulxtsFreeLook());
        Modules.get().add(new KrispyChunkFinder());

        // Water Client render modules (Chukl Render category)
        Modules.get().add(new AmethystESP());
        Modules.get().add(new BedrockVoidESP());
        Modules.get().add(new BlockESP());
        Modules.get().add(new ChinaHat());
        Modules.get().add(new ChunkFinder());
        Modules.get().add(new ExtraESP());
        Modules.get().add(new FullBright());
        Modules.get().add(new FutureDebug());
        Modules.get().add(new HoleESP());
        Modules.get().add(new JumpCircles());
        Modules.get().add(new LightDebug());
        Modules.get().add(new MobESP());
        Modules.get().add(new PearlESP());
        Modules.get().add(new PlayerESP());
        Modules.get().add(new ScanOverlay());
        Modules.get().add(new SpawnerNotifier());
        Modules.get().add(new StorageESP());
        Modules.get().add(new TntExplosionMarker());
        Modules.get().add(new WaterBlockNotifier());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(RENDER_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.chukl.addon";
    }
}
