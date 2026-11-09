package com.truesim.samples.showcase.world;

import static com.truesim.samples.showcase.world.MeshKit.p;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.RenderAssets;
import java.util.List;

/**
 * The village's smaller generated meshes: the square's cobbles and kerbs and the road's gravel, both draped over the
 * {@link Ground} a few centimetres above it; fences of posts and rails; a lamp's glowing bulb; the café's wall mirror.
 * Draped meshes are in world coordinates less their node's position (they follow the ground's heights).
 */
public final class VillageMeshes {

    /**
     * The square about ({@code cx}, {@code cz}) of the world (its node there): cobbles over the ground, kerb stones
     * round its edge. Submeshes: cobbles, kerbs.
     */
    public record Square(double cx, double cz, double halfX, double halfZ) {
    }

    /** The road: a strip {@code width} wide along the layout's centre line. */
    public record Road(double width) {
    }

    /**
     * A fence from world ({@code x0}, {@code z0}) to ({@code x1}, {@code z1}), posts about every {@code spacing}
     * metres, each standing on the ground under it (its foot 0.4 m in), the rails sloping from post to post; in
     * metres from its start, heights the world's (its node at (x0, 0, z0), unturned).
     */
    public record Fence(double x0, double z0, double x1, double z1, double spacing) {
    }

    /** A small glowing shape: a bulb, a flame's glass. */
    public record Bulb(double radius, double height) {
    }

    /** A framed wall mirror {@code width} by {@code height}: submeshes frame, glass. */
    public record Mirror(double width, double height) {
    }

    private static final double[] UP = {0, 1, 0};

    private final Generator<Object, MeshData> generator;

    public VillageMeshes(Ground ground, VillageLayout layout) {
        generator = (shape, seed) -> switch (shape) {
            case Square s -> square(ground, s);
            case Road r -> road(ground, layout.road(), r);
            case Fence f -> fence(ground, f);
            case Bulb b -> new MeshKit(1).cylinder(p(0, 0, 0), b.radius(), b.radius() * 0.6, b.height(), 10, 1, true)
                .build(false);
            case Mirror m -> mirror(m);
            default -> throw new IllegalArgumentException("No mesh " + shape);
        };
    }

    public AssetRef<MeshData> mesh(Object shape) {
        return AssetRef.generated(RenderAssets.MESH, generator, shape, 0);
    }

    private static MeshData square(Ground ground, Square s) {
        MeshKit kit = new MeshKit(2);
        double step = 1.0;
        double lift = 0.06;
        for (double x = -s.halfX(); x < s.halfX() - 1e-6; x += step) {
            for (double z = -s.halfZ(); z < s.halfZ() - 1e-6; z += step) {
                double x1 = Math.min(x + step, s.halfX());
                double z1 = Math.min(z + step, s.halfZ());
                double[] a = p(x, ground.height(s.cx() + x, s.cz() + z) + lift, z);
                double[] b = p(x1, ground.height(s.cx() + x1, s.cz() + z) + lift, z);
                double[] c = p(x1, ground.height(s.cx() + x1, s.cz() + z1) + lift, z1);
                double[] d = p(x, ground.height(s.cx() + x, s.cz() + z1) + lift, z1);
                kit.to(0).groundQuad(a, b, c, d, 2.4);
            }
        }
        // kerb stones along the edges: 0.3 wide, 0.14 above the cobbles
        kit.to(1);
        for (double x = -s.halfX(); x < s.halfX() - 1e-6; x += 1.0) {
            for (double z : new double[] {-s.halfZ(), s.halfZ() - 0.3}) {
                double h = ground.height(s.cx() + x + 0.5, s.cz() + z) + lift;
                kit.box(p(x + 0.01, h - 0.4, z), p(x + 0.99, h + 0.14, z + 0.3), 1, false);
            }
        }
        for (double z = -s.halfZ() + 0.3; z < s.halfZ() - 0.3 - 1e-6; z += 1.0) {
            double z1 = Math.min(z + 0.98, s.halfZ() - 0.3);
            for (double x : new double[] {-s.halfX(), s.halfX() - 0.3}) {
                double h = ground.height(s.cx() + x, s.cz() + z + 0.5) + lift;
                kit.box(p(x, h - 0.4, z + 0.01), p(x + 0.3, h + 0.14, z1), 1, false);
            }
        }
        return kit.build(false);
    }

