package com.truesim.samples.showcase.world;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.RenderAssets;
import java.util.Arrays;

/**
 * The ground's tiles as generated meshes: a square of {@code size} metres from ({@code x0}, {@code z0}) sampled every
 * {@code step} metres from the {@link Ground}, smooth normals from its slope, in four submeshes by what covers it
 * (meadow, forest floor on the steeper and higher slopes, rock on cliffs, bare rock above the tree line), with skirts
 * hanging down its edges so a coarser neighbour never shows a crack. Each quad has its own four vertices, its texture
 * coordinates starting at its repeat's fraction (texture coordinates are half floats: see {@link MeshKit}); a repeat
 * is the surface's real size near the camera, the quad's size on coarse tiles (their mips are the average anyway).
 */
public final class GroundTiles {

    /** Submeshes: the materials a tile takes, in order. */
    public static final int MEADOW = 0;
    public static final int FOREST = 1;
    public static final int ROCK = 2;
    public static final int HIGH = 3;
    public static final int SUBMESHES = 4;

    /** A tile: its corner, size and sample spacing, metres; its texture repeats (meadow, forest, rock). */
    public record Tile(double x0, double z0, double size, double step) {
    }

    private final Generator<Tile, MeshData> generator;

    public GroundTiles(Ground ground, double meadowRepeat, double forestRepeat, double rockRepeat) {
        generator = (tile, seed) -> build(ground, tile, new double[] {meadowRepeat, forestRepeat, rockRepeat, 8});
    }

    /** The tile's mesh asset. */
    public AssetRef<MeshData> tile(double x0, double z0, double size, double step) {
        return AssetRef.generated(RenderAssets.MESH, generator, new Tile(x0, z0, size, step), 0);
    }

