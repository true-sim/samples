package com.truesim.samples.showcase;

import com.truesim.common.math.Maths;
import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.SettlementSettings;
import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.ecology.AlpineSpecies;
import com.truesim.common.procedural.ecology.Ecology;
import com.truesim.common.procedural.ecology.EcologyParameters;
import com.truesim.common.procedural.ecology.EcologyTerrain;
import com.truesim.common.procedural.ecology.rocks.RockField;
import com.truesim.common.procedural.ecology.rocks.RockFieldParameters;
import com.truesim.common.procedural.ecology.rocks.RockScatter;
import com.truesim.common.procedural.ecology.terrain.TalusModifier;
import com.truesim.common.procedural.ecology.terrain.TerrainEcology;
import com.truesim.common.procedural.settlement.Settlement;
import com.truesim.common.procedural.settlement.Settlements;
import com.truesim.common.procedural.settlement.terrain.SettlementTerrain;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterRegion;
import com.truesim.common.procedural.water.WaterSettings;
import com.truesim.effects.terrain.data.TerrainAssets;
import com.truesim.effects.terrain.data.TerrainModifier;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.water.WaterMaterials;
import com.truesim.effects.water.WaterScene;
import com.truesim.effects.water.WaterTerrain;
import com.truesim.effects.water.WaterWetness;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.assets.NamedMaterials;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.assets.Surfaces;
import com.truesim.samples.showcase.day.DayDriver;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.flora.Flora;
import com.truesim.samples.showcase.fx.FxTextures;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.Ground;
import com.truesim.samples.showcase.world.TerrainGround;
import com.truesim.samples.showcase.world.ValleyGround;
import com.truesim.samples.showcase.world.Village;
import com.truesim.samples.showcase.world.VillageLayout;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.render.TextureData;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The showcase's world on a device and frame graph: the renderer with the whole effect chain, the valley's ground,
 * the village streamed about the camera, and the day driving them. Both ways of running share it: the interactive
 * app ({@link ShowcaseApp}) and the tour that films the day ({@link ShowcaseTour}).
 *
 * <pre>{@code
 * ShowcaseWorld world = new ShowcaseWorld(device, graph, assets);
 * SceneView view = world.view(port, camera);
 * // every frame: the camera placed, then
 * world.frame(seconds, state, camera, viewportHeight);
 * graph.beginFrame(); graph.submit(...);
 * }</pre>
 */
public final class ShowcaseWorld implements AutoCloseable {

    private final ShowcaseRenderer renderer;
    private final Ground ground;
    private final VillageLayout layout;
    private final Village village;
    private final DayDriver day;
    private final TerrainSite site;
    private final Flora flora;
    private final WaterScene water; // or null: the valley made from functions
    private final WaterNetwork rivers; // or null

    /** Where the square lies in Lauterbrunnen's frame (x east, z south of the church), metres. */
    public static final double VILLAGE_X = -130;
    public static final double VILLAGE_Z = 300;

