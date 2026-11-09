package com.truesim.samples.showcase;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import java.util.List;

/**
 * The showcase's options as settings (hidden from players; the console and {@code --set name=value} reach them). The
 * engine's and the effect libraries' own settings (render.gi, render.bloom, render.clouds, ...) are set the same way.
 */
public final class ShowcaseSettings {

    /** The Poly Haven cache, mounted as {@code ph://} ({@code ./gradlew :samples-showcase:fetchAssets} fills it). */
    public static final Setting<String> ASSETS = Setting.text("showcase.assets",
        System.getProperty("user.home") + "/.truesim/polyhaven",
        "the Poly Haven cache, mounted as ph:// (./gradlew :samples-showcase:fetchAssets fills it)");
    /**
     * effects-terrain's cache ({@code ./gradlew :samples-showcase:fetchTerrain} fills it): Lauterbrunnen's site; without
     * it the village stands in a valley made from functions.
     */
    public static final Setting<String> TERRAIN = Setting.text("showcase.terrain",
        System.getProperty("user.home") + "/.truesim/terrain",
        "effects-terrain's cache with the Lauterbrunnen site (./gradlew :samples-showcase:fetchTerrain fills it)");
    /**
     * The sun's shadows: {@code cascades} (four shadow-map cascades, PCSS-soft: the grass's blades cast too) or
     * {@code rayTraced} (traced per pixel, soft from the sun's disc, denoised: exact contact, no cascade seams, but
     * rays see no grass and trees at their ray-tracing level).
     */
    public static final Setting<String> SUN_SHADOWS = Setting.text("showcase.sunShadows", "cascades",
        "the sun's shadows: cascades or rayTraced");
    /** Where common-procedural keeps the trees and flowers it generated (made once, read on later runs). */
    public static final Setting<String> PROCEDURAL = Setting.text("showcase.procedural",
        System.getProperty("user.home") + "/.truesim/procedural",
        "common-procedural's generation cache: the showcase's trees, shrubs and flowers");
    /** The quality preset: low, medium, high or ultra (the atmosphere's, the clouds', the shadows', the GI's). */
    public static final Setting<String> QUALITY = Setting.text("showcase.quality", "high",
        "the quality preset: low, medium, high or ultra");
    /** Where the day starts, in hours of local time (the interactive app; the tour follows its script). */
    public static final Setting<Float> HOUR = Setting.decimal("showcase.hour", 12.5f, 0, 24,
        "the hour of the day the interactive app starts at (local time)");
    /** The weather the interactive app starts with: a preset of effects-weather by name. */
    public static final Setting<String> WEATHER = Setting.text("showcase.weather", "scattered",
        "the weather the interactive app starts with (clear, fair, scattered, broken, overcast, rain, storm)");
    /** The frames' size, {@code WIDTHxHEIGHT}. */
    public static final Setting<String> SIZE = Setting.text("showcase.size", "1920x1080", "the frames' size");
    /** The folder the tour writes its frames to (empty: none, timing only). */
    public static final Setting<String> OUT = Setting.text("showcase.out", "", "the folder the tour writes frames to");
    /** The tour's segments to run, comma separated (empty: all). */
    public static final Setting<String> SEGMENTS = Setting.text("showcase.segments", "",
        "the tour's segments to run, comma separated: dawn, noon, storm, dusk, night (empty: all)");
    /** The tour writes every n-th frame (simulated at 60 Hz: 2 makes a 30 fps film). */
    public static final Setting<Integer> EVERY = Setting.integer("showcase.every", 2, 1, 1 << 16,
        "the tour writes every n-th frame (60 Hz simulated: 2 makes 30 fps)");
    /** The tour's speed: 1 its full length, more to skim it (each frame further along). */
    public static final Setting<Float> PACE = Setting.decimal("showcase.pace", 1, 0.01f, 1000,
        "the tour's pace: 1 its full length, more to skim it");
    /** Frames the tour writes as stills too, as seconds into the tour ({@code 12.5,40}). */
    public static final Setting<String> STILLS = Setting.text("showcase.stills", "",
        "seconds into the tour written as stills too (comma separated)");
    /** Seconds a windowed run lasts (0: until closed). */
    public static final Setting<Float> SECONDS = Setting.decimal("showcase.seconds", 0, 0, 1 << 20,
        "seconds a windowed run lasts (0: until closed)");
    /** Global illumination: probes (the default) or cascades. */
    public static final Setting<String> GI = Setting.text("showcase.gi", "probes",
        "global illumination: probes, cascades or off");
    /** Shading: forward or visibility (the visibility buffer). */
    public static final Setting<String> SHADING = Setting.text("showcase.shading", "forward",
        "shading: forward or visibility (the visibility buffer)");
    /** Measure frame times per segment (GPU timestamps on). */
    public static final Setting<Boolean> MEASURE = Setting.bool("showcase.measure", false,
        "measure frame times per segment of the tour (GPU timestamps)");

    /** Every one of them. */
    public static final List<Setting<?>> ALL = List.of(ASSETS, TERRAIN, PROCEDURAL, SUN_SHADOWS, QUALITY, HOUR, WEATHER, SIZE, OUT, SEGMENTS,
        EVERY, PACE, STILLS, SECONDS, GI, SHADING, MEASURE);

    private ShowcaseSettings() {
    }

    /** Registers them, hidden from players. */
    public static void register(SettingRegistry registry) {
        registry.addHidden(ALL.toArray(new Setting<?>[0]));
    }

    /** Settings for a program without an engine: defaults, then {@code --set name=value} from {@code args}. */
    public static EngineSettings standalone(String... args) {
        return new EngineSettings().commandLine(args);
    }
}
