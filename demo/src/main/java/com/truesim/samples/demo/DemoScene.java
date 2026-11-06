package com.truesim.samples.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.scene.Animation;
import com.truesim.engine.scene.LightSync;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * What the demo draws, as a scene: rolling terrain with rocks on it, some of them spinning (nodes turning every
 * frame), lit by the sun and colored lights circling the middle (light nodes moving every frame). Every mesh and
 * material is an asset ({@link #TERRAIN}, {@link #ROCK}, {@link #PANE} and the materials: generated in the shared
 * {@link DemoAssets} cache, uploaded by its scene resources), every rock a node with a {@link Renderable}, every light
 * a node with a {@link LightSource}: a {@link SceneSync} keeps the instances and a {@link LightSync} the lights. With
 * {@link DemoSettings#CHARACTER} naming the Khronos fox's model, an animated fox stands among them
 * ({@link DemoCharacter}). With ray tracing ({@code rays}), every mesh the scene resources upload has a bottom level
 * (its coarsest level), skinned copies refitted as they move.
 */
final class DemoScene implements AutoCloseable {

    /** The sun's shadows: three cascades of 2048 x 2048 to 150 m. */
    static final com.truesim.scene.core.ShadowSpec SUN_SHADOWS = com.truesim.scene.core.ShadowSpec.cascades(3, 2048,
        150);

    /**
     * A showcase's arena ({@link Showcase}): rocks cleared from a circle of {@code radius} about ({@code x}, {@code z}),
     * {@code foxes} foxes of model {@code fox} (an asset path) roaming in it ({@link FoxCrowd}), at dusk: the sun low,
     * dim and orange, the circling lights off, so fires and fireworks light the ground.
     */
    record Arena(double x, double z, double radius, String fox, int foxes) {
    }

    static final int TERRAIN_SIZE = 256;
    /** Where the fox stands, if there is one: in front of the benchmark's camera. */
    static final double CHARACTER_X = 26;
    static final double CHARACTER_Z = 26;
    private static final int CIRCLING_LIGHTS = 6;
    /** Rocks dropped and moved by others: the knocking rocks, a test's props. */
    private static final int EXTRA_ROCKS = 8;

    /** A generated mesh: which, at what detail. */
    record Shape(String name, int detail) {
    }

    /** A generated material: metallic-roughness of a color, alpha blended below 1. */
    record Surface(float r, float g, float b, float a, float metallic, float roughness) {
    }

    static final Generator<Shape, MeshData> MESHES = (shape, seed) -> switch (shape.name()) {
        case "terrain" -> DemoMeshes.terrain(shape.detail());
        case "rock" -> DemoMeshes.rock(shape.detail());
        case "pane" -> DemoMeshes.pane();
        default -> throw new IllegalArgumentException("No mesh " + shape.name());
    };
    static final Generator<Surface, MaterialData> SURFACES = (s, seed) -> {
        MaterialData.Builder b = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", s.r(), s.g(), s.b(), s.a()).set("metallic", s.metallic()).set("roughness", s.roughness());
        return s.a() < 1 ? b.alpha(MaterialData.AlphaMode.BLEND, 0.5f).doubleSided(true).build() : b.build();
    };

    /** The terrain: {@link #TERRAIN_SIZE} metres across, its heights {@link DemoMeshes#height}. */
    static final AssetRef<MeshData> TERRAIN = mesh("terrain", TERRAIN_SIZE);
    /** A bumpy rock of radius about 1. */
    static final AssetRef<MeshData> ROCK = mesh("rock", 4);
    /** A unit square pane facing +Z: windows, a mirror. */
    static final AssetRef<MeshData> PANE = mesh("pane", 0);
    static final AssetRef<MaterialData> GROUND = surface(new Surface(0.35f, 0.45f, 0.25f, 1, 0, 0.9f));
    /** The plain rock, and three others: red, gold (metal), blue. */
    static final List<AssetRef<MaterialData>> PALETTE = List.of(
        surface(new Surface(0.55f, 0.5f, 0.45f, 1, 0, 0.8f)), surface(new Surface(0.8f, 0.3f, 0.2f, 1, 0, 0.5f)),
        surface(new Surface(0.9f, 0.8f, 0.3f, 1, 1, 0.3f)), surface(new Surface(0.2f, 0.4f, 0.9f, 1, 0, 0.2f)));
    /** The plain rock material. */
    static final AssetRef<MaterialData> STONE = PALETTE.getFirst();
    /** A dark wood: torches' poles. */
    static final AssetRef<MaterialData> WOOD = surface(new Surface(0.12f, 0.07f, 0.04f, 1, 0, 0.95f));
    /** Tinted glass, blended, two-sided: windows. */
    static final AssetRef<MaterialData> GLASS = surface(new Surface(0.35f, 0.6f, 0.85f, 0.45f, 0, 0.05f));

    private final DemoAssets assets;
    private final boolean ownsAssets;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
    private final ComponentStore<LightSource> lightSources = scene.components().store(LightSource.TYPE);
    private final GpuScene gpu;
    private final SceneSync sync;
    private final MeshletRenderer renderer;
    private final Lights lights;
    private final LightSync lightSync;
    private final SceneGeometry geometry;
    private final int rockCount;
    private final long[] rockNodes;
    private final float[] rocks; // x, y, z, scale, spin speed (0: still)
    private final long[] extras = new long[EXTRA_ROCKS]; // 0: not made yet
    private final long sun;
    private final long[] pointLights;
    private final Arena arena; // or null
    private final DemoCharacter character;
    private final FoxCrowd crowd;
    private final RayTracing rayTracing; // or null
    private final Animation animation; // runs the fox's and the crowd's animators
    private final boolean ownsAnimation; // made here, for frames recorded without an engine: advanced by update
    // The particles' rock (mesh and material ids): particles name GPU slots themselves until the engine resolves
    // assets for techniques (the engine split plan's 7.5), so this one copy is added by hand, made on first use
    private int particleRock = -1;
    private int particleRockMaterial = -1;
    private double last; // the seconds of the last update

    /**
     * The scene in an application, on the engine's assets ({@link DemoAssets#of}), its animators run by
     * {@code animation} (the plugin, in ANIMATE): {@link #update} in UPDATE sets what plays, {@link #sync} after
     * animation draws it.
     */
    DemoScene(DemoAssets assets, EngineSettings settings, VulkanDevice device, FrameSlots slots, int colorFormat,
        int depthFormat, Animation animation) {
        this(assets, false, settings, device, slots, colorFormat, depthFormat, false, null,
            Objects.requireNonNull(animation));
    }

    /**
     * The scene for frames recorded without an engine: assets of its own ({@link DemoAssets#standalone}), with ray
     * tracing where {@code rays} and the device has it, around {@code arena} at dusk where not null.
     */
    static DemoScene standalone(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat,
        EngineSettings settings, boolean rays, Arena arena) {
        DemoAssets own = DemoAssets.standalone(device, slots, settings);
        try {
            return new DemoScene(own, true, settings, device, slots, colorFormat, depthFormat, rays, arena, null);
        } catch (RuntimeException e) {
            own.close();
            throw e;
        }
    }

    /** {@link #standalone(VulkanDevice, FrameSlots, int, int, EngineSettings, boolean, Arena)} with defaults. */
    static DemoScene standalone(VulkanDevice device, FrameSlots slots, int colorFormat, int depthFormat) {
        return standalone(device, slots, colorFormat, depthFormat, DemoSettings.standalone(), false, null);
    }

    private DemoScene(DemoAssets assets, boolean ownsAssets, EngineSettings settings, VulkanDevice device,
        FrameSlots slots, int colorFormat, int depthFormat, boolean rays, Arena arena, Animation animation) {
        this.assets = assets;
        this.ownsAssets = ownsAssets;
        this.ownsAnimation = animation == null;
        this.animation = ownsAnimation ? new Animation() : animation;
        this.arena = arena;
        rockCount = settings.get(DemoSettings.ROCKS);
        rockNodes = new long[rockCount];
        rocks = new float[rockCount * 5];
        rayTracing = rays && device.rayTracing() ? new RayTracing(device, assets.pool(), rockCount + 64) : null;
        if (rayTracing != null) {
            int every = settings.get(DemoSettings.REBUILD_EVERY);
            if (every > 0) {
                rayTracing.rebuildEvery(every); // refits between builds
            }
            assets.resources().rayTracing(rayTracing, mesh -> mesh.levels().size() - 1); // each at its coarsest
        }
        gpu = new GpuScene(device, assets.pool(), rockCount + EXTRA_ROCKS + 64, slots);
        gpu.setMaterials(assets.resources().materials());
        sync = new SceneSync(assets.resources(), scene, gpu);
        renderer = new MeshletRenderer(device, gpu, colorFormat, depthFormat, slots);
        geometry = SceneGeometry.meshlets(renderer);
        long terrain = scene.create();
        renderables.set(terrain, Renderable.of(TERRAIN, List.of(GROUND)));
        SplittableRandom random = new SplittableRandom(7);
        String characterModel = settings.get(DemoSettings.CHARACTER);
        boolean clearing = arena != null || !characterModel.isEmpty();
        double clearX = arena == null ? CHARACTER_X : arena.x();
        double clearZ = arena == null ? CHARACTER_Z : arena.z();
        double clear = arena == null ? 12 : arena.radius();
        for (int i = 0; i < rockCount; i++) {
            double x = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double z = random.nextDouble(-TERRAIN_SIZE / 2.0 + 4, TERRAIN_SIZE / 2.0 - 4);
            double dx = x - clearX;
            double dz = z - clearZ;
            double away = Math.sqrt(dx * dx + dz * dz);
            if (clearing && away < clear) { // a clearing for the fox (the arena): rocks pushed out of it, spread
                double out = arena == null ? clear : clear * (1 + 0.6 * away / clear);
                x = clearX + dx / Math.max(away, 1e-3) * out;
                z = clearZ + dz / Math.max(away, 1e-3) * out;
            }
            float scale = (float) (0.3 + 1.7 * Maths.pow(random.nextDouble(), 3));
            rocks[5 * i] = (float) x;
            rocks[5 * i + 1] = (float) DemoMeshes.height(x, z) + scale * 0.6f;
            rocks[5 * i + 2] = (float) z;
            rocks[5 * i + 3] = scale;
            rocks[5 * i + 4] = i % 8 == 0 ? (float) random.nextDouble(-2, 2) : 0;
            AssetRef<MaterialData> material = PALETTE.get(i % 23 == 0 ? 0 : random.nextInt(4)); // every 23rd: plain
            long node = scene.create();
            scene.setTranslation(node, x, rocks[5 * i + 1], z);
            scene.setScale(node, scale, scale, scale);
            renderables.set(node, Renderable.of(ROCK, List.of(material)));
            rockNodes[i] = node;
        }
        // The lights: the sun first (light id 0), casting cascaded shadows; then the point lights
        int pointCount = Math.max(CIRCLING_LIGHTS, settings.get(DemoSettings.LIGHTS));
        lights = new Lights(device, pointCount + 1, 8, slots);
        sun = scene.create();
        if (arena == null) { // midday
            lightSources.set(sun, LightSource.directional(2.5f, 2.3f, 2.0f, 1).shadow(SUN_SHADOWS));
            aim(sun, -0.4, -0.8, -0.45);
        } else { // dusk: low, dim and orange
            lightSources.set(sun, LightSource.directional(0.9f, 0.45f, 0.22f, 1).shadow(SUN_SHADOWS));
            aim(sun, -0.75, -0.32, -0.58);
        }
        pointLights = new long[arena != null ? 0 : pointCount];
        for (int l = 0; l < pointLights.length; l++) {
            pointLights[l] = scene.create();
            lightSources.set(pointLights[l], l < CIRCLING_LIGHTS
                ? LightSource.point(l % 3 == 0 ? 8 : 1, l % 3 == 1 ? 8 : 1, l % 3 == 2 ? 8 : 1, 1).range(14)
                : LightSource.point(0.5f + 2.5f * ((l * 7) % 5) / 4f, 0.5f + 2.5f * ((l * 3) % 7) / 6f,
                    0.5f + 2.5f * ((l * 5) % 3) / 2f, 1).range(3.5));
        }
        lightSync = new LightSync(scene, lights);
        try {
            character = characterModel.isEmpty() ? null : new DemoCharacter(assets, gpu, characterModel,
                CHARACTER_X, CHARACTER_Z, this.animation);
            String crowdModel = settings.get(DemoSettings.CROWD).isEmpty() ? characterModel
                : settings.get(DemoSettings.CROWD);
            int foxes = settings.get(DemoSettings.FOXES);
            crowd = arena != null ? new FoxCrowd(assets, gpu, arena, this.animation)
                : foxes == 0 ? null : new FoxCrowd(assets, gpu, crowdModel, foxes,
                settings.get(DemoSettings.FOX_LOD), 30, 30, this.animation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        update(0);
        // The terrain, the rocks and their materials asked for, then on the GPU before the first frame
        scene.update();
        sync.update();
        assets.awaitLoaded();
        sync.update();
    }

    private static AssetRef<MeshData> mesh(String name, int detail) {
        return AssetRef.generated(RenderAssets.MESH, MESHES, new Shape(name, detail), 1);
    }

    private static AssetRef<MaterialData> surface(Surface s) {
        return AssetRef.generated(RenderAssets.MATERIAL, SURFACES, s, 1);
    }

    SceneGeometry geometry() {
        return geometry;
    }

    MeshletRenderer renderer() {
        return renderer;
    }

    Lights lights() {
        return lights;
    }

    /** The world's scene: nodes for props of others (a showcase's torches, a test's mirror). */
    SceneGraph scene() {
        return scene;
    }

    /** The assets everything loads through. */
    DemoAssets assets() {
        return assets;
    }

    /** A node of the scene drawing {@code mesh} in {@code material}, placed, scaled; returns it. */
    long prop(AssetRef<MeshData> mesh, AssetRef<MaterialData> material, double x, double y, double z, float qx,
        float qy, float qz, float qw, float sx, float sy, float sz, boolean castsShadows) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, qx, qy, qz, qw);
        scene.setScale(node, sx, sy, sz);
        renderables.set(node, Renderable.of(mesh, List.of(material)).withCastsShadows(castsShadows));
        return node;
    }

    /**
     * The rock as a particle mesh (debris thrown by blasts): its id in the geometry pool. Particles name pool meshes
     * and material ids themselves until the engine resolves assets for techniques (the engine split plan's 7.5), so
     * the rock's mesh and its plain material are added for them by hand here, once.
     */
    int particleRock() {
        if (particleRock < 0) {
            try (MeshData mesh = DemoMeshes.rock(4)) {
                particleRock = assets.pool().add(mesh);
            }
            particleRockMaterial = assets.resources().materials().add(new Material(MetallicRoughnessParameters.DEFAULTS
                .withBaseColor(new com.truesim.common.math.color.Color(0.55f, 0.5f, 0.45f, 1)).withRoughness(0.8f)));
        }
        return particleRock;
    }

    /** The particle rock's material id ({@link #particleRock}). */
    int particleRockMaterial() {
        particleRock();
        return particleRockMaterial;
    }

    /** The crowd of foxes, or null without. */
    FoxCrowd crowd() {
        return crowd;
    }

    /** The fox, or null without. */
    DemoCharacter character() {
        return character;
    }

    /** Its ray tracing, or null without. */
    RayTracing rayTracing() {
        return rayTracing;
    }

    /** Puts extra rock {@code k} (0 to 7: the knocking rocks) at ({@code x}, {@code y}, {@code z}), unrotated. */
    void extraRock(int k, double x, double y, double z, float scale) {
        long node = extras[Objects.checkIndex(k, EXTRA_ROCKS)];
        if (node == 0) {
            node = scene.create();
            renderables.set(node, Renderable.of(ROCK, List.of(STONE)));
            extras[k] = node;
        }
        scene.setTranslation(node, x, y, z);
        scene.setScale(node, scale, scale, scale);
    }

    /**
     * The world at {@code seconds}: rocks spun, lights moved, what the fox and the crowd do. In an application this is
     * UPDATE, and {@link #sync} follows ANIMATE; with an animation of its own, the animators run and the scene is drawn
     * here too, before the frame records.
     */
    void update(double seconds) {
        assets.drain();
        set(seconds);
        if (ownsAnimation) {
            animation.advance(Math.max(0, seconds - last));
            last = seconds;
            sync();
        }
    }

    /** Draws the world, the fox and the crowd as animation posed them: after ANIMATE, before the frame records. */
    void sync() {
        scene.update();
        sync.update();
        lightSync.update();
        if (character != null) {
            character.draw();
        }
        if (crowd != null) {
            crowd.draw();
        }
    }

    private void set(double seconds) {
        for (int i = 0; i < rockCount; i++) {
            float speed = rocks[5 * i + 4];
            if (speed != 0 || seconds == 0) {
                float angle = (float) (speed * seconds + i);
                scene.setRotation(rockNodes[i], 0, Maths.sin(angle / 2), 0, Maths.cos(angle / 2));
            }
        }
        if (arena != null) {
            // Dusk: the circling lights off; the crowd looks at a point circling above the middle
            double a = seconds * 0.4;
            crowd.update(seconds, arena.x() + 6 * Maths.cos(a), DemoMeshes.height(arena.x(), arena.z()) + 2,
                arena.z() + 6 * Maths.sin(a));
            return;
        }
        for (int l = CIRCLING_LIGHTS; l < pointLights.length; l++) {
            if (seconds == 0) { // scattered and still: a hash of the light's number, within 30 m of the middle
                double x = (((l * 2654435761L) >>> 8 & 0xffff) / 65535.0 - 0.5) * 60;
                double z = (((l * 40503L * 2654435761L) >>> 8 & 0xffff) / 65535.0 - 0.5) * 60;
                scene.setTranslation(pointLights[l], x, DemoMeshes.height(x, z) + 0.6, z);
            }
        }
        for (int l = 0; l < CIRCLING_LIGHTS; l++) {
            double a = seconds * 0.3 + l * 2 * Math.PI / CIRCLING_LIGHTS;
            double x = 25 * Maths.cos(a);
            double z = 25 * Maths.sin(a);
            scene.setTranslation(pointLights[l], x, DemoMeshes.height(x, z) + 2.5, z);
        }
        // The fox (and the crowd) look at the first circling light
        double a = seconds * 0.3;
        double lx = 25 * Maths.cos(a);
        double lz = 25 * Maths.sin(a);
        if (character != null) {
            character.update(seconds, lx, DemoMeshes.height(lx, lz) + 2.5, lz);
        }
        if (crowd != null) {
            crowd.update(seconds, lx, DemoMeshes.height(lx, lz) + 2.5, lz);
        }
    }

    /** The sun casts shadows as {@code spec} says from now on (none, cascades, ray-traced). */
    void sunShadows(com.truesim.scene.core.ShadowSpec spec) {
        lightSources.set(sun, lightSources.get(sun).shadow(spec));
    }

    /**
     * Places camera node {@code node} of {@code scene} at ({@code x}, {@code y}, {@code z}) looking along {@code yaw}
     * (about +Y, 0 along +Z) and {@code pitch} (up), level: a node looks along its -Z, so it is turned by yaw + pi
     * about Y, then by pitch about X.
     */
    static void place(SceneGraph scene, long node, double x, double y, double z, double yaw, double pitch) {
        double a = (yaw + Math.PI) / 2;
        double b = pitch / 2;
        double cy = Maths.cos(a);
        double sy = Maths.sin(a);
        double cx = Maths.cos(b);
        double sx = Maths.sin(b);
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, (float) (cy * sx), (float) (sy * cx), (float) (-sy * sx), (float) (cy * cx));
    }

    /** Places camera node {@code node} at ({@code x}, {@code y}, {@code z}) looking along (dx, dy, dz), level. */
    static void look(SceneGraph scene, long node, double x, double y, double z, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        place(scene, node, x, y, z, Math.atan2(dx, dz), Math.asin(dy / length));
    }

    /** A camera node of the world's scene with a perspective lens of {@code fovY} radians and {@code near} metres. */
    long cameraNode(double fovY, double near) {
        long node = scene.create();
        scene.components().set(node, com.truesim.scene.core.CameraLens.TYPE,
            com.truesim.scene.core.CameraLens.perspective(fovY, near));
        return node;
    }

    /** Turns {@code node} so its -Z (where a directional light shines) points along (dx, dy, dz). */
    private void aim(long node, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double x = dx / length;
        double y = dy / length;
        double z = dz / length;
        // The shortest turn from -Z to the direction: axis (-Z) x d = (y, -x, 0), w = 1 + (-Z . d) = 1 - z
        double qx = y;
        double qy = -x;
        double qw = 1 - z;
        double norm = Math.sqrt(qx * qx + qy * qy + qw * qw);
        scene.setRotation(node, (float) (qx / norm), (float) (qy / norm), 0, (float) (qw / norm));
    }

    /** Notes the frame just recorded, for the crowd's report. */
    void recorded() {
        if (crowd != null) {
            crowd.recorded();
        }
    }

    /** What animating the crowd cost, or null without one. */
    String crowdReport() {
        return crowd == null ? null : crowd.report();
    }

    @Override
    public void close() {
        if (ownsAnimation) {
            animation.stop();
        }
        if (crowd != null) {
            crowd.close();
        }
        if (character != null) {
            character.close();
        }
        sync.close();
        lights.close();
        renderer.close();
        if (rayTracing != null) {
            rayTracing.close();
        }
        gpu.close();
        if (ownsAssets) {
            assets.close();
        }
    }
}
