package com.truesim.samples.showcase.tour;

import com.truesim.effects.weather.WeatherPreset;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.world.Ground;
import java.util.ArrayList;
import java.util.List;

/**
 * The tour: the day in five segments, each a few cinematic shots on camera splines, the day's state keyed along each
 * segment. Heights in the shots are above the ground under each point (eye and target alike), resolved once.
 *
 * <ol>
 *   <li><b>dawn</b>: mist in the valley and fog volumes over the stream's meadows, smoke from the chimneys standing in
 *       still air, the sun rising over the eastern ridge; low through the meadow towards the village, then up over
 *       the street;</li>
 *   <li><b>noon</b>: cumulus drifting, their shadows sweeping the valley; a wide view from the eastern slope, a walk
 *       into the square, and through the café's window to its mirror (depth of field);</li>
 *   <li><b>storm</b>: towering clouds roll in, rain and lightning; the camera climbs to the ridge and looks back;</li>
 *   <li><b>dusk</b>: snow begins at sunset and settles, lamps and fireplaces come on, lit flakes in the lamps' light;
 *       along the street at lamp height, then round the square;</li>
 *   <li><b>night</b>: the sky clears: stars, the moon nearly full, snow on the roofs; fireworks over the square.</li>
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

    /** The tour in the valley made from functions, the square at the origin. */
    public TourScript(Ground ground) {
        this(ground, 0, 0, false);
    }

    /**
     * The tour over {@code ground}, the village's square at ({@code ox}, {@code oz}); {@code real}: Lauterbrunnen's
     * terrain, its landscape shots at the Staubbach cliff and towards the Jungfrau.
     */
    public TourScript(Ground ground, double ox, double oz, boolean real) {
        this.ground = ground;
        this.ox = ox;
        this.oz = oz;
        // keys: t, hour, fog, mist, snowfall, snowCover, rain, lamps, rooms
        segments.add(new Segment("dawn", 32, List.of(
            shot("meadow", 0, 17, new double[][] {
                {0, 12, 3.5, 205, 0, 8, 0}, {17, 6, 3, 128, 4, 8, -10}}),
            shot("street", 17, 15, new double[][] {
                {0, 7, 1.8, 76, -2, 4, 0}, {15, 3, 9, 46, -4, 5, -60}})),
            List.of(new double[] {0, 7.35, 0.0012, 0.006, 0, 0, 0, 1, 0.6}, new double[] {32, 8.15, 0.0005, 0.0015, 0,
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
        segments.add(new Segment("noon", 42, List.of(
            real ? landscape("jungfrau", 0, 14, new double[][] {
                {0, -470, 60, 140, 4130, -600, 6330}, {14, -455, 65, 235, 4130, -650, 6330}})
                : shot("slope", 0, 14, new double[][] {
                    {0, 110, 30, 95, 0, 10, -10}, {14, 82, 22, 50, -10, 6, -30}}),
            shot("square", 14, 15, new double[][] {
                {0, -1, 1.7, 32, 1, 2.5, -14}, {15, 0.5, 1.7, 0, 1.5, 2, -14}}),
            shot("cafe window", 29, 13, new double[][] {
                {0, 1.9, 1.7, -9.6, 1.6, 1.6, -22.5}, {6, 1.2, 1.75, -12.9, 2, 1.7, -22.5}, {13, 1, 1.8, -16.6,
                    2.4, 1.8, -22.5}})),
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
            real ? landscape("up the valley", 17, 17, new double[][] {
                {0, -90, 6, 900, -130, 60, 0}, {17, -80, 8, 780, -120, 80, -200}})
                : shot("ridge", 17, 17, new double[][] {
                    {0, 200, 4, 70, 0, 12, -10}, {17, 182, 4, 25, -10, 12, -40}})),
            List.of(new double[] {0, 15.7, 0.0005, 0, 0, 0, 0.6, 0, 0.5}, new double[] {12, 15.9, 0.0007, 0, 0, 0, 1,
                0.3, 0.8}, new double[] {34, 16.3, 0.0008, 0, 0, 0, 1, 0.5, 1}),
            s -> {
                // nimbostratus over the valley (lifted to its floor) and a storm cell in it
                s.weather = WeatherPreset.RAIN;
                s.weatherRate = 2;
                s.fogFalloff = 0.01;
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
            List.of(new double[] {0, 18.1, 0.0003, 0, 0.4, 0.15, 0, 1, 1}, new double[] {14, 18.3, 0.0004, 0, 0.9,
                0.75, 0, 1, 1}, new double[] {36, 18.6, 0.0005, 0, 1, 1, 0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.ALTOSTRATUS;
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        segments.add(new Segment("night", 34, List.of(
            shot("fireworks", 0, 16, new double[][] {
                {0, 2, 1.7, 8, 10, 150, -150}, {16, -2, 1.7, 3, 10, 160, -150}}),
            shot("village at night", 16, 18, new double[][] {
                {0, -20, 4, 120, 5, 110, -150}, {18, -10, 4, 95, 5, 120, -150}})),
            List.of(new double[] {0, 21.0, 0.0004, 0, 0, 0.95, 0, 1, 1}, new double[] {34, 21.3, 0.0004, 0, 0, 0.95,
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

    /** A shot in the village from keys of (t, x, h, z, tx, th, tz) about the square: heights above the ground. */
    private Shot shot(String name, double start, double duration, double[][] keys) {
        CameraSpline spline = new CameraSpline(true);
        for (double[] k : keys) {
            double x = ox + k[1];
            double z = oz + k[3];
            double tx = ox + k[4];
            double tz = oz + k[6];
            spline.key(k[0], x, ground.height(x, z) + k[2], z, tx, ground.height(tx, tz) + k[5], tz);
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
