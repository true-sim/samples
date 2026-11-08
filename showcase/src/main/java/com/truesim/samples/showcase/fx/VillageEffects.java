package com.truesim.samples.showcase.fx;

import com.truesim.samples.showcase.ShowcaseParticleModules;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.EffectType;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.ParticleEvent;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Space;
import com.truesim.scene.effects.Spawn;

/**
 * The village's particle effects (effects-particles, as scene-effects' data): fire in the hearths, smoke from the
 * chimneys, snowfall and rain about the camera, and the fireworks over the square (a stand-in built from the samples
 * demo's launches until effects-particles' fireworks API lands).
 *
 * <p>Light in the atmosphere's scene units: the scene's colour is luminance times 1 / (1.2 * 2^EV100), and the EV100
 * follows the day from noon to night, so what glows (flames, embers, fireworks) takes its colour from a parameter
 * set every frame to its luminance in those units ({@link #FLAME}, {@link #EMBER}, {@link #GLOW}, {@link #SPARK}:
 * colour times cd/m² times the units). Lit particles (smoke, flakes, rain) take the lights' light, already in them.
 */
public final class VillageEffects {

    /** A flame's colour at its hottest, scene units. */
    public static final EffectParameter.Color FLAME = EffectParameter.ofColor("flame");
    /** Embers' colour. */
    public static final EffectParameter.Color EMBER = EffectParameter.ofColor("ember");
    /** A fire's flickering light's colour (times the light's intensity). */
    public static final EffectParameter.Color GLOW = EffectParameter.ofColor("glow");
    /** Fireworks' stars. */
    public static final EffectParameter.Color SPARK = EffectParameter.ofColor("spark");
    /** Snowflakes or raindrops a second, of the effect's most. */
    public static final EffectParameter.Float RATE = EffectParameter.ofFloat("rate");

    /** Luminance of a flame's hottest core, cd/m² (a wood fire's flames reach some 10^4). */
    public static final double FLAME_LUMINANCE = 9000;
    /** Luminance of an ember. */
    public static final double EMBER_LUMINANCE = 6000;
    /** A fire's light per unit of its glow's colour: candela at full flicker. */
    public static final double GLOW_CANDELA = 180;
    /** Fireworks' stars. */
    public static final double SPARK_LUMINANCE = 30000;

    /** Fires and smoke far away: fewer particles drawn and spawned. */
    static final EffectType FIRES = EffectType.builder("fires").lod(25, 0.6f, 1, 0.5f).lod(60, 0.3f, 2, 0.25f)
        .build();

