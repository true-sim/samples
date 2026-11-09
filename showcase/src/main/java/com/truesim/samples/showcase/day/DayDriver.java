package com.truesim.samples.showcase.day;

import com.truesim.common.math.Maths;
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
import com.truesim.samples.showcase.world.VillageLayout;
import com.truesim.effects.particles.fireworks.FireworkDisplay;
import com.truesim.effects.particles.fireworks.FireworkShow;
import com.truesim.effects.particles.fireworks.FireworksSettings;
import com.truesim.effects.particles.fireworks.FireworksSync;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.effects.Effect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the world follow a {@link DayState} every frame: the sky's clock (the sun, the moon and the stars from the
 * ephemeris for Lauterbrunnen, 46.59° N 7.91° E, on 26 September 2026, a full moon), the weather's preset and
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

    /**
     * Where: Lauterbrunnen's church, the terrain's origin. The world's y is the altitude above the sea (effects-terrain's
     * frame), so the atmosphere's origin is at sea level; the valley made from functions lies at y 0, 800 m up.
     */
    public static final double LATITUDE = 46.5937;
    public static final double LONGITUDE = 7.9087;
    /**
     * When: local midnight (CEST, UTC+2) starting 26 September 2026: late September, berries ripe, the first leaves
     * turning, an early snowfall at dusk, and a full moon over the night.
     */
    public static final Instant MIDNIGHT = Instant.parse("2026-09-25T22:00:00Z");
    /** A street lamp's glass and a bulb, cd/m² at full brightness. */
    static final double LAMP_GLOW = 12_000;
    /** How much brighter than the moon's own the night's moonlight is drawn (day-for-night's art). */
    static final double MOONLIGHT = 10;
    static final double ROOM_GLOW = 20_000;

    private final ShowcaseRenderer renderer;
    private final Village village;
    private final Ground ground;
    private final SceneGraph scene;
    private final EngineSettings settings;
    private final Atmosphere atmosphere;
    private final List<long[]> mist = new ArrayList<>();
    private final long weatherFx;
    private final VillageLayout layout;
    private final FireworksSync fireworks;
    private final long show;
    private boolean showing;
    private final Effect snow;
    private final Effect rain;
    private final double[] scratch = new double[3];
    private final double[] colour = new double[4];
    private WeatherPreset weather;
    private boolean stormSpawned;
    private double ev = 12;
    private double time;
    private boolean first = true;

    public DayDriver(ShowcaseRenderer renderer, Village village, Ground ground, VillageLayout layout, boolean real) {
        this.renderer = renderer;
        this.village = village;
        this.ground = ground;
        scene = renderer.scene();
        settings = renderer.settings();
        atmosphere = renderer.atmosphere();
        // the stars a full moon's sky leaves: to the fourth and a half magnitude (some 800 over the sky), not the dark
        // sky's thousands, which read as a dense field over a moonlit valley
        SkySettings sky = SkySettings.forQuality(renderer.quality(), new Observer(LATITUDE, LONGITUDE, real ? 0 : 800));
        scene.components().set(renderer.skyNode(), SkyComponents.SKY,
            sky.withStars(sky.stars().withLimitingMagnitude(4.5).withBrightness(0.6)));
        scene.components().set(renderer.skyNode(), SkyComponents.SKY_TIME, SkyTime.of(MIDNIGHT, 1));
        settings.set(AutoExposure.HISTOGRAM.setting("key"), AutoExposure.KeyValue.KRAWCZYK);
        // The valley's mist at dawn: flat ellipsoids along the valley floor, over the river's meadows
        double[][] banks = real ? new double[][] {{-60, -600, 220, 14, 300}, {-20, 0, 240, 15, 320},
            {-80, 600, 260, 16, 340}, {-60, 1300, 260, 16, 360}, {-40, 2100, 260, 18, 380}}
            : new double[][] {{20, 60, 160, 9, 90}, {-30, -80, 140, 8, 110}, {70, 180, 200, 10, 120},
                {-40, 260, 180, 12, 140}, {60, -220, 160, 9, 120}};
        for (double[] b : banks) {
            long node = scene.create();
            scene.setTranslation(node, b[0], ground.height(b[0], b[1]) + b[3] * 0.25, b[1]);
            mist.add(new long[] {node});
            scene.components().set(node, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(b[2], b[3], b[4]), 0)
                .withFalloff(0.35f).withBlendDistance(40));
        }
        this.layout = layout;
        fireworks = new FireworksSync(renderer.effects(), settings);
        show = scene.create();
        double sx = layout.originX() + 10;
        double sz = layout.originZ() - 150;
        scene.setTranslation(show, sx, ground.height(sx, sz), sz);
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
            // up the valley, upwind: it drifts over the village
            renderer.weather().spawnStorm(-500, renderer.weather().domain().ground(), -900);
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
        // snow lying and falling, rain: a smooth cover, thinning on slopes (gone past 50 degrees) and where the sky does
        // not reach (the occlusion map: under eaves and trees), its noise only a broad, soft unevenness
        renderer.hooks().set(SimpleSnow.HOOK, SnowParameters.DEFAULTS.withCoverage((float) state.snowCover)
            .withSnowfall((float) state.snowfall).withNoiseScale(0.07f).withNoiseSharpness(0.7f).withSlopeStart(18)
            .withSlopeEnd(50), true);
        scene.setTranslation(weatherFx, x, y + 14, z);
        snow.set(VillageEffects.RATE, (float) Math.max(state.snowfall, 1e-4) * (state.snowfall > 0 ? 1 : 0));
        rain.set(VillageEffects.RATE, (float) state.rain);
        // each drop carries a tenth of the sky's luminance under the clouds (it shows the sky, a few pixels wide)
        atmosphere.skyAmbient(colour);
        float sky = (float) (0.1 * Math.clamp(renderer.environment().intensity(), 0.02, 1.5));
        rain.set(VillageEffects.DROPS, (float) colour[0] * sky, (float) colour[1] * sky, (float) colour[2] * sky, 0.6f);
        // the lights and what glows, in scene units
        double units = atmosphere.sceneUnits();
        lights(village.lamps(), state.lamps * units);
        lights(village.rooms(), state.rooms * units);
        double[] lamp = {LAMP_GLOW * state.lamps * units, LAMP_GLOW * 0.82 * state.lamps * units,
            LAMP_GLOW * 0.55 * state.lamps * units};
        renderer.assets().resources().animate(village.lampGlow, "emissive", lamp, 0, 3);
        // the lantern's frosted glass round the bulb: lit from within, a fifth of the bulb's luminance
        double[] glass = {lamp[0] * 0.2, lamp[1] * 0.2, lamp[2] * 0.2};
        renderer.assets().resources().animate(village.lampGlass, "emissive", glass, 0, 3);
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
        // the fireworks show on the meadow south of the village (effects-particles-fireworks: shells in physical
        // units, the units the pre-exposure's), played from when its display component is set
        settings.set(FireworksSettings.LUMINANCE_SCALE, (float) units);
        if (state.fireworks != showing) {
            if (state.fireworks) {
                scene.components().set(show, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.random(1026, 24,
                    1.4, 70).and(FireworkShow.finale(1027, 22, 10, 36, 80))));
            } else {
                scene.components().remove(show, FireworkDisplay.TYPE);
            }
            showing = state.fireworks;
        }
        fireworks.update(seconds);
        // the moonlight, art-directed as night films are: the full moon's quarter lux times MOONLIGHT, so the valley
        // reads in blue light under the lamps rather than black
        atmosphere.sunDirection(scratch);
        renderer.moonBoost(scratch[1] < -0.05 ? MOONLIGHT : 1);
        // shadows: the sun's while it is up, the moon's at night
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
        if ("rayTraced".equals(settings.get(com.truesim.samples.showcase.ShowcaseSettings.SUN_SHADOWS))
            && renderer.globalIllumination()) {
            return ShadowSpec.softRayTraced(0.0047); // the sun's angular radius
        }
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
        double illuminance = sunLux * Math.max(sunUp, 0) * renderer.direct() + Math.PI * skyRadiance * clouds
            + moonLux * renderer.moonBoost() * 0.3 + 25 * Math.max(state.lamps, state.rooms);
        double luminance = 0.18 * illuminance / Math.PI;
        double target = Math.clamp(AtmosphereExposure.ev100(luminance), -3, 15);
        ev = first ? target : AtmosphereExposure.adapt(ev, target, seconds, 1.5);
        settings.set(AtmosphereSky.ATMOSPHERE.setting("ev100"), (float) ev);
        settings.set(AutoExposure.HISTOGRAM.setting("luminanceScale"), (float) (1.2 * Maths.pow(2, ev)));
    }

    /** The pre-exposure now, EV100. */
    public double ev100() {
        return ev;
    }
}
