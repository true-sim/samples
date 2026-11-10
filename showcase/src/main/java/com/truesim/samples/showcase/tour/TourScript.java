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
    public record Shot(String name, double start, CameraSpline spline, double fov) {

        /** The tour's usual lens: 55 degrees vertically. */
        public static final double FOV = 55;

        public Shot(String name, double start, CameraSpline spline) {
            this(name, start, spline, FOV);
        }

        /** This shot through a lens of {@code degrees} vertically. */
        public Shot wide(double degrees) {
            return new Shot(name, start, spline, degrees);
        }
    }

    /**
     * A segment: its shots and its day keys (segment seconds, state); {@code live}: filmed as the world streams in (no
     * waiting for it before the first frame: the flyover).
     */
    public record Segment(String name, double duration, List<Shot> shots, List<double[]> keys,
        java.util.function.Consumer<DayState> base, boolean live) {

        public Segment(String name, double duration, List<Shot> shots, List<double[]> keys,
            java.util.function.Consumer<DayState> base) {
            this(name, duration, shots, keys, base, false);
        }

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
        if (real) {
            // the opening: in from high over the Thun lake's side, 30 km off, down the Lütschine's valley to the
            // village's meadow, where the dawn takes over; the world streams in as it flies (terrain tiles refining,
            // the forest from impostors to meshes, the village's cells)
            segments.add(new Segment("flyover", 60, List.of(flyover("flyover", 0, 60)),
                List.of(new double[] {0, 6.75, 0.0012, 0.006, 0, 0, 0, 1, 0.6}, new double[] {60, 6.94, 0.0012, 0.006,
                    0, 0, 0, 1, 0.6}),
                s -> {
                    s.weather = WeatherPreset.FAIR;
                    s.weatherRate = 6;
                    s.fogFalloff = 0.03;
                    s.fires = true;
                    s.storm = false;
                    s.fireworks = false;
                    s.fStop = 16;
                }, true));
        }
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
        // dusk: the lamps, windows and hearths coming on, the village still bare (green meadows, dark roofs)
        segments.add(new Segment("dusk", 18, List.of(
            shot("street at dusk", 0, 18, new double[][] {
                {0, -3.2, 2.3, 78, 0, 3, 20}, {18, -1.8, 2.1, 34, 2, 3, -10}})),
            List.of(new double[] {0, 19.05, 0.0003, 0, 0, 0, 0, 1, 1}, new double[] {18, 19.3, 0.0004, 0, 0, 0, 0,
                1, 1}),
            s -> {
                s.weather = WeatherPreset.BROKEN; // snow showers from a broken deck, the afterglow in its gaps
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        // the snowfall: over the square to the church, chalets either side, a heavy snowfall starts and the village
        // goes from bare to white, the cover a time-lapse over the shot while the flakes fall at their own speed, lit
        // by the lamps and windows
        segments.add(new Segment("snowfall", 30, List.of(
            shot("snowfall over the square", 0, 30, new double[][] {
                {0, -6, 8, 46, 0, 4, -20}, {30, 2, 7, 38, 2, 4, -22}})),
            List.of(new double[] {0, 19.4, 0.0004, 0, 0.15, 0, 0, 1, 1}, new double[] {4, 19.45, 0.0005, 0, 1, 0, 0,
                1, 1}, new double[] {27, 19.75, 0.0006, 0, 1, 1, 0, 1, 1}, new double[] {30, 19.8, 0.0006, 0, 1, 1, 0,
                1, 1}),
            s -> {
                s.weather = WeatherPreset.OVERCAST; // a snow deck closing in
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        // the evening: still snowing as the night comes on, the snow easing off, the village white
        segments.add(new Segment("evening", 18, List.of(
            shot("evening snow", 0, 18, new double[][] {
                {0, 1.5, 3.2, 55, 0, 2.5, 0}, {18, 0.5, 3.0, 26, -2, 2.5, -12}})),
            List.of(new double[] {0, 21.6, 0.0005, 0, 0.8, 1, 0, 1, 1}, new double[] {12, 21.9, 0.0004, 0, 0.25,
                1, 0, 1, 1}, new double[] {18, 22.0, 0.0004, 0, 0, 1, 0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.BROKEN;
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        // the night: the sky cleared, the moon up; the show over the village, the roofs, the church and the lit square
        // the frame's lower third, the bursts (up to some 230 m up and 70 m across: 7-inch shells, their trails and
        // smoke above) whole in the sky with room round them: a wide lens (75 degrees) looking well up
        segments.add(new Segment("night", 34, List.of(
            shot("over the village", 0, 17, new double[][] {
                {0, 0, 25, 120, 40, 110, -150}, {17, -4, 24, 110, 45, 108, -150}}).wide(75), // 270 m off, 25 m up
            shot("from the street", 17, 17, new double[][] {
                {0, -6, 6, 40, 40, 107, -150}, {17, -3, 6, 30, 45, 105, -150}}).wide(75)),
            List.of(new double[] {0, 23.3, 0.0004, 0, 0, 1, 0, 1, 1}, new double[] {34, 23.6, 0.0004, 0, 0, 1,
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

    /**
     * The river shot's keys: t, metres along the river, aside from its line as a share of its half width, eye height
     * over the bed, and the metres along where it looks at the water: low over the water itself, moving downstream,
     * the surface with its reflections and foam filling the frame's lower half, the banks either side.
     */
    private static final double[][] RIVER = {{0, -8, 0.2, 5.0, 4}, {15, 8, 0.1, 4.5, 16}};

    /**
     * 0 along the river shot's stretch of {@code bank} to 3 m beyond either edge (no tree or shrub over the water before
     * the camera), 1 a metre further; 1 without a bank.
     */
    public static double riverPath(double[] bank, double x, double z) {
        if (bank == null) {
            return 1;
        }
        double px = x - bank[0];
        double pz = z - bank[1];
        double along = px * bank[2] + pz * bank[3];
        double aside = -px * bank[3] + pz * bank[2]; // towards the camera's side
        double out = Math.max(Math.max(RIVER[0][1] - 4 - along, along - RIVER[1][4] - 4),
            Math.max(-aside - bank[4] - 3, aside - bank[4] - 3));
        return Math.clamp(out, 0, 1);
    }

    /** From the river's bank, a few metres up, moving downstream with it: the water, its reflections and foam. */
    private Shot river(String name, double start) {
        CameraSpline spline = new CameraSpline(true);
        double x = bank[0];
        double z = bank[1];
        double dx = bank[2];
        double dz = bank[3];
        double sx = -dz;
        double sz = dx;
        for (int n = 0; n < RIVER.length; n++) {
            double[] k = RIVER[n];
            double ex = x + dx * k[1] + sx * bank[4] * k[2];
            double ez = z + dz * k[1] + sz * bank[4] * k[2];
            double tx = x + dx * k[4];
            double tz = z + dz * k[4];
            if (bank.length >= 13) { // along the channel's own bends: over the water at either end
                ex = bank[5 + 2 * n];
                ez = bank[6 + 2 * n];
                tx = bank[9 + 2 * n];
                tz = bank[10 + 2 * n];
            }
            spline.key(k[0], ex, ground.height(ex, ez) + k[3], ez, tx, ground.height(tx, tz), tz);
        }
        return new Shot(name, start, spline);
    }

    /**
     * Before the tallest fall's foot, off to one side and crossing it (as effects-water's own film frames it): the spray
     * and mist at the foot first, then looking up the veil to the lip.
     */
    private Shot staubbach(String name, double start) {
        CameraSpline spline = new CameraSpline(true);
        double ax = fall[0] - fall[3];
        double az = fall[2] - fall[5];
        double l = Math.max(Math.sqrt(ax * ax + az * az), 1e-6);
        ax /= l; // away from the cliff, the way the water falls out
        az /= l;
        // (t, metres out, metres aside, eye height, target height over the foot)
        double[][] keys = {{0, 105, 25, 10, 50}, {15, 85, 40, 10, 170}};
        for (double[] k : keys) {
            double ex = fall[0] + ax * k[1] - az * k[2];
            double ez = fall[2] + az * k[1] + ax * k[2];
            spline.key(k[0], ex, ground.height(ex, ez) + k[3], ez, fall[0], fall[1] + k[4], fall[2]);
        }
        return new Shot(name, start, spline);
    }

    /**
     * The flyover: world keys far out (t, x, height over the ground, z, toward the village), slowing as it nears, then
     * the street's frame to the dawn's first pose (its meadow shot's start), so the dawn follows without a cut.
     */
    private Shot flyover(String name, double start, double duration) {
        CameraSpline spline = new CameraSpline(true);
        double[] village = new double[2];
        onStreet(0, 0, village);
        double vy = ground.height(village[0], village[1]) + 30;
        // north is -z: the Thun lake's side some 30 km north-north-west, Interlaken 10 km north, the valley's mouth
        double[][] far = {{0, -9000, 3500, -28000}, {14, -3500, 2200, -14000}, {28, -600, 1000, -6000},
            {40, -250, 350, -2200}};
        for (double[] k : far) {
            double x = village[0] + k[1];
            double z = village[1] + k[3];
            spline.key(k[0], x, ground.height(x, z) + k[2], z, village[0], vy, village[1]);
        }
        double[] eye = new double[2];
        double[] target = new double[2];
        double[][] near = {{50, 10, 45, 520, 0, 10, 0}, {duration, 12, 3.5, 205, 0, 8, 0}}; // the dawn's first key
        for (double[] k : near) {
            onStreet(k[1], k[3], eye);
            onStreet(k[4], k[6], target);
            spline.key(k[0], eye[0], ground.height(eye[0], eye[1]) + k[2], eye[1], target[0],
                ground.height(target[0], target[1]) + k[5], target[1]);
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
