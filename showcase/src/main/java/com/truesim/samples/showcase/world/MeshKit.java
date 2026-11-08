package com.truesim.samples.showcase.world;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the showcase's own meshes from flat pieces: textured rectangles, boxes, prisms, discs, each into a submesh
 * (one material slot each). Texture coordinates are in repeats of a texture's real size ({@code texture} metres
 * across), and kept small: texture coordinates are half floats on the GPU (11 bits of mantissa: at 8 repeats a step is
 * 1/256 of a texture, 16 texels of a 4K one), so a rectangle is cut into cells of at most {@link #CELL} metres, each
 * cell's coordinates starting at its own repeat's fraction (vertices not shared across cells; the image is the same,
 * the texture wrapping). Normals and tangents are flat per piece (the tangent along u, the bitangent along v).
 */
public final class MeshKit {

    /** The largest cell a textured rectangle is cut into, metres. */
    public static final double CELL = 2.0;

    private final List<Part> parts = new ArrayList<>();
    private Part part;

    /** A kit of {@code submeshes} submeshes; {@link #to} picks the one pieces go into. */
    public MeshKit(int submeshes) {
        for (int i = 0; i < submeshes; i++) {
            parts.add(new Part());
        }
        part = parts.getFirst();
    }

    /** Pieces from now on go into submesh {@code submesh}. */
    public MeshKit to(int submesh) {
        part = parts.get(submesh);
        return this;
    }

    /**
     * A rectangle from corner {@code o} along {@code u} (its width, a vector) and {@code v} (its height), facing
     * {@code u x v}; texture repeats every {@code texture} metres, starting at ({@code u0}, {@code v0}) metres.
     */
    public MeshKit rect(double[] o, double[] u, double[] v, double texture, double u0, double v0) {
        double lu = length(u);
        double lv = length(v);
        if (lu <= 1e-9 || lv <= 1e-9) {
            return this;
        }
        double[] n = normalize(cross(u, v));
        double[] t = scale(u, 1 / lu);
        int cu = Math.max(1, (int) Math.ceil(lu / CELL - 1e-9));
        int cv = Math.max(1, (int) Math.ceil(lv / CELL - 1e-9));
        for (int i = 0; i < cu; i++) {
            double a0 = lu * i / cu;
            double a1 = lu * (i + 1) / cu;
            for (int j = 0; j < cv; j++) {
                double b0 = lv * j / cv;
                double b1 = lv * (j + 1) / cv;
                double su = (u0 + a0) / texture;
                double sv = (v0 + b0) / texture;
                double fu = su - Math.floor(su);
                double fv = sv - Math.floor(sv);
                double du = (a1 - a0) / texture;
                double dv = (b1 - b0) / texture;
                int base = part.vertices();
                for (int k = 0; k < 4; k++) {
                    double a = k == 1 || k == 2 ? a1 : a0;
                    double b = k >= 2 ? b1 : b0;
                    double x = o[0] + u[0] * a / lu + v[0] * b / lv;
                    double y = o[1] + u[1] * a / lu + v[1] * b / lv;
                    double z = o[2] + u[2] * a / lu + v[2] * b / lv;
                    double uu = fu + (k == 1 || k == 2 ? du : 0);
                    double vv = fv + (k >= 2 ? dv : 0);
                    // Image rows run down the texture: v grows downwards in it, upwards in the world
                    part.vertex(x, y, z, n, t, uu, 1 - vv);
                }
                part.triangle(base, base + 1, base + 2);
                part.triangle(base, base + 2, base + 3);
            }
        }
        return this;
    }

    /** {@link #rect}, its sides swapped where needed so it faces along {@code facing} (roughly). */
    public MeshKit rectFacing(double[] o, double[] u, double[] v, double texture, double[] facing) {
        return dot(cross(u, v), facing) >= 0 ? rect(o, u, v, texture) : rect(o, v, u, texture);
    }

    /** {@link #rect} with the texture starting at the corner. */
    public MeshKit rect(double[] o, double[] u, double[] v, double texture) {
        return rect(o, u, v, texture, 0, 0);
    }

    /**
     * A box from {@code min} to {@code max} (axis aligned), its six faces outwards ({@code inside}: inwards, a room),
     * the texture in world metres so neighbouring boxes line up.
     */
    public MeshKit box(double[] min, double[] max, double texture, boolean inside) {
        double x0 = min[0];
        double y0 = min[1];
        double z0 = min[2];
        double x1 = max[0];
        double y1 = max[1];
        double z1 = max[2];
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        if (!inside) {
            rect(p(x0, y0, z1), p(dx, 0, 0), p(0, dy, 0), texture, x0, y0); // +z
            rect(p(x1, y0, z0), p(-dx, 0, 0), p(0, dy, 0), texture, -x1, y0); // -z
            rect(p(x1, y0, z1), p(0, 0, -dz), p(0, dy, 0), texture, -z1, y0); // +x
            rect(p(x0, y0, z0), p(0, 0, dz), p(0, dy, 0), texture, z0, y0); // -x
            rect(p(x0, y1, z1), p(dx, 0, 0), p(0, 0, -dz), texture, x0, -z1); // +y
            rect(p(x0, y0, z0), p(dx, 0, 0), p(0, 0, dz), texture, x0, z0); // -y
        } else {
            rect(p(x1, y0, z1), p(-dx, 0, 0), p(0, dy, 0), texture, -x1, y0);
            rect(p(x0, y0, z0), p(dx, 0, 0), p(0, dy, 0), texture, x0, y0);
            rect(p(x1, y0, z0), p(0, 0, dz), p(0, dy, 0), texture, z0, y0);
            rect(p(x0, y0, z1), p(0, 0, -dz), p(0, dy, 0), texture, -z1, y0);
            rect(p(x0, y1, z0), p(dx, 0, 0), p(0, 0, dz), texture, x0, z0);
            rect(p(x0, y0, z1), p(dx, 0, 0), p(0, 0, -dz), texture, x0, -z1);
        }
        return this;
    }

    /** A triangle of three points (counter-clockwise seen from its front), textured by world x and y or z. */
    public MeshKit triangle(double[] a, double[] b, double[] c, double texture) {
        double[] n = normalize(cross(sub(b, a), sub(c, a)));
        // Project onto the plane's own axes: u horizontal in it, v up the slope
        double[] t = Math.abs(n[1]) > 0.99 ? p(1, 0, 0) : normalize(cross(p(0, 1, 0), n));
        double[] bt = cross(n, t);
        double[] origin = a;
        int base = part.vertices();
        for (double[] q : new double[][] {a, b, c}) {
            double[] d = sub(q, origin);
            part.vertex(q[0], q[1], q[2], n, t, dot(d, t) / texture, 1 - dot(d, bt) / texture);
        }
        part.triangle(base, base + 1, base + 2);
        return this;
    }

    /**
     * A quad lying on the ground, corners {@code a b c d} round it (either way), textured by world x and z in repeats
     * of {@code texture} metres starting at the repeat {@code a} lies in, so neighbouring quads continue each other's
     * texture; facing up.
     */
    public MeshKit groundQuad(double[] a, double[] b, double[] c, double[] d, double texture) {
        double[] n = normalize(cross(sub(c, a), sub(b, a)));
        if (n[1] < 0) {
            double[] t = b;
            b = d;
            d = t;
            n = scale(n, -1);
        }
        double ou = Math.floor(a[0] / texture) * texture;
        double ov = Math.floor(a[2] / texture) * texture;
        double[] t = normalize(sub(p(1, 0, 0), scale(n, n[0])));
        int base = part.vertices();
        for (double[] q : new double[][] {a, b, c, d}) {
            part.vertex(q[0], q[1], q[2], n, t, (q[0] - ou) / texture, (q[2] - ov) / texture);
        }
        part.triangle(base, base + 2, base + 1);
        part.triangle(base, base + 3, base + 2);
        return this;
    }

    /** {@link #triangle}, its corners swapped where needed so it faces along {@code facing} (roughly). */
    public MeshKit triangleFacing(double[] a, double[] b, double[] c, double texture, double[] facing) {
        return dot(cross(sub(b, a), sub(c, a)), facing) >= 0 ? triangle(a, b, c, texture) : triangle(a, c, b, texture);
    }

    /**
     * A cylinder (or a cone, {@code r1} at the top) about the vertical through {@code base}, {@code height} high, of
     * {@code sides} sides, capped where {@code caps}.
     */
    public MeshKit cylinder(double[] base, double r0, double r1, double height, int sides, double texture,
        boolean caps) {
        for (int i = 0; i < sides; i++) {
            double a0 = 2 * Math.PI * i / sides;
            double a1 = 2 * Math.PI * (i + 1) / sides;
            double[] p0 = p(base[0] + r0 * Math.cos(a0), base[1], base[2] + r0 * Math.sin(a0));
            double[] p1 = p(base[0] + r0 * Math.cos(a1), base[1], base[2] + r0 * Math.sin(a1));
            double[] q0 = p(base[0] + r1 * Math.cos(a0), base[1] + height, base[2] + r1 * Math.sin(a0));
            double[] q1 = p(base[0] + r1 * Math.cos(a1), base[1] + height, base[2] + r1 * Math.sin(a1));
            // smooth normals around: each vertex's own
            double slope = (r0 - r1) / height;
            double[] n0 = normalize(p(Math.cos(a0), slope, Math.sin(a0)));
            double[] n1 = normalize(p(Math.cos(a1), slope, Math.sin(a1)));
            double[] t0 = p(-Math.sin(a0), 0, Math.cos(a0));
            double[] t1 = p(-Math.sin(a1), 0, Math.cos(a1));
            double arc = 2 * Math.PI * Math.max(r0, r1) / sides / texture;
            double u = (i * arc) % 1.0;
            int b = part.vertices();
            part.vertex(p0[0], p0[1], p0[2], n0, t0, u, 1);
            part.vertex(q0[0], q0[1], q0[2], n0, t0, u, 1 - height / texture);
            part.vertex(q1[0], q1[1], q1[2], n1, t1, u + arc, 1 - height / texture);
            part.vertex(p1[0], p1[1], p1[2], n1, t1, u + arc, 1);
            // seen from outside: p0, p1, q1 counter-clockwise
            part.triangle(b, b + 2, b + 3);
            part.triangle(b, b + 1, b + 2);
            if (caps) {
                double[] top = p(base[0], base[1] + height, base[2]);
                if (r1 > 0) {
                    triangle(top, q1, q0, texture);
                }
                triangle(base, p0, p1, texture);
            }
        }
        return this;
    }

    /** Whether nothing was added. */
    public boolean isEmpty() {
        for (Part p : parts) {
            if (p.indices.size() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The mesh: every submesh (an empty one gets a degenerate triangle far below, so material slots keep their
     * places), as a cluster hierarchy where {@code hierarchy} (detail chosen on the GPU), else one level.
     */
    public MeshData build(boolean hierarchy) {
        int vertices = 0;
        for (Part p : parts) {
            if (p.indices.size() == 0) {
                double[] n = p(0, 1, 0);
                double[] t = p(1, 0, 0);
                p.vertex(0, -1000, 0, n, t, 0, 0);
                p.vertex(0.001, -1000, 0, n, t, 0, 0);
                p.vertex(0, -1000, 0.001, n, t, 0, 0);
                p.triangle(0, 2, 1);
            }
            vertices += p.vertices();
        }
        float[] positions = new float[3 * vertices];
        float[] normals = new float[3 * vertices];
        float[] tangents = new float[4 * vertices];
        float[] uvs = new float[2 * vertices];
        int at = 0;
        int[][] submeshes = new int[parts.size()][];
        for (int s = 0; s < parts.size(); s++) {
            Part p = parts.get(s);
            int n = p.vertices();
            System.arraycopy(p.positions.array, 0, positions, 3 * at, 3 * n);
            System.arraycopy(p.normals.array, 0, normals, 3 * at, 3 * n);
            System.arraycopy(p.tangents.array, 0, tangents, 4 * at, 4 * n);
            System.arraycopy(p.uvs.array, 0, uvs, 2 * at, 2 * n);
            int[] indices = Arrays.copyOf(p.indices.array, p.indices.size());
            for (int i = 0; i < indices.length; i++) {
                indices[i] += at;
            }
            submeshes[s] = indices;
            at += n;
        }
        MeshBuilder builder = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT,
            VertexAttribute.UV0), vertices).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.TANGENT, tangents).attribute(VertexAttribute.UV0, uvs);
        for (int[] indices : submeshes) {
            builder.submesh(indices);
        }
        return hierarchy ? builder.buildHierarchy() : builder.buildFlat();
    }

    // ---------------------------------------------------------------- vectors

    public static double[] p(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    static double[] sub(double[] a, double[] b) {
        return p(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    }

    static double[] add(double[] a, double[] b) {
        return p(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
    }

    static double[] scale(double[] a, double s) {
        return p(a[0] * s, a[1] * s, a[2] * s);
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return p(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }

    static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] normalize(double[] a) {
        double l = length(a);
        return l > 0 ? scale(a, 1 / l) : p(0, 1, 0);
    }

    /** One submesh's vertices and triangles. */
    private static final class Part {
        final Floats positions = new Floats();
        final Floats normals = new Floats();
        final Floats tangents = new Floats();
        final Floats uvs = new Floats();
        final Ints indices = new Ints();

        int vertices() {
            return positions.size() / 3;
        }

        void vertex(double x, double y, double z, double[] n, double[] t, double u, double v) {
            positions.add(x).add(y).add(z);
            normals.add(n[0]).add(n[1]).add(n[2]);
            tangents.add(t[0]).add(t[1]).add(t[2]).add(1);
            uvs.add(u).add(v);
        }

        void triangle(int a, int b, int c) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
        }
    }

    /** A growing float array. */
    private static final class Floats {
        float[] array = new float[256];
        int size;

        Floats add(double v) {
            if (size == array.length) {
                array = Arrays.copyOf(array, size * 2);
            }
            array[size++] = (float) v;
            return this;
        }

        int size() {
            return size;
        }
    }

    /** A growing int array. */
    private static final class Ints {
        int[] array = new int[256];
        int size;

        void add(int v) {
            if (size == array.length) {
                array = Arrays.copyOf(array, size * 2);
            }
            array[size++] = v;
        }

        int size() {
            return size;
        }
    }
}
