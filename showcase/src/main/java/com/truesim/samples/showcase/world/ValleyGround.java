package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;

/**
 * The temporary ground until effects-terrain's real elevation data lands: an Alpine valley made from functions. The
 * valley runs north-south (the world's -z is north), its floor rising gently northwards, a stream bed meandering down
 * it; meadows climb its flanks east and west into forested slopes, then ridges near 1.2 km out, and beyond them
 * mountains to 2.8 km above the floor for the horizon. The village's square at the origin lies level, its streets
 * nearly so; small undulations elsewhere (a metre over 30, a few over 150).
 */
public final class ValleyGround implements Ground {

    /** The village's level ground: within this many metres of the origin the micro relief fades out. */
    private static final double VILLAGE = 70;

    @Override
    public double height(double x, double z) {
        double axis = meander(z);
        double across = Math.abs(x - axis);
        double floor = -0.012 * z; // rising northwards (-z)
        // The flanks: level for 50 m, then a meadow rising, steepening into forest towards the ridges
        double flank = across <= 50 ? 0 : 520 * (1 - Maths.exp(-sq((across - 50) / 520))) + 0.02 * sq(across - 50) / 10;
        // The mountains beyond the ridges, ridged noise growing with distance
        double far = Math.max(Math.max(0, across - 900) / 2200, Math.max(0, Math.abs(z) - 6500) / 3500);
        double mountains = 2600 * smooth(Math.min(far, 1)) * (0.55 + 0.45 * ridged(x * 0.00045, z * 0.00045, 5));
        // Relief: gentle near, larger away from the village
        double r = Math.sqrt(x * x + z * z);
        double fade = smooth(Math.clamp((r - VILLAGE * 0.6) / VILLAGE, 0, 1));
        double relief = fade * (0.7 * fbm(x / 30, z / 30, 3) + 3.0 * fbm(x / 150 + 13.1, z / 150 - 7.7, 3)
            + 14 * smooth(Math.clamp(across / 300, 0, 1)) * fbm(x / 400 - 3.3, z / 400 + 1.9, 4));
        // A stream bed down the valley, east of the village
        double stream = Math.abs(x - axis - 95);
        double bed = -1.6 * Maths.exp(-sq(stream / 6));
        return floor + flank + mountains + relief + bed;
    }

    /** Where the valley's axis lies across at {@code z}: it bends a little. */
    private static double meander(double z) {
        return 40 * Maths.sin(z / 900) + 25 * Maths.sin(z / 370 + 1.3) - 25 * Maths.sin(1.3);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Fractal sum of gradient noise, about -1 to 1. */
    static double fbm(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 0.5;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * noise(x, z);
            x = 2.03 * x + 17.3;
            z = 2.03 * z - 9.1;
            amplitude *= 0.5;
        }
        return 2 * sum;
    }

    /** Ridged fractal noise, 0 to 1: sharp crests. */
    static double ridged(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 0.5;
        double weight = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise(x, z));
            n *= n * weight;
            weight = Math.clamp(n * 2, 0, 1);
            sum += amplitude * n;
            x = 2.1 * x + 5.7;
            z = 2.1 * z + 3.1;
            amplitude *= 0.5;
        }
        return sum;
    }

    /** Gradient noise in two dimensions, about -1 to 1. */
    static double noise(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        double u = fx * fx * fx * (fx * (fx * 6 - 15) + 10);
        double v = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        double a = grad(ix, iz, fx, fz);
        double b = grad(ix + 1, iz, fx - 1, fz);
        double c = grad(ix, iz + 1, fx, fz - 1);
        double d = grad(ix + 1, iz + 1, fx - 1, fz - 1);
        return 1.4 * (a + u * (b - a) + v * (c - a) + u * v * (a - b - c + d));
    }

    private static double grad(int ix, int iz, double fx, double fz) {
        int h = hash(ix, iz);
        double angle = (h & 0xffff) / 65536.0 * 2 * Math.PI;
        return Maths.cos(angle) * fx + Maths.sin(angle) * fz;
    }

    static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }
}
