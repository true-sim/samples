package com.truesim.samples.demo;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderSettings;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.scene.CameraSync;
import com.truesim.engine.scene.LightSync;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshPool;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneBatch;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The stream benchmark's world, shared by its headless run ({@link StreamBench}) and its window
 * ({@link StreamWindow}): a camera flying over a grid of cells, cells coming into range getting content of their own
 * (meshes, a texture, materials, 40 rocks and a ground tile) and cells leaving range losing it, lit by a sun.
 *
 * <p>The camera and the sun are scene nodes: a {@link CameraLens} drawn through a {@link CameraSync}, a
 * {@link LightSource} placed in the renderer's lights by a {@link LightSync}. How cells reach the GPU is the
 * {@link StreamBench.Mode}:
 *
 * <ul>
 *   <li>{@link StreamBench.Mode#SCENE} (the default), the engine's way: a cell is a subtree of the scene graph with
 *       {@link Renderable} components, built as a {@link SceneBatch} on a builder thread (where its rocks go, the
 *       references to its assets) and attached in one pass; its meshes, texture and materials are generated assets
 *       ({@link AssetRef#generated}) loaded through the one asset cache ({@link DemoAssets}), made ready for the GPU
 *       on worker threads ({@link SceneResources#prepareOn}, unless {@code direct}) and uploaded by the shared
 *       {@link SceneResources}; a {@link SceneSync} keeps the nodes as instances of the GPU scene. A cell leaving
 *       range is its subtree removed in one pass: what only it used leaves the GPU and is let go of in the cache,
 *       which keeps none of it ({@code assets.budgetMegabytes} 0 by default: the flight never comes back); its meshes'
 *       memory and pool ranges are kept for the next cells' meshes of the same sizes ({@code assets.meshPool},
 *       {@code render.meshSpares}).</li>
 *   <li>{@link StreamBench.Mode#BASELINE}, the comparison: the hand-written path the engine's way replaced, kept only
 *       to measure it against. Cells are built on executor threads of their own and put straight into a geometry
 *       pool, textures and a material table it owns, as hand-built {@link Material}s and instances set by id
 *       ({@link GpuScene#set}); their CPU-side content is freed a few frames after placing, off the frame's
 *       thread.</li>
 * </ul>
 *
 * <p>Each {@link #step} streams one frame: cells new to the range asked for, those out of it removed, the scene and
 * its syncs updated, and what streamed counted ({@link #meshesAdded} and the rest). Cells are an indexed list searched
 * by key and the range is arithmetic, so a step allocates nothing but the content of cells coming into range. Use it
 * on one thread, the asset cache's.
 */
final class StreamFlight implements AutoCloseable {

    static final int MESHES_PER_CELL = 3;
    static final int INSTANCES_PER_CELL = 40;
    /** Frames after placing a cell before its CPU-side content is freed (the baseline's): its copies recorded by then. */
    static final int FREE_AFTER = 4;

    // ---------------------------------------------------------------- the scene way's assets: recipes, not data

    /** Rock {@code index} of a cell, of {@code subdivisions}: a cache entry of its own however alike. */
    record Rock(int subdivisions, int index) {
    }

    /** The ground tile of cell {@code key}, {@code quads} by {@code quads}: its heights the world's. */
    record Ground(long key, int quads) {
    }

    /** A cell's texture, {@code size} by {@code size}: colours of its seed. */
    record Texels(int size) {
    }

    /** A material with {@code texture} as its base colour. */
    record Painted(AssetRef<TextureData> texture) {
    }

    /** A plain material: a base colour, and a roughness unless negative (the default's then). */
    record Plain(float red, float green, float blue, float roughness) {
    }

    /**
     * The scene way's meshes let go of, which the generators build into ({@link DemoAssets#meshPool()}): set as a
     * flight starts, read by the generators (constants: their identity is part of their assets' names).
     */
    static volatile MeshPool meshPool;
    static final Generator<Rock, MeshData> ROCKS = (rock, seed) -> DemoMeshes.rock(rock.subdivisions(), meshPool);
    static final Generator<Ground, MeshData> GROUNDS = (ground, seed) -> DemoMeshes.groundTile(baseX(ground.key()),
        baseZ(ground.key()), StreamBench.CELL, ground.quads(), meshPool);
    static final Generator<Texels, TextureData> TEXELS = (texels, seed) -> {
        int size = texels.size();
        MemorySegment memory = MemorySegment.ofArray(new int[size * size]); // on the heap: the collector frees it
        paint(seed, size, memory);
        return new TextureData(size, size, TextureData.ColorSpace.SRGB, memory);
    };
    static final Generator<Painted, MaterialData> PAINTED = (painted, seed) -> MaterialData.builder(
        MaterialData.METALLIC_ROUGHNESS).texture("baseColorTexture", painted.texture()).build();
    static final Generator<Plain, MaterialData> PLAIN = (plain, seed) -> {
        MaterialData.Builder material = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", plain.red(), plain.green(), plain.blue(), 1f);
        return (plain.roughness() < 0 ? material : material.set("roughness", plain.roughness())).build();
    };
    /** The ground tiles' material: one for every cell. */
    static final List<AssetRef<MaterialData>> GROUND = List.of(AssetRef.generated(RenderAssets.MATERIAL, PLAIN,
        new Plain(0.3f, 0.45f, 0.25f, -1), 0));
    /** The rocks' untextured material, one per cell (as the baseline adds two materials a cell). */
    private static final Plain STONE = new Plain(0.6f, 0.5f, 0.4f, 0.7f);

    /** A cell in range: its nodes (the scene way), or what it put on the GPU once built (the baseline). */
    static final class Cell {
        final long key;
        final long seed;
        // the scene way's: its subtree's root once attached, and a rock of it (drawn once the cell is placed)
        long root = NodeHandle.NONE;
        long rock = NodeHandle.NONE;
        // the scene way's nodes, built as a batch on a builder thread (placements worked out there), attached later
        CompletableFuture<SceneBatch> placing;
        // the baseline's
        CompletableFuture<Built> building;
        int[] meshes = new int[0];
        int texture = -1;
        int[] materials = new int[0];
        int[] instances = new int[0];
        volatile boolean dropped; // left range before its build began, so the build is skipped
        Built placedContent; // its CPU-side content, freed a few frames after placing (once its copies recorded)
        long freeAt;

        Cell(long key) {
            this.key = key;
            this.seed = seedOf(key);
        }
    }

    /**
     * A baseline cell's content, made on a worker thread: its meshes and its texture's texels, and (unless
     * {@code direct}) both prepared for the GPU there too, so the frame's thread only adds them.
     */
    record Built(List<MeshData> meshes, Arena arena, MemorySegment texels, int textureSize,
        List<GeometryPool.Prepared> preparedMeshes, Textures.Prepared preparedTexture) {

        /**
         * Frees the CPU-side content: the meshes' buffers (a mesh built by hand is closed by its builder once uploaded:
         * a shared arena each, which nothing else frees) and the texels.
         */
        void free() {
            meshes.forEach(MeshData::close);
            arena.close();
            StreamBench.UNFREED.decrementAndGet();
        }

        /** Built but never placed: its prepared content freed too. */
        void discard() {
            if (preparedMeshes != null) {
                preparedMeshes.forEach(GeometryPool.Prepared::close);
                preparedTexture.close();
            }
            free();
        }
    }

    // Closing a shared arena makes every thread reach a safepoint (up to milliseconds): not on the frame's thread.
    private static final ExecutorService FREES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "stream frees");
        thread.setDaemon(true);
        return thread;
    });

    private final StreamBench.Mode mode;
    private final VulkanDevice device;
    private final FrameSlots slots;
    private final SceneGraph scene;
    private final ComponentStore<Renderable> renderables;
    private final long cameraNode;
    private final Camera camera = new Camera();
    private final CameraSync cameraSync;
    private final Lights lights;
    private final LightSync lightSync;
    private final ArrayList<Cell> cells = new ArrayList<>();
    private final GeometryPool pool;
    private final Materials materials;
    private final Textures textures;
    private final GpuScene gpu;
    private final RayTracing rayTracing; // or null
    // the scene way's
    private final DemoAssets assets;
    private final boolean ownAssets; // standalone: drained and closed here
    private final SceneResources resources;
    private final SceneSync sync;
    // the baseline's
    private final ExecutorService builders;
    private final java.util.ArrayDeque<Integer> freeIds = new java.util.ArrayDeque<>();
    private final int groundMaterial;
    private final int[] added = new int[1];
    private MeshletRenderer renderer;
    private Shadows sun; // the sun's cascades, kept while nothing they show moves
    private long frames;
    private long meshesSeen; // added to the pool by the end of the last step (the scene way's counts)
    private long meshesGone; // removed from the pool by then
    private int texturesSeen;

    /** Where the camera is: metres along the flight. */
    double x;
    /** Cells in range now, and of all cells kept (in range or leaving), those placed. */
    int inRange;
    int placed;
    /** What the last step streamed. */
    int meshesAdded;
    int meshesRemoved;
    /** Cells the last step put in (the scene way: attached; the baseline: placed) and took out. */
    int cellsIn;
    int cellsOut;
    int texturesAdded;
    int texturesRemoved;
    /** The scene way's frame-thread time: taking in what arrived (ms in all, worst), and updating the scene. */
    final double[] takeMillis = new double[2];
    final double[] syncMillis = new double[2];
    /** The baseline's frame-thread time in the engine's calls: meshes added, textures added (ms in all, worst). */
    final double[] meshMillis = new double[2];
    final double[] textureMillis = new double[2];

    /**
     * The flight's world on {@code device}, drawn in frames of {@code slots}, as {@code settings} say. Its assets are
     * {@code engineAssets}' (an application's) or, null, its own ({@link DemoAssets#standalone}) on a scheduler it
     * drains each step; {@code threads} make cells' content ready for the GPU (the baseline: build it).
     */
    StreamFlight(VulkanDevice device, FrameSlots slots, EngineSettings settings, Assets engineAssets,
        TaskScheduler engineTasks, int threads) {
        this.mode = StreamBench.mode;
        this.device = device;
        this.slots = slots;
        int nodes = StreamBench.instanceCapacity() + 16;
        scene = new SceneGraph(new ChangeClock(), nodes, SceneGraph.DEFAULT_LOG_CAPACITY);
        renderables = scene.components().store(Renderable.TYPE);
        if (mode == StreamBench.Mode.SCENE) {
            // A flight never comes back over a cell: the cache keeps nothing let go of (the game's default here; the
            // command line's --set assets.budgetMegabytes=n over it).
            settings.set(EngineSettings.Layer.GAME, Assets.BUDGET, 0);
            if (StreamBench.inFrame) { // render.streamBudget 0: every copy in the frame, on the graphics queue
                settings.set(EngineSettings.Layer.GAME, RenderSettings.STREAM_BUDGET, 0);
            }
            ownAssets = engineAssets == null;
            assets = engineAssets == null ? DemoAssets.standalone(device, slots, settings, StreamBench.capacity(),
                StreamBench.materialCapacity()) : DemoAssets.of(engineAssets, engineTasks, settings);
            if (engineAssets == null) { // the plugin follows the setting itself
                assets.cache().setBudget((long) settings.get(Assets.BUDGET) << 20);
            }
            assets.cache().addListener(new Unfreed());
            meshPool = assets.meshPool();
            resources = assets.resources().loading(StreamBench.streamed ? SceneResources.Loading.STREAM
                : SceneResources.Loading.WHOLE);
            if (!StreamBench.direct) {
                resources.prepareOn(assets.tasks(), threads);
            }
            pool = resources.pool();
            materials = resources.materials();
            textures = resources.textures();
            gpu = new GpuScene(device, pool, StreamBench.instanceCapacity(), slots);
            sync = new SceneSync(resources, scene, gpu);
            rayTracing = StreamBench.rays && device.rayTracing() ? new RayTracing(device, pool, 512) : null;
            resources.rayTracing(rayTracing);
            builders = Executors.newFixedThreadPool(threads); // cells' placements worked out off the frame's thread
            groundMaterial = -1;
        } else {
            ownAssets = false;
            assets = null;
            resources = null;
            sync = null;
            pool = new GeometryPool(device, StreamBench.capacity(), slots);
            materials = new Materials(device, StreamBench.materialCapacity(), slots);
            textures = new Textures(device, slots);
            gpu = new GpuScene(device, pool, StreamBench.instanceCapacity(), slots);
            gpu.setMaterials(materials);
            if (StreamBench.inFrame) {
                pool.streamBudget(0);
            }
            rayTracing = StreamBench.rays && device.rayTracing() ? new RayTracing(device, pool, 512) : null;
            builders = Executors.newFixedThreadPool(threads);
            groundMaterial = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(0.3f, 0.45f, 0.25f, 1))));
            for (int id = StreamBench.instanceCapacity() - 1; id >= 1; id--) {
                freeIds.push(id);
            }
        }
        // The camera: looking ahead, down and a little aside, 70 degrees high; it flies along x.
        cameraNode = scene.create();
        Quat look = Quat.lookRotation(new Vec3(1, -0.25, 0.15), Vec3.UNIT_Y);
        scene.setRotation(cameraNode, look.x(), look.y(), look.z(), look.w());
        scene.components().set(cameraNode, CameraLens.TYPE, CameraLens.perspective(70 * Math.PI / 180, 0.1));
        cameraSync = new CameraSync(scene, cameraNode, camera);
        // The sun: shining along its node's -Z, with three cascades of shadows over 120 m.
        lights = new Lights(device, 4, 8, slots);
        lightSync = new LightSync(scene, lights);
        long sunNode = scene.create();
        Quat down = Quat.lookRotation(new Vec3(-0.4, -0.8, -0.45), Vec3.UNIT_Y);
        scene.setRotation(sunNode, down.x(), down.y(), down.z(), down.w());
        scene.components().set(sunNode, LightSource.TYPE, LightSource.directional(1f, 0.92f, 0.8f, 2.5)
            .shadow(ShadowSpec.cascades(3, 2048, 120)));
        scene.update();
        lightSync.update();
    }

    /** Counts the scene way's cell content resident in the cache ({@link StreamBench#UNFREED}): loaded, not evicted. */
    private static final class Unfreed implements AssetCache.Listener {

        @Override
        public void loaded(AssetRef<?> ref, Object data) {
            if (counted(ref)) {
                StreamBench.UNFREED.incrementAndGet();
            }
        }

        @Override
        public void evicted(AssetRef<?> ref, Object data) {
            if (counted(ref)) {
                StreamBench.UNFREED.decrementAndGet();
            }
        }

        private static boolean counted(AssetRef<?> ref) {
            return ref.isGenerated() && (ref.type() == RenderAssets.MESH || ref.type() == RenderAssets.TEXTURE);
        }
    }

    /**
     * Draws the flight in {@code port}: lit, with the sun's shadows, clustered forward shading into {@code color} and
     * {@code depth} formats. The caller gives the port its output.
     */
    void draw(ViewPort port, int color, int depth) {
        renderer = new MeshletRenderer(device, gpu, color, depth, slots);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 22).withOcclusion(true))
            .set(1, MeshletRenderer.MODE_LIT);
        sun = new Shadows(geometry, lights);
        port.add(new ClusteredForwardShading(geometry, view, color, depth).clearColor(0.4f, 0.55f, 0.8f))
            .add(new ClusteredLights(lights, view)).add(sun.view(view));
    }

    /**
     * A frame at {@code at} metres into the flight, its view {@code width} by {@code height}: cells streamed in and
     * out, the scene's camera, light and instances brought up to date. With {@code waitForBuilds} (tests) the frame
     * waits until everything asked for is on the GPU, so the flight's speed does not depend on the machine.
     */
    void step(double at, boolean waitForBuilds, int width, int height) {
        x = at;
        scene.setTranslation(cameraNode, x, 8, 0);
        meshesAdded = 0;
        meshesRemoved = 0;
        cellsIn = 0;
        cellsOut = 0;
        texturesAdded = 0;
        texturesRemoved = 0;
        int range = StreamBench.range;
        int cx = (int) Math.floor(x / StreamBench.CELL);
        if (mode == StreamBench.Mode.SCENE) {
            stepScene(cx, range, waitForBuilds);
        } else {
            stepBaseline(cx, range, waitForBuilds);
        }
        cameraSync.update(width, height);
        frames++;
    }

    /** The scene way: cells as nodes, their content assets the cache loads and the resources upload. */
    private void stepScene(int cx, int range, boolean waitForBuilds) {
        long since = System.nanoTime();
        assets.drain(); // loads and preparing done: uploaded (standalone; the engine drains its own at the frame start)
        spent(takeMillis, since);
        int arrivedTextures = textures.count();
        texturesAdded += arrivedTextures - texturesSeen;
        for (int i = cx - range; i <= cx + range; i++) {
            for (int j = -range; j <= range; j++) {
                long key = (long) i << 32 | (j & 0xffffffffL);
                if (find(key) < 0) {
                    cells.add(enter(new Cell(key)));
                }
            }
        }
        // Cells whose placements arrived get their nodes, within the frame's streaming budget (render.streamFrameBudget,
        // at least one cell): a row of cells entering at once spreads over a few frames
        long attachUntil = since + (long) (resources.settings().get(RenderSettings.STREAM_FRAME_BUDGET) * 1e6);
        for (int c = 0; c < cells.size(); c++) {
            Cell cell = cells.get(c);
            if (cell.root == NodeHandle.NONE && (waitForBuilds || cell.placing.isDone())) {
                attach(cell);
                if (!waitForBuilds && System.nanoTime() - attachUntil > 0) {
                    break;
                }
            }
        }
        inRange = 0;
        placed = 0;
        for (int c = cells.size() - 1; c >= 0; c--) {
            Cell cell = cells.get(c);
            if (!wanted(cell, cx, range)) {
                if (cell.root != NodeHandle.NONE) {
                    cellsOut++;
                    scene.remove(cell.root); // its subtree, renderables with it, in one pass: what only it used leaves
                } else { // the GPU and the cache
                    cell.placing.thenAccept(StreamFlight::recycle); // never attached: its batch back when built
                }
                cells.set(c, cells.get(cells.size() - 1));
                cells.remove(cells.size() - 1);
            } else {
                inRange++;
            }
        }
        since = System.nanoTime();
        scene.update();
        sync.update();
        lightSync.update();
        spent(syncMillis, since);
        int leftTextures = textures.count();
        // gone at the update: the textures only the cells removed used
        texturesRemoved = Math.max(0, arrivedTextures - leftTextures);
        if (waitForBuilds) {
            while (resources.loadingCount() > 0 || resources.preparingCount() > 0) {
                LockSupport.parkNanos(100_000);
                assets.drain();
            }
            sync.update(); // what arrived drawn this frame
        }
        int textureCount = textures.count();
        texturesAdded += Math.max(0, textureCount - leftTextures);
        // the pool's flow since the last step: what arrived here, at the engine's drain or at the update, and what left
        meshesAdded = (int) (pool.addedCount() - meshesSeen);
        meshesRemoved = (int) (pool.removedCount() - meshesGone);
        meshesSeen = pool.addedCount();
        meshesGone = pool.removedCount();
        texturesSeen = textureCount;
        for (int c = 0; c < cells.size(); c++) {
            Cell cell = cells.get(c);
            placed += cell.rock != NodeHandle.NONE && sync.slotOf(cell.rock) >= 0 ? 1 : 0;
        }
    }

    /**
     * A cell coming into range, the scene way: its rocks and ground tile as nodes with renderables of generated
     * meshes, a texture and two materials of its own (the ground's material shared), asked for at the next update.
     */
    private Cell enter(Cell cell) {
        // Its nodes (where its rocks go: the terrain's height is noise, costly) and the references to its assets made
        // as a batch on a builder thread, attached once done, in a later step: the frame's thread only puts it in
        boolean large = StreamBench.large;
        cell.placing = CompletableFuture.supplyAsync(() -> build(cell, large), builders);
        return cell;
    }

    /** {@code cell}'s meshes: generated rocks and its ground tile, or (large) its terrain tile and a rock. */
    private static List<AssetRef<MeshData>> meshesOf(Cell cell, boolean large) {
        List<AssetRef<MeshData>> meshes = new ArrayList<>();
        if (large) { // 66,000 vertices, 131,000 triangles (heavy: 295,000)
            meshes.add(AssetRef.generated(RenderAssets.MESH, GROUNDS,
                new Ground(cell.key, StreamBench.heavy ? 384 : 256), cell.seed));
            meshes.add(AssetRef.generated(RenderAssets.MESH, ROCKS, new Rock(4, 0), cell.seed));
        } else {
            for (int m = 0; m < MESHES_PER_CELL; m++) {
                meshes.add(AssetRef.generated(RenderAssets.MESH, ROCKS, new Rock(rockDetail(cell.seed, m), m),
                    cell.seed));
            }
            meshes.add(AssetRef.generated(RenderAssets.MESH, GROUNDS, new Ground(cell.key, 24), cell.seed));
        }
        return meshes;
    }

    /** {@code cell}'s two rock materials: one painted with its texture, one plain. */
    private static List<List<AssetRef<MaterialData>>> rockMaterialsOf(Cell cell) {
        AssetRef<TextureData> texture = AssetRef.generated(RenderAssets.TEXTURE, TEXELS,
            new Texels(textureSize()), cell.seed);
        return List.of(
            List.of(AssetRef.generated(RenderAssets.MATERIAL, PAINTED, new Painted(texture), cell.seed)),
            List.of(AssetRef.generated(RenderAssets.MATERIAL, PLAIN, STONE, cell.seed)));
    }

    /** Batches attached and cleared, for the builders to fill again: streaming allocates no batches once warm. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<SceneBatch> BATCHES =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void recycle(SceneBatch batch) {
        batch.clear();
        BATCHES.add(batch);
    }

    /**
     * {@code cell}'s nodes as a batch, on a builder thread: a root, under it the rocks (x, y, z and scale from the
     * terrain's heights) and the ground tile (small cells), with renderables of the cell's meshes and materials.
     */
    private static SceneBatch build(Cell cell, boolean large) {
        List<AssetRef<MeshData>> meshes = meshesOf(cell, large);
        List<List<AssetRef<MaterialData>>> rockMaterials = rockMaterialsOf(cell);
        double baseX = baseX(cell.key);
        double baseZ = baseZ(cell.key);
        SceneBatch batch = BATCHES.poll();
        if (batch == null) {
            batch = new SceneBatch(INSTANCES_PER_CELL + 2);
        }
        int root = batch.add();
        SplittableRandom random = new SplittableRandom(cell.key);
        for (int i = 0; i < INSTANCES_PER_CELL; i++) {
            double x = baseX + random.nextDouble(StreamBench.CELL);
            double z = baseZ + random.nextDouble(StreamBench.CELL);
            double s = 0.3 + random.nextDouble(1.5);
            int node = batch.add(root);
            if (large && i == 0) { // the cell's terrain tile, textured
                batch.setTranslation(node, baseX, 0, baseZ);
                batch.set(node, Renderable.TYPE, Renderable.of(meshes.get(0), rockMaterials.get(0)));
                continue;
            }
            batch.setTranslation(node, x, DemoMeshes.height(x, z) + s * 0.6, z);
            batch.setScale(node, s, s, s);
            batch.set(node, Renderable.TYPE, Renderable.of(large ? meshes.get(1) : meshes.get(i % MESHES_PER_CELL),
                rockMaterials.get(i % 2)));
        }
        if (!large) { // the cell's ground tile, after the rocks
            int ground = batch.add(root);
            batch.setTranslation(ground, baseX, 0, baseZ);
            batch.set(ground, Renderable.TYPE, Renderable.of(meshes.get(MESHES_PER_CELL), GROUND));
        }
        batch.prepare(); // world transforms here too
        return batch;
    }

    /** Puts {@code cell}'s batch into the scene, in one pass, and hands the batch back to the builders. */
    private void attach(Cell cell) {
        SceneBatch batch = cell.placing.join();
        cell.root = scene.attach(batch, NodeHandle.NONE);
        cellsIn++;
        cell.rock = scene.firstChild(cell.root);
        recycle(batch);
    }

    /** The baseline: cells built on executor threads and put straight into the pool, textures and materials. */
    private void stepBaseline(int cx, int range, boolean waitForBuilds) {
        added[0] = 0;
        for (int i = cx - range; i <= cx + range; i++) {
            for (int j = -range; j <= range; j++) {
                long key = (long) i << 32 | (j & 0xffffffffL);
                if (find(key) < 0) {
                    Cell cell = new Cell(key);
                    cell.building = CompletableFuture.supplyAsync(() -> cell.dropped ? null : build(cell), builders);
                    cells.add(cell);
                }
            }
        }
        for (int c = 0; c < cells.size(); c++) {
            Cell cell = cells.get(c);
            if (waitForBuilds && cell.building != null) {
                cell.building.join();
            }
            if (cell.building != null && cell.building.isDone()) {
                Built built = cell.building.join(); // null: left range before its build began
                if (built != null) {
                    cellsIn++;
                    texturesAdded += place(cell, built);
                    cell.freeAt = frames + FREE_AFTER;
                }
                cell.building = null;
            }
            freePlaced(cell, frames);
        }
        inRange = 0;
        placed = 0;
        for (int c = cells.size() - 1; c >= 0; c--) {
            Cell cell = cells.get(c);
            boolean wanted = wanted(cell, cx, range);
            if (!wanted && cell.building != null) {
                cell.dropped = true; // a build not begun yet is skipped (the flight outran the builders)
            }
            if (!wanted && cell.building == null) {
                texturesRemoved += cell.texture >= 0 ? 1 : 0;
                cellsOut += cell.meshes.length > 0 ? 1 : 0;
                meshesRemoved += clear(cell);
                cells.set(c, cells.get(cells.size() - 1));
                cells.remove(cells.size() - 1);
            } else {
                inRange += wanted ? 1 : 0;
                placed += cell.building == null ? 1 : 0;
            }
        }
        meshesAdded = added[0];
        scene.update();
        lightSync.update();
    }

    /**
     * A baseline cell's content, on a worker thread: rocks of their own shapes and the cell's ground tile (its heights
     * the world's, so the rocks stand on it), and a texture's texels; prepared for the GPU there unless {@code direct}.
     */
    private Built build(Cell cell) {
        List<MeshData> meshes = new ArrayList<>();
        double baseX = baseX(cell.key);
        double baseZ = baseZ(cell.key);
        if (StreamBench.large) {
            meshes.add(DemoMeshes.groundTile(baseX, baseZ, StreamBench.CELL, StreamBench.heavy ? 384 : 256));
            meshes.add(DemoMeshes.rock(4));
        } else {
            for (int m = 0; m < MESHES_PER_CELL; m++) {
                meshes.add(DemoMeshes.rock(rockDetail(cell.seed, m)));
            }
            meshes.add(DemoMeshes.groundTile(baseX, baseZ, StreamBench.CELL, 24)); // a quad a metre: 1,152 triangles
        }
        int size = textureSize();
        Arena arena = Arena.ofShared(); // freed with the meshes (Built.free), not left to the collector
        StreamBench.UNFREED.incrementAndGet();
        MemorySegment texels = arena.allocate(4L * size * size);
        paint(cell.seed, size, texels);
        if (StreamBench.direct) {
            return new Built(meshes, arena, texels, size, null, null);
        }
        List<GeometryPool.Prepared> prepared = new ArrayList<>();
        for (MeshData mesh : meshes) {
            prepared.add(pool.prepare(mesh));
        }
        return new Built(meshes, arena, texels, size, prepared, textures.prepareColor(size, size, texels));
    }

    /** Puts a built baseline cell on the GPU; the textures added. */
    private int place(Cell cell, Built content) {
        List<MeshData> built = content.meshes();
        cell.placedContent = content;
        cell.meshes = new int[built.size()];
        for (int m = 0; m < built.size(); m++) {
            long since = System.nanoTime();
            cell.meshes[m] = StreamBench.direct ? pool.add(built.get(m)) : pool.add(content.preparedMeshes().get(m));
            spent(meshMillis, since);
            if (rayTracing != null) {
                rayTracing.add(cell.meshes[m], built.get(m), built.get(m).levels().size() - 1);
            }
            added[0]++;
        }
        long since = System.nanoTime();
        cell.texture = StreamBench.direct ? textures.color(content.textureSize(), content.textureSize(),
            content.texels()) : textures.add(content.preparedTexture());
        spent(textureMillis, since);
        cell.materials = new int[] {
            materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColorTexture(cell.texture))),
            materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new Color(STONE.red(), STONE.green(), STONE.blue(), 1))
                .withRoughness(STONE.roughness())))};
        double baseX = baseX(cell.key);
        double baseZ = baseZ(cell.key);
        boolean large = StreamBench.large;
        cell.instances = new int[INSTANCES_PER_CELL + (large ? 0 : 1)];
        if (!large) { // the cell's ground tile, after the rocks' (which keep their places)
            int id = freeIds.pop();
            cell.instances[INSTANCES_PER_CELL] = id;
            gpu.set(id, cell.meshes[MESHES_PER_CELL], groundMaterial, baseX, 0.0, baseZ, 0, 0, 0, 1, 1, 1, 1);
        }
        SplittableRandom random = new SplittableRandom(cell.key);
        for (int i = 0; i < INSTANCES_PER_CELL; i++) {
            int id = freeIds.pop();
            cell.instances[i] = id;
            double x = baseX + random.nextDouble(StreamBench.CELL);
            double z = baseZ + random.nextDouble(StreamBench.CELL);
            float s = (float) (0.3 + random.nextDouble(1.5));
            if (large && i == 0) { // the cell's terrain tile, textured, once
                gpu.set(id, cell.meshes[0], cell.materials[0], baseX, 0.0, baseZ, 0, 0, 0, 1, 1, 1, 1);
                continue;
            }
            int mesh = large ? cell.meshes[1] : cell.meshes[i % MESHES_PER_CELL];
            gpu.set(id, mesh, cell.materials[i % 2], x, DemoMeshes.height(x, z) + s * 0.6, z, 0, 0, 0, 1, s, s, s);
        }
        return 1;
    }

    /** Takes a baseline cell off the GPU; the meshes removed. */
    private int clear(Cell cell) {
        freePlaced(cell, Long.MAX_VALUE);
        for (int id : cell.instances) {
            gpu.remove(id);
            freeIds.push(id);
        }
        for (int material : cell.materials) {
            materials.remove(material);
        }
        if (cell.texture >= 0) {
            textures.remove(cell.texture);
        }
        for (int mesh : cell.meshes) {
            if (rayTracing != null) {
                rayTracing.remove(mesh);
            }
            pool.remove(mesh);
        }
        return cell.meshes.length;
    }

    /** Frees a baseline cell's CPU-side content once frame {@code frame} is {@link Cell#freeAt} or later. */
    private static void freePlaced(Cell cell, long frame) {
        if (cell.placedContent != null && frame >= cell.freeAt) {
            Built content = cell.placedContent;
            FREES.execute(content::free);
            cell.placedContent = null;
        }
    }

    private static boolean wanted(Cell cell, int cx, int range) {
        return Math.abs((int) (cell.key >> 32) - cx) <= range && Math.abs((int) cell.key) <= range;
    }

    private int find(long key) {
        for (int c = 0; c < cells.size(); c++) {
            if (cells.get(c).key == key) {
                return c;
            }
        }
        return -1;
    }

    private static void spent(double[] into, long since) {
        double ms = (System.nanoTime() - since) / 1e6;
        into[0] += ms;
        into[1] = Math.max(into[1], ms);
    }

    /** Cell {@code key}'s seed: the same cell is the same content, whenever it comes. */
    static long seedOf(long key) {
        long z = key * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        return z ^ (z >>> 31);
    }

    /** Rock {@code m} of a cell of {@code seed}: 3 or 4 subdivisions. */
    private static int rockDetail(long seed, int m) {
        return 3 + (int) Math.floorMod(seed + m, 2L);
    }

    private static int textureSize() {
        return StreamBench.heavy ? 4096 : StreamBench.large ? 2048 : 256;
    }

    static double baseX(long key) {
        return (key >> 32) * StreamBench.CELL;
    }

    static double baseZ(long key) {
        return (int) key * StreamBench.CELL;
    }

    /** A cell's texels: {@code size} by {@code size} colours of {@code seed}. */
    static void paint(long seed, int size, MemorySegment texels) {
        int tint = (int) (seed * 2654435761L);
        for (int i = 0; i < size * size; i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, 0xff000000 | (tint ^ i * 31) & 0xffffff);
        }
    }

    StreamBench.Mode mode() {
        return mode;
    }

    GeometryPool pool() {
        return pool;
    }

    Textures textures() {
        return textures;
    }

    Materials materials() {
        return materials;
    }

    /** Cells kept: in range, or (the baseline) leaving once their builds finish. */
    int cellCount() {
        return cells.size();
    }

    /** The asset cache's bytes of loaded assets (the scene way), or 0. */
    long cachedBytes() {
        return assets == null ? 0 : assets.cache().residentBytes();
    }

    /**
     * Frees it all, the device idle: the cells (built ones never placed too), the renderer, the lights, the GPU scene,
     * and what it owns of the assets (the standalone ones: all of them; the engine's stay the engine's).
     */
    @Override
    public void close() {
        if (builders != null) {
            for (int c = 0; c < cells.size(); c++) {
                cells.get(c).dropped = true; // builds queued are skipped
            }
            builders.shutdown(); // the builds started finish (they prepare into the pool), then the pool closes
            try {
                builders.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        device.waitIdle();
        if (mode == StreamBench.Mode.SCENE) {
            sync.close(); // the cells' content let go of: off the GPU, out of the cache
            resources.rayTracing(null);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (ownAssets && resources.preparingCount() > 0 && System.nanoTime() < deadline) {
                assets.drain(); // what the workers still make ready, handed over to be freed (standalone)
                Thread.onSpinWait();
            }
        } else {
            for (int c = 0; c < cells.size(); c++) {
                Cell cell = cells.get(c);
                if (cell.building == null) {
                    clear(cell);
                } else if (cell.building.isDone() && !cell.building.isCompletedExceptionally()) {
                    Built unplaced = cell.building.join(); // null: skipped
                    if (unplaced != null) {
                        unplaced.discard();
                    }
                }
            }
        }
        cells.clear();
        device.waitIdle();
        if (sun != null) {
            sun.close();
        }
        if (renderer != null) {
            renderer.close();
        }
        if (rayTracing != null) {
            rayTracing.close();
        }
        lights.close();
        gpu.close();
        if (mode == StreamBench.Mode.SCENE) {
            assets.close(); // its own when standalone; nothing of the engine's
        } else {
            textures.close();
            materials.close();
            pool.close();
        }
    }
}
