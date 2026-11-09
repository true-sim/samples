package com.truesim.samples.showcase.flora;

import com.truesim.effects.vegetation.GroundMap;
import com.truesim.effects.vegetation.VegetationGround;
import com.truesim.samples.showcase.world.Ground;

/**
 * The ground the vegetation stands on: the showcase's {@link Ground}, with what the settlement's plan clears
 * (common-procedural's {@code Clearing}: no grass on what is paved or built, no trees on the roads, the square, the
 * buildings and their gardens) and nothing on ground too steep to hold soil.
 */
final class Clearing implements VegetationGround {

    private final Ground ground;
    private final com.truesim.common.procedural.settlement.Clearing plan;
    private final boolean trees;
    private GroundMap map;
    // the rocks' contact (1 under a rock) over a raster: no grass under them
    private float[] rocks;
    private double rocksX;
    private double rocksZ;
    private double rocksTexel;
    private int rocksSize;

    /** The plan's clearing on {@code ground}: its trees' ({@code trees}) or its grass's. */
    Clearing(Ground ground, com.truesim.common.procedural.settlement.Clearing plan, boolean trees) {
        this.ground = ground;
        this.plan = plan;
        this.trees = trees;
    }

    @Override
    public double height(double x, double z) {
        return ground.height(x, z);
    }

    @Override
    public double density(double x, double z) {
        double d = trees ? plan.trees(x, z) : plan.grass(x, z);
        if (rocks != null) {
            int i = (int) Math.floor((x - rocksX) / rocksTexel);
            int j = (int) Math.floor((z - rocksZ) / rocksTexel);
            if (i >= 0 && j >= 0 && i < rocksSize && j < rocksSize) {
                d *= 1 - rocks[j * rocksSize + i];
            }
        }
        if (d <= 0) {
            return 0;
        }
        // soil holds on slopes up to some 40 degrees; the cliffs are bare
        double slope = slope(x, z, 2);
        double t = Math.clamp((slope - 33) / 10, 0, 1);
        return d * (1 - t * t * (3 - 2 * t));
    }

    /** The rocks' contact raster ({@code size} texels a side of {@code texel} metres from (x0, z0)): no grass under. */
    void rocks(float[] cover, double x0, double z0, double texel, int size) {
        rocks = cover;
        rocksX = x0;
        rocksZ = z0;
        rocksTexel = texel;
        rocksSize = size;
    }

    void map(GroundMap value) {
        map = value;
    }

    @Override
    public GroundMap map() {
        return map;
    }
}
