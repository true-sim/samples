package com.truesim.samples.showcase.day;

import com.truesim.common.math.Vec3;
import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.effects.atmosphere.fog.AtmosphereFog;
import com.truesim.effects.atmosphere.fog.FogVolume;
import com.truesim.effects.atmosphere.fog.hooks.AtmosphereFogParameters;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.sky.SkyComponents;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.SkyTime;
import com.truesim.effects.post.AutoExposure;
import com.truesim.effects.post.DepthOfField;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.snow.SimpleSnow;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.samples.showcase.fx.VillageEffects;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.Ground;
import com.truesim.samples.showcase.world.Village;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.effects.Effect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Makes the world follow a {@link DayState} every frame: the sky's clock (the sun, the moon and the stars from the
 * ephemeris for Heiligenblut am Großglockner, 47.04° N 12.84° E, 1,290 m, on 24 October 2026), the weather's preset and
 * pace, the height fog and the valley's mist (fog volumes), the snow's cover and fall (the snow hook and the flakes),
 * rain, the lamps, the rooms' lights, the hearths and the fireworks, the camera's depth of field, and the exposure.
 *
 * <p><b>Units.</b> effects-atmosphere makes scene colour luminance times 1 / (1.2 · 2^EV100); the EV100 here is a
 * pre-exposure from the day's light (the sun on the ground, the sky's, the lamps' at night: a CPU estimate eased
 * over a second or two), which keeps half floats in range from noon to night. effects-post's auto exposure then
 * measures what was rendered, its luminance scale set to 1.2 · 2^EV100 with Krawczyk's key: the two layers the
 * libraries document, the image's exposure kept per cd/m² whatever the units. Everything that glows (lamps, bulbs,
 * flames) is set in those units each frame from its physical value.
 */
public final class DayDriver {

    /** Where: Heiligenblut am Großglockner. */
    public static final Observer PLACE = new Observer(47.04, 12.84, 1290);
    /** When: local midnight (CEST, UTC+2) starting 24 October 2026. */
    public static final Instant MIDNIGHT = Instant.parse("2026-10-23T22:00:00Z");
    /** A street lamp's glass and a bulb, cd/m² at full brightness. */
    static final double LAMP_GLOW = 12_000;
    static final double ROOM_GLOW = 20_000;

    private final ShowcaseRenderer renderer;
    private final Village village;
    private final Ground ground;
    private final SceneGraph scene;
    private final EngineSettings settings;
    private final Atmosphere atmosphere;
    private final List<long[]> mist = new ArrayList<>();
    private final long weatherFx;
    private final Effect snow;
    private final Effect rain;
    private final SplittableRandom launches = new SplittableRandom(99);
    private final double[] scratch = new double[3];
    private final double[] colour = new double[4];
    private WeatherPreset weather;
    private boolean stormSpawned;
    private double ev = 12;
    private double nextLaunch;
    private double time;
    private boolean first = true;

