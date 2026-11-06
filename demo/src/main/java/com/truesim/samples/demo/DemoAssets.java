package com.truesim.samples.demo;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.RenderAssets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The one asset manager every part of a sample loads through: the asset cache, assets by path ({@code demo://}: the
 * folder {@link DemoSettings#ASSETS} names, with glTF and image importers) and their GPU side (geometry pool,
 * materials, textures: {@link SceneResources}), shared by every scene sync, sound bank and effect of the sample. In an
 * application it is the engine's {@link Assets} plugin's ({@link #of}); a program without an engine (a benchmark, the
 * showcase) makes one of its own ({@link #standalone}), on a task scheduler it drains each frame ({@link #drain}).
 * Nothing here reads a file or builds GPU content by hand: meshes, materials, models, clips and sounds are asset
 * references, generated or imported.
 */
final class DemoAssets implements AutoCloseable {

    /** The mount sample content is under: {@code demo://Fox/glTF/Fox.gltf}. */
    static final String MOUNT = "demo";

    private final AssetCache cache;
    private final PathAssets paths;
    private final SceneResources resources;
    private final TaskScheduler tasks; // drained here when standalone; the engine drains its own
    private final boolean owned;

    private DemoAssets(AssetCache cache, PathAssets paths, SceneResources resources, TaskScheduler tasks,
        boolean owned, EngineSettings settings) {
        this.cache = cache;
        this.paths = paths;
        this.resources = resources;
        this.tasks = tasks;
        this.owned = owned;
        Path folder = Path.of(settings.get(DemoSettings.ASSETS));
        if (Files.isDirectory(folder)) {
            paths.mounts().add(MOUNT, PathSource.folder(folder));
        }
        new Gltf(paths).register();
        for (String extension : List.of("png", "jpg", "jpeg")) {
            paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
        }
    }

    /** The engine's assets ({@link Assets}): its cache, paths and resources, with the samples' mount and importers. */
    static DemoAssets of(Assets assets, TaskScheduler engineTasks, EngineSettings settings) {
        return new DemoAssets(assets.cache(), assets.paths(), assets.resources(), engineTasks, false, settings);
    }

    /** Assets of their own, for a program without an engine: the GPU side on {@code device}. */
    static DemoAssets standalone(VulkanDevice device, FrameSlots slots, EngineSettings settings) {
        TaskScheduler tasks = new TaskScheduler();
        AssetCache cache = new AssetCache(tasks);
        GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 21, 1 << 16, 1 << 12), slots);
        Materials materials = new Materials(device, 4096, slots);
        Textures textures = new Textures(device, slots);
        SceneResources resources = new SceneResources(cache, pool, materials, textures).settings(settings);
        return new DemoAssets(cache, new PathAssets(cache), resources, tasks, true, settings);
    }

    AssetCache cache() {
        return cache;
    }

    /** Assets by path: {@code demo://...}. */
    PathAssets paths() {
        return paths;
    }

    /** The GPU side, which every scene sync shares. */
    SceneResources resources() {
        return resources;
    }

    GeometryPool pool() {
        return resources.pool();
    }

    /** The tasks loads finish on. */
    TaskScheduler tasks() {
        return tasks;
    }

    /** The reference to the {@code type} asset at {@code path} ({@code demo://...}). */
    <T> AssetRef<T> ref(com.truesim.scene.assets.AssetType<T> type, String path) {
        return paths.ref(type, Objects.requireNonNull(path, "path"));
    }

    /** Finishes the loads done: each frame, standalone; the engine does it for its own. */
    void drain() {
        if (owned) {
            tasks.drainCompleted();
        }
    }

    /** {@code ref}, loaded: acquired (held until released), waited for at most a minute. */
    <T> T await(AssetRef<T> ref) throws InterruptedException {
        cache.acquire(ref);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (cache.get(ref) == null && cache.failure(ref) == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Loading " + ref + " took too long");
            }
            Thread.sleep(1);
            tasks.drainCompleted();
        }
        if (cache.failure(ref) != null) {
            throw new IllegalStateException("Could not load " + ref, cache.failure(ref));
        }
        return cache.get(ref);
    }

    /** Waits until every asset the scenes asked for is on the GPU (or a minute passed). */
    void awaitLoaded() {
        resources.awaitLoaded(tasks, java.time.Duration.ofMinutes(1));
    }

    @Override
    public void close() {
        if (owned) {
            resources.close();
            resources.textures().close();
            resources.materials().close();
            resources.pool().close();
            cache.close();
            tasks.close();
        }
    }
}
