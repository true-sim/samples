package com.truesim.samples.showcase.world;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.effects.terrain.data.TerrainAssets;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * A development look at a terrain site without a GPU: a shaded relief map round the origin as a PNG, with a grid every
 * 500 m, and heights at points given; for planning where the village stands and where the tour's cameras go.
 * {@code TerrainMap <terrain cache> <site> <half width m> <png> [x,z ...]}.
 */
public final class TerrainMap {

    private TerrainMap() {
    }

    /** Loads {@code site} from the terrain cache through an asset cache of its own. */
    public static TerrainSite load(Path cache, String site) throws InterruptedException {
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache assets = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(assets);
            TerrainAssets.register(paths);
            TerrainAssets.mount(paths, cache);
            AssetRef<TerrainSite> ref = TerrainAssets.site(paths, site);
            assets.acquire(ref);
            while (assets.get(ref) == null) {
                if (assets.failure(ref) != null) {
                    throw new IllegalStateException("Could not load " + ref, assets.failure(ref));
                }
                Thread.sleep(5);
                tasks.drainCompleted();
            }
            return assets.get(ref);
        }
    }

    public static void main(String[] args) throws Exception {
        TerrainSite site = load(Path.of(args[0]), args[1]);
        TerrainSurface surface = new TerrainSurface(site, com.truesim.effects.terrain.TerrainSettings.surface(new com.truesim.engine.settings.EngineSettings()));
        double half = Double.parseDouble(args[2]);
        int size = 1024;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double step = 2 * half / size;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double[] h = new double[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                h[j * size + i] = surface.height(-half + i * step, -half + j * step, step);
                min = Math.min(min, h[j * size + i]);
                max = Math.max(max, h[j * size + i]);
            }
        }
        for (int j = 1; j < size - 1; j++) {
            for (int i = 1; i < size - 1; i++) {
                double dx = (h[j * size + i + 1] - h[j * size + i - 1]) / (2 * step);
                double dz = (h[(j + 1) * size + i] - h[(j - 1) * size + i]) / (2 * step);
                double shade = Math.clamp(0.6 + 0.4 * (-dx * 0.6 - dz * 0.6 + 1) / Math.sqrt(dx * dx + dz * dz + 1), 0,
                    1);
                double t = (h[j * size + i] - min) / (max - min);
                int r = (int) (255 * shade * (0.35 + 0.65 * t));
                int g = (int) (255 * shade * (0.5 + 0.4 * t));
                int b = (int) (255 * shade * (0.3 + 0.7 * t));
                double x = -half + i * step;
                double z = -half + j * step;
                double grid = half > 1500 ? 500 : 100;
                if (Math.abs(x % grid) < step || Math.abs(z % grid) < step) {
                    r = g = b = 40;
                }
                if (Math.abs(x) < 2 * step || Math.abs(z) < 2 * step) {
                    r = 255;
                    g = b = 0;
                }
                image.setRGB(i, j, r << 16 | g << 8 | b);
            }
        }
        javax.imageio.ImageIO.write(image, "png", Path.of(args[3]).toFile());
        System.out.printf("heights %.0f to %.0f m; origin %.1f m%n", min, max, surface.height(0, 0, 1));
        for (int a = 4; a < args.length; a++) {
            String[] p = args[a].split(",");
            double x = Double.parseDouble(p[0]);
            double z = Double.parseDouble(p[1]);
            double[] n = new double[3];
            surface.normalAt(x, z, 2, n);
            System.out.printf("  %s: %.1f m, slope %.0f deg%n", args[a], surface.heightAt(x, z, 2),
                Math.toDegrees(Math.acos(n[1])));
        }
    }
}
