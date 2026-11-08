package com.truesim.samples.showcase.assets;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.effects.atmosphere.sky.SkyAssets;
import com.truesim.effects.post.PostTextures;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.ShowcaseSettings;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.assets.mesh.MeshPool;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.RenderAssets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The one asset manager everything the showcase shows loads through: the asset cache, assets by path and their GPU
 * side ({@link SceneResources}). Poly Haven's content is mounted as {@code ph://} from the cache the fetch task fills
 * ({@link ShowcaseSettings#ASSETS}): {@code ph://street_lamp_01/street_lamp_01_2k.gltf} is a model, its textures and
 * buffers side resources next to it; {@code ph://leafy_grass/leafy_grass_diff_4k.jpg} a texture ({@code #linear} for
 * data: normals, the packed occlusion-roughness-metalness). The star catalogue (effects-atmosphere's bundled HYG) and
 * post-processing's tables mount too. In an application it is the engine's {@link Assets} plugin's ({@link #of});
 * a program without an engine makes one of its own ({@link #standalone}) and drains its tasks each frame.
 */
public final class ShowcaseAssets implements AutoCloseable {

    /** Poly Haven's mount. */
    public static final String MOUNT = "ph";

    private final AssetCache cache;
    private final PathAssets paths;
    private final SceneResources resources;
    private final TaskScheduler tasks;
    private final boolean owned;
    private final MeshPool meshPool;
    private final Path folder;
    private final Path terrain;

    private ShowcaseAssets(AssetCache cache, PathAssets paths, SceneResources resources, TaskScheduler tasks,
        boolean owned, EngineSettings settings, MeshPool meshPool) {
        this.cache = cache;
        this.paths = paths;
        this.resources = resources;
        this.tasks = tasks;
        this.owned = owned;
        this.meshPool = meshPool;
        folder = Path.of(settings.get(ShowcaseSettings.ASSETS));
        if (Files.isDirectory(folder)) {
            paths.mounts().add(MOUNT, PathSource.folder(folder));
        } else {
            System.err.println("No Poly Haven cache at " + folder + ": run ./gradlew :samples-showcase:fetchAssets "
                + "(the showcase draws its own shapes in plain colours until then)");
        }
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
        SkyAssets.install(paths);
        com.truesim.effects.terrain.data.TerrainAssets.register(paths);
        terrain = Path.of(settings.get(ShowcaseSettings.TERRAIN));
        if (Files.isRegularFile(terrain.resolve("sites/lauterbrunnen/site.terrain"))) {
            com.truesim.effects.terrain.data.TerrainAssets.mount(paths, terrain);
        }
        PostTextures.install(paths);
    }

    /** The engine's assets: its cache, paths and resources, with the showcase's mounts and importers. */
    public static ShowcaseAssets of(Assets assets, TaskScheduler engineTasks, EngineSettings settings) {
        return new ShowcaseAssets(assets.cache(), assets.paths(), assets.resources(), engineTasks, false, settings,
            assets.meshPool());
    }

    /** Assets of their own on {@code device}, for a program without an engine; meshes prepared on worker threads. */
    public static ShowcaseAssets standalone(VulkanDevice device, FrameSlots slots, EngineSettings settings) {
        TaskScheduler tasks = new TaskScheduler();
        AssetCache cache = new AssetCache(tasks);
        cache.setBudget(2048L << 20); // streamed cells come back without reloading
        GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 24, 1 << 18, 1 << 13), slots);
        Materials materials = new Materials(device, 4096, slots);
        Textures textures = new Textures(device, slots);
        SceneResources resources = new SceneResources(cache, pool, materials, textures).settings(settings)
            .prepareOn(tasks, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
        MeshPool meshPool = new MeshPool(settings.get(Assets.MESH_POOL));
        cache.recycler(RenderAssets.MESH, meshPool);
        return new ShowcaseAssets(cache, new PathAssets(cache), resources, tasks, true, settings, meshPool);
    }

    /** Ray tracing for the meshes the resources upload (bottom levels at their coarsest level but one). */
    public ShowcaseAssets rayTracing(RayTracing rays) {
        resources.rayTracing(rays, mesh -> Math.max(0, mesh.levels().size() - 2));
        return this;
    }

    /** Whether effects-terrain's cache holds the Lauterbrunnen site. */
    public boolean terrain() {
        return Files.isRegularFile(terrain.resolve("sites/lauterbrunnen/site.terrain"));
    }

    /** Whether Poly Haven's cache is there (else plain stand-ins draw). */
    public boolean polyHaven() {
        return Files.isDirectory(folder);
    }

    public AssetCache cache() {
        return cache;
    }

    public PathAssets paths() {
        return paths;
    }

    public SceneResources resources() {
        return resources;
    }

    public MeshPool meshPool() {
        return meshPool;
    }

    public TaskScheduler tasks() {
        return tasks;
    }

    /** The {@code type} asset at {@code path}. */
    public <T> AssetRef<T> ref(AssetType<T> type, String path) {
        return paths.ref(type, path);
    }

    /** Finishes the loads done: each frame, standalone; the engine does it for its own. */
    public void drain() {
        if (owned) {
            tasks.drainCompleted();
        }
    }

    /** Waits until everything the scenes asked for is on the GPU, or {@code timeout} passed. */
    public boolean awaitLoaded(Duration timeout) {
        return resources.awaitLoaded(tasks, timeout);
    }

    @Override
    public void close() {
        if (owned) {
            resources.close();
            resources.textures().close();
            resources.materials().close();
            resources.pool().close();
            cache.close();
            meshPool.clear();
            tasks.close();
        }
    }
}
