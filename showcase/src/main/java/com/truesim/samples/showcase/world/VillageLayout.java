package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;
import com.truesim.common.math.Vec3;
import com.truesim.common.procedural.building.Anchor;
import com.truesim.common.procedural.building.BuildingKind;
import com.truesim.common.procedural.building.Buildings;
import com.truesim.common.procedural.settlement.PropKind;
import com.truesim.common.procedural.settlement.RoadKind;
import com.truesim.common.procedural.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Where everything in the village is, as data, from common-procedural's settlement plan ({@link Settlement}: roads
 * by least-cost paths over the terrain, the square, plots, buildings, props, lamps, fences): the buildings generated
 * on the ground by its buildings generator (chalets, farmhouses, hay barns, the inn on the square's sunny side, the
 * church across it), Poly Haven's props at the plan's anchors and the buildings' fittings (benches, the inn's terrace
 * tables, tables and chairs in its parlour, cabinets and armchairs in the houses' rooms), the street lamps, the fences'
 * lines. The trees, shrubs and flowers are the ecology's ({@code Flora}), kept off what the plan clears.
 *
 * <p>Positions are on the ground plan (x east, z south); a prop in a building ({@code inside}) is in its frame (x
 * across its front, z towards its front, y up from its ground floor).
 */
public final class VillageLayout {

    /**
     * A building: its name, its generated meshes and fittings ({@code model}), where its frame stands (x, z, turned by
     * {@code yaw}: its front faces (sin yaw, cos yaw)), whether its rooms are lit and its hearth burns.
     */
    public record Building(String name, com.truesim.common.procedural.building.Building model, double x, double z,
        double yaw, boolean lit, boolean fire) {

        public BuildingKind kind() {
            return model.spec().kind();
        }

        public double width() {
            return model.spec().footprint().width();
        }

        public double depth() {
            return model.spec().footprint().depth();
        }

        /** The ground floor's height. */
        public double floor() {
            return model.floor();
        }
    }

    /**
     * A prop: a Poly Haven model by asset id at a position and turn, scaled; {@code y} above the ground (or, in a
     * building, {@code inside} names it and x, y, z are in its frame, y above its ground floor).
     */
    public record Prop(String model, double x, double y, double z, double yaw, double scale, String inside) {
    }

    /** A street lamp: a post on the ground at (x, z), turned to its road. */
    public record Lamp(double x, double z, double yaw) {
    }

    /** A fence along a line of x, z pairs, of the plan's kind (a garden's, a pasture's), with its seed. */
    public record Fence(Settlement.FenceKind kind, double[] line, long seed) {

        public double x0() {
            return line[0];
        }

        public double z0() {
            return line[1];
        }
    }

    /** Every Poly Haven model the village may draw (the props' and the furniture's, the street lamps). */
    public static final java.util.Set<String> MODELS = java.util.Set.of("painted_wooden_bench",
        "outdoor_table_chair_set_01", "planter_box_01", "wine_barrel_01", "wooden_crate_01", "tree_stump_01", "round_wooden_table_01", "Lantern_01", "painted_wooden_chair_02",
        "painted_wooden_cabinet", "wooden_display_shelves_01", "wine_bottles_01", "ArmChair_01", "Chandelier_01",
        "street_lamp_01");

    private final Settlement settlement;
    private final List<Building> buildings = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Lamp> lamps = new ArrayList<>();
    private final List<Fence> fences = new ArrayList<>();

