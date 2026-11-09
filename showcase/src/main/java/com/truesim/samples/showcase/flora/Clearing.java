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
        if (d <= 0) {
            return 0;
        }
        // soil holds on slopes up to some 40 degrees; the cliffs are bare
        double slope = slope(x, z, 2);
        double t = Math.clamp((slope - 33) / 10, 0, 1);
        return d * (1 - t * t * (3 - 2 * t));
    }

    void map(GroundMap value) {
        map = value;
    }

    @Override
    public GroundMap map() {
        return map;
    }
}