    public DayDriver(ShowcaseRenderer renderer, Village village, Ground ground) {
        this.renderer = renderer;
        this.village = village;
        this.ground = ground;
        scene = renderer.scene();
        settings = renderer.settings();
        atmosphere = renderer.atmosphere();
        scene.components().set(renderer.skyNode(), SkyComponents.SKY,
            SkySettings.forQuality(renderer.quality(), PLACE));
        scene.components().set(renderer.skyNode(), SkyComponents.SKY_TIME, SkyTime.of(MIDNIGHT, 1));
        settings.set(AutoExposure.HISTOGRAM.setting("key"), AutoExposure.KeyValue.KRAWCZYK);
        // The valley's mist at dawn: flat ellipsoids along the valley floor, the stream's meadows
        double[][] banks = {{20, 60, 160, 9, 90}, {-30, -80, 140, 8, 110}, {70, 180, 200, 10, 120},
            {-40, 260, 180, 12, 140}, {60, -220, 160, 9, 120}};
        for (double[] b : banks) {
            long node = scene.create();
            scene.setTranslation(node, b[0], ground.height(b[0], b[1]) + b[3] * 0.25, b[1]);
            mist.add(new long[] {node});
            scene.components().set(node, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(b[2], b[3], b[4]), 0)
                .withFalloff(0.35f));
        }
        weatherFx = scene.create();
        snow = renderer.effects().play(VillageEffects.SNOW, weatherFx).set(VillageEffects.RATE, 0);
        rain = renderer.effects().play(VillageEffects.RAIN, weatherFx).set(VillageEffects.RATE, 0);
    }

    /** The day as {@code state}, the camera there, {@code seconds} after the last frame; before the renderer's update. */
    public void apply(DayState state, Camera camera, double seconds) {
        time += seconds;
        double x = camera.position(0);
        double y = camera.position(1);
        double z = camera.position(2);
        // the weather
        if (state.weather != weather) {
            renderer.weather().setPreset(state.weather, first ? 0 : state.weatherTransition);
            if (first || state.weatherTransition == 0) {
                renderer.weather().refreshAll();
            }
            weather = state.weather;
        }
        if (state.storm && !stormSpawned) {
            renderer.weather().spawnStorm(-1500, 0, -2500); // up the valley, upwind: it drifts over the village
            stormSpawned = true;
        }
        if (!state.storm) {
            stormSpawned = false;
        }
        // the fog: the valley's height fog, thicker at dawn; the mist's banks
        renderer.hooks().set(AtmosphereFog.HOOK, AtmosphereFogParameters.DEFAULTS.withDensity((float) state.fog)
            .withHeightFalloff((float) state.fogFalloff).withBaseHeight((float) ground.height(0, 0)), true);
        for (long[] m : mist) {
            FogVolume v = scene.components().get(m[0], FogVolume.TYPE);
            if (Math.abs(v.density() - state.mist) > 1e-6) {
                scene.components().set(m[0], FogVolume.TYPE, new FogVolume(v.shape(), v.halfExtents(), (float) state.mist,
                    v.albedo(), v.falloff(), v.blendDistance()));
            }
        }
        // snow lying and falling, rain
        renderer.hooks().set(SimpleSnow.HOOK, SnowParameters.DEFAULTS.withCoverage((float) state.snowCover)
            .withSnowfall((float) state.snowfall), true);
        scene.setTranslation(weatherFx, x, y + 14, z);
        snow.set(VillageEffects.RATE, (float) Math.max(state.snowfall, 1e-4) * (state.snowfall > 0 ? 1 : 0));
        rain.set(VillageEffects.RATE, (float) state.rain);
        // the lights and what glows, in scene units
        double units = atmosphere.sceneUnits();
        lights(village.lamps(), state.lamps * units);
        lights(village.rooms(), state.rooms * units);
        double[] lamp = {LAMP_GLOW * state.lamps * units, LAMP_GLOW * 0.82 * state.lamps * units,
            LAMP_GLOW * 0.55 * state.lamps * units};
        renderer.assets().resources().animate(village.lampGlow, "emissive", lamp, 0, 3);
        double[] room = {ROOM_GLOW * state.rooms * units, ROOM_GLOW * 0.78 * state.rooms * units,
            ROOM_GLOW * 0.5 * state.rooms * units};
        renderer.assets().resources().animate(village.roomGlow, "emissive", room, 0, 3);
        float fire = state.fires ? (float) units : 0;
        for (Effect e : village.fires()) {
            float f = (float) (VillageEffects.FLAME_LUMINANCE * fire);
            float em = (float) (VillageEffects.EMBER_LUMINANCE * fire);
            float g = (float) (VillageEffects.GLOW_CANDELA * fire);
            e.set(VillageEffects.FLAME, f, f, f, 1).set(VillageEffects.EMBER, em, em, em, 1)
                .set(VillageEffects.GLOW, g, g, g, 1);
        }
        // fireworks over the square, from the meadow behind the church
        if (state.fireworks && time >= nextLaunch) {
            float s = (float) (VillageEffects.SPARK_LUMINANCE * units);
            double lx = 45 + launches.nextDouble(-8, 8);
            double lz = -5 + launches.nextDouble(-14, 14);
            renderer.effects().play(VillageEffects.LAUNCH, lx, ground.height(lx, lz), lz)
                .set(VillageEffects.SPARK, s, s, s, 1);
            nextLaunch = time + launches.nextDouble(0.5, 1.8);
        }
        // shadows: the sun's while it is up, the moon's at night
        atmosphere.sunDirection(scratch);
        boolean sunUp = scratch[1] > -0.03;
        renderer.lights().shadow(renderer.sunLight(), sunUp ? sunShadow() : ShadowSpec.NONE);
        renderer.lights().shadow(renderer.moonLight(), sunUp ? ShadowSpec.NONE : moonShadow());
        // depth of field for this shot
        settings.set(DepthOfField.F_STOP, (float) state.fStop);
        exposure(state, seconds, scratch[1], units);
        first = false;
    }

    /** The game seconds the sky's clock takes for {@code state}: seconds since local midnight. */
    public static double gameSeconds(DayState state) {
        return state.hour * 3600;
    }

    private ShadowSpec sunShadow() {
        ShadowSpec spec = renderer.lights().shadow(renderer.sunLight());
        return spec.method() == com.truesim.scene.core.ShadowMethod.NONE
            ? ShadowSpec.cascades(4, renderer.quality() == com.truesim.effects.atmosphere.Quality.LOW ? 2048 : 4096,
                600) : spec;
    }

    private ShadowSpec moonShadow() {
        ShadowSpec spec = renderer.lights().shadow(renderer.moonLight());
        return spec.method() == com.truesim.scene.core.ShadowMethod.NONE ? ShadowSpec.cascades(3, 2048, 250) : spec;
    }

    private void lights(List<Village.Light> lights, double scale) {
        for (int i = 0; i < lights.size(); i++) {
            Village.Light l = lights.get(i);
            LightSource source = scene.components().get(l.node(), LightSource.TYPE);
            if (source != null && Math.abs(source.intensity() - l.candela() * scale) > 1e-9 * l.candela()) {
                source.intensity(l.candela() * scale);
            }
        }
    }

    /**
     * The pre-exposure: the day's light on the ground (the sun's, under the clouds as the sky under them says, the
     * sky's, the lamps' at night) as an 18 % grey's luminance, its EV100 at Krawczyk's key, eased; and the post chain's
     * auto exposure told the units.
     */
    private void exposure(DayState state, double seconds, double sunUp, double units) {
        atmosphere.lightAtCamera(0, colour);
        double sunLux = (0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2]) / units;
        atmosphere.skyAmbient(colour);
        double skyRadiance = (0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2]) / units;
        double clouds = Math.clamp(renderer.environment().intensity(), 0.02, 1);
        double moonLux = 0;
        atmosphere.lightAtCamera(1, colour);
        moonLux = (0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2]) / units;
        double illuminance = sunLux * Math.max(sunUp, 0) * clouds * clouds + Math.PI * skyRadiance * clouds + moonLux * 0.3
            + 25 * Math.max(state.lamps, state.rooms);
        double luminance = 0.18 * illuminance / Math.PI;
        double target = Math.clamp(AtmosphereExposure.ev100(luminance), -3, 15);
        ev = first ? target : AtmosphereExposure.adapt(ev, target, seconds, 1.5);
        settings.set(AtmosphereSky.ATMOSPHERE.setting("ev100"), (float) ev);
        settings.set(AutoExposure.HISTOGRAM.setting("luminanceScale"), (float) (1.2 * Math.pow(2, ev)));
    }

    /** The pre-exposure now, EV100. */
    public double ev100() {
        return ev;
    }
}
