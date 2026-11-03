package com.chukl.addon.cs16;

/**
 * Small software renderer for {@link MdlModel}. It draws the model into an ARGB int[] with a depth buffer
 * (transparent background), so the result can be uploaded as a texture and shown on screen.
 *
 * View space follows the GoldSrc view model convention: the eye is at the origin, +X is forward, +Y is left, +Z is up.
 */
public final class MdlRasterizer {
    public static final class Params {
        /** Zoom (1 = the same field of view Counter-Strike uses for its view models). */
        public float zoom = 1.0f;
        /** Shift of the model in model units: forward, right, up. */
        public float moveForward = 0, moveRight = 0, moveUp = 0;
        /** Mirror horizontally (left-handed view model). */
        public boolean mirror = false;
        /** Extra brightness multiplier. */
        public float brightness = 1.0f;
    }

    private static final float NEAR = 0.6f;
    private static final float[] LIGHT = normalize(-0.35f, 0.65f, -0.67f); // view space: right, up, forward (toward the light)

    private final int w, h;
    private final float[] zbuf;

    // scratch
    private float[] vx = new float[0], vy = new float[0], vz = new float[0];
    private float[] nr = new float[0], nu = new float[0], nd = new float[0];
    private final float[][] poly = new float[8][6];   // d, r, up, u, v, b
    private final float[][] clipped = new float[8][6];

    private float cx, cy, focal;
    private int[] out;
    private boolean additive;
    private MdlModel.Tex tex;
    private boolean mirror;

    public MdlRasterizer(int w, int h) {
        this.w = w;
        this.h = h;
        this.zbuf = new float[w * h];
    }

    public int width() { return w; }
    public int height() { return h; }

