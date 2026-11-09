package com.truesim.samples.showcase;

import com.truesim.common.math.Maths;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.common.procedural.assets.SettlementSettings;
import com.truesim.common.procedural.ecology.AlpineSpecies;
import com.truesim.common.procedural.ecology.Ecology;
import com.truesim.common.procedural.ecology.EcologyParameters;
import com.truesim.common.procedural.ecology.EcologyTerrain;
import com.truesim.common.procedural.ecology.rocks.RockField;
import com.truesim.common.procedural.ecology.rocks.RockFieldParameters;
import com.truesim.common.procedural.ecology.terrain.TalusModifier;
import com.truesim.common.procedural.ecology.terrain.TerrainEcology;
import com.truesim.common.procedural.settlement.terrain.SettlementTerrain;
import com.truesim.effects.terrain.data.TerrainModifier;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.common.procedural.settlement.Settlement;
import com.truesim.common.procedural.settlement.Settlements;
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
import com.truesim.effects.terrain.data.TerrainAssets;
import com.truesim.effects.terrain.data.TerrainSite;
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

    /** Where the square lies in Lauterbrunnen's frame (x east, z south of the church), metres. */
    public static final double VILLAGE_X = -130;
    public static final double VILLAGE_Z = 300;

    public ShowcaseWorld(VulkanDevice device, FrameGraph graph, ShowcaseAssets assets) throws InterruptedException {
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
        if (ground instanceof TerrainGround t) {
            double ox = plan.square().x();
            double oz = plan.square().z();
            modifiers = TerrainModifiers.of(new TalusModifier(rocks, TalusModifier.Parameters.DEFAULTS,
                new TerrainModifier.Bounds(ox - 1500, oz - 1500, ox + 1500, oz + 1500)))
                .with(SettlementTerrain.of(plan));
            t.modify(modifiers);
        }
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
        flora = new Flora(renderer, ground, layout, placing, rocks, named, cache);
        flora.hold();
        village.awaitModels(Duration.ofMinutes(3));
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!flora.ready() && System.nanoTime() < deadline) {
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
        village.update(camera.position(0), camera.position(1), camera.position(2), Maths.toRadians(60),
            viewportHeight);
        day.apply(state, camera, seconds);
        flora.update(state, camera, seconds);
        renderer.indoors(layout.inside(camera.position(0), camera.position(2)) != null);
        renderer.update(seconds, seconds * state.weatherRate, DayDriver.gameSeconds(state), camera);
    }

    /** Whether the streamed content about the camera and everything it asked of the GPU is there. */
    public boolean ready() {
        return village.ready() && flora.ready() && renderer.assets().resources().loaded();
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
            site != null, layout.streetSouth(), layout.innDoor()).clear((x, z) -> layout.near(x, z, 3.5) != null);
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
        renderer.close();
    }
}
