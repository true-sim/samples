package com.truesim.samples.showcase.render;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.FogRegions;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereQuality;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.render.AtmosphereSync;
import com.truesim.effects.atmosphere.render.SkyCapture;
import com.truesim.effects.atmosphere.render.VolumetricFog;
import com.truesim.effects.particles.ParticleDraw;
import com.truesim.effects.particles.ParticleSimulation;
import com.truesim.effects.particles.Particles;
import com.truesim.effects.particles.scene.EffectSync;
import com.truesim.effects.post.AutoExposure;
import com.truesim.effects.post.Bloom;
import com.truesim.effects.post.ColorGrading;
import com.truesim.effects.post.DepthOfField;
import com.truesim.effects.post.FilmGrain;
import com.truesim.effects.post.LensEffects;
import com.truesim.effects.post.PostTextures;
import com.truesim.effects.post.ScreenFlash;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherDomain;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.render.AtmosphereLighting;
import com.truesim.effects.weather.render.CloudLighting;
import com.truesim.effects.weather.render.CloudSettings;
import com.truesim.effects.weather.render.CloudShadowMask;
import com.truesim.effects.weather.render.Clouds;
import com.truesim.effects.weather.render.LightningBolts;
import com.truesim.effects.weather.render.LightningLights;
import com.truesim.effects.weather.render.WeatherGpu;
import com.truesim.effects.weather.snow.SimpleSnow;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.AmbientOcclusion;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ScreenSpaceReflections;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.SortedTransparency;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.LightSync;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.scene.ShadingHookOverrideSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.ShowcaseParticleModules;
import com.truesim.samples.showcase.ShowcaseSettings;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.ParticleWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the showcase draws with, on one device, and the whole effect chain per view. The device-wide parts: the
 * one asset manager ({@link ShowcaseAssets}), the scene ({@link SceneGraph}) kept on the GPU ({@link SceneSync}: its
 * renderables; {@link LightSync}: its lights; {@link ShadingHookOverrideSync}: per-node hook switches), ray tracing
 * (where the device has it: global illumination, ray-traced occlusion), the shadow owner, effects-atmosphere's
 * {@link Atmosphere} (the sky, the sun and the moon from the ephemeris through {@link AtmosphereSync}, its fog regions),
 * effects-weather's {@link Weather} on the GPU (clouds, their shadows, rain shafts, lightning), effects-particles'
 * {@link Particles} with the scene's {@link Effects}, and post-processing's textures.
 *
 * <p>{@link #view} adds the chain to a view, in the order the engine's README and the libraries document: shading
 * (forward or the visibility buffer), blended surfaces and glass, the environment's light from the captured sky, the
 * light clusters and every light's shadows, ambient occlusion, reflections (screen space, falling back to the
 * captured sky), global illumination (the probe volume or radiance cascades: {@code render.gi}), the particles,
 * the atmosphere's sky, its capture and aerial perspective, the fog in froxels with fog volumes and indoor zones,
 * snow lying on surfaces, the clouds, their shadow on the ground and lightning's bolts, then the post chain in scene
 * light (depth of field, bloom, lens, grading, auto exposure) and in display light (film grain, flash).
 *
 * <p>The sun and the moon are lights 0 and 1, which the atmosphere writes ({@link Atmosphere#light}: their
 * illuminance at the camera in its scene units, their discs' sizes as their shadows' softness); every other light is
 * a scene node's ({@link LightSync}).
 */
public final class ShowcaseRenderer implements AutoCloseable {

    public static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int DEPTH = VK_FORMAT_D32_SFLOAT;

    private final VulkanDevice device;
    private final FrameGraph graph;
    private final EngineSettings settings;
    private final Quality quality;
    private final ShowcaseAssets assets;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final RayTracing rays;
    private final GpuScene gpu;
    private final MeshletRenderer renderer;
    private final SceneGeometry geometry;
    private final Lights lights;
    private final int sun;
    private final int moon;
    private final LightSync lightSync;
    private final SceneSync sync;
    private final ShadingHookOverrideSync overrides;
    private final ShadingHookValues hooks;
    private final Shadows shadows;
    private final Environment environment;
    private final Atmosphere atmosphere;
    private final AtmosphereSync sky;
    private final long skyNode;
    private final FogRegions regions;
    private final Weather weather;
    private final WeatherGpu weatherGpu;
    private final CloudLighting cloudLighting;
    private final LightningBolts bolts;
    private final LightningLights flashes;
    private final Particles particles;
    private final Effects effects;
    private final EffectSync effectSync;
    private final PostTextures postTextures;
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final List<SimpleSnow> snows = new ArrayList<>();
    private final List<AtmosphereFog> fogs = new ArrayList<>();
    private final ScreenFlash flash = new ScreenFlash();
    private ProbeVolume volume;
    private double weatherTime;
    private final double[] below = new double[3];
    private final double[] clear = new double[3];
    private final double[] sunLight = new double[3];
    private final double[] sunDirection = new double[3];
    private double direct = 1;
    private double moonBoost = 1;
    private double overcast = 1;
    private boolean indoors;

    /**
     * The device-wide parts on {@code device}, drawing into {@code graph} (whose settings it reads and presets), its
     * content through {@code assets}; particles collide with {@code ground}.
     */
    public ShowcaseRenderer(VulkanDevice device, FrameGraph graph, ShowcaseAssets assets, ParticleWorld ground) {
        this.device = device;
        this.graph = graph;
        this.assets = assets;
        settings = graph.settings();
        FrameSlots slots = graph.frameSlots();
        quality = quality(settings.get(ShowcaseSettings.QUALITY));
        presets(settings, quality);
        // global illumination: the probe volume (the default) or radiance cascades, or none
        settings.set(EngineSettings.Layer.GAME, ProbeVolumeLighting.SLOT.setting(), settings.get(ShowcaseSettings.GI));
        rays = device.rayTracing() ? own(new RayTracing(device, assets.resources().pool(), 1 << 18)) : null;
        if (rays != null) {
            assets.rayTracing(rays);
        }
        gpu = own(new GpuScene(device, assets.resources().pool(), 1 << 18, slots)); // the forest's trees
        gpu.setMaterials(assets.resources().materials());
        assets.resources().materials().register(com.truesim.effects.terrain.material.TerrainType.TYPE);
        assets.resources().materials().register(com.truesim.effects.vegetation.materials.VegetationGrass.TYPE);
        assets.resources().materials().register(com.truesim.effects.vegetation.materials.VegetationPlant.TYPE);
        renderer = own(new MeshletRenderer(device, gpu, COLOR, DEPTH, slots));
        geometry = SceneGeometry.meshlets(renderer);
        lights = own(new Lights(device, 512, 32, slots));
        sun = lights.add();
        moon = lights.add();
        lights.shadow(sun, ShadowSpec.cascades(4, quality == Quality.LOW ? 2048 : 4096, 600));
        lights.shadow(moon, ShadowSpec.NONE);
        lightSync = new LightSync(scene, lights);
        sync = own(new SceneSync(assets.resources(), scene, gpu));
        overrides = new ShadingHookOverrideSync(scene, sync, gpu.hookOverrides());
        hooks = ShadingHookValues.of(device);
        shadows = own(new Shadows(geometry, lights));
        if (rays != null) {
            shadows.rayTracing(rays); // traced shadows where a light asks for them (showcase.sunShadows)
        }
        environment = Environment.uniform("sky", 0, 0, 0);
        atmosphere = own(new Atmosphere(device, slots, settings));
        skyNode = scene.create();
        sky = own(new AtmosphereSync(scene, skyNode, assets.cache(), assets.paths(), atmosphere));
        regions = new FogRegions(scene);
        Quality clouds = clouds(quality);
        // the weather's ground the valley floor's (effects-terrain's y is the altitude): the clouds' layers, the storm
        // and the lightning stand on it
        weather = new Weather(WeatherConfig.forQuality(clouds), WeatherDomain.flat(ground.height(0, 0)),
            WeatherPreset.FAIR);
        weatherGpu = own(new WeatherGpu(device, slots, assets.resources().textures(), weather, cumulus(CloudTypes.physical()),
            CloudsConfig.forQuality(clouds), settings));
        cloudLighting = new AtmosphereLighting(atmosphere);
        bolts = own(new LightningBolts(device, slots, weatherGpu, 3, 400));
        flashes = new LightningLights(scene, com.truesim.scene.core.NodeHandle.NONE, 4, 30_000);
        particles = own(new Particles(device, slots, 256L << 20, 2048)).assets(assets.resources())
            .modules(ShowcaseParticleModules.MODULES);
        particles.heights(ground, 256, 1024);
        if (rays != null) {
            particles.rays(rays, renderer); // flakes and drops stop on roofs, not only on the ground
        }
        effects = new Effects(scene);
        effectSync = new EffectSync(effects, particles).hookOverrides(overrides);
        graph.add(new ParticleSimulation(particles));
        postTextures = new PostTextures(assets.resources(), assets.paths());
        weather.refreshAll();
    }

    /**
     * The clouds' tier for a preset: from high up the ray-marched fly-through clouds (effects-weather's ULTRA), which
     * the camera passes under and beside in the tour; the layered tier at HIGH streaked at the low sun's grazing angles.
     */
    static Quality clouds(Quality quality) {
        return quality == Quality.HIGH ? Quality.ULTRA : quality;
    }

    /**
     * The fair-weather cumulus of an Alpine afternoon: bases 1,000 m over the valley floor, tops growing to some
     * 3,200 m (mediocris to congestus, rising over the ridges), their sides eroded into billows of finer detail and a
     * little sheared, rather than the library's flatter, smoother default.
     */
    static CloudTypes cumulus(CloudTypes types) {
        com.truesim.effects.weather.clouds.CloudType type = com.truesim.effects.weather.clouds.CloudType.CUMULUS;
        com.truesim.effects.weather.clouds.CloudProfile p = types.profile(type);
        return types.with(type, new com.truesim.effects.weather.clouds.CloudProfile(1000, 3200, 0.04, 0.55, 0.35,
            p.anvilSpread(), p.anvilStart(), 0.12, 0.75, 140, p.extinction(), p.dropletDiameter(), p.absorption(),
            false));
    }

    /** The preset named ({@code low}, {@code medium}, {@code high}, {@code ultra}). */
    public static Quality quality(String name) {
        return Quality.valueOf(name.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * The engine's and the libraries' settings for {@code quality} in the game's layer (the player's and the command
     * line's win): the atmosphere's tables, the clouds' tier, the post chain (bloom, AgX with the punchy look,
     * histogram exposure on Krawczyk's key, a natural vignette, subtle grain), depth of field off until a shot asks.
     */
    public static void presets(EngineSettings settings, Quality quality) {
        AtmosphereQuality.apply(settings, quality);
        CloudSettings.forQuality(settings, clouds(quality));
        // billows: the detail noise carving the clouds' edges harder than the default
        settings.set(EngineSettings.Layer.GAME, CloudSettings.DETAIL_STRENGTH, 1.5f);
        EngineSettings.Layer game = EngineSettings.Layer.GAME;
        settings.set(game, com.truesim.engine.render.DisplayTransform.CURVE, "agx");
        settings.set(game, AutoExposure.SLOT.setting(), "histogram");
        // Krawczyk's key brightens a sunny noon towards 0.7 of white: a stop and a half down keeps the snow's and the
        // plaster's texture and the sky's blue under AgX's shoulder
        settings.set(game, AutoExposure.HISTOGRAM.setting("compensation"), -1.5f);
        settings.set(game, com.truesim.engine.render.DisplayTransform.AGX.setting("look"),
            com.truesim.engine.render.DisplayTransform.Look.PUNCHY);
        settings.set(game, LensEffects.VIGNETTE.setting(), "natural");
        settings.set(game, FilmGrain.SLOT.setting(), "film");
        settings.set(game, FilmGrain.FILM.setting("intensity"), 0.035f);
        settings.set(game, DepthOfField.SLOT.setting(), "gather");
        settings.set(game, DepthOfField.AUTOFOCUS, true);
        settings.set(game, DepthOfField.F_STOP, 11f);
        // snow: a wider occlusion map (no snow under the eaves and roofs within 160 m), and the day's snow filling
        // in over half a minute (the tour's dusk is a time-lapse)
        settings.set(game, com.truesim.effects.weather.snow.SnowSettings.AREA, 160f);
        settings.set(game, com.truesim.effects.weather.snow.SnowSettings.MAP_RESOLUTION, 2048);
        settings.set(game, com.truesim.effects.weather.snow.SnowSettings.FILL_SECONDS, 30f);
        // effects-terrain: the snow map's bias a texel's rise on steep ground; the terrain's interest moving in steps
        settings.set(game, com.truesim.effects.weather.snow.SnowSettings.BIAS, 0.5f);
        // the meadows fuller to the eye: a quarter of the blades kept at 12 m rather than 8 (a camera at head height
        // looks across them), the blades widening more as they thin
        settings.set(game, com.truesim.effects.vegetation.VegetationSettings.LOD_DISTANCE, 12f);
        settings.set(game, com.truesim.effects.vegetation.VegetationSettings.MAX_WIDEN, 4f);
    }

    /**
     * Adds the whole chain to {@code port}, drawing what {@code camera} sees; {@code first}: the view the probe volume
     * follows (the first one made). Returns the view.
     */
    public SceneView view(ViewPort port, Camera camera) {
        // the lists start modest and grow as the terrain, the village and the forest need (engine 0.198, #464)
        SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 15, 1 << 22).withOcclusion(true))
            .set(1, MeshletRenderer.MODE_LIT);
        boolean visibility = "visibility".equals(settings.get(ShowcaseSettings.SHADING)) && device.primitiveIds();
        if (visibility) {
            port.add(new VisibilityBufferShading(geometry, view, COLOR, DEPTH).clearColor(0, 0, 0));
        } else {
            port.add(new ClusteredForwardShading(geometry, view, COLOR, DEPTH).clearColor(0, 0, 0));
        }
        // showcase.skip leaves passes out, to find which one a look comes from (a debug view per pass)
        java.util.Set<String> skip = java.util.Set.of(settings.get(ShowcaseSettings.SKIP).split(","));
        port.add(new SortedTransparency(geometry, view));
        if (!skip.contains("environment")) {
            port.add(new EnvironmentLighting(environment, view));
        }
        port.add(new ClusteredLights(lights, view)).add(shadows.view(view));
        if (!skip.contains("ao")) {
            port.add(new AmbientOcclusion(view, rays));
        }
        if (!skip.contains("ssr")) {
            port.add(new ScreenSpaceReflections(view));
        }
        if (rays != null && !skip.contains("gi") && !"off".equals(settings.get(ShowcaseSettings.GI))) {
            if (volume == null) {
                volume = new ProbeVolume(rays, renderer, lights, environment, view);
            }
            port.add(new ProbeVolumeLighting(view, volume));
        }
        port.add(new ParticleDraw(particles, view));
        if (snows.isEmpty()) { // the sky's capture once, from the first view
            port.add(own(new SkyCapture(atmosphere, environment, assets.resources().textures())));
        }
        port.add(new AtmosphereSky(atmosphere, view));
        if (!skip.contains("aerial")) {
            port.add(new AerialPerspective(atmosphere, view));
        }
        AtmosphereFog fog = own(new AtmosphereFog(view, regions).froxels(new VolumetricFog(atmosphere, view))
            .lighting(atmosphere));
        fogs.add(fog);
        if (!skip.contains("fog")) {
            port.add(fog);
        }
        SimpleSnow snow = own(new SimpleSnow(geometry, view));
        snows.add(snow);
        port.add(snow);
        if (!skip.contains("clouds")) {
            port.add(new Clouds(weatherGpu, view))
                .add(own(new CloudShadowMask(weatherGpu, view, renderer, lights, sun)))
                .add(bolts.view(view));
        }
        // Post: in scene light, then in display light (the presenter's or the capture's DisplayTransform made with
        // displayEffects())
        port.add(new DepthOfField(camera)).add(new Bloom(postTextures)).add(new LensEffects(camera))
            .add(new ColorGrading(postTextures)).add(new AutoExposure()).add(new FilmGrain())
            .add(flash.display()).add(flash.scene());
        return view;
    }

    /**
     * The parts per frame, before the graph records: the scene's state after the game's update reaches the GPU.
     * {@code frameSeconds} since the last frame; {@code weatherSeconds} of weather passed in it (clouds may drift
     * faster than life); the sky's clock at {@code gameSeconds}.
     */
    public void update(double frameSeconds, double weatherSeconds, double gameSeconds, Camera camera) {
        assets.drain();
        assets.resources().beginFrame();
        double x = camera.position(0);
        double y = camera.position(1);
        double z = camera.position(2);
        scene.update();
        sky.update(gameSeconds, x, y, z);
        atmosphere.light(lights, sun, moon);
        // the weather: its own clock, the camera its focus
        weather.setFocus(x, y, z);
        weather.advance(weatherSeconds);
        weatherTime += weatherSeconds;
        weatherGpu.update(weatherTime, weatherSeconds, cloudLighting, settings);
        bolts.update();
        flashes.update(weatherGpu);
        // the sky's light under the clouds: the captured clear sky dimmed as much as the clouds' diffused light is
        weatherGpu.skyBelowClouds(x, z, 10_000, below);
        cloudLighting.sky(clear);
        double ratio = (below[0] + below[1] + below[2]) / Math.max(clear[0] + clear[1] + clear[2], 1e-30);
        // indoors the sky reaches through the windows only: its uniform light (no occlusion but the AO's) would fill
        // a room with blue; the rooms' lamps and the probes' bounced light carry it
        environment.intensity((float) (Math.clamp(ratio, 0.02, 1.5) * (indoors ? 0.2 : 1)));
        // under a closed cloud deck the sun gives no direct light: its share follows the sky under the clouds (the
        // cloud shadow mask darkens the ground under single clouds; this, the light the deck lets through at all)
        direct = Math.min(Math.clamp((ratio - 0.3) / 0.5, 0, 1), overcast);
        environment.intensity(environment.intensity() * (float) Math.max(overcast, 0.45));
        if (direct < 1) {
            atmosphere.lightAtCamera(0, sunLight);
            atmosphere.sunDirection(sunDirection);
            double k = direct * direct;
            lights.directional(sun, (float) -sunDirection[0], (float) -sunDirection[1], (float) -sunDirection[2],
                (float) (sunLight[0] * k), (float) (sunLight[1] * k), (float) (sunLight[2] * k));
        }
        if (moonBoost != 1) {
            atmosphere.lightAtCamera(1, sunLight);
            atmosphere.moonDirection(sunDirection);
            lights.directional(moon, (float) -sunDirection[0], (float) -sunDirection[1], (float) -sunDirection[2],
                (float) (sunLight[0] * moonBoost), (float) (sunLight[1] * moonBoost),
                (float) (sunLight[2] * moonBoost));
        }
        scene.update();
        regions.update();
        for (int i = 0; i < snows.size(); i++) {
            snows.get(i).update(frameSeconds);
        }
        particles.heightFocus(x, z);
        effectSync.clearViewers();
        effectSync.addViewer(x, y, z);
        effectSync.update(frameSeconds);
        lightSync.update();
        sync.update();
        overrides.update();
    }

    /** Whether the camera is in a building (the sky's uniform light held back). */
    public void indoors(boolean value) {
        indoors = value;
    }

    /**
     * The share of the sun's direct light and of the sky's at most (1: as the clouds say): under the storm's cells
     * the valley is in their shadow even where the map has a gap overhead.
     */
    public void overcast(double share) {
        overcast = share;
    }

    /** Draws the moon's light {@code factor} times its own (night's art); 1: as the atmosphere gives it. */
    public void moonBoost(double factor) {
        moonBoost = factor;
    }

    public double moonBoost() {
        return moonBoost;
    }

    /** The share of the sun's direct light the clouds let through, 0 to 1. */
    public double direct() {
        return direct;
    }

    /** Whether the probe volume or radiance cascades light the scene (the device has ray tracing). */
    public boolean globalIllumination() {
        return rays != null;
    }

    public VulkanDevice device() {
        return device;
    }

    public FrameGraph graph() {
        return graph;
    }

    public EngineSettings settings() {
        return settings;
    }

    public Quality quality() {
        return quality;
    }

    public ShowcaseAssets assets() {
        return assets;
    }

    public SceneGraph scene() {
        return scene;
    }

    /** The scene's table on the GPU. */
    public GpuScene gpuScene() {
        return gpu;
    }

    public SceneSync sync() {
        return sync;
    }

    public long skyNode() {
        return skyNode;
    }

    public Atmosphere atmosphere() {
        return atmosphere;
    }

    public AtmosphereSync sky() {
        return sky;
    }

    public Weather weather() {
        return weather;
    }

    public WeatherGpu weatherGpu() {
        return weatherGpu;
    }

    public Lights lights() {
        return lights;
    }

    public int sunLight() {
        return sun;
    }

    public int moonLight() {
        return moon;
    }

    public ShadingHookValues hooks() {
        return hooks;
    }

    public Particles particles() {
        return particles;
    }

    public Effects effects() {
        return effects;
    }

    public Environment environment() {
        return environment;
    }

    public ScreenFlash flash() {
        return flash;
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.add(resource);
        return resource;
    }

    @Override
    public void close() {
        device.waitIdle();
        for (int i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).close();
            } catch (Exception e) {
                throw new IllegalStateException("Closing " + owned.get(i), e);
            }
        }
    }
}
