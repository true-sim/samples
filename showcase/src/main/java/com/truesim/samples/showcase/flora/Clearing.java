package com.truesim.samples.showcase.flora;

import com.truesim.common.math.Maths;
import com.truesim.effects.vegetation.GroundMap;
import com.truesim.effects.vegetation.VegetationGround;
import com.truesim.samples.showcase.world.Buildings;
import com.truesim.samples.showcase.world.Ground;
import com.truesim.samples.showcase.world.VillageLayout;
import java.util.List;

/**
 * The ground the vegetation stands on: the showcase's {@link Ground}, with nothing growing on the square, under and
 * round the buildings, on the road and on ground too steep to hold soil. Its density is 0 there, 1 on open ground,
 * fading over a metre or two at the edges, so the grass ends at a building's plinth or a road's verge as it does.
 */
final class Clearing implements VegetationGround {

    /** A footprint: a centre, its axes' turn and half extents, metres. */
    private record Footprint(double x, double z, double cos, double sin, double hw, double hd) {
    }

    private final Ground ground;
    private final List<Footprint> footprints;
    private final List<double[]> road;
    private final double squareX;
    private final double squareZ;
    private final double margin;
    private GroundMap map;
    private double core;

    /**
     * The village of {@code layout} cleared on {@code ground}; {@code margin} metres kept clear round buildings (the
     * grass: a little; trees: a tree's crown).
     */
    Clearing(Ground ground, VillageLayout layout, double margin) {
        this.ground = ground;
        this.margin = margin;
        footprints = layout.buildings().stream().map(b -> new Footprint(b.x(), b.z(), Maths.cos(b.yaw()),
            Maths.sin(b.yaw()), b.spec().width() / 2 + extra(b.spec()), b.spec().depth() / 2 + extra(b.spec())))
            .toList();
        road = layout.road();
        squareX = layout.originX();
        squareZ = layout.originZ();
    }

    /** Keeps the village's core open too: {@code metres} either side of the square, north of it, 2.2 times south. */
    Clearing core(double metres) {
        core = metres;
        return this;
    }

    /** What a building's plan leaves out: the church's tower beside its nave. */
    private static double extra(Buildings.Spec spec) {
        return spec.kind() == Buildings.Kind.CHURCH ? 4 : 0.6;
    }

    @Override
    public double height(double x, double z) {
        return ground.height(x, z);
    }

    @Override
    public double density(double x, double z) {
        double d = 1;
        if (core > 0) {
            // the village's own ground: its meadows, gardens and pastures, kept open
            double cx = Math.abs(x - squareX) - core;
            double cz = Math.max(squareZ - core - (z), z - (squareZ + core * 2.2));
            d = Math.min(d, edge(Math.max(cx, cz) / 8));
        }
        // the square, cobbled
        double sx = Math.abs(x - squareX) - VillageLayout.SQUARE_X;
        double sz = Math.abs(z - squareZ) - VillageLayout.SQUARE_Z;
        d = Math.min(d, edge(Math.max(sx, sz) - margin * 0.5));
        for (Footprint f : footprints) {
            double dx = x - f.x();
            double dz = z - f.z();
            // into the building's frame (its yaw turns +z, its front, about up)
            double lx = dx * f.cos() - dz * f.sin();
            double lz = dx * f.sin() + dz * f.cos();
            double out = Math.max(Math.abs(lx) - f.hw(), Math.abs(lz) - f.hd());
            d = Math.min(d, edge(out - margin));
            if (d <= 0) {
                return 0;
            }
        }
        // the road: 3.5 m of gravel each side of its centre line
        for (int i = 0; i + 1 < road.size(); i++) {
            double[] a = road.get(i);
            double[] b = road.get(i + 1);
            double ax = a[0];
            double az = a[1];
            double bx = b[0];
            double bz = b[1];
            double ex = bx - ax;
            double ez = bz - az;
            double t = Math.clamp(((x - ax) * ex + (z - az) * ez) / (ex * ex + ez * ez), 0, 1);
            double px = ax + t * ex - x;
            double pz = az + t * ez - z;
            d = Math.min(d, edge(Math.sqrt(px * px + pz * pz) - 3.5 - margin * 0.5));
        }
        if (d <= 0) {
            return 0;
        }
        // soil holds on slopes up to some 40 degrees; the cliffs are bare
        double slope = slope(x, z, 2);
        return d * (1 - smooth((slope - 33) / 10));
    }

    /** 0 inside, 1 a metre and a half outside. */
    private static double edge(double outside) {
        return smooth(outside / 1.5);
    }

    private static double smooth(double t) {
        double c = Math.clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    void map(GroundMap value) {
        map = value;
    }

    @Override
    public GroundMap map() {
        return map;
    }
}
