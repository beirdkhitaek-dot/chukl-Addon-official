package com.chukl.addon.cs16;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reader for GoldSrc / Half-Life "studio" models (.mdl, version 10, the format used by Counter-Strike 1.6 view models).
 * Pure Java, no Minecraft classes: it reads the mesh, the embedded textures and the skeletal animations.
 * Only models with embedded animations (one sequence group) are supported, which is how view models are shipped.
 */
public final class MdlModel {
    public static final int TEX_FLAT = 0x01, TEX_CHROME = 0x02, TEX_FULLBRIGHT = 0x04, TEX_ADDITIVE = 0x20, TEX_MASKED = 0x40;

    public static final class Bone {
        public String name;
        public int parent;
        public final float[] pos = new float[3], rot = new float[3], posScale = new float[3], rotScale = new float[3];
    }

    public static final class Tex {
        public String name;
        public int width, height, flags;
        public int[] argb;
    }

    public static final class Seq {
        public String label;
        public float fps;
        public int flags;
        public int numFrames;
        public int animIndex;
        public int numBlends;
        public int group;

        public boolean looping() { return (flags & 1) != 0; }
        public boolean usable() { return group == 0 && numFrames > 0 && animIndex > 0; }
    }

    /** One drawable mesh: an expanded triangle list (3 entries per triangle) that uses one texture. */
    public static final class Mesh {
        public int tex;
        public int[] vert, norm;
        public float[] s, t;   // texel coordinates
        public int triCount() { return vert.length / 3; }
    }

    /** One sub-model (the default one of a body part): vertices, normals and their bones, plus its meshes. */
    public static final class Part {
        public float[] verts;      // x,y,z per vertex
        public int[] vertBone;
        public float[] norms;      // x,y,z per normal
        public int[] normBone;
        public final List<Mesh> meshes = new ArrayList<>();
    }

    public String name = "";
    public Bone[] bones = new Bone[0];
    public Seq[] seqs = new Seq[0];
    public Tex[] textures = new Tex[0];
    public final List<Part> parts = new ArrayList<>();

    private ByteBuffer b;

    private MdlModel() {}

    public static MdlModel load(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        MdlModel m = new MdlModel();
        try {
            m.parse(data);
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
            throw new IOException("Broken or unsupported .mdl file (" + e.getClass().getSimpleName() + ")");
        }
        return m;
    }

    private int i32(int p) { return b.getInt(p); }
    private int u16(int p) { return b.getShort(p) & 0xFFFF; }
    private int s16(int p) { return b.getShort(p); }
    private int u8(int p) { return b.get(p) & 0xFF; }
    private float f32(int p) { return b.getFloat(p); }

