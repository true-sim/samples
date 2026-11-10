package com.truesim.samples.showcase.world;

import com.truesim.effects.terrain.TerrainSettings;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.engine.settings.EngineSettings;

/**
 * The ground of effects-terrain's real elevation data: heights and normals from the site's surface (the same function
 * the terrain's tiles are built from, between the finest tiles' vertices), so what the village places stands on what
 * is drawn. Its frame is the terrain's local frame: x east, y the altitude in metres, z south, the origin at the site's
 * reference point (Lauterbrunnen's church).
 */
public final class TerrainGround implements Ground {

    private volatile TerrainSurface surface;
    private final double spacing;

    public TerrainGround(TerrainSite site, EngineSettings settings) {
        surface = new TerrainSurface(site, TerrainSettings.surface(settings));
        spacing = settings.get(TerrainSettings.TILE_SIZE) / (double) settings.get(TerrainSettings.TILE_QUADS);
    }

    @Override
    public double height(double x, double z) {
        return surface.heightAt(x, z, spacing);
    }

    @Override
    public void normal(double x, double z, double[] out) {
        surface.normalAt(x, z, spacing, out);
    }

    /**
     * The ground with {@code modifiers} (the talus' gravel and roughness, the village's grading): what the terrain's
     * tiles draw, so what stands on it stands on what is drawn.
     */
    public void modify(com.truesim.effects.terrain.data.TerrainModifiers modifiers) {
        surface = surface.withModifiers(modifiers);
    }

    public TerrainSurface surface() {
        return surface;
    }
}