    private static MeshData build(Ground ground, Tile tile, double[] repeats) {
        int n = (int) Math.round(tile.size() / tile.step());
        double step = tile.size() / n;
        double[] heights = new double[(n + 3) * (n + 3)]; // a ring around for normals at the edges
        int w = n + 3;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                heights[j * w + i] = ground.height(tile.x0() + (i - 1) * step, tile.z0() + (j - 1) * step);
            }
        }
        Buffers[] parts = new Buffers[SUBMESHES];
        for (int s = 0; s < SUBMESHES; s++) {
            parts[s] = new Buffers();
        }
        double[] normal = new double[3];
        int[] kinds = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                // classify by the quad's middle: slope and altitude, with a little noise on the borders
                double cx = tile.x0() + (i + 0.5) * step;
                double cz = tile.z0() + (j + 0.5) * step;
                double h = 0.25 * (heights[(j + 1) * w + i + 1] + heights[(j + 1) * w + i + 2]
                    + heights[(j + 2) * w + i + 1] + heights[(j + 2) * w + i + 2]);
                normalAt(heights, w, i + 1, j + 1, step, normal);
                double ny = normal[1];
                normalAt(heights, w, i + 2, j + 2, step, normal);
                ny = 0.5 * (ny + normal[1]);
                double jitter = 0.08 * ValleyGround.noise(cx / 17, cz / 17);
                int kind;
                if (h > 2350 + 150 * ValleyGround.noise(cx / 300, cz / 300) && ny > 0.55) {
                    kind = HIGH;
                } else if (ny < 0.72 + jitter) {
                    kind = ROCK;
                } else if (ny < 0.9 + jitter || h > 260 + 60 * ValleyGround.noise(cx / 140, cz / 140)) {
                    kind = h > 1950 ? ROCK : FOREST;
                } else {
                    kind = MEADOW;
                }
                kinds[j * n + i] = kind;
                double repeat = Math.max(repeats[kind], step);
                Buffers b = parts[kind];
                double u0 = frac(tile.x0() / repeat + i * step / repeat);
                double v0 = frac(tile.z0() / repeat + j * step / repeat);
                double du = step / repeat;
                int base = b.vertices();
                for (int k = 0; k < 4; k++) {
                    int di = k == 1 || k == 2 ? 1 : 0;
                    int dj = k >= 2 ? 1 : 0;
                    int gi = i + di + 1;
                    int gj = j + dj + 1;
                    normalAt(heights, w, gi, gj, step, normal);
                    b.vertex(tile.x0() + (i + di) * step, heights[gj * w + gi], tile.z0() + (j + dj) * step, normal,
                        u0 + di * du, v0 + dj * du);
                }
                // seen from above (+y), x right and z down the screen: 0 (i, j), 1 (i+1, j), 2 (i+1, j+1), 3 (i, j+1)
                // is clockwise, so the triangles go the other way round
                b.triangle(base, base + 2, base + 1);
                b.triangle(base, base + 3, base + 2);
            }
        }
        skirts(parts, kinds, heights, w, n, step, tile, Math.max(2, step * 0.6), repeats);
        int total = 0;
        for (Buffers b : parts) {
            if (b.indices.length == 0 || b.indexCount == 0) {
                // an empty submesh keeps its slot: a degenerate triangle far below
                double[] up = {0, 1, 0};
                int base = b.vertices();
                b.vertex(tile.x0(), -5000, tile.z0(), up, 0, 0);
                b.vertex(tile.x0() + 0.01, -5000, tile.z0(), up, 0, 0);
                b.vertex(tile.x0(), -5000, tile.z0() + 0.01, up, 0, 0);
                b.triangle(base, base + 2, base + 1);
            }
            total += b.vertices();
        }
        float[] positions = new float[3 * total];
        float[] normals = new float[3 * total];
        float[] tangents = new float[4 * total];
        float[] uvs = new float[2 * total];
        int at = 0;
        int[][] submeshes = new int[SUBMESHES][];
        for (int s = 0; s < SUBMESHES; s++) {
            Buffers b = parts[s];
            int count = b.vertices();
            for (int v = 0; v < count; v++) {
                // positions relative to the tile's corner: the node is placed there (floats stay precise far out)
                positions[3 * (at + v)] = (float) (b.positions[3 * v] - tile.x0());
                positions[3 * (at + v) + 1] = (float) b.positions[3 * v + 1];
                positions[3 * (at + v) + 2] = (float) (b.positions[3 * v + 2] - tile.z0());
            }
            System.arraycopy(b.normals, 0, normals, 3 * at, 3 * count);
            System.arraycopy(b.tangents, 0, tangents, 4 * at, 4 * count);
            System.arraycopy(b.uvs, 0, uvs, 2 * at, 2 * count);
            int[] indices = Arrays.copyOf(b.indices, b.indexCount);
            for (int k = 0; k < indices.length; k++) {
                indices[k] += at;
            }
            submeshes[s] = indices;
            at += count;
        }
        MeshBuilder builder = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT,
            VertexAttribute.UV0), total).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.TANGENT, tangents).attribute(VertexAttribute.UV0, uvs);
        for (int[] indices : submeshes) {
            builder.submesh(indices);
        }
        return builder.buildHierarchy();
    }

    /** Vertical strips down the tile's four edges, {@code depth} metres, facing out. */
    private static void skirts(Buffers[] parts, int[] kinds, double[] heights, int w, int n, double step, Tile tile,
        double depth, double[] repeats) {
        for (int edge = 0; edge < 4; edge++) {
            double[] out = switch (edge) {
                case 0 -> new double[] {0, 0, -1};
                case 1 -> new double[] {1, 0, 0};
                case 2 -> new double[] {0, 0, 1};
                default -> new double[] {-1, 0, 0};
            };
            for (int k = 0; k < n; k++) {
                int i0;
                int j0;
                int i1;
                int j1;
                switch (edge) {
                    case 0 -> { i0 = k; j0 = 0; i1 = k + 1; j1 = 0; }
                    case 1 -> { i0 = n; j0 = k; i1 = n; j1 = k + 1; }
                    case 2 -> { i0 = k + 1; j0 = n; i1 = k; j1 = n; }
                    default -> { i0 = 0; j0 = k + 1; i1 = 0; j1 = k; }
                }
                // the skirt takes the material of the quad it hangs from
                int qi = Math.min(Math.min(i0, i1), n - 1);
                int qj = Math.min(Math.min(j0, j1), n - 1);
                int kind = kinds[qj * n + qi];
                Buffers b = parts[kind];
                double repeat = Math.max(repeats[kind], step);
                double xa = tile.x0() + i0 * step;
                double za = tile.z0() + j0 * step;
                double xb = tile.x0() + i1 * step;
                double zb = tile.z0() + j1 * step;
                double ha = heights[(j0 + 1) * w + i0 + 1];
                double hb = heights[(j1 + 1) * w + i1 + 1];
                int base = b.vertices();
                double u = frac(k * step / repeat);
                b.vertex(xa, ha, za, out, u, 0);
                b.vertex(xb, hb, zb, out, u + step / repeat, 0);
                b.vertex(xb, hb - depth, zb, out, u + step / repeat, depth / repeat);
                b.vertex(xa, ha - depth, za, out, u, depth / repeat);
                // both sides: a crack may be seen from either
                b.triangle(base, base + 2, base + 1);
                b.triangle(base, base + 3, base + 2);
                b.triangle(base, base + 1, base + 2);
                b.triangle(base, base + 2, base + 3);
            }
        }
    }

    private static void normalAt(double[] h, int w, int i, int j, double step, double[] out) {
        double dx = (h[j * w + i + 1] - h[j * w + i - 1]) / (2 * step);
        double dz = (h[(j + 1) * w + i] - h[(j - 1) * w + i]) / (2 * step);
        double l = Math.sqrt(dx * dx + 1 + dz * dz);
        out[0] = -dx / l;
        out[1] = 1 / l;
        out[2] = -dz / l;
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** A submesh's vertices and triangles while built. */
    private static final class Buffers {
        double[] positions = new double[3 * 1024];
        float[] normals = new float[3 * 1024];
        float[] tangents = new float[4 * 1024];
        float[] uvs = new float[2 * 1024];
        int[] indices = new int[1536];
        int count;
        int indexCount;

        int vertices() {
            return count;
        }

        void vertex(double x, double y, double z, double[] n, double u, double v) {
            if (count == positions.length / 3) {
                positions = Arrays.copyOf(positions, positions.length * 2);
                normals = Arrays.copyOf(normals, normals.length * 2);
                tangents = Arrays.copyOf(tangents, tangents.length * 2);
                uvs = Arrays.copyOf(uvs, uvs.length * 2);
            }
            positions[3 * count] = x;
            positions[3 * count + 1] = y;
            positions[3 * count + 2] = z;
            normals[3 * count] = (float) n[0];
            normals[3 * count + 1] = (float) n[1];
            normals[3 * count + 2] = (float) n[2];
            // the tangent along +x (u), made perpendicular to the normal; n x t points to -z, where the image's rows
            // go up (v grows with z): w = 1
            double tx = 1 - n[0] * n[0];
            double ty = -n[0] * n[1];
            double tz = -n[0] * n[2];
            double l = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (l < 1e-4) { // a face along x (a skirt): its tangent along z
                tx = -n[2] * n[0];
                ty = -n[2] * n[1];
                tz = 1 - n[2] * n[2];
                l = Math.max(Math.sqrt(tx * tx + ty * ty + tz * tz), 1e-9);
            }
            tangents[4 * count] = (float) (tx / l);
            tangents[4 * count + 1] = (float) (ty / l);
            tangents[4 * count + 2] = (float) (tz / l);
            tangents[4 * count + 3] = 1;
            uvs[2 * count] = (float) u;
            uvs[2 * count + 1] = (float) v;
            count++;
        }

        void triangle(int a, int b, int c) {
            if (indexCount + 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }
    }
}