    private String str(int p, int max) {
        int n = 0;
        while (n < max && b.get(p + n) != 0) n++;
        byte[] raw = new byte[n];
        for (int i = 0; i < n; i++) raw[i] = b.get(p + i);
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private void parse(byte[] data) throws IOException {
        if (data.length < 244) throw new IOException("File too small to be a .mdl");
        b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String id = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
        if (id.equals("IDSQ")) throw new IOException("This is a sequence file (IDSQ), not a model");
        if (!id.equals("IDST")) throw new IOException("Not a GoldSrc model (missing IDST header)");
        int version = i32(4);
        if (version != 10) throw new IOException("Unsupported .mdl version " + version + " (need 10)");
        name = str(8, 64);

        int o = 140;
        int numBones = i32(o), boneIndex = i32(o + 4);
        int numSeq = i32(o + 24), seqIndex = i32(o + 28);
        int numTex = i32(o + 40), texIndex = i32(o + 44);
        int numSkinRef = i32(o + 52), skinIndex = i32(o + 60);
        int numBodyParts = i32(o + 64), bodyPartIndex = i32(o + 68);
        if (numBones <= 0 || numBones > 512) throw new IOException("Bad bone count " + numBones);

        bones = new Bone[numBones];
        for (int i = 0; i < numBones; i++) {
            int p = boneIndex + i * 112;
            Bone bn = new Bone();
            bn.name = str(p, 32);
            bn.parent = i32(p + 32);
            for (int k = 0; k < 3; k++) {
                bn.pos[k] = f32(p + 64 + k * 4);
                bn.rot[k] = f32(p + 76 + k * 4);
                bn.posScale[k] = f32(p + 88 + k * 4);
                bn.rotScale[k] = f32(p + 100 + k * 4);
            }
            bones[i] = bn;
        }

        seqs = new Seq[Math.max(0, numSeq)];
        for (int i = 0; i < seqs.length; i++) {
            int p = seqIndex + i * 176;
            Seq s = new Seq();
            s.label = str(p, 32).trim();
            s.fps = f32(p + 32);
            s.flags = i32(p + 36);
            s.numFrames = i32(p + 56);
            s.numBlends = i32(p + 120);
            s.animIndex = i32(p + 124);
            s.group = i32(p + 156);
            seqs[i] = s;
        }

        textures = new Tex[Math.max(0, numTex)];
        for (int i = 0; i < textures.length; i++) {
            int p = texIndex + i * 80;
            Tex t = new Tex();
            t.name = str(p, 64);
            t.flags = i32(p + 64);
            t.width = i32(p + 68);
            t.height = i32(p + 72);
            int idx = i32(p + 76);
            if (t.width <= 0 || t.height <= 0 || t.width > 4096 || t.height > 4096) throw new IOException("Bad texture size");
            int pal = idx + t.width * t.height;
            t.argb = new int[t.width * t.height];
            for (int k = 0; k < t.argb.length; k++) {
                int pi = u8(idx + k);
                if ((t.flags & TEX_MASKED) != 0 && pi == 255) { t.argb[k] = 0; continue; }
                int r = u8(pal + pi * 3), g = u8(pal + pi * 3 + 1), bl = u8(pal + pi * 3 + 2);
                t.argb[k] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            textures[i] = t;
        }

        int[] skin = new int[Math.max(1, numSkinRef)];
        for (int i = 0; i < numSkinRef; i++) skin[i] = s16(skinIndex + i * 2);

        for (int bp = 0; bp < numBodyParts; bp++) {
            int pp = bodyPartIndex + bp * 76;
            int numModels = i32(pp + 64);
            int modelIndex = i32(pp + 72);
            if (numModels <= 0) continue;
            int mp = modelIndex; // default body: first sub-model
            int numMesh = i32(mp + 72), meshIndex = i32(mp + 76);
            int numVerts = i32(mp + 80), vertInfo = i32(mp + 84), vertIdx = i32(mp + 88);
            int numNorms = i32(mp + 92), normInfo = i32(mp + 96), normIdx = i32(mp + 100);
            if (numVerts < 0 || numNorms < 0 || numMesh < 0) continue;

            Part part = new Part();
            part.verts = new float[numVerts * 3];
            part.vertBone = new int[numVerts];
            for (int i = 0; i < numVerts; i++) {
                part.verts[i * 3] = f32(vertIdx + i * 12);
                part.verts[i * 3 + 1] = f32(vertIdx + i * 12 + 4);
                part.verts[i * 3 + 2] = f32(vertIdx + i * 12 + 8);
                part.vertBone[i] = clampBone(u8(vertInfo + i));
            }
            part.norms = new float[numNorms * 3];
            part.normBone = new int[numNorms];
            for (int i = 0; i < numNorms; i++) {
                part.norms[i * 3] = f32(normIdx + i * 12);
                part.norms[i * 3 + 1] = f32(normIdx + i * 12 + 4);
                part.norms[i * 3 + 2] = f32(normIdx + i * 12 + 8);
                part.normBone[i] = clampBone(u8(normInfo + i));
            }

            for (int mi = 0; mi < numMesh; mi++) {
                int q = meshIndex + mi * 20;
                int triIndex = i32(q + 4);
                int skinRef = i32(q + 8);
                int texId = (skinRef >= 0 && skinRef < skin.length) ? skin[skinRef] : 0;
                if (texId < 0 || texId >= textures.length) continue;
                Mesh mesh = readMesh(triIndex, texId, numVerts, numNorms);
                if (mesh != null && mesh.triCount() > 0) part.meshes.add(mesh);
            }
            if (!part.meshes.isEmpty()) parts.add(part);
        }
        if (parts.isEmpty()) throw new IOException("Model has no drawable meshes");
    }

    private int clampBone(int v) { return v >= bones.length ? 0 : v; }

    private Mesh readMesh(int triIndex, int texId, int numVerts, int numNorms) {
        List<int[]> tris = new ArrayList<>();
        List<float[]> st = new ArrayList<>();
        int p = triIndex;
        int guard = 0;
        while (guard++ < 200000) {
            int n = s16(p);
            p += 2;
            if (n == 0) break;
            boolean fan = n < 0;
            if (fan) n = -n;
            int[][] v = new int[n][4];
            for (int i = 0; i < n; i++) {
                v[i][0] = u16(p);
                v[i][1] = u16(p + 2);
                v[i][2] = s16(p + 4);
                v[i][3] = s16(p + 6);
                p += 8;
            }
            for (int i = 0; i + 2 < n; i++) {
                int[] a, c, d;
                if (fan) { a = v[0]; c = v[i + 1]; d = v[i + 2]; }
                else if ((i & 1) == 0) { a = v[i]; c = v[i + 1]; d = v[i + 2]; }
                else { a = v[i + 1]; c = v[i]; d = v[i + 2]; }
                for (int[] e : new int[][]{a, c, d}) {
                    if (e[0] >= numVerts || e[1] >= numNorms) return null;
                }
                tris.add(new int[]{a[0], a[1], c[0], c[1], d[0], d[1]});
                st.add(new float[]{a[2], a[3], c[2], c[3], d[2], d[3]});
            }
        }
        Mesh m = new Mesh();
        m.tex = texId;
        int n = tris.size() * 3;
        m.vert = new int[n];
        m.norm = new int[n];
        m.s = new float[n];
        m.t = new float[n];
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            float[] c = st.get(i);
            for (int k = 0; k < 3; k++) {
                m.vert[i * 3 + k] = t[k * 2];
                m.norm[i * 3 + k] = t[k * 2 + 1];
                m.s[i * 3 + k] = c[k * 2];
                m.t[i * 3 + k] = c[k * 2 + 1];
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- animation

    /** Value of one animation channel (0-2 position, 3-5 rotation) of a bone at a frame, in model units / radians. */
    private float channel(Seq s, int bone, int c, int frame) {
        Bone bn = bones[bone];
        int base = s.animIndex + bone * 12;
        int off = u16(base + c * 2);
        float def = c < 3 ? bn.pos[c] : bn.rot[c - 3];
        float sc = c < 3 ? bn.posScale[c] : bn.rotScale[c - 3];
        if (off == 0) return def;
        int p = base + off;
        int k = frame;
        int valid = u8(p), total = u8(p + 1);
        int guard = 0;
        while (total <= k) {
            if (total == 0 || guard++ > 4096) return def;
            k -= total;
            p += (valid + 1) * 2;
            valid = u8(p);
            total = u8(p + 1);
        }
        int v = valid > k ? s16(p + (k + 1) * 2) : s16(p + valid * 2);
        return def + v * sc;
    }

    /** Index of the first usable sequence whose label satisfies the test, or -1. */
    public int findSeq(java.util.function.Predicate<String> test) {
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i].usable() && test.test(seqs[i].label.toLowerCase(Locale.ROOT))) return i;
        }
        return -1;
    }

    /**
     * Bone matrices (3x4, row major: 3 rows of [rotation x3, translation]) of a sequence at a (fractional) frame.
     * Frames beyond the end are clamped, or wrapped when {@code wrap} is true.
     */
    public float[][] pose(int seq, float frame, boolean wrap) {
        Seq s = (seq >= 0 && seq < seqs.length) ? seqs[seq] : null;
        int n = bones.length;
        float[][] local = new float[n][12];
        float[][] out = new float[n][12];
        int nf = s == null ? 0 : s.numFrames;
        int f0 = 0, f1 = 0;
        float t = 0;
        if (s != null && s.usable()) {
            if (wrap && nf > 0) frame = ((frame % nf) + nf) % nf;
            frame = Math.max(0, Math.min(frame, nf - 1));
            f0 = (int) Math.floor(frame);
            t = frame - f0;
            f1 = f0 + 1;
            if (f1 >= nf) f1 = wrap ? 0 : nf - 1;
        }
        boolean animated = s != null && s.usable();
        for (int i = 0; i < n; i++) {
            float px, py, pz;
            float[] q;
            Bone bn = bones[i];
            if (animated) {
                px = lerp(channel(s, i, 0, f0), channel(s, i, 0, f1), t);
                py = lerp(channel(s, i, 1, f0), channel(s, i, 1, f1), t);
                pz = lerp(channel(s, i, 2, f0), channel(s, i, 2, f1), t);
                float[] qa = quat(channel(s, i, 3, f0), channel(s, i, 4, f0), channel(s, i, 5, f0));
                float[] qb = quat(channel(s, i, 3, f1), channel(s, i, 4, f1), channel(s, i, 5, f1));
                q = slerp(qa, qb, t);
            } else {
                px = bn.pos[0]; py = bn.pos[1]; pz = bn.pos[2];
                q = quat(bn.rot[0], bn.rot[1], bn.rot[2]);
            }
            quatToMatrix(q, px, py, pz, local[i]);
        }
        for (int i = 0; i < n; i++) {
            int par = bones[i].parent;
            if (par < 0 || par >= i) {
                // root bone (bones are stored parent-first, so a valid parent always has a lower index)
                System.arraycopy(local[i], 0, out[i], 0, 12);
            } else {
                concat(out[par], local[i], out[i]);
            }
        }
        return out;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Half-Life AngleQuaternion: euler angles (radians, x=roll, y=pitch, z=yaw) to a quaternion {x,y,z,w}. */
    private static float[] quat(float ax, float ay, float az) {
        float sy = (float) Math.sin(az * 0.5), cy = (float) Math.cos(az * 0.5);
        float sp = (float) Math.sin(ay * 0.5), cp = (float) Math.cos(ay * 0.5);
        float sr = (float) Math.sin(ax * 0.5), cr = (float) Math.cos(ax * 0.5);
        return new float[]{
            sr * cp * cy - cr * sp * sy,
            cr * sp * cy + sr * cp * sy,
            cr * cp * sy - sr * sp * cy,
            cr * cp * cy + sr * sp * sy
        };
    }

    private static float[] slerp(float[] p, float[] q, float t) {
        float a = 0, bsum = 0;
        for (int i = 0; i < 4; i++) {
            a += (p[i] - q[i]) * (p[i] - q[i]);
            bsum += (p[i] + q[i]) * (p[i] + q[i]);
        }
        if (a > bsum) { q = new float[]{-q[0], -q[1], -q[2], -q[3]}; }
        float cosom = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3];
        float sclp, sclq;
        if (1.0f + cosom > 0.000001f) {
            if (1.0f - cosom > 0.000001f) {
                float omega = (float) Math.acos(cosom);
                float sinom = (float) Math.sin(omega);
                sclp = (float) Math.sin((1.0f - t) * omega) / sinom;
                sclq = (float) Math.sin(t * omega) / sinom;
            } else {
                sclp = 1.0f - t;
                sclq = t;
            }
            return new float[]{sclp * p[0] + sclq * q[0], sclp * p[1] + sclq * q[1], sclp * p[2] + sclq * q[2], sclp * p[3] + sclq * q[3]};
        }
        float[] r = new float[4];
        r[0] = -p[1]; r[1] = p[0]; r[2] = -p[3]; r[3] = p[2];
        sclp = (float) Math.sin((1.0f - t) * 0.5f * Math.PI);
        sclq = (float) Math.sin(t * 0.5f * Math.PI);
        for (int i = 0; i < 3; i++) r[i] = sclp * p[i] + sclq * r[i];
        r[3] = p[3];
        return r;
    }

    private static void quatToMatrix(float[] q, float px, float py, float pz, float[] m) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        m[0] = 1.0f - 2.0f * y * y - 2.0f * z * z;
        m[4] = 2.0f * x * y + 2.0f * w * z;
        m[8] = 2.0f * x * z - 2.0f * w * y;
        m[1] = 2.0f * x * y - 2.0f * w * z;
        m[5] = 1.0f - 2.0f * x * x - 2.0f * z * z;
        m[9] = 2.0f * y * z + 2.0f * w * x;
        m[2] = 2.0f * x * z + 2.0f * w * y;
        m[6] = 2.0f * y * z - 2.0f * w * x;
        m[10] = 1.0f - 2.0f * x * x - 2.0f * y * y;
        m[3] = px; m[7] = py; m[11] = pz;
    }

    /** out = a * b for 3x4 affine matrices. */
    private static void concat(float[] a, float[] b, float[] out) {
        for (int r = 0; r < 3; r++) {
            int ro = r * 4;
            out[ro] = a[ro] * b[0] + a[ro + 1] * b[4] + a[ro + 2] * b[8];
            out[ro + 1] = a[ro] * b[1] + a[ro + 1] * b[5] + a[ro + 2] * b[9];
            out[ro + 2] = a[ro] * b[2] + a[ro + 1] * b[6] + a[ro + 2] * b[10];
            out[ro + 3] = a[ro] * b[3] + a[ro + 1] * b[7] + a[ro + 2] * b[11] + a[ro + 3];
        }
    }
}
