package com.truesim.samples.showcase.flora;

import com.truesim.common.math.Maths;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.Recipe;
import com.truesim.common.procedural.tree.Foliage;
import com.truesim.common.procedural.tree.TreeSpecies;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The materials of common-procedural's trees, shrubs and flowers, which its recipes name by path: a mount of their own
 * ({@code flora://}) serving materials made here in code, through an importer of {@code .mat} paths. Bark is Poly
 * Haven's (pine bark on the conifers, brown bark on the broadleaves, willow bark by the water); the leaf and needle
 * atlases are drawn here (the generators lay cards out on a 2 x 2 atlas of the seasons, which no scan provides), masked
 * and two-sided; flowers, fruits, cones and the herbs' foliage take each recipe's palette ({@code #palette}). The
 * vegetation's sync turns them into its plant type when it spawns a model.
 */
public final class FloraMaterials {

    /** The mount. */
    public static final String MOUNT = "flora";

    private static final int TILE = 256;

    private final Map<String, MaterialData> materials = new ConcurrentHashMap<>();
    private final ShowcaseAssets assets;
    private final ProceduralAssets procedural;
    private final Generator<Recipe, TextureData> palettes;

    public FloraMaterials(ShowcaseAssets assets, ProceduralAssets procedural) {
        this.assets = assets;
        this.procedural = procedural;
        palettes = (recipe, seed) -> procedural.palette(recipe);
        PathAssets paths = assets.paths();
        paths.mounts().add(MOUNT, path -> materials.containsKey(path) ? new ByteArrayInputStream(new byte[0]) : null);
        paths.importers().add(RenderAssets.MATERIAL, "mat", input -> {
            MaterialData m = materials.get(input.path().path());
            if (m == null) {
                throw new IllegalArgumentException("No flora material " + input.path());
            }
            return m;
        });
    }

    /** The material slots of a tree recipe of {@code species} on {@code day}: bark, leaves, attachments. */
    public java.util.List<String> tree(TreeSpecies species, Recipe palette) {
        String bark = put("bark/" + species.name() + ".mat", bark(species));
        String leaves = put("leaves/" + species.name() + ".mat", leaves(species));
        String attachments = put("attachments/" + palette.preset() + "-" + palette.seed() + ".mat",
            paletted(palette, false));
        return java.util.List.of(bark, leaves, attachments);
    }

    /** The material slots of a plant recipe: foliage and flowers, both its palette. */
    public java.util.List<String> plant(Recipe palette) {
        String foliage = put("foliage/" + palette.preset() + "-" + palette.seed() + ".mat", paletted(palette, true));
        return java.util.List.of(foliage, foliage);
    }

    private String put(String path, MaterialData material) {
        materials.putIfAbsent(path, material);
        return MOUNT + "://" + path;
    }

    /** The scan of bark for {@code species}. */
    private MaterialData bark(TreeSpecies species) {
        String scan = switch (species.name()) {
            case "spruce", "fir", "larch", "pine", "stone-pine", "mugo" -> "pine_bark";
            case "willow", "alder", "green-alder", "birch" -> "bark_willow";
            default -> "bark_brown_02";
        };
        float[] tint = switch (species.name()) {
            case "fir", "beech" -> new float[] {0.85f, 0.85f, 0.85f};
            case "birch" -> new float[] {1.6f, 1.6f, 1.55f};
            default -> new float[] {1, 1, 1};
        };
        MaterialData.Builder b = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", tint[0], tint[1], tint[2], 1).set("roughness", 1f).set("metallic", 1f);
        if (assets.polyHaven()) {
            String base = ShowcaseAssets.MOUNT + "://" + scan + "/" + scan + "_";
            b.texture("baseColorTexture", texture(base + "diff_2k.jpg"))
                .texture("ormTexture", texture(base + "arm_2k.jpg#linear"))
                .texture("normalTexture", texture(base + "nor_gl_2k.jpg#linear"));
        } else {
            b.set("baseColor", 0.18f, 0.13f, 0.1f, 1).set("metallic", 0f).set("roughness", 0.9f);
        }
        return b.build();
    }

    private AssetRef<TextureData> texture(String path) {
        return assets.ref(RenderAssets.TEXTURE, path);
    }

    /** Masked, two-sided leaf or needle cards on the species' atlas. */
    private static MaterialData leaves(TreeSpecies species) {
        AssetRef<TextureData> atlas = AssetRef.generated(RenderAssets.TEXTURE, ATLASES, species.name(), 1);
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", 1, 1, 1, 1)
            .texture("baseColorTexture", atlas).set("roughness", 0.6f).set("metallic", 0f)
            .alpha(MaterialData.AlphaMode.MASK, 0.5f).doubleSided(true).build();
    }

    /** A recipe's palette as its parts' base color. */
    private MaterialData paletted(Recipe recipe, boolean twoSided) {
        AssetRef<TextureData> palette = AssetRef.generated(RenderAssets.TEXTURE, palettes, recipe, recipe.seed());
        return MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", 1, 1, 1, 1)
            .texture("baseColorTexture", palette).set("roughness", 0.55f).set("metallic", 0f).doubleSided(twoSided)
            .build();
    }

    /** The procedural assets the recipes generate through. */
    public ProceduralAssets procedural() {
        return procedural;
    }

    // ------------------------------------------------------------------------------------------- the leaf atlases

    /** The leaf atlases, by species name. */
    static final Generator<String, TextureData> ATLASES = (name, seed) -> atlas(species(name));

    static TreeSpecies species(String name) {
        for (TreeSpecies s : TreeSpecies.PRESETS) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No tree preset " + name);
    }

    /**
     * The species' needle or leaf colors (sRGB) in spring, summer, autumn and winter. The showcase's day is late
     * September: the autumn tile is the season's start, mostly green with the first leaves turning ({@link #turned}).
     */
    private static int[] seasonColors(String species) {
        return switch (species) {
            case "spruce", "mugo" -> new int[] {0x3d6b2a, 0x22421b, 0x22421b, 0x1d3a18};
            case "fir" -> new int[] {0x427035, 0x274c24, 0x274c24, 0x213f1d};
            case "pine" -> new int[] {0x4f7a45, 0x395c38, 0x395c38, 0x33533a};
            case "stone-pine" -> new int[] {0x3e6a3a, 0x2a4a2c, 0x2a4a2c, 0x26422a};
            case "larch" -> new int[] {0x8cbf4a, 0x5f8f35, 0x7f9a3a, 0x5f8f35};
            case "beech" -> new int[] {0x8fc24d, 0x4d7a2a, 0x627f2c, 0x4d7a2a};
            case "birch" -> new int[] {0x9ccc58, 0x5d8f30, 0x8a9a36, 0x5d8f30};
            case "alpenrose", "lingonberry" -> new int[] {0x3e6a2c, 0x2c5222, 0x2c5222, 0x2a4a22};
            case "alder", "green-alder" -> new int[] {0x6a9a3e, 0x2e5420, 0x34561f, 0x2e5420};
            case "willow" -> new int[] {0xa0c070, 0x7a9a5a, 0x8a9a52, 0x7a9a5a};
            default -> new int[] {0x86b84a, 0x4c7c2b, 0x5a7f2c, 0x4c7c2b};
        };
    }

    /** The share of a deciduous species' leaves turned yellow, orange or red by late September. */
    private static double turned(String species) {
        return switch (species) {
            case "rowan", "dog-rose", "blueberry", "birch", "ash" -> 0.35;
            case "beech", "hazel", "elder", "apple", "oak" -> 0.15;
            case "larch" -> 0.1;
            default -> 0;
        };
    }

    private static int turnedColor(String species, Random random) {
        return switch (species) {
            case "rowan", "dog-rose", "blueberry" -> random.nextBoolean() ? 0xb8402c : 0xc7682a;
            case "larch" -> 0xc99a35;
            case "beech", "oak" -> random.nextBoolean() ? 0xb8742a : 0xa88a30;
            default -> random.nextBoolean() ? 0xd8b23a : 0xc2a03a;
        };
    }

    /** The species' atlas: its foliage's columns and rows of tiles, 256 texels each, alpha 0 or 1. */
    static TextureData atlas(TreeSpecies species) {
        Foliage foliage = species.foliage();
        int columns = foliage.atlasColumns();
        int rows = foliage.atlasRows();
        BufferedImage image = new BufferedImage(columns * TILE, rows * TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        int[] colors = seasonColors(species.name());
        int[] tiles = {foliage.springTile(), foliage.summerTile(), foliage.autumnTile(), foliage.winterTile()};
        for (int season = 0; season < 4; season++) {
            int tile = tiles[season];
            AffineTransform saved = g.getTransform();
            // v = 0 (where the card is attached) at the tile's top in texture space
            g.translate((tile % columns) * TILE, (tile / columns) * TILE);
            Random random = new Random(17L + tile * 31L + species.name().hashCode());
            double turned = season == 2 ? turned(species.name()) : 0;
            switch (foliage.kind()) {
                case NEEDLE_SPRAY -> spray(g, colors[season], random);
                case NEEDLE_TUFT -> tuft(g, colors[season], random, turned, species.name());
                case LEAF_CLUSTER -> cluster(g, colors[season], random, turned, species.name());
            }
            g.setTransform(saved);
        }
        g.dispose();
        int w = image.getWidth();
        int h = image.getHeight();
        MemorySegment texels = Arena.ofAuto().allocate(4L * w * h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = image.getRGB(x, y);
                long o = 4L * (y * (long) w + x);
                texels.set(ValueLayout.JAVA_BYTE, o, (byte) (p >> 16));
                texels.set(ValueLayout.JAVA_BYTE, o + 1, (byte) (p >> 8));
                texels.set(ValueLayout.JAVA_BYTE, o + 2, (byte) p);
                texels.set(ValueLayout.JAVA_BYTE, o + 3, (byte) ((p >>> 24) >= 128 ? 255 : 0));
            }
        }
        return new TextureData(w, h, TextureData.ColorSpace.SRGB, texels);
    }

    private static Color shade(int rgb, double factor) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * factor);
        int gg = (int) Math.min(255, ((rgb >> 8) & 255) * factor);
        int b = (int) Math.min(255, (rgb & 255) * factor);
        return new Color(r, gg, b);
    }

    /** A shoot along the tile, needles to both sides: spruce, fir. */
    private static void spray(Graphics2D g, int color, Random random) {
        g.setStroke(new BasicStroke(4));
        g.setColor(shade(0x4a3020, 1));
        g.draw(new Line2D.Double(TILE / 2.0, 4, TILE / 2.0, TILE - 4));
        for (int i = 0; i < 90; i++) {
            double y = 8 + (TILE - 24) * i / 90.0;
            double width = (TILE / 2.0 - 10) * (0.55 + 0.45 * Maths.sin(Math.PI * Math.min(1, y / (TILE * 0.9))));
            for (int side = -1; side <= 1; side += 2) {
                g.setStroke(new BasicStroke(5));
                g.setColor(shade(color, 0.75 + 0.5 * random.nextDouble()));
                double length = width * (0.7 + 0.3 * random.nextDouble());
                g.draw(new Line2D.Double(TILE / 2.0, y, TILE / 2.0 + side * length, y + 0.45 * length));
            }
        }
    }

    /** Needles fanning out from where the tuft is attached: larch, pine. */
    private static void tuft(Graphics2D g, int color, Random random, double turned, String species) {
        g.setStroke(new BasicStroke(4.5f));
        for (int i = 0; i < 110; i++) {
            double angle = (random.nextDouble() - 0.5) * 1.6;
            double length = TILE * (0.55 + 0.4 * random.nextDouble());
            double x0 = TILE / 2.0 + (random.nextDouble() - 0.5) * 30;
            int c = random.nextDouble() < turned ? turnedColor(species, random) : color;
            g.setColor(shade(c, 0.75 + 0.5 * random.nextDouble()));
            g.draw(new Line2D.Double(x0, 6, x0 + Maths.sin(angle) * length, 6 + Maths.cos(angle) * length));
        }
    }

    /** Leaves on a twig: ellipses fanning out along it, some turned. */
    private static void cluster(Graphics2D g, int color, Random random, double turned, String species) {
        g.setStroke(new BasicStroke(3));
        g.setColor(shade(0x4a3020, 1));
        g.draw(new Line2D.Double(TILE / 2.0, 2, TILE / 2.0, TILE * 0.8));
        for (int i = 0; i < 22; i++) {
            double y = 16 + (TILE - 70) * random.nextDouble();
            double angle = (random.nextDouble() - 0.5) * 2.6;
            double length = 64 + 44 * random.nextDouble();
            AffineTransform saved = g.getTransform();
            g.translate(TILE / 2.0, y);
            g.rotate(-angle);
            int c = random.nextDouble() < turned ? turnedColor(species, random) : color;
            g.setColor(shade(c, 0.75 + 0.5 * random.nextDouble()));
            g.fill(new Ellipse2D.Double(-length * 0.22, 0, length * 0.44, length));
            g.setColor(shade(c, 0.55));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Line2D.Double(0, 2, 0, length * 0.9)); // the midrib
            g.setTransform(saved);
        }
    }
}