    /** The village of {@code settlement}, its buildings generated on {@code ground}. */
    public VillageLayout(Settlement settlement, Ground ground) {
        this.settlement = settlement;
        List<Settlement.Building> planned = settlement.buildings();
        // the buildings, generated on all cores (some 30,000 triangles each at full detail)
        com.truesim.common.procedural.building.Building[] made =
            new com.truesim.common.procedural.building.Building[planned.size()];
        IntStream.range(0, planned.size()).parallel().forEach(i ->
            made[i] = Buildings.generate(planned.get(i).spec(), ground::height));
        int house = 0;
        for (int i = 0; i < planned.size(); i++) {
            Settlement.Building b = planned.get(i);
            BuildingKind kind = made[i].spec().kind();
            String name = switch (kind) {
                case INN -> "cafe";
                case CHURCH -> "church";
                default -> kind.id() + " " + (++house);
            };
            boolean dwelling = kind == BuildingKind.CHALET || kind == BuildingKind.FARMHOUSE
                || kind == BuildingKind.INN;
            long seed = b.spec().seed();
            buildings.add(new Building(name, made[i], b.x(), b.z(), b.yaw(),
                kind == BuildingKind.CHURCH || kind == BuildingKind.INN || dwelling && Math.floorMod(seed, 4) != 0,
                dwelling && Math.floorMod(seed, 3) != 0));
        }
        for (Building b : buildings) {
            furnish(b);
        }
        for (Settlement.Prop p : settlement.props()) {
            String model = model(p.kind());
            if (model != null) {
                double scale = p.kind() == PropKind.STUMP ? 1 + unit(p.x(), p.z()) : 1;
                props.add(new Prop(model, p.x(), 0, p.z(), p.yaw(), scale, null));
            }
        }
        for (Settlement.Lamp l : settlement.lamps()) {
            lamps.add(new Lamp(l.x(), l.z(), l.yaw()));
        }
        long n = 0;
        for (Settlement.Fence f : settlement.fences()) {
            fences.add(new Fence(f.kind(), f.line(), settlement.seed() * 1000 + n++));
        }
    }

    /**
     * Poly Haven's model for a plan's prop, or null: rocks and boulders are the rock field's (common-procedural's
     * rocks, {@code Flora}); fountains, troughs, signposts and woodpiles are not drawn yet.
     */
    static String model(PropKind kind) {
        return switch (kind) {
            case BENCH -> "painted_wooden_bench";
            case TABLE_SET -> "outdoor_table_chair_set_01";
            case PLANTER -> "planter_box_01";
            case BARREL -> "wine_barrel_01";
            case CRATE -> "wooden_crate_01";
            case STUMP -> "tree_stump_01";
            default -> null;
        };
    }

