package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;
import com.truesim.samples.showcase.world.Buildings.Kind;
import com.truesim.samples.showcase.world.Buildings.Spec;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Where everything in the village is, as data: buildings, Poly Haven's props, lamps, paths, fences, rocks and the
 * stand-in trees. Positions are on the ground plan only (x east, z south); heights come from the {@link Ground} when
 * things are placed, so the same plan stands on the temporary valley and on effects-terrain's elevation data.
 *
 * <p>The plan: a cobbled square at the origin; the café on its north side facing the square (south, the sun), the
 * church east of it, its bell tower at the square's corner; chalets on the square's west side and along the street
 * running south through the meadow and north up the valley; farmhouses scattered on the slopes; wooden fences along
 * the pastures, mossy rocks, and trees at the forest's edge.
 */
public final class VillageLayout {

    /** The square's half extents (x, z), metres. */
    public static final double SQUARE_X = 17;
    public static final double SQUARE_Z = 13;

    /** A building: its spec, ground plan position and turn (radians, about up; 0: its front faces +z, south). */
    public record Building(String name, Spec spec, double x, double z, double yaw, boolean lit, boolean fire) {
    }

    /**
     * A prop: a Poly Haven model by asset id at a position and turn, scaled; {@code y} above the ground (or, in a
     * building, {@code inside} names it and x, y, z are in its frame, y above its ground floor).
     */
    public record Prop(String model, double x, double y, double z, double yaw, double scale, String inside) {
    }

    /** A street lamp: a post on the ground at (x, z). */
    public record Lamp(double x, double z, double yaw) {
    }

    /** A fence along a line, posts every 2.4 m. */
    public record Fence(double x0, double z0, double x1, double z1) {
    }

    private final List<Building> buildings = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Lamp> lamps = new ArrayList<>();
    private final List<Fence> fences = new ArrayList<>();
    private final List<double[]> road = new ArrayList<>();
    private final double originX;
    private final double originZ;

    /** The plan about the origin, on flat ground (the tests). */
    public VillageLayout() {
        this(0, 0, null);
    }

