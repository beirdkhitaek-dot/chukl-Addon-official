package com.chukl.addon.cs16;

import java.util.ArrayList;
import java.util.List;

/**
 * A GoldSrc model baked into flat coloured triangles that can be drawn in the 3D world (the world has no textured
 * mesh path for addons, so each triangle takes the colour of the texture at its centre; big triangles are split first).
 * Shared by the CS16 Knife (third person and other players) and the future Player Model module.
 *
 * Coordinates stay in model space: x forward, y left, z up, in model units.
 */
public final class MdlWorldMesh {
    /** 9 floats per triangle: x,y,z of its three corners (rest pose, model space). */
    public float[] pos = new float[0];
    /** One ARGB colour per triangle (already lit). */
    public int[] argb = new int[0];
    /** The bone each triangle follows (the bone of its first vertex). */
    public int[] bone = new int[0];
    public int count;

    /** Bone that holds most of the geometry and where it sits in the baked pose (the natural grip point for attachments). */
    public int mainBone = -1;
    public float gripX, gripY, gripZ;

    private static final float[] LIGHT = norm(0.3f, 0.35f, 0.88f);

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    /**
     * Bakes {@code model} in the given pose.
     * @param subdivide split every triangle into four (nicer texture detail) when the model has at most this many triangles
     */
    public static MdlWorldMesh bake(MdlModel model, float[][] pose, int subdivide) {
        MdlWorldMesh out = new MdlWorldMesh();
        List<float[]> tris = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        List<Integer> bones = new ArrayList<>();
        int[] boneUse = new int[Math.max(1, model.bones.length)];

        int total = 0;
        for (MdlModel.Part p : model.parts) for (MdlModel.Mesh m : p.meshes) total += m.triCount();
        boolean split = total <= subdivide;

        for (MdlModel.Part part : model.parts) {
            int nv = part.vertBone.length;
            float[] wx = new float[nv], wy = new float[nv], wz = new float[nv];
            for (int i = 0; i < nv; i++) {
                float[] m = pose[part.vertBone[i]];
                float x = part.verts[i * 3], y = part.verts[i * 3 + 1], z = part.verts[i * 3 + 2];
                wx[i] = m[0] * x + m[1] * y + m[2] * z + m[3];
                wy[i] = m[4] * x + m[5] * y + m[6] * z + m[7];
                wz[i] = m[8] * x + m[9] * y + m[10] * z + m[11];
            }
            for (MdlModel.Mesh mesh : part.meshes) {
                MdlModel.Tex tex = model.textures[mesh.tex];
                if ((tex.flags & MdlModel.TEX_ADDITIVE) != 0) continue; // glows would turn into solid blobs
                boolean chrome = (tex.flags & MdlModel.TEX_CHROME) != 0;
                int n = mesh.triCount();
                for (int t = 0; t < n; t++) {
                    int e0 = t * 3, e1 = e0 + 1, e2 = e0 + 2;
                    int v0 = mesh.vert[e0], v1 = mesh.vert[e1], v2 = mesh.vert[e2];
                    float[] a = {wx[v0], wy[v0], wz[v0]}, b = {wx[v1], wy[v1], wz[v1]}, c = {wx[v2], wy[v2], wz[v2]};
                    float[] ua = {mesh.s[e0], mesh.t[e0]}, ub = {mesh.s[e1], mesh.t[e1]}, uc = {mesh.s[e2], mesh.t[e2]};

                    float shade = shade(a, b, c);
                    int bn = part.vertBone[v0];
                    boneUse[bn]++;

                    if (!split) {
                        add(tris, cols, bones, a, b, c, centroidColor(tex, chrome, ua, ub, uc, shade), bn);
                        continue;
                    }
                    float[] ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
                    float[] uab = mid2(ua, ub), ubc = mid2(ub, uc), uca = mid2(uc, ua);
                    addSub(tris, cols, bones, tex, chrome, shade, bn, a, ab, ca, ua, uab, uca);
                    addSub(tris, cols, bones, tex, chrome, shade, bn, b, bc, ab, ub, ubc, uab);
                    addSub(tris, cols, bones, tex, chrome, shade, bn, c, ca, bc, uc, uca, ubc);
                    addSub(tris, cols, bones, tex, chrome, shade, bn, ab, bc, ca, uab, ubc, uca);
                }
            }
        }

        out.count = tris.size();
        out.pos = new float[out.count * 9];
        out.argb = new int[out.count];
        out.bone = new int[out.count];
        for (int i = 0; i < out.count; i++) {
            System.arraycopy(tris.get(i), 0, out.pos, i * 9, 9);
            out.argb[i] = cols.get(i);
            out.bone[i] = bones.get(i);
        }
        int best = -1, bestUse = 0;
        for (int i = 0; i < boneUse.length; i++) if (boneUse[i] > bestUse) { bestUse = boneUse[i]; best = i; }
        out.mainBone = best;
        if (best >= 0) {
            out.gripX = pose[best][3];
            out.gripY = pose[best][7];
            out.gripZ = pose[best][11];
        }
        return out;
    }

