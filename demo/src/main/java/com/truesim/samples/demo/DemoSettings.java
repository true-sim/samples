package com.truesim.samples.demo;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;
import java.util.List;

/**
 * The samples' developer options as settings, not system properties: each is a {@link Setting} the registry lists
 * (hidden from players, reached by the console and the command line), set with {@code --set name=value}
 * ({@code ./gradlew :samples-demo:run --args="--set demo.rocks=2000"}; the Gradle tasks turn their {@code -P}
 * properties into these). Programs running without an engine (the benchmarks, the showcase) read them from
 * {@link #standalone}.
 *
 * <p>Content is named by asset path, never by file: {@link #ASSETS} is the folder mounted as {@code demo://} (the
 * Khronos sample models, say; it stays out of the repository), and {@link #CHARACTER} and {@link #CROWD} name a model
 * in it ({@code demo://Fox/glTF/Fox.gltf}).
 */
public final class DemoSettings {

    /** The folder mounted as {@code demo://}: sample models and textures, kept out of the repository. */
    public static final Setting<String> ASSETS = Setting.text("demo.assets", "assets",
        "the folder mounted as demo:// (sample models; not in the repository)");
    /** The fox standing among the rocks: a glTF model's asset path ({@code demo://...}), empty for none. */
    public static final Setting<String> CHARACTER = Setting.text("demo.character", "",
        "the animated fox among the rocks: its model's asset path (demo://.../Fox.gltf), empty for none");
    /** The crowd's model: an asset path; empty: the character's. */
    public static final Setting<String> CROWD = Setting.text("demo.crowd", "",
        "the crowd's model: an asset path (empty: the character's)");
    /** Foxes in the crowd (the stereo bench's; the showcase's arena has 100 by default). */
    public static final Setting<Integer> FOXES = Setting.integer("demo.foxes", 0, 0, 100_000,
        "foxes in the crowd (0: none; the showcase's arena: 100)");
    /** The crowd's animation level of detail by distance. */
    public static final Setting<Boolean> FOX_LOD = Setting.bool("demo.foxLod", false,
        "the crowd's animation level of detail by distance");
    /** Rocks on the terrain. */
    public static final Setting<Integer> ROCKS = Setting.integer("demo.rocks", 800, 0, 1_000_000,
        "rocks on the terrain");
    /** Point lights: the six circling ones and the rest scattered near the middle. */
    public static final Setting<Integer> LIGHTS = Setting.integer("demo.lights", 6, 6, 65_536,
        "point lights: six circling, the rest scattered near the middle");
    /** Ray tracing: refits of a skinned copy's bottom level between rebuilds (0: the engine's default). */
    public static final Setting<Integer> REBUILD_EVERY = Setting.integer("demo.rebuildEvery", 0, 0, 1 << 20,
        "refits of a skinned copy's bottom level between rebuilds (0: the default)");
    /** Frames a benchmark runs (0: its own default). */
    public static final Setting<Integer> FRAMES = Setting.integer("demo.frames", 0, 0, 1 << 24,
        "frames a benchmark runs (0: its default)");
    /** Seconds a windowed run lasts (0: until closed). */
    public static final Setting<Float> SECONDS = Setting.decimal("demo.seconds", 0, 0, 1 << 20,
        "seconds a windowed run lasts (0: until closed)");
    /** The stereo bench's particle effects: where they play ({@code x,y,z}, {@code g+h} for heights above ground). */
    public static final Setting<String> EFFECT = Setting.text("demo.effect", "",
        "where the stereo bench plays the demo's particle effects (x,y,z; g+h: h above the ground)");
    /** A PNG file the stereo bench writes the left eye to. */
    public static final Setting<String> PNG = Setting.text("demo.png", "", "a PNG file of the left eye (the stereo bench)");
    /** Frames the stereo bench writes as PNGs ({@code 108,612}). */
    public static final Setting<String> PNG_FRAMES = Setting.text("demo.pngFrames", "",
        "frames the stereo bench writes as PNGs (comma separated)");
    /** The shadow casters' detail in the stereo bench. */
    public static final Setting<String> CASTER_DETAIL = Setting.text("demo.casterDetail", "",
        "the shadow casters' detail in the stereo bench");
    /** A camera of the stereo bench's own: {@code x,y,z,tx,ty,tz} ({@code g+h} for heights above the ground). */
    public static final Setting<String> EYE = Setting.text("demo.eye", "",
        "the stereo bench's camera: x,y,z,tx,ty,tz (g+h: h above the ground)");
    /** The stream benchmark's cells in range each way (0: its default). */
    public static final Setting<Integer> RANGE = Setting.integer("demo.range", 0, 0, 64,
        "the stream benchmark's cells in range each way (0: its default)");
    /** The stream benchmark's speed, metres a second (0: its default). */
    public static final Setting<Float> SPEED = Setting.decimal("demo.speed", 0, 0, 10_000,
        "the stream benchmark's speed in metres a second (0: its default)");
    /** Every how many frames the stream benchmark prints memory (0: never). */
    public static final Setting<Integer> MEMORY = Setting.integer("demo.memory", 0, 0, 1 << 24,
        "every how many frames the stream benchmark prints memory (0: never)");
    /** The showcase's camera: {@code orbit}, {@code keys:...} or {@code follow:...}. */
    public static final Setting<String> CAMERA = Setting.text("demo.camera", "orbit",
        "the showcase's camera: orbit, keys:t,x,y,z,tx,ty,tz;... or follow:fox,distance,height,side");
    /** The showcase's frame size, {@code WIDTHxHEIGHT}. */
    public static final Setting<String> SIZE = Setting.text("demo.size", "1920x1080", "the showcase's frame size");
    /** The showcase's first frame written. */
    public static final Setting<Integer> FIRST = Setting.integer("demo.first", 0, 0, 1 << 24,
        "the showcase's first frame written");
    /** The showcase writes every n-th frame. */
    public static final Setting<Integer> EVERY = Setting.integer("demo.every", 2, 1, 1 << 16,
        "the showcase writes every n-th frame");
    /** The folder the showcase writes its frames to (empty: none). */
    public static final Setting<String> OUT = Setting.text("demo.out", "", "the folder the showcase writes frames to");
    /** Frames the showcase also writes as stills ({@code 360,720}). */
    public static final Setting<String> STILLS = Setting.text("demo.stills", "", "frames written as stills");
    /** The showcase's exposure (0: the display transform's). */
    public static final Setting<Float> EXPOSURE = Setting.decimal("demo.exposure", 0, 0, 64,
        "the showcase's exposure (0: the display transform's)");
    /** Torches in the showcase (8 and more: extra ones on a ring). */
    public static final Setting<Integer> TORCHES = Setting.integer("demo.torches", 8, 0, 100_000,
        "torches in the showcase");
    /** Spark trails in the showcase, for a stress test (0: none). */
    public static final Setting<Integer> SPARKS = Setting.integer("demo.sparks", 0, 0, 1 << 24,
        "spark trails in the showcase (a stress test)");
    /** Glass panes in the showcase. */
    public static final Setting<Boolean> GLASS = Setting.bool("demo.glass", false, "glass panes in the showcase");
    /** The particle simulation on the async compute queue. */
    public static final Setting<Boolean> ASYNC = Setting.bool("demo.async", false,
        "the particle simulation on the async compute queue");
    /** Rocks in the UI gallery's 3D scene. */
    public static final Setting<Integer> GALLERY_ROCKS = Setting.integer("gallery.rocks", 400, 0, 100_000,
        "rocks in the UI gallery's 3D scene");
    /** A folder the UI gallery's tour writes what each page shows to (empty: none). */
    public static final Setting<String> GALLERY_SHOTS = Setting.text("gallery.shots", "",
        "a folder the UI gallery's tour writes each page's picture to");

    /** Every one of them. */
    public static final List<Setting<?>> ALL = List.of(ASSETS, CHARACTER, CROWD, FOXES, FOX_LOD, ROCKS, LIGHTS,
        REBUILD_EVERY, FRAMES, SECONDS, EFFECT, PNG, PNG_FRAMES, CASTER_DETAIL, EYE, RANGE, SPEED, MEMORY, CAMERA,
        SIZE, FIRST, EVERY, OUT, STILLS, EXPOSURE, TORCHES, SPARKS, GLASS, ASYNC, GALLERY_ROCKS, GALLERY_SHOTS);

    private DemoSettings() {
    }

    /** Registers them, hidden from players: the console and the command line reach them. */
    public static void register(SettingRegistry registry) {
        registry.addHidden(ALL.toArray(new Setting<?>[0]));
    }

    /** Settings for a program without an engine: defaults, then {@code --set name=value} from {@code args}. */
    public static EngineSettings standalone(String... args) {
        return new EngineSettings().commandLine(args);
    }

    /** {@code value} if positive, else {@code fallback}: for the settings whose 0 means "the default". */
    public static int or(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
