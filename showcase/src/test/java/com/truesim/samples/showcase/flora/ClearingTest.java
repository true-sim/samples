package com.truesim.samples.showcase.flora;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.samples.showcase.world.ValleyGround;
import com.truesim.samples.showcase.world.VillageLayout;
import org.junit.jupiter.api.Test;

/** Where the vegetation may grow: not on the square, in the buildings or on the road; on the open meadow. */
class ClearingTest {

    private final VillageLayout layout = new VillageLayout();
    private final Clearing clearing = new Clearing(new ValleyGround(), layout, 0.4);

    @Test
    void nothingGrowsOnTheSquareTheRoadOrInTheBuildings() {
        assertThat(clearing.density(0, 0)).isZero(); // the square's middle
        assertThat(clearing.density(0, 120)).isZero(); // the road through the meadow
        VillageLayout.Building cafe = layout.building("cafe");
        assertThat(clearing.density(cafe.x(), cafe.z())).isZero();
        assertThat(layout.inside(cafe.x(), cafe.z())).isEqualTo(cafe);
        assertThat(layout.inside(0, 0)).isNull();
    }

    @Test
    void theMeadowGrows() {
        assertThat(clearing.density(40, 110)).isGreaterThan(0.9); // beside the street, between the fences
    }
}
