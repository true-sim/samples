package com.truesim.samples.showcase.world;

import com.truesim.scene.effects.ParticleWorld;

/**
 * Where the ground is: every placement in the village asks it ({@link #height}), never a constant, so the temporary
 * valley ({@link ValleyGround}) can give way to effects-terrain's real elevation data by implementing this over its
 * height queries. Thread-safe and allocation-free (particles and animation ask it from worker threads).
 */
public interface Ground extends ParticleWorld {

    /** The ground's height at world ({@code x}, {@code z}), metres. */
    @Override
    double height(double x, double z);

    /** The ground's unit normal at ({@code x}, {@code z}) into {@code out} (x, y, z). */
    default void normal(double x, double z, double[] out) {
        double e = 0.25;
        double dx = height(x + e, z) - height(x - e, z);
        double dz = height(x, z + e) - height(x, z - e);
        double nx = -dx / (2 * e);
        double nz = -dz / (2 * e);
        double l = Math.sqrt(nx * nx + 1 + nz * nz);
        out[0] = nx / l;
        out[1] = 1 / l;
        out[2] = nz / l;
    }

    /** The lowest height under a footprint {@code w} by {@code d} metres about ({@code x}, {@code z}), turned by {@code yaw}. */
    default double lowest(double x, double z, double w, double d, double yaw) {
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        double lowest = Double.MAX_VALUE;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double a = w * i / 4;
                double b = d * j / 4;
                lowest = Math.min(lowest, height(x + a * c + b * s, z - a * s + b * c));
            }
        }
        return lowest;
    }

    /** Marches the height field: hits where a ray first goes below the ground. */
    @Override
    default boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
        Hit hit) {
        double step = 0.5;
        double previous = y - height(x, z);
        for (double t = step; t <= maxDistance; t += step) {
            double above = y + dy * t - height(x + dx * t, z + dz * t);
            if (above <= 0) {
                double at = t - step * above / (above - previous);
                hit.set(at, 0, 1, 0, 0);
                return true;
            }
            previous = above;
        }
        return false;
    }
}