    /**
     * The plan with its square at ({@code originX}, {@code originZ}) of the world; rocks and the stand-in trees kept
     * off ground steeper than they would lie on ({@code ground}, null: all kept).
     */
    public VillageLayout(double originX, double originZ, Ground ground) {
        this.originX = originX;
        this.originZ = originZ;
        double q = Math.PI / 2;
        // The square's buildings
        building("cafe", new Spec(Kind.CAFE, 14, 9.5, 3.4, 2.9, 24, 11), 1, -SQUARE_Z - 5.3, 0, true, true);
        building("church", new Spec(Kind.CHURCH, 10.5, 22, 9, 0, 42, 3), SQUARE_X + 12, 1, -q, true, false);
        building("square west 1", chalet(9.5, 11.5, 21), -SQUARE_X - 7, -5.5, q, true, true);
        building("square west 2", chalet(9, 10.5, 22), -SQUARE_X - 6.5, 9.5, q, true, true);
        building("square south east", chalet(10, 12, 23), 12.5, SQUARE_Z + 9, Math.PI, true, true);
        // along the street south through the meadow (x 0, z +), facing it
        building("street 1", chalet(9, 11, 31), -12.5, 38, q, true, true);
        building("street 2", chalet(10, 12, 32), 13, 50, -q, true, false);
        building("street 3", chalet(9.5, 11, 33), -13.5, 66, q, false, true);
        building("street 4", chalet(9, 10, 34), 14, 82, -q, true, true);
        // north up the valley, behind the café
        building("north 1", chalet(10, 12, 41), -14, -42, q, true, true);
        building("north 2", chalet(9, 11, 42), 16, -48, -q, false, true);
        building("north 3", chalet(9.5, 11.5, 43), -15, -64, q, true, false);
        // farmhouses on the slopes, their fronts to the south
        building("farm west", chalet(11, 13, 51), -78, 42, 0.15, true, true);
        building("farm east", chalet(10.5, 12, 52), 74, 64, -0.25, false, true);
        building("farm north west", chalet(10, 12, 53), -92, -58, 0.3, true, true);
        building("farm north east", chalet(11, 12.5, 54), 88, -35, -0.1, false, true);
        building("hut", chalet(7, 8, 55), -48, 128, 0.05, false, false);

        // the road: from the meadow in the south, across the square, up the valley north
        for (double[] p : new double[][] {{-6, 220}, {-3, 150}, {1, 100}, {0, 60}, {0, SQUARE_Z}}) {
            road.add(p);
        }
        // lamps around the square and along the street
        for (double[] p : new double[][] {{-SQUARE_X + 1.2, -SQUARE_Z + 1.2}, {SQUARE_X - 1.2, -SQUARE_Z + 1.2},
            {-SQUARE_X + 1.2, SQUARE_Z - 1.2}, {SQUARE_X - 1.2, SQUARE_Z - 1.2}, {-SQUARE_X + 1.2, 1},
            {-3.6, 30}, {3.6, 46}, {-3.6, 62}, {3.4, 80}, {-6, -SQUARE_Z - 14}, {6, -38}}) {
            lamps.add(new Lamp(p[0], p[1], 0));
        }

        // the café: outside tables on the square, inside tables, the bar's shelves and bottles, the mirror, the
        // chandelier, plants at the door
        for (int i = 0; i < 3; i++) {
            props.add(new Prop("outdoor_table_chair_set_01", -7 + 6.5 * i, 0, -SQUARE_Z + 2.6, 0.3 * i - 0.2, 1,
                null));
        }
        props.add(new Prop("potted_plant_01", -1.3, 0, 5.15, 0, 1, "cafe"));
        props.add(new Prop("potted_plant_01", 1.3, 0, 5.15, 0.8, 1, "cafe"));
        props.add(new Prop("planter_box_01", -5.6, 0, 5.2, 0, 1.2, "cafe"));
        props.add(new Prop("planter_box_01", 4.9, 0, 5.2, 0, 1.2, "cafe"));
        for (int i = 0; i < 3; i++) {
            double x = -4.2 + 4.2 * i;
            props.add(new Prop("round_wooden_table_01", x, 0, 0.6, 0, 0.75, "cafe"));
            props.add(new Prop("painted_wooden_chair_02", x - 0.95, 0, 0.6, q, 0.8, "cafe"));
            props.add(new Prop("painted_wooden_chair_02", x + 0.95, 0, 0.6, -q, 0.8, "cafe"));
            props.add(new Prop("Lantern_01", x + 0.1, 0.76, 0.5, 0, 1, "cafe"));
        }
        props.add(new Prop("wooden_display_shelves_01", -4.5, 0, -4.1, 0, 1.2, "cafe"));
        props.add(new Prop("wooden_display_shelves_01", 4.5, 0, -4.1, 0, 1.2, "cafe"));
        props.add(new Prop("wine_bottles_01", -4.5, 1.05, -4.1, 0, 1.1, "cafe"));
        props.add(new Prop("wine_bottles_01", 4.5, 1.05, -4.1, 0, 1.1, "cafe"));
        props.add(new Prop("wine_bottles_01", 4.5, 1.5, -4.1, 0, 1.1, "cafe"));
        props.add(new Prop("ornate_mirror_01", -1.7, 1.2, -4.27, 0, 2.2, "cafe"));
        props.add(new Prop("Chandelier_01", 0, 2.55, 0, 0, 1, "cafe"));
        props.add(new Prop("wine_barrel_01", 6.0, 0, -3.6, 0, 1, "cafe"));

        // the chalets' rooms: an armchair at the hearth, a cabinet, a lantern on it
        for (Building b : buildings) {
            if (b.spec().kind() != Kind.CHALET || !b.lit()) {
                continue;
            }
            Buildings.Fittings f = Buildings.fittings(b.spec());
            double hw = b.spec().width() / 2;
            double hd = b.spec().depth() / 2;
            if (b.fire()) {
                props.add(new Prop("ArmChair_01", f.fire()[0] - 1.2, 0, f.fire()[2] + 2.2, Math.PI - 0.5, 1,
                    b.name()));
            }
            props.add(new Prop("painted_wooden_cabinet", -hw + 0.9, 0, -hd + 1.6, q, 1, b.name()));
            props.add(new Prop("Lantern_01", -hw + 0.9, 1.18, -hd + 1.4, 0, 1, b.name()));
        }
        // outside the houses: barrels, crates, buckets, a bench by the door; around the square, benches
        SplittableRandom random = new SplittableRandom(5);
        for (Building b : buildings) {
            if (b.spec().kind() != Kind.CHALET) {
                continue;
            }
            Buildings.Fittings f = Buildings.fittings(b.spec());
            double hd = b.spec().depth() / 2;
            props.add(new Prop("painted_wooden_bench", f.door()[0] + 1.9, 0, hd + 0.45, Math.PI, 1, b.name()));
            if (random.nextBoolean()) {
                props.add(new Prop("wine_barrel_01", b.spec().width() / 2 + 0.6, 0, hd - 1.2, 0, 1, b.name()));
                props.add(new Prop("wooden_bucket_01", b.spec().width() / 2 + 0.55, 0, hd - 2.2, 0, 1, b.name()));
            } else {
                props.add(new Prop("wooden_crate_01", -b.spec().width() / 2 - 0.6, 0, hd - 1.5, q, 1, b.name()));
                props.add(new Prop("wooden_crate_01", -b.spec().width() / 2 - 0.62, 0.35, hd - 1.45, q + 0.2, 1,
                    b.name()));
            }
        }
        props.add(new Prop("painted_wooden_bench", -SQUARE_X + 1.5, 0, -4, q, 1.1, null));
        props.add(new Prop("painted_wooden_bench", -SQUARE_X + 1.5, 0, 6, q, 1.1, null));
        props.add(new Prop("painted_wooden_bench", 4, 0, SQUARE_Z - 1.2, Math.PI, 1.1, null));
        props.add(new Prop("wine_barrel_01", SQUARE_X - 2.5, 0, -6, 0.4, 1, null));
        props.add(new Prop("wooden_crate_01", SQUARE_X - 2.6, 0, -4.9, 0.1, 1, null));

        // fences round the pastures south of the village
        fences.add(new Fence(6, 30, 6, 120));
        fences.add(new Fence(6, 120, 70, 140));
        fences.add(new Fence(-7, 95, -7, 170));
        fences.add(new Fence(-7, 170, -60, 185));
        fences.add(new Fence(30, 20, 60, 30));
        // rocks on the meadows and at the forest's edge
        SplittableRandom rocks = new SplittableRandom(17);
        for (int i = 0; i < 26; i++) {
            double x = (rocks.nextBoolean() ? 1 : -1) * rocks.nextDouble(40, 190);
            double z = rocks.nextDouble(-200, 240);
            String model = switch (i % 4) {
                case 0 -> "rock_moss_set_01";
                case 1 -> "rock_moss_set_02";
                case 2 -> "boulder_01";
                default -> "tree_stump_01";
            };
            double scale = model.startsWith("rock_moss") ? rocks.nextDouble(0.3, 0.7) : rocks.nextDouble(0.8, 2.2);
            if (gentle(ground, x, z, 30)) {
                props.add(new Prop(model, x, -0.15 * scale, z, rocks.nextDouble(0, 2 * Math.PI), scale, null));
            }
        }
        // stand-in trees on the slopes until common-procedural's forest lands: Poly Haven's saplings, scaled up
        SplittableRandom trees = new SplittableRandom(23);
        for (int i = 0; i < 90; i++) {
            double side = trees.nextBoolean() ? 1 : -1;
            double x = side * trees.nextDouble(120, 330);
            double z = trees.nextDouble(-320, 320);
            String model = trees.nextBoolean() ? "fir_sapling" : "pine_sapling_small";
            if (gentle(ground, x, z, 38)) {
                props.add(new Prop(model, x, -0.3, z, trees.nextDouble(0, 2 * Math.PI), trees.nextDouble(6, 11),
                    "tree"));
            }
        }
        // everything on the ground plan moved to the square's place in the world
        if (originX != 0 || originZ != 0) {
            buildings.replaceAll(b -> new Building(b.name(), b.spec(), b.x() + originX, b.z() + originZ, b.yaw(),
                b.lit(), b.fire()));
            props.replaceAll(p -> p.inside() != null && !"tree".equals(p.inside()) ? p : new Prop(p.model(),
                p.x() + originX, p.y(), p.z() + originZ, p.yaw(), p.scale(), p.inside()));
            lamps.replaceAll(l -> new Lamp(l.x() + originX, l.z() + originZ, l.yaw()));
            fences.replaceAll(f -> new Fence(f.x0() + originX, f.z0() + originZ, f.x1() + originX, f.z1() + originZ));
            road.replaceAll(p -> new double[] {p[0] + originX, p[1] + originZ});
        }
    }

