package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;
import static com.truesim.samples.showcase.world.MeshKit.p;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.RenderAssets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The village's buildings, made in code as generated mesh assets: Alpine houses (a stone ground floor, a timber or
 * plastered upper floor, a wide gable roof with its eaves far out, a chimney, a balcony on the gable, shuttered
 * windows), the café on the square (a plastered front of tall shop windows) and the church (a nave and a bell tower
 * with a spire). Walls have their thickness: windows sit in reveals with frames, mullions and glass; the ground
 * floor is a room (floor, ceiling, inner walls, a hearth), so what is seen through the windows is an inside.
 *
 * <p>A building's mesh is in its own frame: x across, z along (+z its front), y up from its ground floor, its
 * foundation reaching {@link #FOUNDATION} metres below, so it stands on sloping ground whatever its height there.
 * Its submeshes are the materials of {@link #SUBMESHES}, in order.
 */
public final class Buildings {

    /** Submeshes, each a material slot. */
    public static final int STONE = 0;
    public static final int UPPER = 1;
    public static final int ROOF = 2;
    public static final int TRIM = 3;
    public static final int INTERIOR = 4;
    public static final int FLOOR = 5;
    public static final int GLASS = 6;
    public static final int HEARTH = 7;
    public static final int SHUTTERS = 8;
    public static final int SUBMESHES = 9;

    /** How far below its ground floor a building's foundation reaches, metres. */
    public static final double FOUNDATION = 3.5;

    /** What kind of building. */
    public enum Kind { CHALET, CAFE, CHURCH }

    /**
     * A building: its kind, footprint ({@code width} across, {@code depth} along), the ground floor's height and the
     * upper floor's, the roof's pitch in degrees, a seed for its windows and details.
     */
    public record Spec(Kind kind, double width, double depth, double ground, double upper, double pitch, long seed) {

        /** The height of the walls' top. */
        public double eaves() {
            return ground + upper;
        }

        /** The height of the roof's ridge above the ground floor. */
        public double ridge() {
            return eaves() + (width / 2 + ROOF_OVERHANG) * Maths.tan(Maths.toRadians(pitch));
        }
    }

    static final double ROOF_OVERHANG = 1.3;
    static final double STONE_THICK = 0.45;
    static final double UPPER_THICK = 0.22;
    static final double FRAME = 0.07;

    /** Where things go inside and on a building, in its frame: the hearth's fire, the chimney's top, the lamp. */
    public record Fittings(double[] fire, double[] chimney, double[] ceilingLamp, double[] door, double[][] lanterns) {
    }

    static final Generator<Spec, MeshData> GENERATOR = (spec, seed) -> build(spec).build(true);

    private Buildings() {
    }

    /** The building's mesh asset. */
    public static AssetRef<MeshData> mesh(Spec spec) {
        return AssetRef.generated(RenderAssets.MESH, GENERATOR, spec, 0);
    }

    /** The building's mesh, made now (the asset's generator makes the same). */
    public static MeshData make(Spec spec) {
        return build(spec).build(true);
    }

    /** Where its fittings are (the same numbers its mesh is made from). */
    public static Fittings fittings(Spec spec) {
        double hw = spec.width() / 2;
        double hd = spec.depth() / 2;
        double chimneyX = hw * 0.45;
        double chimneyZ = -hd + 1.2;
        double[] fire = p(chimneyX, 0.15, chimneyZ + 0.25);
        double[] chimney = p(chimneyX, spec.ridge() + 0.9, chimneyZ);
        double[] lamp = p(0, spec.ground() - 0.45, 0);
        double[] door = p(spec.kind() == Kind.CAFE ? 0 : -hw * 0.45, 0, hd + 0.05);
        double[][] lanterns = {p(door[0] + 0.95, 2.3, hd + 0.25), p(door[0] - 0.95, 2.3, hd + 0.25)};
        if (spec.kind() == Kind.CHURCH) {
            fire = null;
            chimney = null;
            lamp = p(0, spec.eaves() - 1, 0);
            lanterns = new double[][] {p(1.4, 2.6, hd + 0.25)};
        }
        return new Fittings(fire, chimney, lamp, door, lanterns);
    }

    /** The kit with the building's pieces. */
    static MeshKit build(Spec spec) {
        return switch (spec.kind()) {
            case CHALET, CAFE -> house(spec);
            case CHURCH -> church(spec);
        };
    }

    // ---------------------------------------------------------------- houses

    private static MeshKit house(Spec spec) {
        boolean cafe = spec.kind() == Kind.CAFE;
        SplittableRandom random = new SplittableRandom(spec.seed());
        MeshKit kit = new MeshKit(SUBMESHES);
        double w = spec.width();
        double d = spec.depth();
        double hw = w / 2;
        double hd = d / 2;
        double g = spec.ground();
        double top = spec.eaves();
        // Foundation: a plinth below the ground floor, all round
        kit.to(STONE).box(p(-hw - 0.05, -FOUNDATION, -hd - 0.05), p(hw + 0.05, 0.02, hd + 0.05), 2, false);
        // Ground floor: thick stone walls (the café's plastered: its material is the upper's), windows in reveals
        int groundOuter = cafe ? UPPER : STONE;
        double gt = STONE_THICK;
        List<double[]> front = new ArrayList<>();
        Fittings fit = fittings(spec);
        double doorX = fit.door()[0];
        if (cafe) {
            // shop windows either side of the door, tall
            front.add(new double[] {doorX - 0.6, 0, 1.2, 2.3, 1});
            for (double x : new double[] {-hw + 0.8, hw - 3.2}) {
                front.add(new double[] {x, 0.45, 2.4, 2.1, 0});
            }
            front.add(new double[] {-hw + 0.8 + 2.4 + 0.5, 0.45, Math.max(0.1, doorX - 0.6 - (-hw + 0.8 + 2.4 + 0.5)
                - 0.4), 2.1, 0});
        } else {
            front.add(new double[] {doorX - 0.5, 0, 1.0, 2.1, 1});
            front.add(new double[] {hw * 0.3, 0.9, 1.0, 1.15, 0});
        }
        List<double[]> back = List.of(new double[] {-hw * 0.4 - 0.5, 0.9, 1.0, 1.15, 0});
        List<double[]> side = windowsAlong(d, 0.9, 1.0, 1.15, cafe ? 2 : 2 + random.nextInt(2));
        wall(kit, p(-hw, 0, hd), p(1, 0, 0), w, g, gt, front, groundOuter, INTERIOR, TRIM, true);
        wall(kit, p(hw, 0, -hd), p(-1, 0, 0), w, g, gt, back, groundOuter, INTERIOR, TRIM, true);
        wall(kit, p(hw, 0, hd), p(0, 0, -1), d, g, gt, side, groundOuter, INTERIOR, TRIM, true);
        wall(kit, p(-hw, 0, -hd), p(0, 0, 1), d, g, gt, side, groundOuter, INTERIOR, TRIM, true);
        // the room: its floor and ceiling (the upper floor's boards above)
        kit.to(FLOOR).rect(p(-hw + gt, 0.02, hd - gt), p(w - 2 * gt, 0, 0), p(0, 0, -(d - 2 * gt)), cafe ? 3.4 : 2);
        kit.to(TRIM).rect(p(-hw + gt, g - 0.02, -hd + gt), p(w - 2 * gt, 0, 0), p(0, 0, d - 2 * gt), 2);
        // ceiling beams
        for (double z = -hd + gt + 1.0; z < hd - gt - 0.5; z += 1.6) {
            kit.box(p(-hw + gt, g - 0.24, z - 0.09), p(hw - gt, g - 0.02, z + 0.09), 1, false);
        }
        // Upper floor: timber (or plaster), thinner, windows with shutters
        int upperOuter = UPPER;
        double ut = UPPER_THICK;
        double floor = g;
        List<double[]> upperFront = windowsAlong(w, 0.85, 0.9, 1.1, cafe ? 3 : 2);
        List<double[]> upperSide = windowsAlong(d, 0.85, 0.9, 1.1, 2 + random.nextInt(2));
        wall(kit, p(-hw, floor, hd), p(1, 0, 0), w, spec.upper(), ut, upperFront, upperOuter, INTERIOR, TRIM, false);
        wall(kit, p(hw, floor, -hd), p(-1, 0, 0), w, spec.upper(), ut, windowsAlong(w, 0.85, 0.9, 1.1, 2),
            upperOuter, INTERIOR, TRIM, false);
        wall(kit, p(hw, floor, hd), p(0, 0, -1), d, spec.upper(), ut, upperSide, upperOuter, INTERIOR, TRIM, false);
        wall(kit, p(-hw, floor, -hd), p(0, 0, 1), d, spec.upper(), ut, upperSide, upperOuter, INTERIOR, TRIM, false);
        kit.to(FLOOR).rect(p(-hw + ut, floor + 0.02, hd - ut), p(w - 2 * ut, 0, 0), p(0, 0, -(d - 2 * ut)), 2);
        // shutters beside the upper windows (green-painted wood) and on the ground floor's of the chalets
        shutters(kit, p(-hw, floor, hd), p(1, 0, 0), upperFront);
        shutters(kit, p(hw, floor, hd), p(0, 0, -1), upperSide);
        shutters(kit, p(-hw, floor, -hd), p(0, 0, 1), upperSide);
        if (!cafe) {
            shutters(kit, p(hw, 0, hd), p(0, 0, -1), side);
            shutters(kit, p(-hw, 0, -hd), p(0, 0, 1), side);
        }
        // gables: the upper walls' triangles up to the roof, front and back, timber
        double rise = (hw + ROOF_OVERHANG) * Maths.tan(Maths.toRadians(spec.pitch()));
        double gableTop = top + hw * Maths.tan(Maths.toRadians(spec.pitch()));
        for (double z : new double[] {hd, -hd}) {
            double s = Math.signum(z);
            kit.to(upperOuter).triangle(p(-hw * s, top, z), p(hw * s, top, z), p(0, gableTop, z), 2);
            kit.to(INTERIOR).triangle(p(hw * s, top, z - s * ut), p(-hw * s, top, z - s * ut),
                p(0, gableTop, z - s * ut), 2);
        }
        // the attic floor (seen from below: the upper room's ceiling)
        kit.to(TRIM).rect(p(-hw + ut, top - 0.02, -hd + ut), p(w - 2 * ut, 0, 0), p(0, 0, d - 2 * ut), 2);
        roof(kit, spec, hw, hd, top, rise);
        // a balcony on the front gable, along the upper floor (not the café's)
        if (!cafe) {
            balcony(kit, hw, hd, floor);
        }
        // the chimney: through the roof's back slope, a hearth in the room below it
        double[] c = fit.chimney();
        kit.to(STONE).box(p(c[0] - 0.4, top - 0.5, c[2] - 0.4), p(c[0] + 0.4, c[1], c[2] + 0.4), 2, false);
        kit.to(TRIM).box(p(c[0] - 0.5, c[1], c[2] - 0.5), p(c[0] + 0.5, c[1] + 0.08, c[2] + 0.5), 1, false);
        hearth(kit, fit.fire(), hd, gt);
        // the door: planks set back in its opening
        kit.to(TRIM).box(p(doorX - 0.5, 0, hd - gt * 0.7), p(doorX + 0.5, 2.1, hd - gt * 0.7 + 0.06), 1, false);
        if (cafe) {
            // the door is glazed: its glass
            kit.to(GLASS).rect(p(doorX - 0.45, 0.9, hd - gt * 0.7 + 0.07), p(0.9, 0, 0), p(0, 1.1, 0), 1);
        }
        return kit;
    }

    /** {@code count} windows evenly along a wall of {@code length}: sill, width, height. */
    private static List<double[]> windowsAlong(double length, double sill, double width, double height, int count) {
        List<double[]> windows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double centre = length * (i + 1) / (count + 1);
            windows.add(new double[] {centre - width / 2, sill, width, height, 0});
        }
        return windows;
    }

    /**
     * A wall from {@code start} along unit {@code along} for {@code length}, {@code height} high, {@code thick} deep
     * inwards (to the left of {@code along}, seen from above), with openings (x along it, sill, width, height, door):
     * its outer face, inner face and reveals; in each window a frame, a mullion cross and glass.
     */
    private static void wall(MeshKit kit, double[] start, double[] along, double length, double height,
        double thick, List<double[]> openings, int outer, int inner, int reveal, boolean framed) {
        double[] up = p(0, 1, 0);
        double[] in = p(along[2], 0, -along[0]); // inwards: along +x on the south front, inwards is -z
        // the outer face: x breaks and y breaks, cells outside every opening
        List<Double> xs = new ArrayList<>(List.of(0.0, length));
        List<Double> ys = new ArrayList<>(List.of(0.0, height));
        for (double[] o : openings) {
            xs.add(o[0]);
            xs.add(o[0] + o[2]);
            ys.add(o[1]);
            ys.add(Math.min(height, o[1] + o[3]));
        }
        xs.sort(null);
        ys.sort(null);
        double[] inside = MeshKit.add(start, MeshKit.scale(in, thick));
        for (int i = 0; i + 1 < xs.size(); i++) {
            double x0 = xs.get(i);
            double x1 = xs.get(i + 1);
            if (x1 - x0 < 1e-6) {
                continue;
            }
            for (int j = 0; j + 1 < ys.size(); j++) {
                double y0 = ys.get(j);
                double y1 = ys.get(j + 1);
                if (y1 - y0 < 1e-6 || inOpening(openings, (x0 + x1) / 2, (y0 + y1) / 2)) {
                    continue;
                }
                double[] o = MeshKit.add(MeshKit.add(start, MeshKit.scale(along, x0)), p(0, y0, 0));
                kit.to(outer).rect(o, MeshKit.scale(along, x1 - x0), p(0, y1 - y0, 0), 2,
                    x0 + start[0] * along[0] + start[2] * along[2], y0 + start[1]);
                // the inner face, facing in: from the far end back
                double[] oi = MeshKit.add(MeshKit.add(inside, MeshKit.scale(along, x1)), p(0, y0, 0));
                kit.to(inner).rect(oi, MeshKit.scale(along, -(x1 - x0)), p(0, y1 - y0, 0), 2);
            }
        }
        // the reveals and the windows
        for (double[] o : openings) {
            double x0 = o[0];
            double x1 = o[0] + o[2];
            double y0 = o[1];
            double y1 = Math.min(height, o[1] + o[3]);
            double[] a = MeshKit.add(MeshKit.add(start, MeshKit.scale(along, x0)), p(0, y0, 0));
            double[] b = MeshKit.add(MeshKit.add(start, MeshKit.scale(along, x1)), p(0, y0, 0));
            double[] dIn = MeshKit.scale(in, thick);
            double[] dUp = p(0, y1 - y0, 0);
            kit.to(reveal);
            kit.rect(a, dIn, dUp, 1); // left reveal, facing along
            kit.rect(MeshKit.add(b, dIn), MeshKit.scale(dIn, -1), dUp, 1); // right reveal, facing back
            kit.rect(MeshKit.add(a, dUp), dIn, MeshKit.scale(along, x1 - x0), 1); // lintel's underside
            if (y0 > 0.01) {
                kit.rect(a, MeshKit.scale(along, x1 - x0), dIn, 1); // sill
            }
            if (o[4] > 0) {
                continue; // a door: its leaf is the building's
            }
            // the frame, in the middle of the wall's depth, and the glass
            double depth = framed ? thick * 0.55 : thick * 0.5;
            double[] f = MeshKit.add(a, MeshKit.scale(in, depth));
            frame(kit, f, along, in, x1 - x0, y1 - y0);
            kit.to(GLASS).rect(MeshKit.add(f, MeshKit.scale(in, 0.02)), MeshKit.scale(along, x1 - x0),
                p(0, y1 - y0, 0), 1);
        }
    }

    private static boolean inOpening(List<double[]> openings, double x, double y) {
        for (double[] o : openings) {
            if (x > o[0] && x < o[0] + o[2] && y > o[1] && y < o[1] + o[3]) {
                return true;
            }
        }
        return false;
    }

    /** A window's frame and mullion cross at {@code o} (its lower left, outside), {@code w} by {@code h}. */
    private static void frame(MeshKit kit, double[] o, double[] along, double[] in, double w, double h) {
        kit.to(TRIM);
        double t = FRAME;
        double deep = 0.07;
        double[][] bars = {
            {0, 0, w, t}, {0, h - t, w, t}, {0, 0, t, h}, {w - t, 0, t, h}, // the frame
            {w / 2 - t / 3, 0, 2 * t / 3, h}, {0, h * 0.62, w, 2 * t / 3}, // the cross
        };
        for (double[] bar : bars) {
            double[] lo = MeshKit.add(MeshKit.add(o, MeshKit.scale(along, bar[0])), p(0, bar[1], 0));
            beam(kit, lo, MeshKit.scale(along, bar[2]), p(0, bar[3], 0), MeshKit.scale(in, deep));
        }
    }

    /** A box spanned by three edge vectors from {@code o}: two faces each way. */
    private static void beam(MeshKit kit, double[] o, double[] a, double[] b, double[] c) {
        if (MeshKit.dot(MeshKit.cross(a, b), c) > 0) { // the faces below take a x b facing away from c
            double[] t = a;
            a = b;
            b = t;
        }
        double[] oc = MeshKit.add(o, c);
        kit.rect(o, a, b, 1); // its face along a x b
        kit.rect(MeshKit.add(oc, a), MeshKit.scale(a, -1), b, 1);
        kit.rect(MeshKit.add(o, a), c, b, 1);
        kit.rect(oc, MeshKit.scale(c, -1), b, 1);
        kit.rect(MeshKit.add(o, b), a, c, 1);
        kit.rect(MeshKit.add(oc, MeshKit.scale(c, 0)), a, MeshKit.scale(c, -1), 1);
    }

    /** Shutters, open beside each window of {@code windows} on the wall from {@code start} along {@code along}. */
    private static void shutters(MeshKit kit, double[] start, double[] along, List<double[]> windows) {
        double[] out = p(-along[2], 0, along[0]);
        kit.to(SHUTTERS);
        for (double[] o : windows) {
            if (o[4] > 0) {
                continue;
            }
            double sw = o[2] / 2;
            for (double x : new double[] {o[0] - sw - 0.04, o[0] + o[2] + 0.04}) {
                double[] lo = MeshKit.add(MeshKit.add(MeshKit.add(start, MeshKit.scale(along, x)), p(0, o[1], 0)),
                    MeshKit.scale(out, 0.02));
                beam(kit, lo, MeshKit.scale(along, sw), p(0, o[3], 0), MeshKit.scale(out, 0.04));
            }
        }
    }

    /** The gable roof: two slabs from the eaves (overhanging) to the ridge, their undersides and edges. */
    private static void roof(MeshKit kit, Spec spec, double hw, double hd, double top, double rise) {
        double pitch = Maths.toRadians(spec.pitch());
        double run = hw + ROOF_OVERHANG;
        double slope = run / Maths.cos(pitch);
        double thick = 0.22;
        double len = 2 * (hd + ROOF_OVERHANG);
        double eave = top - ROOF_OVERHANG * Maths.tan(pitch) + 0.15;
        double ridge = eave + rise;
        for (int side = -1; side <= 1; side += 2) {
            double[] lowEdge = p(side * run, eave, hd + ROOF_OVERHANG);
            double[] upSlope = p(-side * run, ridge - eave, 0);
            double[] alongRidge = p(0, 0, -len);
            double[] normal = MeshKit.normalize(MeshKit.cross(side > 0 ? alongRidge : upSlope,
                side > 0 ? upSlope : alongRidge));
            if (normal[1] < 0) {
                normal = MeshKit.scale(normal, -1);
            }
            double[] lift = MeshKit.scale(normal, thick);
            double[] o = MeshKit.add(lowEdge, lift);
            double[] downward = MeshKit.scale(normal, -1);
            // the top: tiles running up the slope; the underside (soffit), boards
            kit.to(ROOF).rectFacing(o, alongRidge, upSlope, 3, normal);
            kit.to(TRIM).rectFacing(lowEdge, alongRidge, upSlope, 2, downward);
            // the fascia along the eave (facing down the slope), the verges at the gables (front and back)
            double[] outward = MeshKit.scale(upSlope, -1);
            kit.to(TRIM).rectFacing(lowEdge, alongRidge, lift, 1, outward);
            kit.to(TRIM).rectFacing(lowEdge, lift, upSlope, 1, p(0, 0, 1));
            kit.to(TRIM).rectFacing(MeshKit.add(lowEdge, alongRidge), lift, upSlope, 1, p(0, 0, -1));
        }
        // the ridge cap
        kit.to(ROOF).box(p(-0.18, ridge + thick - 0.05, -hd - ROOF_OVERHANG),
            p(0.18, ridge + thick + 0.12, hd + ROOF_OVERHANG), 1, false);
    }

    /** A wooden balcony along the front gable at the upper floor: its floor, posts, rail and slats. */
    private static void balcony(MeshKit kit, double hw, double hd, double floor) {
        kit.to(TRIM);
        double depth = 1.1;
        double z0 = hd;
        double z1 = hd + depth;
        double x0 = -hw + 0.3;
        double x1 = hw - 0.3;
        kit.box(p(x0, floor - 0.12, z0), p(x1, floor, z1), 2, false);
        // brackets under it
        for (double x = x0 + 0.2; x <= x1 - 0.1; x += (x1 - x0 - 0.4) / 3) {
            kit.box(p(x - 0.06, floor - 0.55, z0), p(x + 0.06, floor - 0.12, z1 - 0.1), 1, false);
        }
        // rail and slats
        kit.box(p(x0, floor + 0.95, z1 - 0.08), p(x1, floor + 1.05, z1), 1, false);
        for (double x = x0; x <= x1 + 1e-6; x += 0.14) {
            kit.box(p(x, floor, z1 - 0.06), p(x + 0.09, floor + 0.95, z1 - 0.03), 1, false);
        }
        for (double x : new double[] {x0, x1 - 0.08}) {
            for (double z = z0 + 0.1; z <= z1; z += depth - 0.18) {
                kit.box(p(x, floor, z - 0.04), p(x + 0.08, floor + 0.95, z), 1, false);
            }
        }
    }

    /** The hearth: an open fireplace of stone against the back wall, its firebox where the fire burns. */
    private static void hearth(MeshKit kit, double[] fire, double hd, double wallThick) {
        double x = fire[0];
        double z = -hd + wallThick;
        kit.to(HEARTH);
        // sides, the lintel above the opening, the hood up to the ceiling
        kit.box(p(x - 0.85, 0, z), p(x - 0.55, 1.05, z + 0.75), 1, false);
        kit.box(p(x + 0.55, 0, z), p(x + 0.85, 1.05, z + 0.75), 1, false);
        kit.box(p(x - 0.85, 1.05, z), p(x + 0.85, 1.35, z + 0.75), 1, false);
        kit.box(p(x - 0.6, 1.35, z), p(x + 0.6, 2.8, z + 0.55), 1, false);
        // the hearthstone and the soot-dark back
        kit.box(p(x - 0.9, 0, z), p(x + 0.9, 0.12, z + 1.05), 1, false);
        kit.to(STONE).rect(p(x + 0.55, 0.12, z + 0.02), p(-1.1, 0, 0), p(0, 0.93, 0), 1);
    }

    // ---------------------------------------------------------------- the church

    private static MeshKit church(Spec spec) {
        MeshKit kit = new MeshKit(SUBMESHES);
        double w = spec.width();
        double d = spec.depth();
        double hw = w / 2;
        double hd = d / 2;
        double h = spec.eaves();
        kit.to(STONE).box(p(-hw - 0.1, -FOUNDATION, -hd - 0.1), p(hw + 0.1, 0.4, hd + 0.1), 2, false);
        // the nave: plastered walls with tall windows
        List<double[]> tall = windowsAlong(d, 2.2, 1.0, 3.2, 3);
        List<double[]> portal = List.of(new double[] {hw - 1.0, 0, 2.0, 3.2, 1}, new double[] {hw - 0.4, 4.8, 0.8,
            1.6, 0});
        wall(kit, p(-hw, 0, hd), p(1, 0, 0), w, h, 0.7, portal, UPPER, INTERIOR, STONE, true);
        wall(kit, p(hw, 0, -hd), p(-1, 0, 0), w, h, 0.7, List.of(), UPPER, INTERIOR, STONE, true);
        wall(kit, p(hw, 0, hd), p(0, 0, -1), d, h, 0.7, tall, UPPER, INTERIOR, STONE, true);
        wall(kit, p(-hw, 0, -hd), p(0, 0, 1), d, h, 0.7, tall, UPPER, INTERIOR, STONE, true);
        kit.to(FLOOR).rect(p(-hw + 0.7, 0.02, hd - 0.7), p(w - 1.4, 0, 0), p(0, 0, -(d - 1.4)), 2);
        kit.to(TRIM).rect(p(-hw + 0.7, h - 0.05, -hd + 0.7), p(w - 1.4, 0, 0), p(0, 0, d - 1.4), 2);
        double rise = (hw + ROOF_OVERHANG) * Maths.tan(Maths.toRadians(spec.pitch()));
        double gableTop = h + hw * Maths.tan(Maths.toRadians(spec.pitch()));
        for (double z : new double[] {hd, -hd}) {
            double s = Math.signum(z);
            kit.to(UPPER).triangle(p(-hw * s, h, z), p(hw * s, h, z), p(0, gableTop, z), 3);
        }
        roof(kit, spec, hw, hd, h, rise);
        // the door's leaf
        kit.to(TRIM).box(p(-1.0, 0, hd - 0.5), p(1.0, 3.2, hd - 0.42), 1, false);
        // the bell tower at the front left: plastered, a stone base, louvred openings, a spire of dark slates
        double tw = 2.6;
        double tx = -hw + tw - 0.5;
        double tz = hd + tw - 0.2;
        double towerTop = h + 13;
        kit.to(STONE).box(p(tx - tw - 0.1, -FOUNDATION, tz - tw - 0.1), p(tx + tw + 0.1, 1.2, tz + tw + 0.1), 2,
            false);
        double[][] faces = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        List<double[]> belfry = List.of(new double[] {tw - 0.6, towerTop - 4.5, 1.2, 2.4, 0});
        wall(kit, p(tx - tw, 0, tz + tw), p(1, 0, 0), 2 * tw, towerTop, 0.6, belfry, UPPER, INTERIOR, STONE, false);
        wall(kit, p(tx + tw, 0, tz - tw), p(-1, 0, 0), 2 * tw, towerTop, 0.6, belfry, UPPER, INTERIOR, STONE, false);
        wall(kit, p(tx + tw, 0, tz + tw), p(0, 0, -1), 2 * tw, towerTop, 0.6, belfry, UPPER, INTERIOR, STONE, false);
        wall(kit, p(tx - tw, 0, tz - tw), p(0, 0, 1), 2 * tw, towerTop, 0.6, belfry, UPPER, INTERIOR, STONE, false);
        faces.clone();
        // a cornice, then the spire: four steep faces to a point, a cross of gilt metal on top
        kit.to(STONE).box(p(tx - tw - 0.25, towerTop, tz - tw - 0.25), p(tx + tw + 0.25, towerTop + 0.35,
            tz + tw + 0.25), 1, false);
        double sb = towerTop + 0.35;
        double apex = sb + 12;
        double r = tw + 0.3;
        double[][] corners = {p(tx - r, sb, tz + r), p(tx + r, sb, tz + r), p(tx + r, sb, tz - r),
            p(tx - r, sb, tz - r)};
        double[] tip = p(tx, apex, tz);
        for (int i = 0; i < 4; i++) {
            kit.to(ROOF).triangle(corners[i], corners[(i + 1) % 4], tip, 2);
        }
        kit.to(TRIM).box(p(tx - 0.05, apex - 0.2, tz - 0.05), p(tx + 0.05, apex + 2.0, tz + 0.05), 1, false);
        kit.to(TRIM).box(p(tx - 0.45, apex + 1.2, tz - 0.05), p(tx + 0.45, apex + 1.3, tz + 0.05), 1, false);
        // clock faces below the belfry, each side: a white disc and its hands
        for (double[] face : faces) {
            double cx = tx + face[0] * (tw + 0.01);
            double cz = tz + face[1] * (tw + 0.01);
            double cy = towerTop - 7.2;
            double[] u = p(-face[1], 0, face[0]);
            double[] o = p(cx - u[0] * 0.9, cy - 0.9, cz - u[2] * 0.9);
            kit.to(INTERIOR).rect(o, MeshKit.scale(u, 1.8), p(0, 1.8, 0), 1.8);
            double[] out = p(face[0] * 0.03, 0, face[1] * 0.03);
            kit.to(SHUTTERS).rect(MeshKit.add(p(cx - u[0] * 0.04, cy - 0.05, cz - u[2] * 0.04), out),
                MeshKit.scale(u, 0.08), p(0, 0.75, 0), 1);
            kit.to(SHUTTERS).rect(MeshKit.add(p(cx - u[0] * 0.04, cy - 0.04, cz - u[2] * 0.04), out),
                MeshKit.scale(u, 0.55), p(0, 0.08, 0), 1);
        }
        return kit;
    }
}
