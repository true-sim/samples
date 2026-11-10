package com.truesim.samples.showcase.tour;

import com.truesim.effects.weather.WeatherPreset;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.world.Ground;
import java.util.ArrayList;
import java.util.List;

/**
 * The tour: the day in seven segments, each a few cinematic shots on camera splines, the day's state keyed along each
 * segment. Heights in the shots are above the ground under each point (eye and target alike), resolved once.
 *
 * <ol>
 *   <li><b>dawn</b>: mist in the valley, smoke from the chimneys standing in still air, the sun rising over the
 *       eastern ridge; low through the meadow towards the village, then up over the street;</li>
 *   <li><b>water</b> (late morning, where the network has them): low along the Weisse Lütschine's bank, its stones,
 *       foam and wet banks; then the Staubbach Fall from the meadow before it, its spray at the foot;</li>
 *   <li><b>noon</b>: cumulus drifting, their shadows sweeping the valley; a wide view towards the Jungfrau, a walk
 *       into the square, and in through the inn's door;</li>
 *   <li><b>storm</b>: storm cells roll in, rain and lightning; the street in the rain, then the square;</li>
 *   <li><b>dusk</b>: snow begins at sunset and settles, lamps and fireplaces come on; along the street, then round
 *       the square;</li>
 *   <li><b>evening</b>: still snowing as the night comes on, easing off; up the street to the square;</li>
 *   <li><b>night</b>: the sky clears, the moon up, a dusting of snow; the fireworks over the village, across its
 *       roofs and from the square.</li>
 * </ol>
 */
public final class TourScript {

    /** A shot: when it starts in its segment and its move. */
    public record Shot(String name, double start, CameraSpline spline) {
    }

    /** A segment: its shots and its day keys (segment seconds, state). */
    public record Segment(String name, double duration, List<Shot> shots, List<double[]> keys,
        java.util.function.Consumer<DayState> base) {

        /** The state at {@code t} seconds into the segment: the base, then the keys interpolated. */
        public void state(double t, DayState out) {
            base.accept(out);
            // keys: t, hour, fog, mist, snowfall, snowCover, rain, lamps, rooms, fStop
            double[] a = keys.getFirst();
            double[] b = keys.getLast();
            for (int i = 0; i + 1 < keys.size(); i++) {
                if (t >= keys.get(i)[0] && t <= keys.get(i + 1)[0]) {
                    a = keys.get(i);
                    b = keys.get(i + 1);
                    break;
                }
            }
            double s = b[0] > a[0] ? Math.clamp((t - a[0]) / (b[0] - a[0]), 0, 1) : 0;
            s = s * s * (3 - 2 * s);
            out.hour = lerp(a[1], b[1], s);
            out.fog = lerp(a[2], b[2], s);
            out.mist = lerp(a[3], b[3], s);
            out.snowfall = lerp(a[4], b[4], s);
            out.snowCover = lerp(a[5], b[5], s);
            out.rain = lerp(a[6], b[6], s);
            out.lamps = lerp(a[7], b[7], s);
            out.rooms = lerp(a[8], b[8], s);
        }

        /** The shot at {@code t} seconds into the segment. */
        public Shot shot(double t) {
            Shot at = shots.getFirst();
            for (Shot shot : shots) {
                if (t >= shot.start()) {
                    at = shot;
                }
            }
            return at;
        }
    }

    private final List<Segment> segments = new ArrayList<>();
    /** The Jungfrau's summit, metres above the sea. */
    static final double JUNGFRAU = 4158;

    private final Ground ground;
    private final double ox;
    private final double oz;
    /** Whether a world point (x, z) is in or beside a building: the village's shots keep their eyes off them. */
    private java.util.function.BiPredicate<Double, Double> blocked = (x, z) -> false;
    /** The street the village's shots follow: x, z pairs from the square's centre outwards (south). */
    private final double[] street;
    private final boolean real;
    private final double[] inn;
    /** The river's bank (x, z, the way it flows dx, dz, its half width) and the tallest fall (foot, lip), or null. */
    private double[] bank;
    private double[] fall;

