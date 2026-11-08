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
    private final Ground ground;

    public TourScript(Ground ground) {
        this.ground = ground;
        // keys: t, hour, fog, mist, snowfall, snowCover, rain, lamps, rooms
        segments.add(new Segment("dawn", 32, List.of(
            shot("meadow", 0, 17, new double[][] {
                {0, -26, 0.7, 190, 12, 14, 10}, {17, -12, 1.3, 118, 14, 12, 0}}),
            shot("street", 17, 15, new double[][] {
                {0, 7, 1.8, 76, -2, 4, 0}, {15, 3, 9, 46, -4, 5, -60}})),
            List.of(new double[] {0, 6.95, 0.0035, 0.02, 0, 0, 0, 1, 0.6}, new double[] {32, 7.75, 0.0012, 0.006, 0, 0,
                0, 0, 0.3}),
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
            shot("slope", 0, 14, new double[][] {
                {0, 110, 30, 95, 0, 10, -10}, {14, 82, 22, 50, -10, 6, -30}}),
            shot("square", 14, 15, new double[][] {
                {0, -1, 1.7, 32, 1, 2.5, -14}, {15, 0.5, 1.7, 0, 1.5, 2, -14}}),
            shot("cafe window", 29, 13, new double[][] {
                {0, 0.9, 1.5, -9.6, 0.6, 1.4, -22.5}, {6, 0.2, 1.45, -12.9, 1, 1.5, -22.5}, {13, 0, 1.4, -16.6,
                    1.4, 1.6, -22.5}})),
            List.of(new double[] {0, 12.7, 0.00025, 0, 0, 0, 0, 0, 0}, new double[] {42, 13.4, 0.00025, 0, 0, 0, 0, 0,
                0.4}),
            s -> {
                s.weather = WeatherPreset.SCATTERED;
                s.weatherRate = 45;
                s.fogFalloff = 0.02;
                s.fires = false;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 11;
            }));
        segments.add(new Segment("storm", 34, List.of(
            shot("climb", 0, 17, new double[][] {
                {0, 30, 6, 40, -20, 6, -10}, {9, 120, 30, 70, -10, 6, -10}, {17, 215, 6, 85, -20, 10, -40}}),
            shot("ridge", 17, 17, new double[][] {
                {0, 232, 2.2, 92, 0, 60, -150}, {17, 226, 2.6, 60, -40, 70, -300}})),
            List.of(new double[] {0, 15.7, 0.0008, 0, 0, 0, 0.6, 0, 0.5}, new double[] {12, 15.9, 0.0012, 0, 0, 0, 1,
                0.3, 0.8}, new double[] {34, 16.3, 0.0015, 0, 0, 0, 1, 0.5, 1}),
            s -> {
                s.weather = WeatherPreset.STORM;
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
            List.of(new double[] {0, 17.75, 0.0012, 0, 0.35, 0.05, 0, 0.6, 1}, new double[] {14, 17.95, 0.0016, 0, 0.8,
                0.45, 0, 1, 1}, new double[] {36, 18.3, 0.002, 0, 1, 0.85, 0, 1, 1}),
            s -> {
                s.weather = WeatherPreset.OVERCAST;
                s.weatherRate = 3;
                s.fogFalloff = 0.02;
                s.fires = true;
                s.storm = false;
                s.fireworks = false;
                s.fStop = 8;
            }));
        segments.add(new Segment("night", 34, List.of(
            shot("fireworks", 0, 16, new double[][] {
                {0, -12, 1.7, 9, 18, 55, -12}, {16, -8, 1.7, 3, 22, 60, -8}}),
            shot("village at night", 16, 18, new double[][] {
                {0, -22, 3, 128, 15, 30, -10}, {18, -10, 3.5, 104, 10, 35, -10}})),
            List.of(new double[] {0, 21.0, 0.0006, 0, 0, 0.85, 0, 1, 1}, new double[] {34, 21.3, 0.0006, 0, 0, 0.85,
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

    /** A shot from keys of (t, x, h, z, tx, th, tz): heights above the ground. */
    private Shot shot(String name, double start, double duration, double[][] keys) {
        CameraSpline spline = new CameraSpline(true);
        for (double[] k : keys) {
            spline.key(k[0], k[1], ground.height(k[1], k[3]) + k[2], k[3], k[4], ground.height(k[4], k[6]) + k[5],
                k[6]);
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
