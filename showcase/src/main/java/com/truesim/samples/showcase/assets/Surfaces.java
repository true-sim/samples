package com.truesim.samples.showcase.assets;

import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;

/**
 * The showcase's materials: Poly Haven's scanned surfaces (base color, the packed occlusion-roughness-metalness map
 * and an OpenGL normal map, by asset path) and a few plain ones (window frames, glass, bulbs). Each is a material
 * asset made from its data ({@code data.ref()}: equal data is one material). Without Poly Haven's cache every
 * scanned surface falls back to a plain one of its average colour, so the village still draws.
 *
 * <p>Every texture's real size is its own: {@link #size} gives the metres one repeat covers (Poly Haven's dimensions),
 * which the meshes' texture coordinates follow.
 */
public final class Surfaces {

    /** A scanned surface: asset, resolution, metres a repeat covers, fallback colour, roughness scale. */
    public record Scan(String asset, String resolution, double size, float r, float g, float b, float roughness) {
    }

    public static final Scan COBBLES = new Scan("cobblestone_floor_03", "4k", 2.4, 0.42f, 0.4f, 0.37f, 1);
    public static final Scan MEADOW = new Scan("leafy_grass", "4k", 2.0, 0.22f, 0.3f, 0.1f, 1);
    public static final Scan STONE_WALL = new Scan("old_stone_wall", "4k", 2.0, 0.42f, 0.38f, 0.33f, 1);
    public static final Scan PLASTER = new Scan("white_plaster_02", "4k", 3.0, 0.75f, 0.73f, 0.68f, 1);
    public static final Scan PLANKS = new Scan("weathered_planks", "4k", 2.0, 0.25f, 0.2f, 0.16f, 1);
    public static final Scan SLATES = new Scan("roof_slates_02", "4k", 3.0, 0.3f, 0.3f, 0.3f, 1);
    public static final Scan PARQUET = new Scan("herringbone_parquet", "4k", 3.4, 0.4f, 0.25f, 0.13f, 0.45f);
    public static final Scan PAINTED_PLASTER = new Scan("painted_plaster_wall", "2k", 2.0, 0.8f, 0.78f, 0.72f, 1);
    public static final Scan TILES = new Scan("roof_09", "2k", 4.0, 0.38f, 0.22f, 0.15f, 1);
    public static final Scan DARK_SLATES = new Scan("roof_slates_03", "2k", 3.0, 0.22f, 0.23f, 0.25f, 1);
    public static final Scan WORN_WOOD = new Scan("wood_cabinet_worn_long", "2k", 2.0, 0.35f, 0.18f, 0.1f, 1);
    public static final Scan TRAIL = new Scan("rocky_trail", "2k", 2.0, 0.4f, 0.35f, 0.3f, 1);
    public static final Scan FOREST_FLOOR = new Scan("forrest_ground_01", "2k", 2.0, 0.25f, 0.27f, 0.15f, 1);
    public static final Scan MOSSY_ROCK = new Scan("mossy_rock", "2k", 3.0, 0.35f, 0.36f, 0.3f, 1);
    public static final Scan FLOORBOARDS = new Scan("plank_flooring_02", "2k", 2.0, 0.45f, 0.3f, 0.18f, 0.7f);
    public static final Scan GRANITE = new Scan("granite_wall", "2k", 2.0, 0.5f, 0.47f, 0.43f, 1);

    private final ShowcaseAssets assets;

    public Surfaces(ShowcaseAssets assets) {
        this.assets = assets;
    }

    /** The metres one repeat of {@code scan} covers. */
    public static double size(Scan scan) {
        return scan.size();
    }

    /** The scanned surface as a material (or its plain colour without Poly Haven's cache). */
    public AssetRef<MaterialData> scan(Scan scan) {
        return scan(scan, 1, 1, 1);
    }

    /** The scanned surface, its base colour tinted by ({@code r}, {@code g}, {@code b}). */
    public AssetRef<MaterialData> scan(Scan scan, float r, float g, float b) {
        if (!assets.polyHaven()) {
            return plain(scan.r() * r, scan.g() * g, scan.b() * b, 0.85f * scan.roughness());
        }
        String base = ShowcaseAssets.MOUNT + "://" + scan.asset() + "/" + scan.asset() + "_";
        String suffix = "_" + scan.resolution() + ".jpg";
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", r, g, b, 1)
            .texture("baseColorTexture", texture(base + "diff" + suffix))
            .texture("ormTexture", texture(base + "arm" + suffix + "#linear"))
            .texture("normalTexture", texture(base + "nor_gl" + suffix + "#linear"))
            .set("roughness", scan.roughness()).set("metallic", 1)
            .build().ref();
    }

    private AssetRef<TextureData> texture(String path) {
        return assets.ref(RenderAssets.TEXTURE, path);
    }

    /** A plain dielectric of a linear colour and roughness. */
    public static AssetRef<MaterialData> plain(float r, float g, float b, float roughness) {
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", r, g, b, 1)
            .set("roughness", roughness).set("metallic", 0).build().ref();
    }

    /** A plain metal. */
    public static AssetRef<MaterialData> metal(float r, float g, float b, float roughness) {
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", r, g, b, 1)
            .set("roughness", roughness).set("metallic", 1).build().ref();
    }

    /** Window glass: clear, slightly green, transmissive, two-sided. */
    public static AssetRef<MaterialData> glass() {
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", 0.92f, 0.97f, 0.95f, 1)
            .set("roughness", 0.02f).set("metallic", 0f)
            .feature(TransmissionParameters.NAME, f -> f.set("transmissionFactor", 1f)).doubleSided(true).build()
            .ref();
    }

    /**
     * A glowing surface: a lamp's bulb, a lit window's curtain. {@code emissive} is the luminance in cd/m² at full
     * strength; the day scales it into scene units each frame ({@code SceneResources.animate}).
     */
    public static AssetRef<MaterialData> glowing(float r, float g, float b, float roughness) {
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", r, g, b, 1)
            .set("roughness", roughness).set("metallic", 0).set("emissive", 0, 0, 0).build().ref();
    }
}