    /** Whether the ground at the plan's ({@code x}, {@code z}) is no steeper than {@code degrees}. */
    private boolean gentle(Ground ground, double x, double z, double degrees) {
        if (ground == null) {
            return true;
        }
        double[] n = new double[3];
        ground.normal(x + originX, z + originZ, n);
        return n[1] >= Maths.cos(Maths.toRadians(degrees));
    }

    /** Where the square lies in the world. */
    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    private static Spec chalet(double width, double depth, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return new Spec(Kind.CHALET, width, depth, 2.9 + random.nextDouble(0, 0.25), 2.7 + random.nextDouble(0, 0.3),
            22 + random.nextDouble(0, 6), seed);
    }

    private void building(String name, Spec spec, double x, double z, double yaw, boolean lit, boolean fire) {
        buildings.add(new Building(name, spec, x, z, yaw, lit, fire));
    }

    public List<Building> buildings() {
        return buildings;
    }

    public List<Prop> props() {
        return props;
    }

    public List<Lamp> lamps() {
        return lamps;
    }

    public List<Fence> fences() {
        return fences;
    }

    /** The road's centre line, ground plan points from the south. */
    public List<double[]> road() {
        return road;
    }

    /** The building named {@code name}, or null. */
    public Building building(String name) {
        for (Building b : buildings) {
            if (b.name().equals(name)) {
                return b;
            }
        }
        return null;
    }

    /** The ground plan position of ({@code lx}, {@code lz}) in building {@code b}'s frame, into {@code out}. */
    public static void toWorld(Building b, double lx, double lz, double[] out) {
        double c = Maths.cos(b.yaw());
        double s = Maths.sin(b.yaw());
        out[0] = b.x() + lx * c + lz * s;
        out[1] = b.z() - lx * s + lz * c;
    }
}
