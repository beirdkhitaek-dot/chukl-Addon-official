package com.chukl.addon.modules.water;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;

/**
 * Colors every block whose light level is inside the range you choose, so dim spots and hidden lit rooms stand out.
 *
 * Built to stay light on weak phones: it reads the light data of each chunk once (about 8 chunks per pass, nearest
 * first, again every few seconds), keeps the result in small arrays, and draws only the nearest few thousand blocks.
 */
public final class LightDebug extends WModule {
   private static final int SCAN_RADIUS = 3;          // chunks around you
   private static final int PASS_TICKS = 10;          // look for chunks that need a scan twice a second
   private static final long RESCAN_MS = 4000L;       // a scanned chunk is read again after this long
   private static final int CHUNKS_PER_PASS = 8;
   private static final int MAX_CELLS_PER_CHUNK = 4000;
   private static final int MAX_DRAWN = 2500;         // blocks drawn per frame
   private static final double DRAW_DISTANCE = 40.0;  // blocks
   private static final long REBUILD_MS = 250L;       // the list of drawn blocks is rebuilt this often

   private final Setting<Integer> minLight = new Setting<>("Min Light", 1, 0, 15);
   private final Setting<Integer> maxLight = new Setting<>("Max Light", 7, 0, 15);
   private final Setting<Integer> minY = new Setting<>("Min Y", -51, -64, 20);
   private final Setting<Integer> maxY = new Setting<>("Max Y", 20, -64, 20);
   private final Setting<Color> colorLow = new Setting<>("Color Low", new Color(50, 200, 50, 60));
   private final Setting<Color> colorHigh = new Setting<>("Color High", new Color(255, 50, 50, 60));

   /** Block positions of one chunk (x, y, z packed in groups of three) and their light level. */
   private record Cells(int[] xyz, byte[] lvl) {}

   private final Map<ChunkPos, Cells> cells = new ConcurrentHashMap<>();
   private final Map<ChunkPos, Long> scannedAt = new ConcurrentHashMap<>();
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private ExecutorService scanExec;
   private ClientWorld lastWorld;
   private int scanTimer;
   private int settingsSig = Integer.MIN_VALUE;

   // what is drawn (rebuilt a few times a second, not every frame)
   private int[] drawXyz = new int[0];
   private byte[] drawLvl = new byte[0];
   private int drawCount;
   private long drawAt;
   private Color[] palette;
   private int paletteSig;

   public LightDebug() {
      super("LightDebug", Category.RENDER);
      this.addSetting(this.minLight);
      this.addSetting(this.maxLight);
      this.addSetting(this.minY);
      this.addSetting(this.maxY);
      this.addSetting(this.colorLow);
      this.addSetting(this.colorHigh);
   }

   @Override
   public void onEnable() {
      this.clearData();
      this.lastWorld = mc.world;
      this.scanTimer = 0;
      this.scanning.set(false);
   }