    private static MeshData road(Ground ground, List<double[]> line, Road r) {
        MeshKit kit = new MeshKit(1);
        double half = r.width() / 2;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i);
            double[] b = line.get(i + 1);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double length = Math.sqrt(dx * dx + dz * dz);
            int steps = (int) Math.ceil(length / 1.0);
            double nx = -dz / length;
            double nz = dx / length;
            for (int k = 0; k < steps; k++) {
                double t0 = (double) k / steps;
                double t1 = (double) (k + 1) / steps;
                double x0 = a[0] + dx * t0;
                double z0 = a[1] + dz * t0;
                double x1 = a[0] + dx * t1;
                double z1 = a[1] + dz * t1;
                // three strips across, so the gravel follows the ground's camber
                for (int s = 0; s < 3; s++) {
                    double w0 = -half + r.width() * s / 3;
                    double w1 = -half + r.width() * (s + 1) / 3;
                    double[] p00 = drape(ground, x0 + nx * w0, z0 + nz * w0);
                    double[] p01 = drape(ground, x0 + nx * w1, z0 + nz * w1);
                    double[] p10 = drape(ground, x1 + nx * w0, z1 + nz * w0);
                    double[] p11 = drape(ground, x1 + nx * w1, z1 + nz * w1);
                    kit.groundQuad(p00, p10, p11, p01, 2);
                }
            }
        }
        return kit.build(true);
    }

    private static double[] drape(Ground ground, double x, double z) {
        return p(x, ground.height(x, z) + 0.05, z);
    }

    private static MeshData fence(Ground ground, Fence f) {
        MeshKit kit = new MeshKit(1);
        double dx = f.x1() - f.x0();
        double dz = f.z1() - f.z0();
        double length = Math.sqrt(dx * dx + dz * dz);
        int posts = Math.max(1, (int) Math.round(length / f.spacing()));
        double[] along = {dx / length, 0, dz / length};
        double[] side = {-along[2], 0, along[0]};
        double[][] tops = new double[posts + 1][];
        for (int i = 0; i <= posts; i++) {
            double t = (double) i / posts;
            double x = dx * t;
            double z = dz * t;
            double h = ground.height(f.x0() + x, f.z0() + z);
            kit.box(p(x - 0.06, h - 0.4, z - 0.06), p(x + 0.06, h + 1.15, z + 0.06), 1, false);
            tops[i] = p(x, h, z);
        }
        // the rails, on the posts' side, each span sloping from one post's height to the next
        for (double y : new double[] {0.45, 0.95}) {
            for (int i = 0; i < posts; i++) {
                double[] a = MeshKit.add(MeshKit.add(tops[i], p(0, y - 0.05, 0)), MeshKit.scale(side, 0.06));
                double[] b = MeshKit.add(MeshKit.add(tops[i + 1], p(0, y - 0.05, 0)), MeshKit.scale(side, 0.06));
                rail(kit, a, MeshKit.add(b, MeshKit.scale(a, -1)), p(0, 0.1, 0), MeshKit.scale(side, 0.05));
            }
        }
        return kit.build(false);
    }

    /** A box spanned by three edge vectors from {@code o}. */
    private static void rail(MeshKit kit, double[] o, double[] a, double[] b, double[] c) {
        if (MeshKit.dot(MeshKit.cross(a, b), c) > 0) {
            double[] t = a;
            a = b;
            b = t;
        }
        double[] oc = MeshKit.add(o, c);
        kit.rect(o, a, b, 1);
        kit.rect(MeshKit.add(oc, a), MeshKit.scale(a, -1), b, 1);
        kit.rect(MeshKit.add(o, a), c, b, 1);
        kit.rect(oc, MeshKit.scale(c, -1), b, 1);
        kit.rect(MeshKit.add(o, b), a, c, 1);
        kit.rect(oc, a, MeshKit.scale(c, -1), 1);
    }

    private static MeshData mirror(Mirror m) {
        MeshKit kit = new MeshKit(2);
        double w = m.width();
        double h = m.height();
        double t = 0.08;
        kit.to(0);
        kit.box(p(-w / 2 - t, -t, 0), p(w / 2 + t, 0, 0.05), 1, false);
        kit.box(p(-w / 2 - t, h, 0), p(w / 2 + t, h + t, 0.05), 1, false);
        kit.box(p(-w / 2 - t, 0, 0), p(-w / 2, h, 0.05), 1, false);
        kit.box(p(w / 2, 0, 0), p(w / 2 + t, h, 0.05), 1, false);
        kit.to(1).rect(p(-w / 2, 0, 0.03), p(w, 0, 0), p(0, h, 0), Math.max(w, h));
        return kit.build(false);
    }
}
