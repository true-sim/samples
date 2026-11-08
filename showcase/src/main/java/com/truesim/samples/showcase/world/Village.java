package com.truesim.samples.showcase.world;

import com.truesim.common.math.Maths;
import com.truesim.common.math.Vec3;
import com.truesim.effects.atmosphere.fog.IndoorZone;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.assets.Surfaces;
import com.truesim.samples.showcase.fx.VillageEffects;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.Buildings.Kind;
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
    private final Map<Kind, List<AssetRef<MaterialData>>> buildingMaterials = new HashMap<>();
    private final List<AssetRef<MaterialData>> chaletPlaster;
    private final AssetRef<MaterialData> cobbles;
    private final AssetRef<MaterialData> kerb;
    private final AssetRef<MaterialData> gravel;
    private final AssetRef<MaterialData> fenceWood;
    /** Glowing materials the day animates: lamps' bulbs, the rooms' bulbs. */
    public final AssetRef<MaterialData> lampGlow = Surfaces.glowing(1, 0.85f, 0.6f, 0.4f);
    public final AssetRef<MaterialData> roomGlow = Surfaces.glowing(1, 0.8f, 0.55f, 0.45f);
    private final AssetRef<MaterialData> mirrorGlass = Surfaces.metal(0.95f, 0.95f, 0.95f, 0.02f);
    private final AssetRef<MaterialData> mirrorFrame = Surfaces.metal(0.8f, 0.6f, 0.3f, 0.35f);
    /** The day's registries: street lamps and door lanterns, rooms' lights, fires, chimneys' smoke. */
    private final List<Light> lamps = new ArrayList<>();
    private final List<Light> rooms = new ArrayList<>();
    private final List<Effect> fires = new ArrayList<>();
    private final List<Effect> smokes = new ArrayList<>();

    public Village(ShowcaseRenderer renderer, Ground ground, VillageLayout layout, Surfaces surfaces,
        com.truesim.effects.terrain.data.TerrainSite site) {
        this.renderer = renderer;
        this.scene = renderer.scene();
        this.ground = ground;
        this.layout = layout;
        this.assets = renderer.assets();
        tiles = new GroundTiles(ground, Surfaces.MEADOW.size(), Surfaces.FOREST_FLOOR.size(),
            Surfaces.MOSSY_ROCK.size());
        meshes = new VillageMeshes(ground, layout);
        groundMaterials = List.of(surfaces.scan(Surfaces.MEADOW, Surfaces.MEADOW_TINT[0], Surfaces.MEADOW_TINT[1],
            Surfaces.MEADOW_TINT[2]), surfaces.scan(Surfaces.FOREST_FLOOR),
            surfaces.scan(Surfaces.MOSSY_ROCK), Surfaces.plain(0.62f, 0.62f, 0.64f, 0.85f));
        AssetRef<MaterialData> stone = surfaces.scan(Surfaces.STONE_WALL);
        AssetRef<MaterialData> planks = surfaces.scan(Surfaces.PLANKS);
        AssetRef<MaterialData> worn = surfaces.scan(Surfaces.WORN_WOOD);
        AssetRef<MaterialData> interior = surfaces.scan(Surfaces.PAINTED_PLASTER, 0.95f, 0.9f, 0.82f);
        AssetRef<MaterialData> glass = Surfaces.glass();
        AssetRef<MaterialData> hearth = surfaces.scan(Surfaces.GRANITE, 0.7f, 0.7f, 0.7f);
        AssetRef<MaterialData> shutters = Surfaces.plain(0.08f, 0.2f, 0.11f, 0.6f);
        buildingMaterials.put(Kind.CHALET, List.of(stone, planks, surfaces.scan(Surfaces.SLATES), worn, interior,
            surfaces.scan(Surfaces.FLOORBOARDS), glass, hearth, shutters));
        chaletPlaster = List.of(stone, surfaces.scan(Surfaces.PLASTER, 0.97f, 0.93f, 0.85f),
            surfaces.scan(Surfaces.TILES), worn, interior, surfaces.scan(Surfaces.FLOORBOARDS), glass, hearth,
            Surfaces.plain(0.25f, 0.08f, 0.05f, 0.6f));
        buildingMaterials.put(Kind.CAFE, List.of(stone, surfaces.scan(Surfaces.PLASTER, 0.95f, 0.88f, 0.75f),
            surfaces.scan(Surfaces.TILES), worn, interior, surfaces.scan(Surfaces.PARQUET), glass, hearth,
            Surfaces.plain(0.12f, 0.1f, 0.08f, 0.5f)));
        buildingMaterials.put(Kind.CHURCH, List.of(surfaces.scan(Surfaces.GRANITE), surfaces.scan(Surfaces.PLASTER),
            surfaces.scan(Surfaces.DARK_SLATES), worn, Surfaces.plain(0.85f, 0.85f, 0.82f, 0.5f),
            surfaces.scan(Surfaces.FLOORBOARDS), glass, hearth, Surfaces.metal(0.85f, 0.65f, 0.3f, 0.3f)));
        cobbles = surfaces.scan(Surfaces.COBBLES);
        kerb = surfaces.scan(Surfaces.GRANITE);
        gravel = surfaces.scan(Surfaces.TRAIL);
        fenceWood = surfaces.scan(Surfaces.PLANKS, 0.8f, 0.8f, 0.8f);
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
            var rules = com.truesim.effects.terrain.material.TerrainParameters.DEFAULTS;
            if (assets.polyHaven()) {
                rules = rules.withRockColor(white).withGrassColor(new com.truesim.common.math.color.Color(
                    Surfaces.MEADOW_TINT[0], Surfaces.MEADOW_TINT[1], Surfaces.MEADOW_TINT[2], 1)).withScreeColor(white)
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
            if (data.origin()) { // the square and the road live in the origin's cell
                long square = content.create();
                scene.setTranslation(square, layout.originX(), 0, layout.originZ());
                scene.components().set(square, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Square(
                    layout.originX(), layout.originZ(), VillageLayout.SQUARE_X, VillageLayout.SQUARE_Z)),
                    List.of(cobbles, kerb)));
                long road = content.create();
                scene.components().set(road, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Road(4.5)),
                    List.of(gravel)));
            }
        }

        @Override
        public void detach(Picked data, CellContent content) {
            int count = content.nodeCount();
            for (int i = 0; i < count; i++) {
                long node = content.node(i);
                lamps.removeIf(l -> l.node() == node);
                rooms.removeIf(l -> l.node() == node);
            }
            fires.removeIf(e -> !e.alive());
            smokes.removeIf(e -> !e.alive());
            content.removeAll();
        }
    }

    // ---------------------------------------------------------------- placing

    private double floorOf(Building b) {
        // the ground floor at the highest ground under the footprint, a little above: the plinth fills the rest
        double highest = -Double.MAX_VALUE;
        double c = Maths.cos(b.yaw());
        double s = Maths.sin(b.yaw());
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double lx = b.spec().width() * i / 4;
                double lz = b.spec().depth() * j / 4;
                highest = Math.max(highest, ground.height(b.x() + lx * c + lz * s, b.z() - lx * s + lz * c));
            }
        }
        return highest + 0.15;
    }

    private void place(long node, double x, double y, double z, double yaw) {
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
    }

    private void building(Building b, CellContent content) {
        long node = content.create();
        double floor = floorOf(b);
        place(node, b.x(), floor, b.z(), b.yaw());
        List<AssetRef<MaterialData>> materials = buildingMaterials.get(b.spec().kind());
        if (b.spec().kind() == Kind.CHALET && b.spec().seed() % 3 == 0) {
            materials = chaletPlaster;
        }
        scene.components().set(node, Renderable.TYPE, Renderable.of(Buildings.mesh(b.spec()), materials));
        Buildings.Fittings f = Buildings.fittings(b.spec());
        // the rooms' air: no outdoor fog inside
        long zone = content.create(node);
        scene.setTranslation(zone, 0, b.spec().eaves() / 2, 0);
        scene.components().set(zone, IndoorZone.TYPE, IndoorZone.box(new Vec3(b.spec().width() / 2 - 0.2,
            b.spec().eaves() / 2, b.spec().depth() / 2 - 0.2)));
        if (b.lit()) {
            long lamp = content.create(node);
            scene.setTranslation(lamp, f.ceilingLamp()[0], f.ceilingLamp()[1], f.ceilingLamp()[2]);
            double candela = b.spec().kind() == Kind.CAFE ? 450 : b.spec().kind() == Kind.CHURCH ? 500 : 260;
            scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 0.74f, 0.48f, 0)
                .range(b.spec().kind() == Kind.CHURCH ? 20 : 11));
            rooms.add(new Light(lamp, candela));
            if (b.spec().kind() != Kind.CAFE) { // the café's chandelier is its lamp
                long bulb = content.create(node);
                scene.setTranslation(bulb, f.ceilingLamp()[0], f.ceilingLamp()[1] - 0.05, f.ceilingLamp()[2]);
                scene.components().set(bulb, Renderable.TYPE, Renderable.of(meshes.mesh(
                    new VillageMeshes.Bulb(0.07, 0.12)), List.of(roomGlow)));
                Renderable.setCastsShadows(scene, bulb, false);
            }
        }
        for (double[] lantern : f.lanterns()) {
            if (!b.lit()) {
                break;
            }
            long l = content.create(node);
            scene.setTranslation(l, lantern[0], lantern[1], lantern[2]);
            scene.components().set(l, LightSource.TYPE, LightSource.point(1, 0.7f, 0.42f, 0).range(9));
            lamps.add(new Light(l, 90));
            AssetRef<ModelData> model = models.get("Lantern_01");
            spawn(model, content, node, lantern[0], lantern[1] - 0.18, lantern[2], 0, 1.4);
            long glow = content.create(node);
            scene.setTranslation(glow, lantern[0], lantern[1] - 0.12, lantern[2]);
            scene.components().set(glow, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Bulb(0.035,
                0.08)), List.of(lampGlow)));
            Renderable.setCastsShadows(scene, glow, false);
        }
        if (b.fire() && f.fire() != null) {
            long fire = content.create(node);
            scene.setTranslation(fire, f.fire()[0], f.fire()[1], f.fire()[2]);
            fires.add(renderer.effects().play(VillageEffects.HEARTH, fire));
            long chimney = content.create(node);
            scene.setTranslation(chimney, f.chimney()[0], f.chimney()[1] + 0.1, f.chimney()[2]);
            smokes.add(renderer.effects().play(VillageEffects.CHIMNEY, chimney));
        }
        if (b.spec().kind() == Kind.CAFE) {
            // the big mirror on the back wall, behind the bar
            long mirror = content.create(node);
            scene.setTranslation(mirror, 1.6, 0.9, -b.spec().depth() / 2 + Buildings.STONE_THICK + 0.01);
            scene.components().set(mirror, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Mirror(2.6,
                1.7)), List.of(mirrorFrame, mirrorGlass)));
        }
    }

    private void lamp(VillageLayout.Lamp l, CellContent content) {
        long node = content.create();
        place(node, l.x(), ground.height(l.x(), l.z()), l.z(), l.yaw());
        spawn(models.get("street_lamp_01"), content, node, 0, 0, 0, 0, 1);
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
        double dx = f.x1() - f.x0();
        double dz = f.z1() - f.z0();
        double length = Math.sqrt(dx * dx + dz * dz);
        double yaw = Maths.atan2(-dz, dx); // local +x along the fence
        int sections = (int) Math.ceil(length / 12);
        for (int i = 0; i < sections; i++) {
            double t = (double) i / sections;
            double x = f.x0() + dx * t;
            double z = f.z0() + dz * t;
            long node = content.create();
            place(node, x, ground.height(x, z), z, yaw);
            scene.components().set(node, Renderable.TYPE, Renderable.of(meshes.mesh(new VillageMeshes.Fence(
                length / sections, 2.4)), List.of(fenceWood)));
        }
    }

    private void prop(Prop p, CellContent content) {
        AssetRef<ModelData> model = models.get(p.model());
        Building b = p.inside() == null || "tree".equals(p.inside()) ? null : layout.building(p.inside());
        if (b != null) {
            long anchor = content.create();
            place(anchor, b.x(), floorOf(b), b.z(), b.yaw());
            spawn(model, content, anchor, p.x(), p.y(), p.z(), p.yaw(), p.scale());
        } else {
            long anchor = content.create();
            place(anchor, p.x(), ground.height(p.x(), p.z()) + p.y(), p.z(), p.yaw());
            spawn(model, content, anchor, 0, 0, 0, 0, p.scale());
        }
    }

    /** Spawns {@code model} under a new node of {@code parent} at the local position, turn and scale. */
    private void spawn(AssetRef<ModelData> model, CellContent content, long parent, double x, double y, double z,
        double yaw, double scale) {
        ModelData data = model == null ? null : assets.cache().get(model);
        if (data == null) {
            return; // without Poly Haven's cache (or failed to load): left out
        }
        long node = content.create(parent);
        place(node, x, y, z, yaw);
        scene.setScale(node, scale, scale, scale);
        long[] nodes = data.spawn(scene, node);
        for (long n : nodes) {
            content.adopt(n); // removed with the cell
        }
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