   @Override
   public void onDisable() {
      this.clearData();
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
         this.scanExec = null;
      }
   }

   private void clearData() {
      this.cells.clear();
      this.scannedAt.clear();
      this.drawXyz = new int[0];
      this.drawLvl = new byte[0];
      this.drawCount = 0;
      this.settingsSig = Integer.MIN_VALUE;
   }

   @Override
   public void onTick() {
      if (mc.world == null || mc.player == null) {
         return;
      }

      ClientWorld world = mc.world;
      if (world != this.lastWorld) { // new world or dimension
         this.lastWorld = world;
         this.clearData();
      }

      int minL = Math.min(this.minLight.getValue(), this.maxLight.getValue());
      int maxL = Math.max(this.minLight.getValue(), this.maxLight.getValue());
      int yMin = Math.min(this.minY.getValue(), this.maxY.getValue());
      int yMax = Math.max(this.minY.getValue(), this.maxY.getValue());

      int sig = Objects.hash(minL, maxL, yMin, yMax);
      if (sig != this.settingsSig) { // a range changed: read everything again
         this.settingsSig = sig;
         this.cells.clear();
         this.scannedAt.clear();
      }

      if (++this.scanTimer % PASS_TICKS != 0) {
         return;
      }
      if (!this.scanning.compareAndSet(false, true)) {
         return;
      }

      ChunkPos here = mc.player.getChunkPos();
      long now = System.currentTimeMillis();
      List<ChunkPos> todo = new ArrayList<>();
      for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
         for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
            int cx = here.x + dx;
            int cz = here.z + dz;
            if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
               continue;
            }
            ChunkPos cp = new ChunkPos(cx, cz);
            Long at = this.scannedAt.get(cp);
            if (at == null || now - at > RESCAN_MS) {
               todo.add(cp);
            }
         }
      }

      // forget chunks that are now far away
      this.cells.keySet().removeIf(c -> Math.abs(c.x - here.x) > SCAN_RADIUS + 1 || Math.abs(c.z - here.z) > SCAN_RADIUS + 1);
      this.scannedAt.keySet().removeIf(c -> Math.abs(c.x - here.x) > SCAN_RADIUS + 1 || Math.abs(c.z - here.z) > SCAN_RADIUS + 1);

      if (todo.isEmpty()) {
         this.scanning.set(false);
         return;
      }

      // nearest chunks first, a few per pass, so a scan never takes long
      todo.sort(Comparator.comparingInt(c -> (c.x - here.x) * (c.x - here.x) + (c.z - here.z) * (c.z - here.z)));
      List<ChunkPos> job = todo.size() > CHUNKS_PER_PASS ? new ArrayList<>(todo.subList(0, CHUNKS_PER_PASS)) : todo;

      if (this.scanExec == null || this.scanExec.isShutdown()) {
         this.scanExec = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "lightdebug-scan");
            th.setDaemon(true);
            th.setPriority(Thread.MIN_PRIORITY);
            return th;
         });
      }

      this.scanExec.execute(() -> {
         try {
            for (ChunkPos cp : job) {
               if (mc.world != world) {
                  break;
               }
               Cells found = scanChunk(world, cp, minL, maxL, yMin, yMax);
               if (found == null) {
                  this.cells.remove(cp);
               } else {
                  this.cells.put(cp, found);
               }
               this.scannedAt.put(cp, System.currentTimeMillis());
            }
         } catch (Throwable t) {
            ChuklAddon.LOG.error("LightDebug scan failed", t);
         } finally {
            this.scanning.set(false);
         }
      });
   }

   /** Background thread: reads the block light of one chunk straight from the light data, no per-block lookups. */
   private static Cells scanChunk(ClientWorld world, ChunkPos cp, int minL, int maxL, int yMin, int yMax) {
      var view = world.getLightingProvider().get(LightType.BLOCK);
      int bottom = world.getBottomSectionCoord();
      int baseX = cp.x << 4;
      int baseZ = cp.z << 4;

      int cap = 128;
      int[] xyz = new int[cap * 3];
      byte[] lvl = new byte[cap];
      int n = 0;

      int firstSection = Math.max(bottom, Math.floorDiv(yMin, 16));
      int lastSection = Math.floorDiv(yMax, 16);

      scan:
      for (int sy = firstSection; sy <= lastSection; sy++) {
         ChunkNibbleArray array = view.getLightSection(ChunkSectionPos.from(cp.x, sy, cp.z));
         if (array == null) {
            continue;
         }
         int sectionMinY = sy << 4;

         for (int y = 0; y < 16; y++) {
            int worldY = sectionMinY + y;
            if (worldY < yMin || worldY > yMax) {
               continue;
            }
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  int level = array.get(x, y, z);
                  if (level < minL || level > maxL) {
                     continue;
                  }
                  if (n >= MAX_CELLS_PER_CHUNK) {
                     break scan;
                  }
                  if (n >= cap) {
                     cap *= 2;
                     xyz = Arrays.copyOf(xyz, cap * 3);
                     lvl = Arrays.copyOf(lvl, cap);
                  }
                  xyz[n * 3] = baseX + x;
                  xyz[n * 3 + 1] = worldY;
                  xyz[n * 3 + 2] = baseZ + z;
                  lvl[n] = (byte) level;
                  n++;
               }
            }
         }
      }

      if (n == 0) {
         return null;
      }
      return new Cells(Arrays.copyOf(xyz, n * 3), Arrays.copyOf(lvl, n));
   }

   /** Render thread, a few times a second: picks the nearest blocks to draw. */
   private void rebuildDrawList() {
      double px = mc.player.getX();
      double py = mc.player.getY();
      double pz = mc.player.getZ();
      double maxSq = DRAW_DISTANCE * DRAW_DISTANCE;

      // nearest chunks first, so the cap cuts off the far ones
      List<Map.Entry<ChunkPos, Cells>> list = new ArrayList<>(this.cells.entrySet());
      list.sort(Comparator.comparingDouble((Map.Entry<ChunkPos, Cells> e) -> {
         double dx = (e.getKey().x << 4) + 8 - px;
         double dz = (e.getKey().z << 4) + 8 - pz;
         return dx * dx + dz * dz;
      }));

      int[] xyz = new int[MAX_DRAWN * 3];
      byte[] lv = new byte[MAX_DRAWN];
      int n = 0;

      draw:
      for (Map.Entry<ChunkPos, Cells> entry : list) {
         Cells c = entry.getValue();
         int[] src = c.xyz();
         byte[] srcLvl = c.lvl();
         for (int i = 0; i < srcLvl.length; i++) {
            int x = src[i * 3];
            int y = src[i * 3 + 1];
            int z = src[i * 3 + 2];
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > maxSq) {
               continue;
            }
            if (n >= MAX_DRAWN) {
               break draw;
            }
            xyz[n * 3] = x;
            xyz[n * 3 + 1] = y;
            xyz[n * 3 + 2] = z;
            lv[n] = srcLvl[i];
            n++;
         }
      }

      this.drawXyz = xyz;
      this.drawLvl = lv;
      this.drawCount = n;
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world == null || mc.player == null || this.cells.isEmpty()) {
         return;
      }

      Camera cam = RenderUtils.getCamera();
      if (cam == null) {
         return;
      }

      long now = System.currentTimeMillis();
      if (now - this.drawAt >= REBUILD_MS) {
         this.drawAt = now;
         this.rebuildDrawList();
      }

      int count = this.drawCount;
      if (count == 0) {
         return;
      }

      Vec3d camPos = RenderUtils.getCameraPos(cam);
      Color[] colors = this.colors();
      int[] xyz = this.drawXyz;
      byte[] lv = this.drawLvl;
      matrices.push();

      try {
         RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
         for (int i = 0; i < count; i++) {
            double x1 = xyz[i * 3] - camPos.x;
            double y1 = xyz[i * 3 + 1] - camPos.y;
            double z1 = xyz[i * 3 + 2] - camPos.z;
            batch.renderFilledBox(x1, y1, z1, x1 + 1.0, y1 + 1.0, z1 + 1.0, colors[lv[i] & 15]);
         }

         batch.flush();
      } finally {
         matrices.pop();
      }
   }

   /** One color per light level, made once instead of once per block per frame. */
   private Color[] colors() {
      int minL = Math.min(this.minLight.getValue(), this.maxLight.getValue());
      int maxL = Math.max(this.minLight.getValue(), this.maxLight.getValue());
      Color low = this.colorLow.getValue();
      Color high = this.colorHigh.getValue();
      int sig = Objects.hash(minL, maxL, low.getRGB(), high.getRGB());
      if (this.palette == null || sig != this.paletteSig) {
         Color[] made = new Color[16];
         for (int level = 0; level < 16; level++) {
            made[level] = this.interpolateColor(level, minL, maxL, low, high);
         }
         this.palette = made;
         this.paletteSig = sig;
      }

      return this.palette;
   }

   private Color interpolateColor(int level, int minL, int maxL, Color low, Color high) {
      float range = Math.max(1, maxL - minL);
      float ratio = Math.max(0.0F, Math.min(1.0F, (level - minL) / range));
      int r = (int)(low.getRed() + (high.getRed() - low.getRed()) * ratio);
      int g = (int)(low.getGreen() + (high.getGreen() - low.getGreen()) * ratio);
      int b = (int)(low.getBlue() + (high.getBlue() - low.getBlue()) * ratio);
      int a = (int)(low.getAlpha() + (high.getAlpha() - low.getAlpha()) * ratio);
      return new Color(r, g, b, a);
   }
}
