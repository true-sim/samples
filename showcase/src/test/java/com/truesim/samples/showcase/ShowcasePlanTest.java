package com.truesim.samples.showcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.samples.showcase.assets.PolyHavenManifest;
import com.truesim.samples.showcase.tour.CameraSpline;
import com.truesim.samples.showcase.tour.TourScript;
import com.truesim.samples.showcase.world.Buildings;
import com.truesim.samples.showcase.world.ValleyGround;
import com.truesim.samples.showcase.world.VillageLayout;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.scene.assets.mesh.MeshData;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The showcase's plan without a device: the manifest, the village's layout, its meshes, the tour's script. */
class ShowcasePlanTest {

    @Test
    void theManifestPinsEveryFileOnceWithItsHash() throws Exception {
        PolyHavenManifest manifest = PolyHavenManifest.read(Path.of("polyhaven.tsv"));
        assertThat(manifest.entries()).isNotEmpty();
        Set<String> files = new HashSet<>();
        for (PolyHavenManifest.Entry e : manifest.entries()) {
            assertThat(e.sha256()).matches("[0-9a-f]{64}");
            assertThat(e.url()).startsWith("https://dl.polyhaven.org/");
            assertThat(files.add(e.asset() + "/" + e.file())).as("pinned once: " + e.file()).isTrue();
        }
        VillageLayout layout = new VillageLayout();
        for (VillageLayout.Prop p : layout.props()) {
            assertThat(manifest.assets()).as("the model " + p.model() + " is pinned").contains(p.model());
        }
    }

    @Test
    void buildingsStandApartAndOffTheSquare() {
        VillageLayout layout = new VillageLayout();
        var buildings = layout.buildings();
        for (int i = 0; i < buildings.size(); i++) {
            VillageLayout.Building a = buildings.get(i);
            double ra = Math.hypot(a.spec().width(), a.spec().depth()) / 2;
            for (int j = i + 1; j < buildings.size(); j++) {
                VillageLayout.Building b = buildings.get(j);
                double rb = Math.hypot(b.spec().width(), b.spec().depth()) / 2;
                assertThat(Math.hypot(a.x() - b.x(), a.z() - b.z())).as(a.name() + " and " + b.name())
                    .isGreaterThan(0.8 * (ra + rb));
            }
            // no building's middle on the square
            assertThat(Math.abs(a.x()) < VillageLayout.SQUARE_X && Math.abs(a.z()) < VillageLayout.SQUARE_Z)
                .as(a.name()).isFalse();
        }
    }

    @Test
    void theSquareLiesNearlyLevelAndTheMountainsRiseFarOut() {
        ValleyGround ground = new ValleyGround();
        double h0 = ground.height(0, 0);
        for (double x = -VillageLayout.SQUARE_X; x <= VillageLayout.SQUARE_X; x += 2) {
            for (double z = -VillageLayout.SQUARE_Z; z <= VillageLayout.SQUARE_Z; z += 2) {
                assertThat(ground.height(x, z) - h0).isBetween(-0.5, 0.5);
            }
        }
        assertThat(ground.height(5000, 0)).isGreaterThan(h0 + 1500);
        assertThat(ground.height(-5000, 3000)).isGreaterThan(h0 + 1500);
    }

    @Test
    void aHouseIsMadeOfEveryMaterialSlot() {
        try (MeshData mesh = Buildings.make(new Buildings.Spec(Buildings.Kind.CHALET, 9, 11, 3, 2.8, 24, 1))) {
            Set<Integer> submeshes = new HashSet<>();
            for (int m = 0; m < mesh.meshletCount(); m++) {
                submeshes.add(mesh.meshletSubmesh(m));
            }
            assertThat(submeshes).hasSize(Buildings.SUBMESHES);
        }
    }

    @Test
    void theEffectsAreValid() {
        // made as the class loads: an effect asking more than its capacity, or naming an unknown module, throws
        assertThat(java.util.List.of(com.truesim.samples.showcase.fx.VillageEffects.HEARTH,
            com.truesim.samples.showcase.fx.VillageEffects.CHIMNEY, com.truesim.samples.showcase.fx.VillageEffects.SNOW,
            com.truesim.samples.showcase.fx.VillageEffects.RAIN, com.truesim.samples.showcase.fx.VillageEffects.LAUNCH))
            .doesNotContainNull();
    }

    @Test
    void cameraSplinesPassThroughTheirEndsAndMoveSmoothly() {
        CameraSpline spline = new CameraSpline(true).key(0, 0, 1, 0, 0, 1, -10).key(5, 10, 2, 0, 10, 1, -10)
            .key(10, 20, 1, 5, 20, 1, -10);
        double[] at = new double[6];
        spline.at(0, at);
        assertThat(at[0]).isCloseTo(0, within(1e-9));
        spline.at(10, at);
        assertThat(at[0]).isCloseTo(20, within(1e-9));
        double previous = Double.NaN;
        double[] next = new double[6];
        for (double t = 0; t <= 10; t += 1 / 60.0) {
            spline.at(t, next);
            if (!Double.isNaN(previous)) {
                assertThat(Math.abs(next[0] - previous)).as("no jump at " + t).isLessThan(0.2);
            }
            previous = next[0];
        }
    }

    @Test
    void theDayGoesFromDawnToNightThroughItsSegments() {
        TourScript script = new TourScript(new ValleyGround());
        DayState state = new DayState();
        double hour = -1;
        for (TourScript.Segment segment : script.segments()) {
            segment.state(0, state);
            assertThat(state.hour).as(segment.name()).isGreaterThan(hour);
            segment.state(segment.duration(), state);
            hour = state.hour;
            for (TourScript.Shot shot : segment.shots()) {
                assertThat(shot.start() + shot.spline().duration()).isLessThanOrEqualTo(segment.duration() + 1e-9);
            }
        }
        script.segment("night").state(0, state);
        assertThat(state.fireworks).isTrue();
        assertThat(state.snowCover).as("the snow of the dusk still lies").isGreaterThan(0.5);
    }
}