    private static float[] normalize(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    /** Renders the model in the given pose into {@code pixels} (length width*height, ARGB, cleared first). */
    public void render(MdlModel model, float[][] pose, int[] pixels, Params p) {
        java.util.Arrays.fill(pixels, 0);
        java.util.Arrays.fill(zbuf, 0f);
        out = pixels;
        mirror = p.mirror;
        cx = w * 0.5f;
        cy = h * 0.5f;
        // CS uses a 90 degree horizontal field of view on a 4:3 screen -> tan(vfov/2) = 0.75
        focal = (h * 0.5f) / 0.75f * p.zoom;

        for (int pass = 0; pass < 2; pass++) {
            additive = pass == 1;
            for (MdlModel.Part part : model.parts) {
                transformPart(part, pose, p);
                for (MdlModel.Mesh mesh : part.meshes) {
                    MdlModel.Tex t = model.textures[mesh.tex];
                    boolean add = (t.flags & MdlModel.TEX_ADDITIVE) != 0;
                    if (add != additive) continue;
                    tex = t;
                    drawMesh(mesh, t, p);
                }
            }
        }
    }

    private void transformPart(MdlModel.Part part, float[][] pose, Params p) {
        int nv = part.vertBone.length, nn = part.normBone.length;
        if (vx.length < nv) { vx = new float[nv]; vy = new float[nv]; vz = new float[nv]; }
        if (nr.length < nn) { nr = new float[nn]; nu = new float[nn]; nd = new float[nn]; }
        for (int i = 0; i < nv; i++) {
            float[] m = pose[part.vertBone[i]];
            float x = part.verts[i * 3], y = part.verts[i * 3 + 1], z = part.verts[i * 3 + 2];
            float mx = m[0] * x + m[1] * y + m[2] * z + m[3];
            float my = m[4] * x + m[5] * y + m[6] * z + m[7];
            float mz = m[8] * x + m[9] * y + m[10] * z + m[11];
            // GoldSrc (x fwd, y left, z up) -> view (d forward, r right, u up)
            vx[i] = mx + p.moveForward;
            vy[i] = -(my) + p.moveRight;
            vz[i] = mz + p.moveUp;
        }
        for (int i = 0; i < nn; i++) {
            float[] m = pose[part.normBone[i]];
            float x = part.norms[i * 3], y = part.norms[i * 3 + 1], z = part.norms[i * 3 + 2];
            float mx = m[0] * x + m[1] * y + m[2] * z;
            float my = m[4] * x + m[5] * y + m[6] * z;
            float mz = m[8] * x + m[9] * y + m[10] * z;
            nd[i] = mx;
            nr[i] = -my;
            nu[i] = mz;
        }
    }

    private void drawMesh(MdlModel.Mesh mesh, MdlModel.Tex t, Params p) {
        boolean chrome = (t.flags & MdlModel.TEX_CHROME) != 0;
        boolean full = (t.flags & MdlModel.TEX_FULLBRIGHT) != 0;
        int tris = mesh.triCount();
        for (int i = 0; i < tris; i++) {
            for (int k = 0; k < 3; k++) {
                int e = i * 3 + k;
                int vi = mesh.vert[e], ni = mesh.norm[e];
                float[] v = poly[k];
                v[0] = vx[vi];
                v[1] = vy[vi];
                v[2] = vz[vi];
                if (chrome) {
                    v[3] = nr[ni] * 0.5f + 0.5f;
                    v[4] = nu[ni] * 0.5f + 0.5f;
                } else {
                    v[3] = mesh.s[e] / t.width;
                    v[4] = mesh.t[e] / t.height;
                }
                if (full) v[5] = 1.0f;
                else {
                    float dot = nr[ni] * LIGHT[0] + nu[ni] * LIGHT[1] + nd[ni] * LIGHT[2];
                    v[5] = 0.55f + 0.5f * Math.max(0f, dot);
                }
                v[5] *= p.brightness;
            }
            clipAndDraw(3);
        }
    }

    /** Clips the polygon in {@code poly[0..n)} against the near plane and draws it as a triangle fan. */
    private void clipAndDraw(int n) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            float[] a = poly[i], b = poly[(i + 1) % n];
            boolean ina = a[0] >= NEAR, inb = b[0] >= NEAR;
            if (ina) copy(a, clipped[m++]);
            if (ina != inb) {
                float t = (NEAR - a[0]) / (b[0] - a[0]);
                float[] o = clipped[m++];
                for (int c = 0; c < 6; c++) o[c] = a[c] + (b[c] - a[c]) * t;
                o[0] = NEAR;
            }
        }
        if (m < 3) return;
        for (int i = 1; i + 1 < m; i++) rasterize(clipped[0], clipped[i], clipped[i + 1]);
    }

    private static void copy(float[] a, float[] b) { System.arraycopy(a, 0, b, 0, 6); }

    private void rasterize(float[] a, float[] b, float[] c) {
        float ax = screenX(a), ay = cy - a[2] / a[0] * focal;
        float bx = screenX(b), by = cy - b[2] / b[0] * focal;
        float cxp = screenX(c), cyp = cy - c[2] / c[0] * focal;

        float area = (bx - ax) * (cyp - ay) - (cxp - ax) * (by - ay);
        if (area > -1e-4f && area < 1e-4f) return;
        float invArea = 1.0f / area;

        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cxp))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cxp))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cyp))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cyp))));
        if (minX > maxX || minY > maxY) return;

        float ia = 1.0f / a[0], ib = 1.0f / b[0], ic = 1.0f / c[0];
        float ua = a[3] * ia, ub = b[3] * ib, uc = c[3] * ic;
        float va = a[4] * ia, vb = b[4] * ib, vc = c[4] * ic;
        float ba = a[5], bb = b[5], bc = c[5];

        // edge function steps (weights of a, b, c)
        float w0dx = -(cyp - by) * invArea, w0dy = (cxp - bx) * invArea;   // edge b->c
        float w1dx = -(ay - cyp) * invArea, w1dy = (ax - cxp) * invArea;   // edge c->a
        float w2dx = -(by - ay) * invArea, w2dy = (bx - ax) * invArea;     // edge a->b

        final int[] pix = out;
        final int tw = tex.width, th = tex.height;
        final int[] texels = tex.argb;
        final boolean add = additive;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f, px0 = minX + 0.5f;
            float w0 = ((cxp - bx) * (py - by) - (cyp - by) * (px0 - bx)) * invArea;
            float w1 = ((ax - cxp) * (py - cyp) - (ay - cyp) * (px0 - cxp)) * invArea;
            float w2 = ((bx - ax) * (py - ay) - (by - ay) * (px0 - ax)) * invArea;
            int row = y * w;
            for (int x = minX; x <= maxX; x++, w0 += w0dx, w1 += w1dx, w2 += w2dx) {
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float inv = w0 * ia + w1 * ib + w2 * ic;
                int idx = row + x;
                if (inv <= zbuf[idx]) continue;
                float rec = 1.0f / inv;
                float u = (w0 * ua + w1 * ub + w2 * uc) * rec;
                float v = (w0 * va + w1 * vb + w2 * vc) * rec;
                int tx = (int) Math.floor(u * tw) % tw;
                int ty = (int) Math.floor(v * th) % th;
                if (tx < 0) tx += tw;
                if (ty < 0) ty += th;
                int texel = texels[ty * tw + tx];
                int ta = texel >>> 24;
                if (ta == 0) continue;
                float br = w0 * ba + w1 * bb + w2 * bc;
                int r = (int) (((texel >> 16) & 0xFF) * br);
                int g = (int) (((texel >> 8) & 0xFF) * br);
                int bl = (int) ((texel & 0xFF) * br);
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (bl > 255) bl = 255;
                if (add) {
                    int d = pix[idx];
                    int dr = Math.min(255, ((d >> 16) & 0xFF) + r);
                    int dg = Math.min(255, ((d >> 8) & 0xFF) + g);
                    int db = Math.min(255, (d & 0xFF) + bl);
                    int da = Math.max(d >>> 24, Math.max(r, Math.max(g, bl)));
                    pix[idx] = (da << 24) | (dr << 16) | (dg << 8) | db;
                } else {
                    zbuf[idx] = inv;
                    pix[idx] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                }
            }
        }
    }

    private float screenX(float[] v) {
        float sx = v[1] / v[0] * focal;
        return mirror ? cx - sx : cx + sx;
    }
}
