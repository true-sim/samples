package com.truesim.samples.showcase.flora;

import com.truesim.common.math.Vec3;
import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.Recipe;
import com.truesim.common.procedural.ecology.Ecology;
import com.truesim.common.procedural.ecology.EcologyTile;
import com.truesim.common.procedural.ecology.Instances;
import com.truesim.common.procedural.ecology.Layer;
import com.truesim.common.procedural.ecology.Species;
import com.truesim.common.procedural.ecology.rocks.RockField;
import com.truesim.common.procedural.ecology.rocks.RockInstances;
import com.truesim.common.procedural.assets.RockAssets;
import com.truesim.common.procedural.tree.Season;
import com.truesim.common.procedural.tree.TreeSpecies;
import com.truesim.effects.vegetation.GroundMap;
import com.truesim.effects.vegetation.VegetationHooks;
import com.truesim.effects.vegetation.materials.VegetationFieldParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.effects.vegetation.scene.GrassField;
import com.truesim.effects.vegetation.scene.PlantPlacement;
import com.truesim.effects.vegetation.scene.PlantPlacer;
import com.truesim.effects.vegetation.scene.PlantScatter;
import com.truesim.effects.vegetation.scene.VegetationAssets;
import com.truesim.effects.vegetation.scene.VegetationSync;
import com.truesim.engine.render.Camera;
import com.truesim.samples.showcase.ShowcaseSettings;
import com.truesim.samples.showcase.assets.NamedMaterials;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.Ground;
import com.truesim.samples.showcase.world.VillageLayout;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.assets.AssetRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The valley's vegetation, all of it through the scene: effects-vegetation's GPU grass over the meadows (a
 * {@link GrassField} standing on a {@link GroundMap} of the valley floor round the village, cleared from what the
 * village's plan paves or builds on), and common-procedural's trees, shrubs, dwarf shrubs and flowers where its ecology
 * puts them on Lauterbrunnen's terrain ({@link Ecology} over effects-terrain's surface, its meadows kept open on the
 * valley floor): one {@link PlantScatter} per species and variant, its plants the ecology's instances
 * ({@link PlantPlacer#instances}), swaying in the day's wind ({@link VegetationHooks}). The trees and flowers are
 * generated for the showcase's day, late September ({@link #DAY}): rowan berries scarlet, apples ripe, the first
 * leaves turning, larches still green.
 *
 * <p>Trees and shrubs stand on {@link #TREES} metres a side round the village, dwarf shrubs and herbs on
 * {@link #HERBS} a side, all of them always in the scene.
 */
public final class Flora implements AutoCloseable {

    /** The day of the year the plants are generated for: 26 September, the showcase's day. */
    public static final double DAY = 268;
    /** Metres a side of the region the trees stand in, about the village. */
    public static final double TREES = 2048;
    /** Of the shrubs' and herbs'. */
    public static final double HERBS = 512;
    /** Variants of each species. */
    private static final int VARIANTS = 3;
    /** Metres a side of the grass's ground map, and its texels. */
    private static final double MAP = 768;
    private static final int MAP_TEXELS = 1536;

    private final ShowcaseRenderer renderer;
    private final VegetationSync sync;
    private final VegetationHooks hooks;
    private final GroundMap map;
    private final ProceduralAssets procedural;
    private final FloraMaterials materials;
    private final Map<PlantScatter, List<PlantPlacement.Plant>> placements = new IdentityHashMap<>();
    private final List<AssetRef<ModelData>> models = new ArrayList<>();
    private int plants;
    private RockInstances rocks;
    private Map<Integer, AssetRef<ModelData>> rockModels = Map.of();
    private com.truesim.common.procedural.settlement.Clearing rockClearing;
    private boolean rocksPlaced;
    private int trees;

    /**
     * The vegetation of {@code layout}'s village on {@code ground} as {@code ecology} has it grow, off what the
     * village's plan clears; generated meshes cached in {@code cache}, materials named through {@code named}.
     */
    public Flora(ShowcaseRenderer renderer, Ground ground, VillageLayout layout, Ecology ecology, RockField rocks,
        NamedMaterials named, Path cache) {
        this.renderer = renderer;
        var assets = renderer.assets();
        procedural = new ProceduralAssets(assets.paths(), new GenerationCache(cache)).install();
        materials = new FloraMaterials(assets, named, procedural);
        double ox = layout.originX();
        double oz = layout.originZ();
        var plan = layout.settlement().clearing();
        Clearing grassGround = new Clearing(ground, plan, false);
        double texel = MAP / MAP_TEXELS;
        grassGround.rocks(rocks.footprint(ox - MAP / 2, oz - MAP / 2, texel, MAP_TEXELS, MAP_TEXELS), ox - MAP / 2,
            oz - MAP / 2, texel, MAP_TEXELS);
        map = GroundMap.upload(assets.resources().textures(), grassGround, ox - MAP / 2, oz - MAP / 2, MAP,
            MAP_TEXELS);
        grassGround.map(map);
        sync = new VegetationSync(renderer.scene(), assets.cache(), renderer.settings()).ground(grassGround)
            .placer(PlantPlacer.instances(placements::get));
        hooks = new VegetationHooks(renderer.device(), renderer.gpuScene()).ground(map);
        var scene = renderer.scene();
        // the meadows: grass over the map, late September's (green, a quarter of the blades' tips dry)
        long field = scene.create();
        scene.setTranslation(field, ox, ground.height(ox, oz), oz);
        MaterialData.Values grass = MaterialData.builder(VegetationAssets.GRASS_TYPE)
            .set("rootColor", 0.022f, 0.06f, 0.012f).set("tipColor", 0.15f, 0.26f, 0.055f)
            .set("dryColor", 0.38f, 0.32f, 0.13f).set("dryness", 0.24f).set("colorVariation", 0.4f)
            .set("height", 0.4f).set("width", 0.018f).build().values();
        scene.components().set(field, GrassField.TYPE, new GrassField(MAP / 2 - 2, MAP / 2 - 2, grass, 0.4f, true));
        long start = System.nanoTime();
        if (java.util.List.of(renderer.settings().get(ShowcaseSettings.SKIP).split(",")).contains("plants")) {
            System.out.println("Flora: the grass only (showcase.skip=plants)");
        } else {
            // trees and shrubs off the plan's roads, square, buildings and gardens, and off the village's meadows
            // within some 100 m of the square (the tour's paths); the flowers off what is paved or built
            Clearing trees = new Clearing(ground, plan, true);
            double cell = ecology.parameters().cell();
            place(ecology, (x, z) -> trees.density(x, z) * open(x - ox, z - oz), snap(ox - TREES / 2, cell),
                snap(oz - TREES / 2, cell), TREES, Set.of(Layer.CANOPY, Layer.SHRUB));
            place(ecology, grassGround::density, snap(ox - HERBS / 2, cell), snap(oz - HERBS / 2, cell), HERBS,
                Set.of(Layer.DWARF_SHRUB, Layer.HERB));
            // the rocks over the trees' region, a model per variant (common-procedural's), off the roads and plots
            this.rocks = rocks.region(snap(ox - TREES / 2, cell), snap(oz - TREES / 2, cell), TREES, TREES)
                .instances();
            String limestone = named.name("rocks/limestone.mat", rockMaterial(assets));
            rockModels = RockAssets.models(procedural, this.rocks, limestone, "");
            models.addAll(rockModels.values());
            this.rockClearing = plan;
        }
        System.out.printf("Flora: %d plants (%d trees) of %d models placed in %.1f s%n", plants, trees, models.size(),
            (System.nanoTime() - start) / 1e9);
    }

    /** Limestone for the rocks: Poly Haven's rock face, its albedo tinted by each rock's vertex colour. */
    private static MaterialData rockMaterial(com.truesim.samples.showcase.assets.ShowcaseAssets assets) {
        var scan = com.truesim.samples.showcase.assets.Surfaces.LIMESTONE;
        MaterialData.Builder b = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", 0.82f, 0.85f, 0.9f, 1).set("roughness", 1f).set("metallic", 1f);
        if (!assets.polyHaven()) {
            return b.set("baseColor", 0.5f, 0.5f, 0.48f, 1).set("metallic", 0f).set("roughness", 0.9f).build();
        }
        String base = com.truesim.samples.showcase.assets.ShowcaseAssets.MOUNT + "://" + scan.asset() + "/"
            + scan.asset() + "_";
        String suffix = "_" + scan.resolution() + ".jpg";
        return b.texture("baseColorTexture", assets.ref(com.truesim.scene.render.RenderAssets.TEXTURE,
                base + "diff" + suffix))
            .texture("ormTexture", assets.ref(com.truesim.scene.render.RenderAssets.TEXTURE, base + "arm" + suffix
                + "#linear"))
            .texture("normalTexture", assets.ref(com.truesim.scene.render.RenderAssets.TEXTURE, base + "nor_gl"
                + suffix + "#linear")).build();
    }

    /**
     * Places the rocks once their models have loaded ({@link #ready}): a node per rock, its variant's model at its
     * place, turn and scale; none on the village's roads, square, buildings and gardens.
     */
    public void placeRocks() {
        if (rocks == null || rocksPlaced) {
            return;
        }
        var scene = renderer.scene();
        var cache = renderer.assets().cache();
        int placed = 0;
        for (int i = 0; i < rocks.count(); i++) {
            if (rockClearing.trees(rocks.x(i), rocks.z(i)) < 0.5) {
                continue;
            }
            ModelData data = cache.get(rockModels.get(rocks.variantIndex(i)));
            if (data == null) {
                continue;
            }
            long node = scene.create();
            scene.setTranslation(node, rocks.x(i), rocks.y(i), rocks.z(i));
            scene.setRotation(node, rocks.qx(i), rocks.qy(i), rocks.qz(i), rocks.qw(i));
            float s = rocks.scale(i);
            scene.setScale(node, s, s, s);
            data.spawn(scene, node);
            placed++;
        }
        rocksPlaced = true;
        System.out.printf("Rocks: %d of %d in %d variants%n", placed, rocks.count(), rockModels.size());
    }

    /** 0 within 100 m of the square, 1 from 130 m. */
    private static double open(double dx, double dz) {
        double t = Math.clamp((Math.sqrt(dx * dx + dz * dz) - 100) / 30, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double snap(double v, double cell) {
        return Math.floor(v / cell) * cell;
    }

    /** The plants of {@code layers} in the region where {@code keep} is half or more, a scatter per species. */
    private void place(Ecology ecology, java.util.function.DoubleBinaryOperator keep, double x0, double z0,
        double size, Set<Layer> layers) {
        EcologyTile tile = ecology.region(x0, z0, size, size);
        Instances instances = tile.instances();
        List<Species> species = ecology.species();
        Map<Integer, List<PlantPlacement.Plant>> bySpecies = new HashMap<>();
        for (int i = 0; i < instances.count(); i++) {
            Species s = species.get(instances.species(i));
            if (!layers.contains(s.layer()) || recipe(s.id(), 0) == null) {
                continue;
            }
            if (keep.applyAsDouble(instances.x(i), instances.z(i)) < 0.5) {
                continue; // the village, its roads and square, and the cliffs
            }
            int key = instances.species(i) * VARIANTS + Math.floorMod(instances.variant(i), VARIANTS);
            bySpecies.computeIfAbsent(key, k -> new ArrayList<>()).add(new PlantPlacement.Plant(instances.x(i),
                instances.y(i), instances.z(i), instances.yaw(i), Math.max(0.15, instances.height(i))));
        }
        var scene = renderer.scene();
        for (Map.Entry<Integer, List<PlantPlacement.Plant>> e : bySpecies.entrySet()) {
            Species s = species.get(e.getKey() / VARIANTS);
            Recipe recipe = recipe(s.id(), e.getKey() % VARIANTS);
            // a cluster hierarchy (common-procedural 0.4: detail by distance)
            AssetRef<ModelData> model = procedural.model(recipe);
            boolean tree = s.layer() == Layer.CANOPY;
            // every plant always in the scene: scatters following the viewer (a radius) made the vegetation's sync
            // swap materials under instances still drawing them (true-sim/effects-vegetation#2)
            PlantScatter scatter = new PlantScatter(model, size / 2, size / 2, 4, 1, 1, 90, -1e4, 1e4,
                e.getKey(), sway(recipe), 0);
            placements.put(scatter, e.getValue());
            long node = scene.create();
            scene.setTranslation(node, x0 + size / 2, 0, z0 + size / 2);
            scene.components().set(node, PlantScatter.TYPE, scatter);
            models.add(model);
            plants += e.getValue().size();
            trees += tree ? e.getValue().size() : 0;
        }
    }

    /** The recipe of species {@code id}'s variant, on the day; null for a species without a generator. */
    private Recipe recipe(String id, int variant) {
        long seed = 1000L * (id.hashCode() & 0xffff) + variant;
        for (TreeSpecies t : TreeSpecies.PRESETS) {
            if (t.name().equals(id)) {
                Recipe palette = new Recipe("tree", id, seed, Season.AUTUMN, 0, List.of(), DAY);
                return new Recipe("tree", id, seed, Season.AUTUMN, 0, materials.tree(t, palette), DAY);
            }
        }
        if (PLANTS.contains(id)) {
            Recipe palette = new Recipe("plant", id, seed, Season.AUTUMN, 0, List.of(), DAY);
            return new Recipe("plant", id, seed, Season.AUTUMN, 0, materials.plant(palette), DAY);
        }
        return null;
    }

    private static final Set<String> PLANTS = Set.of("gentian", "arnica", "alpine-aster", "edelweiss", "buttercup",
        "clover", "grass", "sedge", "cotton-grass", "rush", "marsh-marigold");

    /** The plant type's sway for a recipe's model: common-procedural's wind colors. */
    private static MaterialData.Values sway(Recipe recipe) {
        boolean tree = "tree".equals(recipe.generator());
        return MaterialData.builder(VegetationAssets.PLANT_TYPE).set("windColors", 1)
            .set("height", tree ? 20f : 0.4f).set("radius", tree ? 4f : 0.2f).build().values();
    }

    /** The viewer the grass's detail falls with (the main camera). */
    public void viewer(Camera camera) {
        hooks.viewer(camera, 1);
    }

    /**
     * The next frame, {@code seconds} after the last, the viewer at the camera: grass patches and herbs follow it,
     * the wind and the lying snow follow the day. Before the scene updates.
     */
    public void update(DayState state, Camera camera, double seconds) {
        sync.update(camera.position(0), camera.position(1), camera.position(2));
        double speed = state.storm ? 11 : state.rain > 0.2 ? 7 : state.snowfall > 0.2 ? 3.5 : 2.2;
        double gusts = state.storm ? 0.9 : 0.55;
        hooks.wind(VegetationWindParameters.DEFAULTS.withDirection(new Vec3(0.35, 0, 1)).withSpeed((float) speed)
            .withGustStrength((float) gusts).withGustScale(20).withTurbulence(0.3f).withFlutter(0.1f));
        renderer.hooks().set(VegetationHooks.FIELD, VegetationFieldParameters.DEFAULTS
            .withSnowDepth((float) (0.07 * state.snowCover)), true);
        hooks.update(seconds, renderer.settings());
    }

    /** Whether every plant's model has loaded (or failed: the rest still stand). */
    public boolean ready() {
        var cache = renderer.assets().cache();
        for (AssetRef<ModelData> m : models) {
            if (cache.get(m) == null && cache.failure(m) == null) {
                return false;
            }
        }
        return true;
    }

    /** Holds every plant's model, so they load now (the tour waits for them before its first frame). */
    public void hold() {
        for (AssetRef<ModelData> m : models) {
            renderer.assets().cache().acquire(m);
        }
    }

    /** The models, for holding or waiting on. */
    public List<AssetRef<ModelData>> models() {
        return models;
    }

    @Override
    public void close() {
        sync.close();
        renderer.assets().resources().textures().remove(map.slot());
    }
}