    public ShowcaseWorld(VulkanDevice device, FrameGraph graph, ShowcaseAssets assets) throws InterruptedException {
        // effects-terrain's cliffs at their finest: 32 m tiles over 11 levels (the walls' relief meshed); set before
        // the ground reads the tiles' spacing, so what stands on it stands on what is drawn
        graph.settings().set(EngineSettings.Layer.GAME, com.truesim.effects.terrain.TerrainSettings.TILE_SIZE, 32f);
        graph.settings().set(EngineSettings.Layer.GAME, com.truesim.effects.terrain.TerrainSettings.LEVELS, 11);
        site = assets.terrain() ? site(assets) : null;
        ground = site != null ? new TerrainGround(site, graph.settings()) : new ValleyGround();
        renderer = new ShowcaseRenderer(device, graph, assets, ground);
        // the ecology over the terrain (or the valley made from functions, 800 m up), and the village's plan on it:
        // common-procedural's settlement, its square on the meadow west of the Lütschine below the Staubbach falls
        long start = System.nanoTime();
        EcologyTerrain terrain = ground instanceof TerrainGround t ? TerrainEcology.of(t.surface(), 2)
            : (x, z) -> ground.height(x, z) + 800;
        Ecology plain = new Ecology(terrain, EcologyParameters.ALPS.withSeed(7)
            .withLandUse(EcologyParameters.LandUse.MEADOWS), AlpineSpecies.ALL);
        // the rocks where the terrain sheds them (talus under the walls, rockfall, stream boulders, clearance piles,
        // erratics), and the plants kept off them
        // (fewer variants than the library's: two seeds, two cover levels, one depth, so fewer models on the GPU)
        RockField rocks = new RockField(plain, RockFieldParameters.DEFAULTS.withVariants(
            new RockFieldParameters.Variants(2, 4, 2, 1)));
        Ecology ecology = plain.avoiding(rocks);
        Ecology placing;
        Settlement plan = Settlements.plan(SettlementSettings.parameters(graph.settings()), ecology,
            site != null ? VILLAGE_X : 0, site != null ? VILLAGE_Z : 0, 7);
        // what the plan and the rocks change in the terrain, as effects-terrain's modifiers: the talus' gravel and
        // roughness, then the village's grading (its roads' and square's cut and fill, verges, gravel scatter); the
        // ground then gives the modified heights, so the buildings, the props and the grass stand on what is drawn
        TerrainModifiers modifiers = TerrainModifiers.NONE;
        WaterNetwork network = null;
        if (ground instanceof TerrainGround t) {
            double ox = plan.square().x();
            double oz = plan.square().z();
            // the Weisse Lütschine, its streams and the Staubbach Fall from the terrain's flow, on the bare ground
            // (common-procedural-water); their beds and banks carved first, then the talus, then the roads
            var bare = t.surface();
            network = WaterNetwork.build((x, z) -> bare.heightAt(x, z, 2), WaterRegion.around(ox, oz, 4000),
                WaterSettings.parameters(graph.settings()));
            modifiers = TerrainModifiers.of(WaterTerrain.of(network)).with(new TalusModifier(rocks,
                TalusModifier.Parameters.DEFAULTS, new TerrainModifier.Bounds(ox - 1500, oz - 1500, ox + 1500,
                oz + 1500))).with(SettlementTerrain.of(plan));
            t.modify(modifiers);
        }
        rivers = network;
        layout = new VillageLayout(plan, ground);
        System.out.printf("Village: %d buildings, %d roads, %d props, %d fences planned and built in %.1f s%n",
            layout.buildings().size(), plan.roads().size(), layout.props().size(), layout.fences().size(),
            (System.nanoTime() - start) / 1e9);
        Surfaces surfaces = new Surfaces(assets);
        NamedMaterials named = new NamedMaterials(assets);
        Path cache = Path.of(graph.settings().get(ShowcaseSettings.PROCEDURAL));
        village = new Village(renderer, ground, layout, surfaces, site, named, cache, modifiers);
        // the plants kept off the plan's roads, square, plots and buildings before they are spaced
        placing = ecology.withClearing(plan.clearing());
        // the particles' textures, held and loaded before the first fire
        for (AssetRef<TextureData> ref : List.of(FxTextures.FLAME, FxTextures.GLOW, FxTextures.EMBER, FxTextures.SMOKE,
            FxTextures.SMOKE_NORMALS)) {
            assets.resources().hold(ref);
        }
        // the valley's grass, trees, shrubs and flowers, where the ecology puts them
        ProceduralAssets procedural = new ProceduralAssets(assets.paths(), new GenerationCache(cache)).install();
        // the river's close-up walks its bank: no tree or shrub on the camera's way
        double[] bank = riverBank();
        flora = new Flora(renderer, ground, layout, placing, rocks, named, procedural,
            (x, z) -> com.truesim.samples.showcase.tour.TourScript.riverPath(bank, x, z), rivers);
        if (rivers != null && !rivers.falls().isEmpty()) {
            // the talus' small stones round the Staubbach's foot (the water's close-up): single stones near the
            // camera's way, patches beyond
            var tallest = rivers.tallest().path();
            int last = tallest.x().length - 1;
            flora.scree(new RockScatter(rocks, RockScatter.Parameters.DEFAULTS, (x, z) -> ground.height(x, z)),
                tallest.x()[last], tallest.z()[last], 320, 22, named);
        }
        // the water: its channels, falls, stones, spray and mist placed in the scene (effects-water)
        if (rivers != null) {
            WaterMaterials.register(assets.resources().materials());
            WaterScene.Look look = WaterScene.Look.of(WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
                // the falls as veils: thinner where whole, their sides thinning in over most of the half width
                WaterMaterials.sheet(FallingWaterParameters.DEFAULTS.withOpacity(0.7f).withEdge(0.65f)
                    .withVeil(0.35f)).ref(),
                WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(), surfaces.scan(Surfaces.LIMESTONE));
            // the materials loaded first: the falls displace, so theirs must be there before their nodes
            for (var m : List.of(look.river(), look.sheet(), look.jets())) {
                assets.cache().acquire(m);
            }
            long until = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            while (System.nanoTime() < until && List.of(look.river(), look.sheet(), look.jets()).stream()
                .anyMatch(m -> assets.cache().get(m) == null && assets.cache().failure(m) == null)) {
                assets.drain();
                Thread.sleep(5);
            }
            water = new WaterScene(renderer.scene(), new WaterAssets(assets.paths()), rivers, look)
                .stones(procedural).effects(renderer.effects()).place(graph.settings());
            renderer.water(WaterWetness.of(rivers, graph.settings()).upload(device));
            System.out.printf("Water: %d nodes, %d sprays%n", water.nodes().size(), water.sprays().size());
        } else {
            water = null;
        }
        flora.hold();
        village.awaitModels(Duration.ofMinutes(3));
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!flora.modelsLoaded() && System.nanoTime() < deadline) {
            assets.drain();
            Thread.sleep(10);
        }
        flora.placeRocks();
        day = new DayDriver(renderer, village, ground, layout, site != null);
    }

    /** The whole chain in {@code port}, seeing what {@code camera} sees. */
    public SceneView view(ViewPort port, Camera camera) {
        flora.viewer(camera);
        return renderer.view(port, camera);
    }

    /**
     * One frame's update, {@code seconds} after the last, the day as {@code state}, the camera placed: streaming about
     * it, the day's lights, weather and effects, then the renderer's syncs. Before the graph records.
     */
    public void frame(double seconds, DayState state, Camera camera, double viewportHeight) {
        Profiler.begin(VILLAGE_ZONE);
        village.update(camera.position(0), camera.position(1), camera.position(2), Maths.toRadians(60),
            viewportHeight);
        Profiler.end(VILLAGE_ZONE);
        Profiler.begin(DAY_ZONE);
        day.apply(state, camera, seconds);
        Profiler.end(DAY_ZONE);
        Profiler.begin(FLORA_ZONE);
        flora.update(state, camera, seconds);
        Profiler.end(FLORA_ZONE);
        renderer.indoors(layout.inside(camera.position(0), camera.position(2)) != null);
        Profiler.begin(RENDERER_ZONE);
        renderer.update(seconds, seconds * state.weatherRate, DayDriver.gameSeconds(state), camera);
        Profiler.end(RENDERER_ZONE);
    }

    // the showcase's own CPU zones in the engine's profiler (the app's timeline, the measured tour's recordings)
    private static final com.truesim.engine.app.profile.Zone VILLAGE_ZONE = Profiler.zone("showcase.village");
    private static final com.truesim.engine.app.profile.Zone DAY_ZONE = Profiler.zone("showcase.day");
    private static final com.truesim.engine.app.profile.Zone FLORA_ZONE = Profiler.zone("showcase.flora");
    private static final com.truesim.engine.app.profile.Zone RENDERER_ZONE = Profiler.zone("showcase.renderer");

    /** Whether the streamed content about the camera and everything it asked of the GPU is there. */
    public boolean ready() {
        return village.ready() && flora.ready() && renderer.assets().resources().loaded();
    }

    /**
     * Whether the flyover may start: the ground's tiles of its first view built, and every plant's model, texture and
     * impostor loaded and baked (else the near trees stood leafless and the impostors pale, baked before their
     * textures were in); the village's cells and the terrain's finer tiles stream in as it flies.
     */
    public boolean groundReady() {
        return village.groundReady() && flora.ready() && renderer.assets().resources().loaded();
    }

    /** What {@link #ready} still waits for (a pre-roll that timed out says so). */
    public String waitingFor() {
        return "village " + village.ready() + ", plant models " + flora.modelsLoaded() + ", scatters waiting "
            + flora.waitingScatters() + ", resources loaded " + renderer.assets().resources().loaded();
    }

    public ShowcaseRenderer renderer() {
        return renderer;
    }

    /** The terrain's site, or null (the valley made from functions). */
    public TerrainSite site() {
        return site;
    }

    public VillageLayout layout() {
        return layout;
    }

    /** The Lauterbrunnen site through the asset manager, waited for. */
    private static TerrainSite site(ShowcaseAssets assets) throws InterruptedException {
        AssetRef<TerrainSite> ref = TerrainAssets.site(assets.paths(), "lauterbrunnen");
        assets.cache().acquire(ref); // held while the world is
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (assets.cache().get(ref) == null) {
            if (assets.cache().failure(ref) != null || System.nanoTime() > deadline) {
                System.err.println("Could not load the terrain site: " + assets.cache().failure(ref));
                return null;
            }
            assets.drain();
            Thread.sleep(5);
        }
        System.out.println("Terrain: " + assets.cache().get(ref).title() + "; " + String.join("; ",
            assets.cache().get(ref).attributions()));
        return assets.cache().get(ref);
    }

    public Ground ground() {
        return ground;
    }

    /** The tour through this world: the village's shots along its main street, the café's through the inn's door. */
    public com.truesim.samples.showcase.tour.TourScript tour() {
        return new com.truesim.samples.showcase.tour.TourScript(ground, layout.originX(), layout.originZ(),
            site != null, layout.streetSouth(), layout.innDoor()).clear((x, z) -> layout.near(x, z, 3.5) != null)
            .water(riverBank(), fall());
    }

    /**
     * The Weisse Luetschine where it is widest within 1.5 km of the village (off its buildings): the point, the way it
     * flows and its half width; or null.
     */
    private double[] riverBank() {
        if (rivers == null) {
            return null;
        }
        double ox = layout.originX();
        double oz = layout.originZ();
        double most = 0;
        for (var c : rivers.channels()) {
            for (int i = 0; i < c.count(); i++) {
                most = Math.max(most, c.discharge(i));
            }
        }
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (var c : rivers.channels()) {
            for (int i = 0; i + 1 < c.count(); i++) {
                if (c.discharge(i) < 0.5 * most) {
                    continue; // the river, not its brooks
                }
                double dx = c.x(i) - ox;
                double dz = c.z(i) - oz;
                double d = dx * dx + dz * dz;
                double score = d > 1500 * 1500 ? Double.MAX_VALUE : -c.width(i) + Math.sqrt(d) / 500;
                if (score < best && layout.near(c.x(i), c.z(i), 25) == null) {
                    double fx = c.x(i + 1) - c.x(i);
                    double fz = c.z(i + 1) - c.z(i);
                    double l = Math.max(Math.sqrt(fx * fx + fz * fz), 1e-6);
                    best = score;
                    // the camera's way and where it looks, along the channel itself (it bends)
                    double[] e0 = along(c, i, -8);
                    double[] e1 = along(c, i, 8);
                    double[] t0 = along(c, i, 4);
                    double[] t1 = along(c, i, 16);
                    out = new double[] {c.x(i), c.z(i), fx / l, fz / l, c.width(i) / 2, e0[0], e0[1], e1[0], e1[1],
                        t0[0], t0[1], t1[0], t1[1]};
                }
            }
        }
        double widest = 0;
        for (var c : rivers.channels()) {
            for (int i = 0; i < c.count(); i++) {
                widest = Math.max(widest, c.width(i));
            }
        }
        if (out != null) {
            System.out.printf(java.util.Locale.ROOT, "River close-up: %.1f m wide at (%.0f, %.0f), %.0f m from the "
                + "village; the widest anywhere %.1f m%n", out[4] * 2, out[0] - ox, out[1] - oz,
                Math.sqrt((out[0] - ox) * (out[0] - ox) + (out[1] - oz) * (out[1] - oz)), widest);
        }
        return out;
    }

    /** The point {@code metres} along channel {@code c} from its point {@code i} (back where negative): x, z. */
    private static double[] along(com.truesim.common.procedural.water.Channel c, int i, double metres) {
        int step = metres < 0 ? -1 : 1;
        double left = Math.abs(metres);
        double x = c.x(i);
        double z = c.z(i);
        for (int j = i + step; j >= 0 && j < c.count(); j += step) {
            double dx = c.x(j) - x;
            double dz = c.z(j) - z;
            double l = Math.sqrt(dx * dx + dz * dz);
            if (l >= left) {
                return new double[] {x + dx * left / l, z + dz * left / l};
            }
            left -= l;
            x = c.x(j);
            z = c.z(j);
        }
        return new double[] {x, z};
    }

    /** The tallest fall's foot and lip (x, y, z each); or null. */
    private double[] fall() {
        if (rivers == null || rivers.falls().isEmpty()) {
            return null;
        }
        var p = rivers.tallest().path();
        int last = p.x().length - 1;
        return new double[] {p.x()[last], p.y()[last], p.z()[last], p.x()[0], p.y()[0], p.z()[0]};
    }

    public Flora flora() {
        return flora;
    }

    public Village village() {
        return village;
    }

    public DayDriver day() {
        return day;
    }

    @Override
    public void close() {
        village.close();
        flora.close();
        if (water != null) {
            water.close();
        }
        renderer.close();
    }
}
