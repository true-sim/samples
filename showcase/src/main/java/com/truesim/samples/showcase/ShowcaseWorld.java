package com.truesim.samples.showcase;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.assets.Surfaces;
import com.truesim.samples.showcase.day.DayDriver;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.fx.FxTextures;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.Ground;
import com.truesim.samples.showcase.world.ValleyGround;
import com.truesim.samples.showcase.world.Village;
import com.truesim.samples.showcase.world.VillageLayout;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.render.TextureData;
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

    public ShowcaseWorld(VulkanDevice device, FrameGraph graph, ShowcaseAssets assets) throws InterruptedException {
        ground = new ValleyGround();
        renderer = new ShowcaseRenderer(device, graph, assets, ground);
        layout = new VillageLayout();
        Surfaces surfaces = new Surfaces(assets);
        village = new Village(renderer, ground, layout, surfaces);
        // the particles' textures, held and loaded before the first fire
        for (AssetRef<TextureData> ref : List.of(FxTextures.FLAME, FxTextures.GLOW, FxTextures.EMBER, FxTextures.SMOKE,
            FxTextures.SMOKE_NORMALS)) {
            assets.resources().hold(ref);
        }
        village.awaitModels(Duration.ofMinutes(3));
        day = new DayDriver(renderer, village, ground);
    }

    /** The whole chain in {@code port}, seeing what {@code camera} sees. */
    public SceneView view(ViewPort port, Camera camera) {
        return renderer.view(port, camera);
    }

    /**
     * One frame's update, {@code seconds} after the last, the day as {@code state}, the camera placed: streaming about
     * it, the day's lights, weather and effects, then the renderer's syncs. Before the graph records.
     */
    public void frame(double seconds, DayState state, Camera camera, double viewportHeight) {
        village.update(camera.position(0), camera.position(1), camera.position(2), Math.toRadians(60),
            viewportHeight);
        day.apply(state, camera, seconds);
        renderer.update(seconds, seconds * state.weatherRate, DayDriver.gameSeconds(state), camera);
    }

    /** Whether the streamed content about the camera and everything it asked of the GPU is there. */
    public boolean ready() {
        return village.ready() && renderer.assets().resources().loaded();
    }

    public ShowcaseRenderer renderer() {
        return renderer;
    }

    public Ground ground() {
        return ground;
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
        renderer.close();
    }
}
