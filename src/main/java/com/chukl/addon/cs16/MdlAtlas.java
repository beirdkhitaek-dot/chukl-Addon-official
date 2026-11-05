package com.chukl.addon.cs16;

import java.util.ArrayList;
import java.util.List;

/**
 * A model prepared for textured drawing: all of its textures stacked into one image, and its triangles as flat arrays
 * with their texture coordinates already pointing into that image. Contains no Minecraft classes.
 */
public final class MdlAtlas {
    public int width, height;
    /** The combined texture, ARGB. */
    public int[] argb;

    /** All vertices of all body parts: model-space position (x,y,z per vertex) and the bone that moves each one. */
    public float[] vpos;
    public int[] vbone;

    /** Vertex normals of all parts (x,y,z each) and the bone that rotates each one. */
    public float[] npos;
    public int[] nbone;

    public int triCount;
    /** 3 normal indices per triangle (one per corner). */
    public int[] cnorm;
    /** 3 vertex indices per triangle. */
    public int[] corner;
    /** 3 (u,v) pairs per triangle, normalised to the combined texture. */
    public float[] uv;

    public static MdlAtlas build(MdlModel m) {
        MdlAtlas a = new MdlAtlas();

        // stack the textures vertically
        int w = 1, h = 0;
        int[] yOff = new int[m.textures.length];
        for (int i = 0; i < m.textures.length; i++) {
            yOff[i] = h;
            w = Math.max(w, m.textures[i].width);
            h += m.textures[i].height;
        }
        h = Math.max(1, h);
        a.width = w;
        a.height = h;
        a.argb = new int[w * h];
        for (int i = 0; i < m.textures.length; i++) {
            MdlModel.Tex t = m.textures[i];
            for (int y = 0; y < t.height; y++) {
                System.arraycopy(t.argb, y * t.width, a.argb, (yOff[i] + y) * w, t.width);
            }
        }

        // vertices of all parts, in one array
        int nv = 0;
        for (MdlModel.Part p : m.parts) nv += p.vertBone.length;
        a.vpos = new float[nv * 3];
        a.vbone = new int[nv];
        int[] partBase = new int[m.parts.size()];
        int base = 0;
        for (int pi = 0; pi < m.parts.size(); pi++) {
            MdlModel.Part p = m.parts.get(pi);
            partBase[pi] = base;
            System.arraycopy(p.verts, 0, a.vpos, base * 3, p.vertBone.length * 3);
            System.arraycopy(p.vertBone, 0, a.vbone, base, p.vertBone.length);
            base += p.vertBone.length;
        }

        int nn = 0;
        for (MdlModel.Part p : m.parts) nn += p.normBone.length;
        a.npos = new float[nn * 3];
        a.nbone = new int[nn];
        int[] normBase = new int[m.parts.size()];
        int nbase = 0;
        for (int pi = 0; pi < m.parts.size(); pi++) {
            MdlModel.Part p = m.parts.get(pi);
            normBase[pi] = nbase;
            System.arraycopy(p.norms, 0, a.npos, nbase * 3, p.normBone.length * 3);
            System.arraycopy(p.normBone, 0, a.nbone, nbase, p.normBone.length);
            nbase += p.normBone.length;
        }

        List<int[]> normIdx = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        for (int pi = 0; pi < m.parts.size(); pi++) {
            for (MdlModel.Mesh mesh : m.parts.get(pi).meshes) {
                MdlModel.Tex tex = m.textures[mesh.tex];
                if ((tex.flags & MdlModel.TEX_ADDITIVE) != 0) continue; // glow layers would turn into solid blocks
                boolean chrome = (tex.flags & MdlModel.TEX_CHROME) != 0;
                int n = mesh.triCount();
                for (int t = 0; t < n; t++) {
                    int[] c = new int[3];
                    int[] nc = new int[3];
                    float[] uv = new float[6];
                    for (int k = 0; k < 3; k++) {
                        int e = t * 3 + k;
                        c[k] = partBase[pi] + mesh.vert[e];
                        nc[k] = normBase[pi] + mesh.norm[e];
                        float s, tt;
                        if (chrome) { s = tex.width * 0.5f; tt = tex.height * 0.5f; } // env-mapped metal: one steady colour
                        else {
                            s = Math.max(0.5f, Math.min(mesh.s[e], tex.width - 0.5f));
                            tt = Math.max(0.5f, Math.min(mesh.t[e], tex.height - 0.5f));
                        }
                        uv[k * 2] = s / w;
                        uv[k * 2 + 1] = (yOff[mesh.tex] + tt) / h;
                    }
                    tris.add(c);
                    normIdx.add(nc);
                    uvs.add(uv);
                }
            }
        }
        a.triCount = tris.size();
        a.corner = new int[a.triCount * 3];
        a.uv = new float[a.triCount * 6];
        a.cnorm = new int[a.triCount * 3];
        for (int i = 0; i < a.triCount; i++) {
            System.arraycopy(tris.get(i), 0, a.corner, i * 3, 3);
            System.arraycopy(uvs.get(i), 0, a.uv, i * 6, 6);
            System.arraycopy(normIdx.get(i), 0, a.cnorm, i * 3, 3);
        }
        return a;
    }
}