    /** The tour in the valley made from functions, the square at the origin, the street straight south. */
    public TourScript(Ground ground) {
        this(ground, 0, 0, false, null, null);
    }

    /**
     * The tour over {@code ground}, the village's square at ({@code ox}, {@code oz}); {@code real}: Lauterbrunnen's
     * terrain, its landscape shots at the Staubbach cliff and towards the Jungfrau. The village's shots follow
     * {@code street} (the main road's centre line south of the square, x, z pairs; null: straight south): a shot's x is
     * across it (east of it where it runs south), its z along it from the square's centre. {@code inn}: the inn's door
     * and the way its front faces (x, z, dx, dz), which the café shot walks in through (null: the old plan's café).
     */
    public TourScript(Ground ground, double ox, double oz, boolean real, double[] street, double[] inn) {
        this.ground = ground;
        this.ox = ox;
        this.oz = oz;
        if (street == null) {
            this.street = new double[] {ox, oz, ox, oz + 1000};
        } else {
            this.street = new double[street.length + 2];
            this.street[0] = ox;
            this.street[1] = oz;
            System.arraycopy(street, 0, this.street, 2, street.length);
        }
        this.real = real;
        this.inn = inn;
        build();
    }

    /** The segments and their shots. */
    private void build() {
        // keys: t, hour, fog, mist, snowfall, snowCover, rain, lamps, rooms
        segments.add(new Segment("dawn", 32, List.of(
            shot("meadow", 0, 17, new double[][] {
                {0, 12, 3.5, 205, 0, 8, 0}, {17, 6, 3, 128, 4, 8, -10}}),
            shot("street", 17, 15, new double[][] {
                {0, 7, 1.8, 76, -2, 4, 0}, {15, 3, 9, 46, -4, 5, -60}})),
            List.of(new double[] {0, 6.95, 0.0012, 0.006, 0, 0, 0, 1, 0.6}, new double[] {32, 7.7, 0.0005, 0.0015, 0,
                0, 0, 0, 0.3}),
            s -> {
                s.weather = WeatherPreset.FAIR;
                s.weatherRate = 6;
                s.fogFalloff = 0.03;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 16;
            }));
        if (bank != null && fall != null) {
            segments.add(new Segment("water", 30, List.of(river("lütschine", 0), staubbach("staubbach", 15)),
                List.of(new double[] {0, 10.9, 0.0003, 0, 0, 0, 0, 0, 0}, new double[] {30, 11.2, 0.0003, 0, 0, 0, 0,
                    0, 0}),
                s -> {
                    s.weather = WeatherPreset.FAIR;
                    s.weatherRate = 10;
                    s.fogFalloff = 0.02;
                    s.fires = false;
                    s.storm = false;
                    s.fireworks = false;
                    s.fStop = 5.6;
                }));
        }
        segments.add(new Segment("noon", 42, List.of(
            real ? landscape("jungfrau", 0, 14, new double[][] {
                {0, -470, 60, 140, 4130, -600, 6330}, {14, -455, 65, 235, 4130, -650, 6330}})
                : shot("slope", 0, 14, new double[][] {
                    {0, 110, 30, 95, 0, 10, -10}, {14, 82, 22, 50, -10, 6, -30}}),
            shot("square", 14, 15, new double[][] {
                {0, -1, 1.7, 32, 1, 2.5, -14}, {15, 0.5, 1.7, 0, 1.5, 2, -14}}),
            // straight in through the inn's open door, across its parlour
            inn != null ? door("cafe window", 29, inn) : shot("cafe window", 29, 13, new double[][] {
                {0, 1.0, 2.3, -8.0, 0.4, 2.2, -22.5}, {6, 1.0, 2.5, -12.2, 0.2, 2.4, -22.5}, {13, 0.9, 2.6, -16.6,
                    -0.2, 2.5, -22.5}})),
            List.of(new double[] {0, 12.7, 0.00025, 0, 0, 0, 0, 0, 0}, new double[] {29, 13.2, 0.00025, 0, 0, 0, 0, 0,
                0.9}, new double[] {42, 13.4, 0.00025, 0, 0, 0, 0, 0, 1}),
            s -> {
                s.weather = WeatherPreset.SCATTERED;
                s.weatherRate = 15;
                s.fogFalloff = 0.02;
                s.fires = false;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 11;
            }));
        segments.add(new Segment("storm", 34, List.of(
            real ? shot("rain in the street", 0, 17, new double[][] {
                {0, -3, 2.2, 62, 0, 5, 0}, {17, -2, 2, 34, 2, 6, -10}})
                : shot("climb", 0, 17, new double[][] {
                    {0, 30, 6, 40, -20, 6, -10}, {9, 120, 30, 70, -10, 6, -10}, {17, 215, 6, 85, -20, 10, -40}}),
            // the square in the rain, the church and the wall's falls under the cells (from low: over the treetops
            // the camera met the cells' bases, effects-weather#18)
            real ? shot("square in the rain", 17, 17, new double[][] {
                {0, -8, 3, 14, 0, 12, -60}, {17, 4, 3.5, 9, 2, 16, -60}})
                : shot("ridge", 17, 17, new double[][] {
                    {0, 200, 4, 70, 0, 12, -10}, {17, 182, 4, 25, -10, 12, -40}})),
            List.of(new double[] {0, 15.7, 0.0002, 0, 0, 0, 0.6, 0, 0.5}, new double[] {12, 15.9, 0.00026, 0, 0, 0, 1,
                0.3, 0.8}, new double[] {34, 16.3, 0.0003, 0, 0, 0, 1, 0.5, 1}),
            s -> {
                // storm cells over the valley (standing on its floor), one spawned upwind of the village
                s.weather = WeatherPreset.STORM; // cells with their bases, rain shafts and gaps between them
                s.weatherRate = 2;
                s.fogFalloff = 0.025; // the rain's haze low in the valley, thinning up the walls
                s.fires = true;
                s.storm = true;
                s.fireworks = false;
                s.fStop = 16;
            }));
        segments.add(new Segment("dusk", 36, List.of(
            shot("street at dusk", 0, 18, new double[][] {
                {0, -3.2, 2.3, 78, 0, 3, 20}, {18, -1.8, 2.1, 34, 2, 3, -10}}),
            shot("square in snow", 18, 18, new double[][] {
                {0, -14, 5.5, 10, 2, 1.5, -6}, {9, -6, 4.5, 11.5, 4, 1.5, -8}, {18, 6, 4, 10, 2, 1.5, -10}})),
            // a light snowfall settling: a dusting on the roofs and the square by the evening
            List.of(new double[] {0, 19.05, 0.0003, 0, 0.4, 0.05, 0, 1, 1}, new double[] {14, 19.25, 0.0004, 0, 0.8,
                0.2, 0, 1, 1}, new double[] {36, 19.55, 0.0005, 0, 0.9, 0.35, 0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.BROKEN; // snow showers from a broken deck, the afterglow in its gaps
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        // the evening: still snowing as the night comes on, the snow easing off
        segments.add(new Segment("evening", 18, List.of(
            shot("evening snow", 0, 18, new double[][] {
                {0, 1.5, 3.2, 55, 0, 2.5, 0}, {18, 0.5, 3.0, 26, -2, 2.5, -12}})),
            List.of(new double[] {0, 21.6, 0.0005, 0, 0.8, 0.4, 0, 1, 1}, new double[] {12, 21.9, 0.0004, 0, 0.25,
                0.45, 0, 1, 1}, new double[] {18, 22.0, 0.0004, 0, 0, 0.45, 0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.BROKEN;
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        // the night: the sky cleared, the moon up; the show over the village, framed across its roofs
        segments.add(new Segment("night", 34, List.of(
            shot("over the village", 0, 17, new double[][] {
                {0, 0, 14, 80, 40, 55, -150}, {17, -3, 13, 68, 45, 60, -150}}), // the show east of the wall
            shot("from the square", 17, 17, new double[][] {
                {0, -7, 2.0, 9, 25, 45, -150}, {17, -3, 2.2, 5, 28, 50, -150}})),
            List.of(new double[] {0, 23.3, 0.0004, 0, 0, 0.45, 0, 1, 1}, new double[] {34, 23.6, 0.0004, 0, 0, 0.45,
                0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.CLEAR;
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = true;
                s.fStop = 16;
            }));
    }

    /**
     * A shot in the village from keys of (t, x, h, z, tx, th, tz) in the street's frame (x across it, z along it from
     * the square's centre): heights above the ground.
     */
    private Shot shot(String name, double start, double duration, double[][] keys) {
        CameraSpline spline = new CameraSpline(true);
        double[] eye = new double[2];
        double[] target = new double[2];
        for (double[] k : keys) {
            double h = k[2];
            onStreet(k[1], k[3], eye);
            if (blocked.test(eye[0], eye[1])) {
                onStreet(0, k[3], eye);
                if (blocked.test(eye[0], eye[1])) {
                    h += 14;
                }
            }
            onStreet(k[4], k[6], target);
            spline.key(k[0], eye[0], ground.height(eye[0], eye[1]) + h, eye[1], target[0],
                ground.height(target[0], target[1]) + k[5], target[1]);
        }
        return new Shot(name, start, spline);
    }

    /** The world point {@code across} metres beside the street {@code along} metres from the square, into out. */
    public void onStreet(double across, double along, double[] out) {
        int n = street.length / 2;
        double left = along;
        int i = 0;
        // the segment the distance falls in (before the first: extended back; past the last: extended on)
        while (i < n - 2) {
            double l = Math.sqrt(sq(street[2 * i + 2] - street[2 * i]) + sq(street[2 * i + 3] - street[2 * i + 1]));
            if (left <= l || along < 0) {
                break;
            }
            left -= l;
            i++;
        }
        double dx = street[2 * i + 2] - street[2 * i];
        double dz = street[2 * i + 3] - street[2 * i + 1];
        double l = Math.max(Math.sqrt(dx * dx + dz * dz), 1e-9);
        dx /= l;
        dz /= l;
        // across: to the right of the way south (east where it runs south)
        out[0] = street[2 * i] + dx * left + dz * across;
        out[1] = street[2 * i + 1] + dz * left - dx * across;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The river shot's keys along the bank: (t, metres along, eye height). */
    private static final double[][] RIVER = {{0, -12, 0.9}, {15, 6, 0.7}};

    /** 0 within 3 m of the river shot's way along {@code bank} (see {@link #water}), 1 from 4 m; 1 without one. */
    public static double riverPath(double[] bank, double x, double z) {
        if (bank == null) {
            return 1;
        }
        double side = bank[4] + 2.5;
        double px = x - (bank[0] - bank[3] * side);
        double pz = z - (bank[1] + bank[2] * side);
        double along = Math.clamp(px * bank[2] + pz * bank[3], RIVER[0][1] - 3, RIVER[1][1] + 3);
        double d = Math.hypot(px - bank[2] * along, pz - bank[3] * along);
        return Math.clamp(d - 3, 0, 1);
    }

    /** Low along the river's bank downstream, the water's surface, its stones and foam ahead. */
    private Shot river(String name, double start) {
        CameraSpline spline = new CameraSpline(true);
        double x = bank[0];
        double z = bank[1];
        double dx = bank[2];
        double dz = bank[3];
        double side = bank[4] + 2.5; // on the bank, beside the water
        double sx = -dz;
        double sz = dx;
        for (double[] k : RIVER) {
            double ex = x + dx * k[1] + sx * side;
            double ez = z + dz * k[1] + sz * side;
            double tx = x + dx * (k[1] + 22) + sx * bank[4] * 0.2;
            double tz = z + dz * (k[1] + 22) + sz * bank[4] * 0.2;
            spline.key(k[0], ex, ground.height(ex, ez) + k[2], ez, tx, ground.height(tx, tz) + 0.2, tz);
        }
        return new Shot(name, start, spline);
    }

    /** Before the tallest fall, from the meadow: the sheet down the wall and its spray at the foot, closing in. */
    private Shot staubbach(String name, double start) {
        CameraSpline spline = new CameraSpline(true);
        double ax = fall[0] - fall[3];
        double az = fall[2] - fall[5];
        double l = Math.max(Math.sqrt(ax * ax + az * az), 1e-6);
        ax /= l; // away from the cliff, the way the water falls out
        az /= l;
        double[][] keys = {{0, 230, 8}, {15, 190, 6}}; // from the meadow: the whole fall, its spray at the foot
        for (double[] k : keys) {
            double ex = fall[0] + ax * k[1];
            double ez = fall[2] + az * k[1];
            double ty = fall[1] + (fall[4] - fall[1]) * 0.45;
            spline.key(k[0], ex, ground.height(ex, ez) + k[2], ez, fall[3], ty, fall[5]);
        }
        return new Shot(name, start, spline);
    }

    /** Walking in through a door at (x, z) facing (dx, dz): from 12 m out to 2.5 m in, at eye height. */
    private Shot door(String name, double start, double[] inn) {
        CameraSpline spline = new CameraSpline(true);
        double x = inn[0];
        double z = inn[1];
        double dx = inn[2];
        double dz = inn[3];
        double floor = inn.length > 4 ? inn[4] : ground.height(x, z);
        double[][] keys = {{0, 12, 1.7}, {6, 4, 1.65}, {13, -2.5, 1.6}};
        for (double[] k : keys) {
            double ex = x + dx * k[1];
            double ez = z + dz * k[1];
            double ey = Math.max(ground.height(ex, ez), k[1] < 0.5 ? floor : -1e9) + k[2];
            double tx = x - dx * 12;
            double tz = z - dz * 12;
            spline.key(k[0], ex, ey, ez, tx, floor + 1.5, tz);
        }
        return new Shot(name, start, spline);
    }

    /**
     * A shot over the landscape from keys of (t, x, h, z, tx, th, tz) in the world: heights above the ground under each
     * point; a negative target height is that far below the Jungfrau's summit (4,158 m) instead.
     */
    private Shot landscape(String name, double start, double duration, double[][] keys) {
        CameraSpline spline = new CameraSpline(true);
        for (double[] k : keys) {
            double ty = k[5] < 0 ? JUNGFRAU + k[5] : ground.height(k[4], k[6]) + k[5];
            spline.key(k[0], k[1], ground.height(k[1], k[3]) + k[2], k[3], k[4], ty, k[6]);
        }
        return new Shot(name, start, spline);
    }

    /**
     * Adds the water's close-ups: along {@code bank} (x, z, dx, dz, half width: a point of the river and the way it
     * flows) and at the foot of {@code fall} (foot x, y, z, lip x, y, z). Remakes the segments.
     */
    public TourScript water(double[] riverBank, double[] tallest) {
        bank = riverBank;
        fall = tallest;
        segments.clear();
        build();
        return this;
    }

    /**
     * Keeps the village's shots' eyes off what {@code test} says is a building (or beside one): an eye there moves
     * onto the street's middle, or, if that is too, above the roofs. Remakes the segments.
     */
    public TourScript clear(java.util.function.BiPredicate<Double, Double> test) {
        blocked = test;
        segments.clear();
        build();
        return this;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** The segment named {@code name}, or null. */
    public Segment segment(String name) {
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
