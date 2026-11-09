package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;
import com.truesim.common.math.Vec3;
import com.truesim.common.procedural.assets.BuildingAssets;
import com.truesim.common.procedural.assets.FenceAssets;
import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.SceneMeshes;
import com.truesim.common.procedural.building.Anchor;
import com.truesim.common.procedural.building.BuildingKind;
import com.truesim.common.procedural.building.BuildingSlot;
import com.truesim.common.procedural.building.Opening;
import com.truesim.common.procedural.building.Room;
import com.truesim.common.procedural.fence.FenceMaterial;
import com.truesim.common.procedural.fence.FenceOptions;
import com.truesim.common.procedural.fence.FenceRecipe;
import com.truesim.common.procedural.fence.FenceType;
import com.truesim.common.procedural.settlement.RoadMeshes;
import com.truesim.common.procedural.settlement.Settlement;
import com.truesim.samples.showcase.assets.NamedMaterials;
import com.truesim.effects.atmosphere.fog.IndoorZone;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.engine.scene.ShadingHookOverride;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.assets.Surfaces;
import com.truesim.samples.showcase.fx.VillageEffects;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.VillageLayout.Building;
import com.truesim.samples.showcase.world.VillageLayout.Prop;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.world.Cell;
import com.truesim.scene.world.CellContent;
import com.truesim.scene.world.CellLoader;
import com.truesim.scene.world.Detail;
import com.truesim.scene.world.InterestProfile;
import com.truesim.scene.world.InterestSource;
import com.truesim.scene.world.Layer;
import com.truesim.scene.world.Levels;
import com.truesim.scene.world.Priority;
import com.truesim.scene.world.Range;
import com.truesim.scene.world.World;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The village and its valley in the scene, streamed through scene-world's multi-level streaming: three layers, each
 * with cells of its own sizes, one interest source following the camera.
 *
 * <ul>
 *   <li>{@link #GROUND}: the valley's tiles in four levels (64 m tiles sampled every metre to 4 km tiles every 64
 *       m), each finer level within its band of the camera, out to the horizon 24 km away: the near tiles and the far
 *       terrain ring as one layer;</li>
 *   <li>{@link #BUILDINGS}: 64 m cells of the buildings, the square, the road, the fences, the lamps and their
 *       lights, the hearths' fires and the chimneys' smoke, within 3 km;</li>
 *   <li>{@link #PROPS}: 32 m cells of what is small (furniture inside, barrels and benches outside, rocks, the stand-in
 *       trees) within 250 m, so it streams in as the camera comes near.</li>
 * </ul>
 *
 * <p>Loading a cell only picks the layout's entries in it (on the layer's lane); attaching it, at the frame boundary,
 * makes their nodes: renderables of generated meshes and of Poly Haven's models (loaded once, held while the village
 * is), lights, indoor zones (no outdoor fog in the rooms) and effects. Unloading removes them. Lights and effects are
 * registered for the day ({@link #lamps}, {@link #rooms}, {@link #fires}).
 */
public final class Village implements AutoCloseable {

    public static final Layer<GroundCell> GROUND = Layer.of("ground", GroundCell.class,
        Levels.sizes(64, 256, 1024, 4096));
    public static final Layer<Picked> BUILDINGS = Layer.of("buildings", Picked.class, Levels.single(64));
    public static final Layer<Picked> PROPS = Layer.of("props", Picked.class, Levels.single(32));

    /** What the camera asks for: the village's layers, and the made-up valley's ground (effects-terrain follows itself). */
    static InterestProfile camera(boolean ground) {
        InterestProfile.Builder b = InterestProfile.builder()
            .layer(BUILDINGS, Detail.full(), Range.radius(3000))
            .layer(PROPS, Detail.full(), Range.radius(250));
        if (ground) {
            b.layer(GROUND, Detail.bands(380, 1500, 6000, 24000));
        }
        return b.build();
    }

    /** A ground cell's tile. */
    public record GroundCell(AssetRef<com.truesim.scene.assets.mesh.MeshData> mesh, double x0, double z0) {
    }

    /** A cell's entries of the layout. */
    public record Picked(List<Building> buildings, List<Prop> props, List<VillageLayout.Lamp> lamps,
        List<VillageLayout.Fence> fences, boolean origin) {
    }

    /** A building in the scene: its node and the level of detail it draws. */
    private static final class Placed {
        final long node;
        final Building building;
        int level = -1;

        Placed(long node, Building building) {
            this.node = node;
            this.building = building;
        }
    }

    /** A light the day turns up and down: its node and its full intensity (cd). */
    public record Light(long node, double candela) {
    }

    private final ShowcaseRenderer renderer;
    private final SceneGraph scene;
    private final Ground ground;
    private final VillageLayout layout;
    private final ShowcaseAssets assets;
    private final World world;
    private final InterestSource camera;
    private final com.truesim.effects.terrain.Terrain terrain; // or null: the valley made from functions
    private final GroundTiles tiles;
    private final VillageMeshes meshes;
    private final Map<String, AssetRef<ModelData>> models = new HashMap<>();
    private final List<AssetRef<MaterialData>> groundMaterials;
    private final Map<BuildingSlot, AssetRef<MaterialData>> buildingMaterials = new EnumMap<>(BuildingSlot.class);
    private final List<AssetRef<MaterialData>> roadMaterials;
    private final FenceAssets fenceAssets;
    private final List<String> fenceMaterials;
    /** The buildings in the scene and the level each draws (switched by the camera's distance). */
    private final List<Placed> placed = new ArrayList<>();
    /** Glowing materials the day animates: lamps' bulbs, the rooms' bulbs. */
    public final AssetRef<MaterialData> lampGlow = Surfaces.glowing(1, 0.85f, 0.6f, 0.4f);
    /** The street lamps' lantern glass: frosted, lit from within. */
    public final AssetRef<MaterialData> lampGlass = Surfaces.glowing(0.9f, 0.85f, 0.75f, 0.25f);
    public final AssetRef<MaterialData> roomGlow = Surfaces.glowing(1, 0.8f, 0.55f, 0.45f);
    /** A lit room's window seen from outside: its curtain glowing, two-sided. */
    public final AssetRef<MaterialData> windowGlow = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
        .set("baseColor", 0.9f, 0.75f, 0.5f, 1).set("roughness", 0.8f).set("metallic", 0)
        .set("emissive", 0, 0, 0).doubleSided(true).build().ref();
    /** The day's registries: street lamps and door lanterns, rooms' lights, fires, chimneys' smoke. */
    private final List<Light> lamps = new ArrayList<>();
    private final List<Light> rooms = new ArrayList<>();
    private final List<Effect> fires = new ArrayList<>();
    private final List<Effect> smokes = new ArrayList<>();

    public Village(ShowcaseRenderer renderer, Ground ground, VillageLayout layout, Surfaces surfaces,
        com.truesim.effects.terrain.data.TerrainSite site, NamedMaterials named, java.nio.file.Path cache) {
        this.renderer = renderer;
        this.scene = renderer.scene();
        this.ground = ground;
        this.layout = layout;
        this.assets = renderer.assets();
        tiles = new GroundTiles(ground, Surfaces.MEADOW.size(), Surfaces.FOREST_FLOOR.size(),
            Surfaces.MOSSY_ROCK.size());
        meshes = new VillageMeshes();
        groundMaterials = List.of(surfaces.scan(Surfaces.MEADOW, Surfaces.MEADOW_TINT[0], Surfaces.MEADOW_TINT[1],
            Surfaces.MEADOW_TINT[2]), surfaces.scan(Surfaces.FOREST_FLOOR),
            surfaces.scan(Surfaces.MOSSY_ROCK), Surfaces.plain(0.62f, 0.62f, 0.64f, 0.85f));
        // the buildings' slots (common-procedural's BuildingSlot): Poly Haven's scans at their real sizes
        AssetRef<MaterialData> glass = Surfaces.glass();
        Map<BuildingSlot, AssetRef<MaterialData>> m = buildingMaterials;
        m.put(BuildingSlot.STONE, surfaces.scan(Surfaces.STONE_WALL));
        m.put(BuildingSlot.PLASTER, surfaces.scan(Surfaces.PLASTER, 0.97f, 0.94f, 0.88f));
        m.put(BuildingSlot.TIMBER, surfaces.scan(Surfaces.PLANKS));
        m.put(BuildingSlot.TRIM, surfaces.scan(Surfaces.BEAMS));
        m.put(BuildingSlot.SHINGLES, surfaces.scan(Surfaces.SHINGLES));
        m.put(BuildingSlot.SLATES, surfaces.scan(Surfaces.SLATES));
        // limewashed rooms, warm
        m.put(BuildingSlot.INTERIOR, surfaces.scan(Surfaces.PLASTER, 0.98f, 0.9f, 0.78f));
        m.put(BuildingSlot.FLOOR, surfaces.scan(Surfaces.FLOORBOARDS));
        m.put(BuildingSlot.GLASS, glass);
        m.put(BuildingSlot.WINDOW, Surfaces.plain(0.02f, 0.025f, 0.03f, 0.08f)); // far levels' dark panes
        m.put(BuildingSlot.HEARTH, surfaces.scan(Surfaces.GRANITE, 0.7f, 0.7f, 0.7f));
        m.put(BuildingSlot.SHUTTERS, surfaces.scan(Surfaces.BEAMS, 0.35f, 0.95f, 0.5f)); // painted green
        m.put(BuildingSlot.DOOR, surfaces.scan(Surfaces.DARK_WOOD));
        m.put(BuildingSlot.METAL, Surfaces.metal(0.3f, 0.3f, 0.32f, 0.5f));
        m.put(BuildingSlot.FIREWOOD, surfaces.scan(Surfaces.WORN_WOOD, 1.1f, 0.95f, 0.8f));
        m.put(BuildingSlot.FOLIAGE, Surfaces.plain(0.05f, 0.12f, 0.03f, 0.6f));
        m.put(BuildingSlot.FLOWERS, Surfaces.plain(0.55f, 0.04f, 0.06f, 0.5f)); // geraniums
        // the roads' slots: cobbles, gravel, dirt, kerb, steps
        roadMaterials = List.of(surfaces.scan(Surfaces.COBBLES), surfaces.scan(Surfaces.TRAIL),
            surfaces.scan(Surfaces.TRAIL, 0.75f, 0.65f, 0.55f), surfaces.scan(Surfaces.GRANITE),
            surfaces.scan(Surfaces.GRANITE));
        // fences: common-procedural's, on the ground; their albedo is their vertex colour, so the materials are white
        // with the scans' relief (normals and roughness) only
        fenceAssets = new FenceAssets(assets.paths(), new GenerationCache(cache)).install();
        fenceAssets.ground("showcase", ground::height);
        fenceMaterials = List.of(named.name("fence/wood.mat", surfaces.relief(Surfaces.PLANKS)),
            named.name("fence/stone.mat", surfaces.relief(Surfaces.STONE_WALL)),
            named.name("fence/metal.mat", MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
                .set("baseColor", 1, 1, 1, 1).set("roughness", 0.5f).set("metallic", 1f).build()));
        Set<String> used = new LinkedHashSet<>();
        for (Prop p : layout.props()) {
            used.add(p.model());
        }
        used.add("street_lamp_01");
        for (String model : used) {
            AssetRef<ModelData> ref = assets.ref(RenderAssets.MODEL, ShowcaseAssets.MOUNT + "://" + model + "/" + model
                + "_2k.gltf");
            models.put(model, ref);
            if (assets.polyHaven()) {
                assets.cache().acquire(ref); // held while the village is: every cell spawns from them
            }
        }
        world = new World(scene, assets.tasks());
        // no attach budget: the world's budget is every layer's, and terrain tiles waiting behind the village's cells
        // left holes in the far mountains
        if (site != null) {
            // effects-terrain's tiles: its own layer of the same world, its own interest following the camera
            com.truesim.common.math.color.Color white = new com.truesim.common.math.color.Color(1, 1, 1, 1);
            // Lauterbrunnen's walls are near vertical: rock (triplanar, Poly Haven's rock face, greyed to the
            // limestone's: the bedded cliff's strata tiled into brickwork) from 28 degrees, so no face keeps a layer projected from above (its texels stretched down
            // the cliff as streaks), and the projections parting sharply
            var rules = com.truesim.effects.terrain.material.TerrainParameters.DEFAULTS.withRockSlopeStart(28)
                .withRockSlopeEnd(37).withScreeSlopeStart(20).withScreeSlopeEnd(28).withTriplanarSharpness(12);
            if (assets.polyHaven()) {
                // the scan's beige turned to the limestone's grey
                rules = rules.withRockColor(new com.truesim.common.math.color.Color(0.82f, 0.86f, 0.95f, 1))
                    .withGrassColor(new com.truesim.common.math.color.Color(Surfaces.MEADOW_TINT[0],
                        Surfaces.MEADOW_TINT[1], Surfaces.MEADOW_TINT[2], 1)).withScreeColor(white)
                    .withSnowColor(white);
            }
            AssetRef<MaterialData> material = com.truesim.effects.terrain.TerrainMaterial.data(rules,
                surfaces.terrainLayer(Surfaces.LIMESTONE), surfaces.terrainLayer(Surfaces.MEADOW),
                surfaces.terrainLayer(Surfaces.SCREE), surfaces.terrainLayer(Surfaces.SNOW)).ref();
            terrain = new com.truesim.effects.terrain.Terrain(world, assets.resources(), site, material,
                renderer.settings());
        } else {
            terrain = null;
            world.register(GROUND, new GroundLoader());
        }
        world.register(BUILDINGS, new PickLoader(true));
        world.register(PROPS, new PickLoader(false));
        camera = world.addInterest(camera(terrain == null), Priority.HIGH);
    }

    /** Waits for Poly Haven's models (every cell spawns from them), at most {@code timeout}. */
    public void awaitModels(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (AssetRef<ModelData> ref : models.values()) {
            while (assets.polyHaven() && assets.cache().get(ref) == null && assets.cache().failure(ref) == null) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Loading " + ref + " took too long");
                }
                assets.drain();
                Thread.sleep(2);
            }
            if (assets.cache().failure(ref) != null) {
                System.err.println("Could not load " + ref + ": " + assets.cache().failure(ref));
            }
        }
    }

    /** Streams about the camera at ({@code x}, {@code y}, {@code z}) seeing {@code fovY} over {@code height} pixels. */
    public void update(double x, double y, double z, double fovY, double height) {
        camera.setView(fovY, height);
        camera.moveTo(x, y, z);
        if (terrain != null) {
            terrain.follow(x, y, z, fovY, height);
        }
        world.update();
        if (terrain != null) {
            terrain.update();
        }
        levels(x, z);
    }

    /** Whether everything the camera asks for has loaded and attached. */
    public boolean ready() {
        return world.isReady(camera) && (terrain == null || terrain.pendingTiles() == 0);
    }

    public World world() {
        return world;
    }

    public List<Light> lamps() {
        return lamps;
    }

    public List<Light> rooms() {
        return rooms;
    }

    public List<Effect> fires() {
        return fires;
    }

    public List<Effect> smokes() {
        return smokes;
    }

    // ---------------------------------------------------------------- loaders

    /** The ground: a tile per cell, sampled more finely the finer the level. */
    private final class GroundLoader implements CellLoader<GroundCell> {
        @Override
        public GroundCell load(Cell cell) {
            double size = cell.size();
            double step = switch (cell.level()) {
                case 0 -> 1;
                case 1 -> 4;
                case 2 -> 16;
                default -> 64;
            };
            return new GroundCell(tiles.tile(cell.minX(), cell.minZ(), size, step), cell.minX(), cell.minZ());
        }

        @Override
        public void attach(GroundCell data, CellContent content) {
            long node = content.create();
            scene.setTranslation(node, data.x0(), 0, data.z0());
            scene.components().set(node, Renderable.TYPE, Renderable.of(data.mesh(), groundMaterials));
        }
    }

    /** The layout's entries whose ground plan position lies in the cell. */
    private final class PickLoader implements CellLoader<Picked> {
        private final boolean buildings;

        PickLoader(boolean buildings) {
            this.buildings = buildings;
        }

        @Override
        public Picked load(Cell cell) {
            double x0 = cell.minX();
            double z0 = cell.minZ();
            double s = cell.size();
            List<Building> bs = new ArrayList<>();
            List<Prop> ps = new ArrayList<>();
            List<VillageLayout.Lamp> ls = new ArrayList<>();
            List<VillageLayout.Fence> fs = new ArrayList<>();
            double[] at = new double[2];
            if (buildings) {
                for (Building b : layout.buildings()) {
                    if (in(b.x(), b.z(), x0, z0, s)) {
                        bs.add(b);
                    }
                }
                for (VillageLayout.Lamp l : layout.lamps()) {
                    if (in(l.x(), l.z(), x0, z0, s)) {
                        ls.add(l);
                    }
                }
                for (VillageLayout.Fence f : layout.fences()) {
                    if (in(f.x0(), f.z0(), x0, z0, s)) {
                        fs.add(f);
                    }
                }
            } else {
                for (Prop p : layout.props()) {
                    Building b = p.inside() == null || "tree".equals(p.inside()) ? null : layout.building(p.inside());
                    if (b != null) {
                        VillageLayout.toWorld(b, p.x(), p.z(), at);
                    } else {
                        at[0] = p.x();
                        at[1] = p.z();
                    }
                    if (in(at[0], at[1], x0, z0, s)) {
                        ps.add(p);
                    }
                }
            }
            return new Picked(bs, ps, ls, fs, buildings && in(layout.originX(), layout.originZ(), x0, z0, s));
        }

        private boolean in(double x, double z, double x0, double z0, double s) {
            return x >= x0 && x < x0 + s && z >= z0 && z < z0 + s;
        }

        @Override
        public void attach(Picked data, CellContent content) {
            for (Building b : data.buildings()) {
                building(b, content);
            }
            for (VillageLayout.Lamp l : data.lamps()) {
                lamp(l, content);
            }
            for (VillageLayout.Fence f : data.fences()) {
                fence(f, content);
            }
            for (Prop p : data.props()) {
                prop(p, content);
            }
            if (data.origin()) { // the plan's roads and square live in the origin's cell: its road meshes' tiles
                List<RoadMeshes.Tile> tiles = layout.settlement().roadMeshes();
                for (int t = 0; t < tiles.size(); t++) {
                    RoadMeshes.Tile tile = tiles.get(t);
                    List<AssetRef<MaterialData>> materials = new ArrayList<>();
                    for (int slot = 0; slot < RoadMeshes.SLOTS; slot++) {
                        if (SceneMeshes.kept(tile.mesh(), slot)) {
                            materials.add(roadMaterials.get(slot));
                        }
                    }
                    long node = content.create();
                    scene.setTranslation(node, tile.x(), tile.y(), tile.z());
                    scene.components().set(node, Renderable.TYPE, Renderable.of(
                        com.truesim.common.procedural.assets.SettlementAssets.mesh(layout.settlement(), t), materials));
                }
            }
        }

        @Override
        public void detach(Picked data, CellContent content) {
            int count = content.nodeCount();
            for (int i = 0; i < count; i++) {
                long node = content.node(i);
                lamps.removeIf(l -> l.node() == node);
                rooms.removeIf(l -> l.node() == node);
                placed.removeIf(p -> p.node == node);
            }
            fires.removeIf(e -> !e.alive());
            smokes.removeIf(e -> !e.alive());
            content.removeAll();
        }
    }

    // ---------------------------------------------------------------- placing

    private void place(long node, double x, double y, double z, double yaw) {
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
    }

    private void building(Building b, CellContent content) {
        long node = content.create();
        place(node, b.x(), b.floor(), b.z(), b.yaw());
        Placed p = new Placed(node, b);
        placed.add(p);
        level(p, 2); // until the camera's distance says otherwise
        // no aerial perspective on the house and what is in it: its nearest slice tinted the rooms blue
        // (true-sim/effects-atmosphere#9); a house is near enough that its air shows nothing outside
        scene.components().set(node, ShadingHookOverride.TYPE, ShadingHookOverride.off(AerialPerspective.HOOK));
        var fittings = b.model().fittings();
        // the rooms' air: no outdoor fog inside
        for (Room room : fittings.rooms()) {
            long zone = content.create(node);
            Vec3 c = room.bounds().center();
            scene.setTranslation(zone, c.x(), c.y(), c.z());
            Vec3 e = room.bounds().extents();
            scene.components().set(zone, IndoorZone.TYPE, IndoorZone.box(new Vec3(e.x(), e.y(), e.z())));
        }
        BuildingKind kind = b.kind();
        if (b.lit()) {
            for (Anchor a : fittings.all(Anchor.Kind.CEILING_LAMP)) {
                Vec3 at = a.position();
                if (a.room() < 0 || at.y() > 4) {
                    continue; // the ground floor's rooms (the windows the tour sees)
                }
                long lamp = content.create(node);
                scene.setTranslation(lamp, at.x(), at.y(), at.z());
                double candela = kind == BuildingKind.INN ? 450 : kind == BuildingKind.CHURCH ? 500 : 260;
                scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 0.74f, 0.48f, 0)
                    .range(kind == BuildingKind.CHURCH ? 20 : 11));
                rooms.add(new Light(lamp, candela));
                if (kind != BuildingKind.INN) { // the inn's chandelier is its lamp
                    long bulb = content.create(node);
                    scene.setTranslation(bulb, at.x(), at.y() - 0.05, at.z());
                    scene.components().set(bulb, Renderable.TYPE, Renderable.of(meshes.mesh(
                        new VillageMeshes.Bulb(0.07, 0.12)), List.of(roomGlow)));
                    Renderable.setCastsShadows(scene, bulb, false);
                }
            }
            // the lit rooms' windows: a curtain glowing behind each, seen from outside at night (not the inn's and
            // the church's: the tour goes into the one, the other's are tall stone-framed lights)
            if (kind == BuildingKind.CHALET || kind == BuildingKind.FARMHOUSE) {
                for (Opening o : fittings.openings(Opening.Kind.WINDOW)) {
                    if (o.room() < 0) {
                        continue;
                    }
                    Vec3 c = o.center();
                    Vec3 out = o.outward();
                    long pane = content.create(node);
                    double back = o.depth() + 0.04;
                    place(pane, c.x() - out.x() * back, c.y(), c.z() - out.z() * back, Maths.atan2(out.x(), out.z()));
                    scene.components().set(pane, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Pane(
                        o.width() * 0.9, o.height() * 0.9)), List.of(windowGlow)));
                    Renderable.setCastsShadows(scene, pane, false);
                }
            }
            for (Anchor a : fittings.all(Anchor.Kind.LANTERN)) {
                Vec3 at = a.position();
                long l = content.create(node);
                scene.setTranslation(l, at.x(), at.y(), at.z());
                scene.components().set(l, LightSource.TYPE, LightSource.point(1, 0.7f, 0.42f, 0).range(9));
                lamps.add(new Light(l, 90));
                spawn(models.get("Lantern_01"), content, node, at.x(), at.y() - 0.18, at.z(), a.yaw(), 1.4);
                long glow = content.create(node);
                scene.setTranslation(glow, at.x(), at.y() - 0.12, at.z());
                scene.components().set(glow, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Bulb(0.035,
                    0.08)), List.of(lampGlow)));
                Renderable.setCastsShadows(scene, glow, false);
            }
        }
        Anchor hearth = fittings.first(Anchor.Kind.FIRE);
        Anchor chimney = fittings.first(Anchor.Kind.CHIMNEY);
        if (b.fire() && hearth != null && chimney != null) {
            long fire = content.create(node);
            scene.setTranslation(fire, hearth.position().x(), hearth.position().y(), hearth.position().z());
            fires.add(renderer.effects().play(VillageEffects.HEARTH, fire));
            long smoke = content.create(node);
            scene.setTranslation(smoke, chimney.position().x(), chimney.position().y() + 0.1, chimney.position().z());
            smokes.add(renderer.effects().play(VillageEffects.CHIMNEY, smoke));
        }
    }

    /** Draws {@code p}'s building at {@code level}: its meshes' level, a material for each slot it has. */
    private void level(Placed p, int level) {
        if (p.level == level) {
            return;
        }
        var model = p.building.model();
        var mesh = model.lods().get(level);
        List<AssetRef<MaterialData>> materials = new ArrayList<>();
        for (BuildingSlot slot : BuildingSlot.all()) {
            if (SceneMeshes.kept(mesh, slot.ordinal())) {
                materials.add(buildingMaterials.get(slot));
            }
        }
        scene.components().set(p.node, Renderable.TYPE, Renderable.of(BuildingAssets.mesh(model, level, false),
            materials));
        p.level = level;
    }

    /**
     * The buildings' levels by the camera's distance (the generator's four: everything, without small carving and
     * single logs, closed walls with dark panes, the volumes): common-procedural builds no cluster hierarchy for a
     * building, its levels are its detail.
     */
    private void levels(double x, double z) {
        for (Placed p : placed) {
            double dx = p.building.x() - x;
            double dz = p.building.z() - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            int want = d < 60 ? 0 : d < 160 ? 1 : d < 450 ? 2 : 3;
            // a little hysteresis: a level changes only once past its band by a tenth
            if (p.level >= 0 && Math.abs(want - p.level) == 1) {
                double edge = switch (Math.min(want, p.level)) {
                    case 0 -> 60;
                    case 1 -> 160;
                    default -> 450;
                };
                if (Math.abs(d - edge) < edge * 0.1) {
                    continue;
                }
            }
            level(p, want);
        }
    }

    private void lamp(VillageLayout.Lamp l, CellContent content) {
        long node = content.create();
        place(node, l.x(), ground.height(l.x(), l.z()), l.z(), l.yaw());
        long[] parts = spawn(models.get("street_lamp_01"), content, node, 0, 0, 0, 0, 1);
        // the lantern's glass and bulb glow (its own materials are dark glass): Poly Haven's lamp has three, in its
        // glTF's order body, glass, bulb; the day animates the glass and the bulb as the lamps' other bulbs
        for (long n : parts) {
            Renderable r = scene.components().get(n, Renderable.TYPE);
            if (r != null && r.materials().size() == 3) {
                scene.components().set(n, Renderable.TYPE, r.withMaterials(List.of(r.materials().get(0), lampGlass,
                    lampGlow)));
            }
        }
        long light = content.create(node);
        scene.setTranslation(light, 0, 3.35, 0);
        scene.components().set(light, LightSource.TYPE, LightSource.point(1, 0.72f, 0.45f, 0).range(22));
        lamps.add(new Light(light, 650));
        long bulb = content.create(node);
        scene.setTranslation(bulb, 0, 3.28, 0);
        scene.components().set(bulb, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Bulb(0.06, 0.14)),
            List.of(lampGlow)));
        Renderable.setCastsShadows(scene, bulb, false);
    }

    private void fence(VillageLayout.Fence f, CellContent content) {
        // common-procedural's fence on the ground: every post on the ground under it, rails from post to post; the
        // gardens' Schaerhag or pickets, the pastures' Stangenzaun, post and rail or a dry-stone wall, by its seed
        long seed = f.seed();
        int pick = (int) Math.floorMod(seed, 4L);
        FenceType type = f.kind() == Settlement.FenceKind.GARDEN ? (pick < 2 ? FenceType.SCHAERHAG : FenceType.PICKET)
            : switch (pick) {
                case 0 -> FenceType.STANGENZAUN;
                case 1 -> FenceType.POST_AND_RAIL;
                case 2 -> FenceType.DRY_STONE;
                default -> FenceType.SCHAERHAG;
            };
        FenceOptions options = FenceOptions.DEFAULTS.withWood(pick % 2 == 0 ? FenceMaterial.WEATHERED_LARCH
            : FenceMaterial.WEATHERED_SPRUCE).withStone(FenceMaterial.LIMESTONE).withWeathering(0.7).withLichen(0.35)
            .withMoss(0.4).withDamage(0.05);
        List<Double> line = new ArrayList<>();
        for (double v : f.line()) {
            line.add(v);
        }
        FenceRecipe recipe = new FenceRecipe(type, options, seed, line, List.of(), "showcase", fenceMaterials);
        // generated now (milliseconds), for its origin and the slots it uses; its mesh a cluster hierarchy
        com.truesim.common.procedural.fence.Fence fence = fenceAssets.fence(recipe);
        var mesh = fence.lods().getFirst();
        List<AssetRef<MaterialData>> materials = new ArrayList<>();
        for (int slot = 0; slot < fenceMaterials.size(); slot++) {
            if (SceneMeshes.kept(mesh, slot)) {
                materials.add(assets.ref(RenderAssets.MATERIAL, fenceMaterials.get(slot)));
            }
        }
        long node = content.create();
        scene.setTranslation(node, fence.origin().x(), fence.origin().y(), fence.origin().z());
        scene.components().set(node, Renderable.TYPE, Renderable.of(fenceAssets.mesh(recipe, 0, true), materials));
    }

    private void prop(Prop p, CellContent content) {
        AssetRef<ModelData> model = models.get(p.model());
        Building b = p.inside() == null || "tree".equals(p.inside()) ? null : layout.building(p.inside());
        if (b != null) {
            long anchor = content.create();
            place(anchor, b.x(), b.floor(), b.z(), b.yaw());
            for (long n : spawn(model, content, anchor, p.x(), p.y(), p.z(), p.yaw(), p.scale())) {
                if (scene.components().get(n, Renderable.TYPE) != null) {
                    scene.components().set(n, ShadingHookOverride.TYPE,
                        ShadingHookOverride.off(AerialPerspective.HOOK));
                }
            }
        } else {
            long anchor = content.create();
            place(anchor, p.x(), ground.height(p.x(), p.z()) + p.y(), p.z(), p.yaw());
            spawn(model, content, anchor, 0, 0, 0, 0, p.scale());
        }
    }

    /** Spawns {@code model} under a new node of {@code parent} at the local position, turn and scale. */
    private long[] spawn(AssetRef<ModelData> model, CellContent content, long parent, double x, double y, double z,
        double yaw, double scale) {
        ModelData data = model == null ? null : assets.cache().get(model);
        if (data == null) {
            return new long[0]; // without Poly Haven's cache (or failed to load): left out
        }
        long node = content.create(parent);
        place(node, x, y, z, yaw);
        scene.setScale(node, scale, scale, scale);
        long[] nodes = data.spawn(scene, node);
        for (long n : nodes) {
            content.adopt(n); // removed with the cell
        }
        return nodes;
    }

    @Override
    public void close() {
        if (terrain != null) {
            terrain.close();
        }
        camera.close();
        world.close();
        for (AssetRef<ModelData> ref : models.values()) {
            if (assets.polyHaven()) {
                assets.cache().release(ref);
            }
        }
    }
}