    /** The building's rooms furnished at its fittings' anchors with Poly Haven's furniture. */
    private void furnish(Building b) {
        boolean inn = b.kind() == BuildingKind.INN;
        for (Anchor a : b.model().fittings().anchors()) {
            Vec3 p = a.position();
            double yaw = a.yaw();
            switch (a.kind()) {
                case TABLE -> {
                    props.add(new Prop("round_wooden_table_01", p.x(), p.y(), p.z(), yaw, 0.75, b.name()));
                    if (inn) {
                        props.add(new Prop("Lantern_01", p.x() + 0.1, p.y() + 0.76, p.z() - 0.1, 0, 1, b.name()));
                    }
                }
                case CHAIR -> props.add(new Prop("painted_wooden_chair_02", p.x(), p.y(), p.z(), yaw, 0.8, b.name()));
                case CABINET -> {
                    props.add(new Prop("painted_wooden_cabinet", p.x(), p.y(), p.z(), yaw, 1, b.name()));
                    props.add(new Prop("Lantern_01", p.x(), p.y() + 1.18, p.z(), 0, 1, b.name()));
                }
                case BAR -> {
                    props.add(new Prop("wooden_display_shelves_01", p.x(), p.y(), p.z(), yaw, 1.2, b.name()));
                    props.add(new Prop("wine_bottles_01", p.x(), p.y() + 1.05, p.z(), yaw, 1.1, b.name()));
                    props.add(new Prop("wine_barrel_01", p.x() + 1.6, p.y(), p.z(), yaw, 1, b.name()));
                }
                case TERRACE_TABLE -> props.add(new Prop("outdoor_table_chair_set_01", p.x(), p.y(), p.z(), yaw, 1,
                    b.name()));
                case BENCH -> {
                    if (a.room() < 0) {
                        props.add(new Prop("painted_wooden_bench", p.x(), p.y(), p.z(), yaw, 1, b.name()));
                    }
                }
                case FIRE -> {
                    if (b.lit() && !inn && b.kind() != BuildingKind.CHURCH) {
                        // an armchair before the hearth, turned to it
                        double c = Maths.cos(yaw);
                        double s = Maths.sin(yaw);
                        props.add(new Prop("ArmChair_01", p.x() + 2.0 * s - 0.8 * c, p.y() - 0.15,
                            p.z() + 2.0 * c + 0.8 * s, yaw + Math.PI - 0.4, 1, b.name()));
                    }
                }
                default -> {
                }
            }
        }
        if (inn) {
            for (Anchor a : b.model().fittings().anchors()) {
                if (a.kind() == Anchor.Kind.CEILING_LAMP && a.room() >= 0) {
                    Vec3 p = a.position();
                    props.add(new Prop("Chandelier_01", p.x(), p.y() - 0.05, p.z(), 0, 1, b.name()));
                    break;
                }
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double unit(double x, double z) {
        long h = Double.doubleToLongBits(x * 31.7 + z * 17.3) * 0x9E3779B97F4A7C15L;
        return ((h >>> 11) & 0xffff) / 65535.0;
    }

    /** The plan. */
    public Settlement settlement() {
        return settlement;
    }

    /** Where the square lies in the world: its centre. */
    public double originX() {
        return settlement.square().x();
    }

    public double originZ() {
        return settlement.square().z();
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

    /** The main road's centre line south of the square, from the square outwards: x, z pairs. */
    public double[] streetSouth() {
        double[] best = null;
        double bestZ = -Double.MAX_VALUE;
        for (Settlement.Road r : settlement.roads()) {
            if (r.kind() != RoadKind.MAIN || r.count() < 2) {
                continue;
            }
            int n = r.count();
            // the end nearer the square first
            double first = sq(r.x()[0] - originX()) + sq(r.z()[0] - originZ());
            double last = sq(r.x()[n - 1] - originX()) + sq(r.z()[n - 1] - originZ());
            boolean forward = first <= last;
            double[] line = new double[2 * n];
            for (int i = 0; i < n; i++) {
                int k = forward ? i : n - 1 - i;
                line[2 * i] = r.x()[k];
                line[2 * i + 1] = r.z()[k];
            }
            double far = line[2 * n - 1];
            if (far > bestZ) {
                bestZ = far;
                best = line;
            }
        }
        return best;
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

    /** The building whose walls stand round world ({@code x}, {@code z}), or null. */
    public Building inside(double x, double z) {
        return near(x, z, 0);
    }

    /** The building within {@code margin} metres of whose walls (or inside) world ({@code x}, {@code z}) is, or null. */
    public Building near(double x, double z, double margin) {
        for (Building b : buildings) {
            double c = Maths.cos(b.yaw());
            double s = Maths.sin(b.yaw());
            double dx = x - b.x();
            double dz = z - b.z();
            double lx = dx * c - dz * s;
            double lz = dx * s + dz * c;
            if (Math.abs(lx) < b.width() / 2 + margin && Math.abs(lz) < b.depth() / 2 + margin) {
                return b;
            }
        }
        return null;
    }

    /** The inn's main door in the world: x, z, the way out (dx, dz), its floor's height; null without an inn. */
    public double[] innDoor() {
        Building inn = building("cafe");
        if (inn == null) {
            return null;
        }
        Anchor door = null;
        for (Anchor a : inn.model().fittings().all(Anchor.Kind.DOOR)) {
            if (door == null || a.position().z() > door.position().z()) {
                door = a; // the front's
            }
        }
        double[] at = new double[2];
        double yaw = inn.yaw() + (door != null ? door.yaw() : 0);
        toWorld(inn, door != null ? door.position().x() : 0, door != null ? door.position().z() : inn.depth() / 2, at);
        return new double[] {at[0], at[1], Maths.sin(yaw), Maths.cos(yaw), inn.floor()};
    }

    /** The ground plan position of ({@code lx}, {@code lz}) in building {@code b}'s frame, into {@code out}. */
    public static void toWorld(Building b, double lx, double lz, double[] out) {
        double c = Maths.cos(b.yaw());
        double s = Maths.sin(b.yaw());
        out[0] = b.x() + lx * c + lz * s;
        out[1] = b.z() - lx * s + lz * c;
    }
}