    private static void addSub(List<float[]> tris, List<Integer> cols, List<Integer> bones, MdlModel.Tex tex, boolean chrome, float shade, int bone,
                               float[] a, float[] b, float[] c, float[] ua, float[] ub, float[] uc) {
        int col = centroidColor(tex, chrome, ua, ub, uc, shade);
        add(tris, cols, bones, a, b, c, col, bone);
    }

    private static void add(List<float[]> tris, List<Integer> cols, List<Integer> bones, float[] a, float[] b, float[] c, int col, int bone) {
        if ((col >>> 24) == 0) return; // masked-out texel
        tris.add(new float[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]});
        cols.add(col);
        bones.add(bone);
    }

    private static int centroidColor(MdlModel.Tex tex, boolean chrome, float[] ua, float[] ub, float[] uc, float shade) {
        float s = (ua[0] + ub[0] + uc[0]) / 3f, t = (ua[1] + ub[1] + uc[1]) / 3f;
        int tx = Math.floorMod((int) Math.floor(s), tex.width), ty = Math.floorMod((int) Math.floor(t), tex.height);
        int texel = tex.argb[ty * tex.width + tx];
        int a = texel >>> 24;
        if (a < 128) return 0;
        int r = (texel >> 16) & 0xFF, g = (texel >> 8) & 0xFF, bl = texel & 0xFF;
        if (chrome) { r = 170; g = 176; bl = 188; } // metal: a flat steel colour looks better than a stretched env-map
        r = Math.min(255, (int) (r * shade));
        g = Math.min(255, (int) (g * shade));
        bl = Math.min(255, (int) (bl * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static float shade(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6f) return 0.8f;
        float d = Math.abs((nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / l);
        return 0.6f + 0.5f * d;
    }

    private static float[] mid(float[] a, float[] b) { return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}; }
    private static float[] mid2(float[] a, float[] b) { return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2}; }

    // ---- 3x4 matrix helpers (row-major, 12 floats) shared with the rig hook

    /** Rigid inverse of a 3x4 bone matrix (rotation transposed). */
    public static float[] invert(float[] m) {
        float[] r = new float[12];
        r[0] = m[0]; r[1] = m[4]; r[2] = m[8];
        r[4] = m[1]; r[5] = m[5]; r[6] = m[9];
        r[8] = m[2]; r[9] = m[6]; r[10] = m[10];
        r[3] = -(r[0] * m[3] + r[1] * m[7] + r[2] * m[11]);
        r[7] = -(r[4] * m[3] + r[5] * m[7] + r[6] * m[11]);
        r[11] = -(r[8] * m[3] + r[9] * m[7] + r[10] * m[11]);
        return r;
    }

    /** a * b (apply b first, then a). */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[12];
        for (int row = 0; row < 3; row++) {
            int o = row * 4;
            for (int col = 0; col < 3; col++) {
                r[o + col] = a[o] * b[col] + a[o + 1] * b[4 + col] + a[o + 2] * b[8 + col];
            }
            r[o + 3] = a[o] * b[3] + a[o + 1] * b[7] + a[o + 2] * b[11] + a[o + 3];
        }
        return r;
    }
}
