package com.truesim.samples.showcase.world;

import static com.truesim.samples.showcase.world.MeshKit.p;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.RenderAssets;

/**
 * The village's small generated meshes the plan's generators do not make: a lamp's glowing bulb, a lit window's
 * curtain, a wall mirror. (The roads, the square, the buildings and the fences are common-procedural's.)
 */
public final class VillageMeshes {

    /** A lit window's curtain: a rectangle {@code width} by {@code height} about its node, facing +z. */
    public record Pane(double width, double height) {
    }

    /** A small glowing shape: a bulb, a flame's glass. */
    public record Bulb(double radius, double height) {
    }

    /** A framed wall mirror {@code width} by {@code height}: submeshes frame, glass. */
    public record Mirror(double width, double height) {
    }

    private static final Generator<Object, MeshData> GENERATOR = (shape, seed) -> switch (shape) {
        case Bulb b -> new MeshKit(1).cylinder(p(0, 0, 0), b.radius(), b.radius() * 0.6, b.height(), 10, 1, true)
            .build(false);
        case Pane w -> new MeshKit(1).rect(p(-w.width() / 2, -w.height() / 2, 0), p(w.width(), 0, 0),
            p(0, w.height(), 0), 1).build(false);
        case Mirror m -> mirror(m);
        default -> throw new IllegalArgumentException("No mesh " + shape);
    };

    public AssetRef<MeshData> mesh(Object shape) {
        return AssetRef.generated(RenderAssets.MESH, GENERATOR, shape, 0);
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