    /** Flames cooling over their life: white hot, yellow, orange, red, gone (times the birth colour). */
    static final ColorGradient COOLING = ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.06f, 1, 1, 1, 1)
        .linear(0.25f, 1, 0.78f, 0.45f, 1).linear(0.55f, 0.85f, 0.38f, 0.12f, 0.8f)
        .linear(0.8f, 0.5f, 0.1f, 0.025f, 0.3f).linear(1, 0.2f, 0.03f, 0.01f, 0).build();

    /** A hearth's fire: a hot core, flame tongues from the flipbook, embers, and its flickering light. */
    public static final EffectData HEARTH = hearth();
    /** Smoke rising from a chimney, lit, drifting with the wind. */
    public static final EffectData CHIMNEY = chimney();
    /** Snowflakes in a box about the camera, lit per vertex, fluttering as they fall, settling on what they hit. */
    public static final EffectData SNOW = snow();
    /** Rain in a box about the camera: streaks along their fall, lit per vertex. */
    public static final EffectData RAIN = rain();
    /** One launch of the fireworks show. */
    public static final EffectData LAUNCH = launch();

    private VillageEffects() {
    }

    private static EffectData hearth() {
        float size = 0.42f;
        float radius = 0.22f;
        float rise = (float) Math.sqrt(size / 0.26f);
        return EffectData.builder(FIRES).parameter(FLAME, 1, 1, 1, 1).parameter(EMBER, 1, 1, 1, 1)
            .parameter(GLOW, 1, 1, 1, 1)
            .emitter("core", e -> e.space(Space.WORLD).spawn(Spawn.rate(26))
                .init(Shape.disc(0.7f * radius), Init.randomRotation(), Init.velocity(Bind.random(-0.05f, 0.05f),
                    Bind.random(0.2f, 0.4f), Bind.random(-0.05f, 0.05f)), Init.lifetime(Bind.random(0.15f, 0.3f)),
                    Init.size(Bind.random(0.8f * size, 1.1f * size)), Init.color(Bind.param(FLAME)))
                .update(Forces.drag(2), Over.color(ColorGradient.builder().linear(0, 1, 0.47f, 0.14f, 0)
                    .linear(0.3f, 1, 0.47f, 0.14f, 0.3f).linear(1, 0.6f, 0.14f, 0.03f, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive().set("soft", 0.1f)
                    .asset("texture", FxTextures.GLOW)))
            .emitter("flames", e -> e.space(Space.WORLD).spawn(Spawn.rate(110))
                .init(Shape.disc(radius), Init.randomFrame(), Init.velocity(Bind.random(-0.1f, 0.1f),
                        Bind.random(0.6f * rise, 1.1f * rise), Bind.random(-0.1f, 0.1f)),
                    Init.lifetime(Bind.random(0.4f, 0.7f)), Init.size(Bind.random(0.75f * size, 1.15f * size)),
                    Init.color(Bind.param(FLAME)))
                .update(Forces.gravity(Bind.constant(-0.3f)), Forces.drag(1.4f),
                    Forces.curl(Bind.constant(1.6f * rise), 1.8f / rise, 1.2f, 2),
                    Forces.vortex(Bind.constant(0.3f), 2.5f, 0, 1, 0),
                    Over.size(FloatCurve.of(0, 0.45f, 0.25f, 1, 0.7f, 0.85f, 1, 0.35f)),
                    Over.color(ColorGradient.builder().linear(0, 0.5f, 0.38f, 0.27f, 0).linear(0.06f, 0.5f, 0.38f,
                        0.27f, 1).linear(0.25f, 0.5f, 0.3f, 0.12f, 1).linear(0.55f, 0.42f, 0.14f, 0.03f, 0.8f)
                        .linear(0.8f, 0.25f, 0.04f, 0.007f, 0.3f).linear(1, 0.1f, 0.01f, 0.003f, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.3f)
                    .set("soft", 0.1f).asset("texture", FxTextures.FLAME)
                    .set("flipbook.columns", FxTextures.FLAME_FRAMES).set("flipbook.rows", FxTextures.FLAME_FRAMES)))
            .emitter("embers", e -> e.space(Space.WORLD).spawn(Spawn.rate(12))
                .init(Shape.disc(radius), Init.velocity(Bind.random(-0.3f, 0.3f), Bind.random(0.8f, 1.8f),
                        Bind.random(-0.3f, 0.3f)), Init.lifetime(Bind.random(0.8f, 1.6f)),
                    Init.size(Bind.random(0.012f, 0.025f)), Init.color(Bind.param(EMBER)))
                .update(Forces.gravity(Bind.constant(-0.08f)), Forces.drag(0.5f),
                    Forces.curl(Bind.constant(2.5f), 2.2f, 1.5f, 2),
                    Over.color(ColorGradient.builder().linear(0, 1, 0.37f, 0.1f, 1).linear(0.5f, 0.8f, 0.2f, 0.05f, 1)
                        .linear(1, 0.4f, 0.04f, 0, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.04f)
                    .asset("texture", FxTextures.EMBER)))
            .emitter("glow", e -> e.spawn(Spawn.rate(30))
                .init(Shape.disc(0.15f), Init.velocity(Bind.constant(0), Bind.random(0.4f, 0.8f), Bind.constant(0)),
                    Init.lifetime(Bind.random(0.08f, 0.16f)), Init.color(Bind.param(GLOW)))
                .update(Over.color(ColorGradient.builder().linear(0, 0.55f, 0.27f, 0.08f, 1)
                    .linear(0.5f, 1, 0.5f, 0.16f, 1).linear(1, 0.7f, 0.33f, 0.1f, 1).build()))
                .render(Renderer.light(7, 1).set("max", 1)))
            .build(ShowcaseParticleModules.MODULES.registry());
    }

    private static EffectData chimney() {
        return EffectData.builder(FIRES)
            .emitter("smoke", e -> e.space(Space.WORLD).capacity(400).spawn(Spawn.rate(5))
                .init(Shape.disc(0.25f), Init.randomRotation(), Init.velocity(Bind.random(-0.1f, 0.1f),
                        Bind.random(0.9f, 1.4f), Bind.random(-0.1f, 0.1f)), Init.lifetime(Bind.random(9, 13)),
                    Init.size(Bind.random(0.6f, 0.9f)), Init.color(Bind.color(0.55f, 0.53f, 0.52f, 0.32f)))
                .update(Forces.drag(0.35f), Forces.curl(Bind.constant(0.35f), 0.35f, 0.3f, 2), Forces.wind(0.6f),
                    Forces.gravity(Bind.constant(-0.015f)), Over.size(FloatCurve.of(0, 0.5f, 1, 5.5f)),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.08f, 1, 1, 1, 0.8f)
                        .linear(0.4f, 1.05f, 1.05f, 1.05f, 0.45f).linear(1, 1.1f, 1.1f, 1.1f, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_PIXEL)
                    .set("soft", 0.6f).asset("texture", FxTextures.SMOKE).asset("normal", FxTextures.SMOKE_NORMALS)
                    .set("flipbook.columns", FxTextures.SMOKE_FRAMES)
                    .set("flipbook.rows", FxTextures.SMOKE_FRAMES)))
            .build(ShowcaseParticleModules.MODULES.registry());
    }

    private static EffectData snow() {
        float perSecond = 9000;
        return EffectData.builder().parameter(RATE, 1)
            .emitter("flakes", e -> e.space(Space.WORLD).capacity(200_000)
                .spawn(Spawn.rate(Bind.param(RATE).times(perSecond)))
                .init(Shape.box(70, 4, 70), Init.velocity(Bind.random(-0.2f, 0.2f), Bind.random(-1.2f, -0.8f),
                    Bind.random(-0.2f, 0.2f)), Init.lifetime(Bind.random(16, 20)),
                    Init.size(Bind.random(0.012f, 0.03f)), Init.color(Bind.color(0.9f, 0.9f, 0.95f, 0.95f)))
                .update(Forces.gravity(Bind.constant(0.1f)), Forces.drag(0.9f), Forces.wind(0.7f),
                    Forces.curl(Bind.constant(0.5f), 0.3f, 0.4f, 2),
                    Module.of("showcase.flutter").with("radius", Bind.constant(0.1f)).with("rate",
                        Bind.constant(0.5f)),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.03f, 1, 1, 1, 1)
                        .linear(0.95f, 1, 1, 1, 1).linear(1, 1, 1, 1, 0).build()))
                .collide(Collide.rays().orElse(Collide.height()).kill())
                .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX)
                    .set("soft", 0.05f).asset("texture", FxTextures.GLOW)))
            .build(ShowcaseParticleModules.MODULES.registry());
    }

    private static EffectData rain() {
        float perSecond = 30000;
        return EffectData.builder().parameter(RATE, 1)
            .emitter("drops", e -> e.space(Space.WORLD).capacity(100_000)
                .spawn(Spawn.rate(Bind.param(RATE).times(perSecond)))
                .init(Shape.box(50, 3, 50), Init.velocity(Bind.random(-0.4f, 0.4f), Bind.random(-9.5f, -8),
                    Bind.random(-0.4f, 0.4f)), Init.lifetime(Bind.random(2.6f, 3)),
                    Init.size(Bind.random(0.008f, 0.012f)), Init.color(Bind.color(0.7f, 0.75f, 0.8f, 0.35f)))
                .update(Forces.wind(0.5f))
                .collide(Collide.rays().orElse(Collide.height()).kill())
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).lighting(Renderer.Lighting.PER_VERTEX)
                    .set("stretch", 0.06f).set("soft", 0.05f).asset("texture", FxTextures.EMBER)))
            .build(ShowcaseParticleModules.MODULES.registry());
    }

    private static EffectData launch() {
        return EffectData.builder().parameter(SPARK, 1, 1, 1, 1)
            .emitter("rockets", e -> e.duration(0.5).spawn(Spawn.burst(0, Bind.random(1, 2.99f)))
                .init(Shape.disc(0.4f), Init.velocity(Bind.random(-3, 3), Bind.random(36, 42), Bind.random(-3, 3)),
                    Init.lifetime(Bind.random(2.3f, 2.8f)), Init.size(Bind.constant(0.3f)),
                    Init.color(Bind.param(SPARK)))
                .update(Forces.gravity(), Forces.drag(0.12f), Over.color(ColorGradient.builder()
                    .linear(0, 0.5f, 0.3f, 0.1f, 1).linear(1, 0.5f, 0.3f, 0.1f, 1).build()))
                .event(ParticleEvent.onDeath("stars", Bind.random(120, 180)), ParticleEvent.onDeath("flash",
                    Bind.constant(1)), ParticleEvent.emit("trail", Bind.constant(45)))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.06f)
                    .asset("texture", FxTextures.EMBER)))
            .emitter("trail", e -> e.capacity(900).spawn(Spawn.fromEvents()).init(Shape.sphere(0.05f),
                    Init.speed(Bind.random(0.2f, 0.8f)), Init.lifetime(Bind.random(0.5f, 1.1f)),
                    Init.size(Bind.random(0.08f, 0.14f)), Init.color(Bind.param(SPARK)), Init.inherit(0.15f))
                .update(Forces.gravity(Bind.constant(0.5f)), Forces.drag(1.5f),
                    Over.color(ColorGradient.builder().linear(0, 0.3f, 0.2f, 0.1f, 1).linear(1, 0.06f, 0.02f, 0.005f,
                        0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", FxTextures.EMBER)))
            .emitter("stars", e -> e.capacity(2400).spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.2f, 0), Init.speed(Bind.random(18, 24)), Init.lifetime(Bind.random(1.6f, 2.4f)),
                    Init.size(Bind.random(0.35f, 0.5f)), Init.color(Bind.random(ColorGradient.builder()
                        .linear(0, 1, 0.2f, 0.08f, 1).linear(0.25f, 1, 0.65f, 0.15f, 1).linear(0.5f, 0.15f, 0.9f,
                            0.25f, 1).linear(0.75f, 0.2f, 0.35f, 1, 1).linear(1, 1, 0.2f, 0.85f, 1).build())))
                .update(Forces.gravity(Bind.constant(0.3f)), Forces.drag(2.2f),
                    Over.color(ColorGradient.builder().linear(0, 1.8f, 1.8f, 1.8f, 1).linear(0.12f, 1, 1, 1, 1)
                        .linear(0.55f, 0.8f, 0.65f, 0.5f, 1).linear(1, 0.3f, 0.15f, 0.08f, 0).build()))
                .event(ParticleEvent.onDeath("sparkles", Bind.constant(3)).chance(0.35f))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive().set("stretch", 0.05f)
                    .asset("texture", FxTextures.EMBER), Renderer.trail(8).additive().set("width.head", 0.5f)
                    .set("width.tail", 0f)))
            .emitter("sparkles", e -> e.capacity(3000).spawn(Spawn.fromEvents()).init(Shape.sphere(0.1f),
                    Init.speed(Bind.random(1, 2.5f)), Init.lifetime(Bind.random(0.25f, 0.6f)),
                    Init.size(Bind.random(0.1f, 0.16f)), Init.color(Bind.param(SPARK)))
                .update(Forces.gravity(Bind.constant(0.3f)), Forces.drag(1),
                    Over.color(ColorGradient.builder().linear(0, 0.6f, 0.55f, 0.4f, 1).linear(0.4f, 0.2f, 0.2f,
                        0.2f, 1).linear(0.55f, 0.6f, 0.6f, 0.6f, 1).linear(1, 0.25f, 0.2f, 0.1f, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", FxTextures.EMBER)))
            .emitter("flash", e -> e.capacity(4).spawn(Spawn.fromEvents()).init(Shape.point(), Init.lifetime(0.7f),
                    Init.size(Bind.constant(14)), Init.color(Bind.param(SPARK)), Init.inherit(0, true, false))
                .update(Over.color(ColorGradient.builder().linear(0, 0.2f, 0.2f, 0.2f, 0.25f).linear(0.2f, 0.1f,
                    0.1f, 0.1f, 0.2f).linear(1, 0, 0, 0, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive().asset("texture", FxTextures.GLOW),
                    Renderer.light(160, 0.15f).set("max", 2)))
            .build(ShowcaseParticleModules.MODULES.registry());
    }
}
