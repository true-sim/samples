package com.truesim.samples.showcase.render;

import com.truesim.scene.effects.ParticleWorld;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The particles' ground heights cached in tiles of 64 m at a metre's spacing, bilinear between: the particles' height
 * field takes a million heights each time it follows the camera, and the terrain's own lookups (geodesy, the DEMs'
 * blend, the detail noise) cost microseconds each. Tiles are made as first asked and kept (a few thousand at most over
 * the tour: some 17 kB each). Rays go to the ground as given.
 */
final class CachedHeights implements ParticleWorld {

    private static final int TILE = 64;
    private static final int SIDE = TILE + 1;

    private final ParticleWorld ground;
    private final ConcurrentHashMap<Long, float[]> tiles = new ConcurrentHashMap<>();

    CachedHeights(ParticleWorld ground) {
        this.ground = ground;
    }

    @Override
    public double height(double x, double z) {
        double fx = Math.floor(x);
        double fz = Math.floor(z);
        int ix = (int) fx;
        int iz = (int) fz;
        int tx = Math.floorDiv(ix, TILE);
        int tz = Math.floorDiv(iz, TILE);
        float[] t = tile(tx, tz);
        int lx = ix - tx * TILE;
        int lz = iz - tz * TILE;
        double u = x - fx;
        double v = z - fz;
        double h00 = t[lz * SIDE + lx];
        double h10 = t[lz * SIDE + lx + 1];
        double h01 = t[(lz + 1) * SIDE + lx];
        double h11 = t[(lz + 1) * SIDE + lx + 1];
        return (h00 * (1 - u) + h10 * u) * (1 - v) + (h01 * (1 - u) + h11 * u) * v;
    }

    private float[] tile(int tx, int tz) {
        long key = ((long) tx << 32) ^ (tz & 0xffffffffL);
        float[] t = tiles.get(key);
        if (t == null) {
            t = new float[SIDE * SIDE];
            for (int j = 0; j < SIDE; j++) {
                for (int i = 0; i < SIDE; i++) {
                    t[j * SIDE + i] = (float) ground.height(tx * TILE + i, tz * TILE + j);
                }
            }
            float[] was = tiles.putIfAbsent(key, t);
            if (was != null) {
                t = was;
            }
        }
        return t;
    }

    @Override
    public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
        Hit hit) {
        return ground.raycast(x, y, z, dx, dy, dz, maxDistance, hit);
    }
}
