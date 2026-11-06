package com.truesim.samples.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.TrueSim;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Profiling;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.input.Input;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.ui.UiPlugin;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.style.Rule;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.ArrayList;
import java.util.List;

/**
 * The stream benchmark's fly-over in a window, to watch: the same cells, content and camera path as
 * {@link StreamBench}'s headless run ({@link StreamFlight}: the scene way on the engine's {@link Assets}, or the
 * {@code baseline}), presented in a resizable window and paced by the display (the presenter), with the profiler's
 * frame graph overlay (F3, on at start) and detailed view (Shift+F3), and a line at the bottom left: cells in range,
 * meshes and textures added and removed in the last second. Runs until the window is closed, or for
 * {@code demo.seconds} ({@code -Pseconds=n}). {@code ./gradlew :samples-demo:streamBench --args=window} (with
 * baseline, rays, large, heavy, direct, inframe, streamed or grow as the benchmark takes them).
 *
 * <p>Speed ({@code demo.speed}, {@code -Pspeed=n} metres a second, 60 by default against the benchmark's 10) changes
 * live with + and - (the keypad's too), and Space pauses: the {@code stream} actions ({@link StreamActions}),
 * rebindable as any. The camera follows real time ({@code x} advances by the speed times the frame's real delta, as
 * the benchmark's {@code frame * speed / 90} does at 90 Hz). Per frame it allocates nothing: the flight's step does
 * not, and the line is rewritten in place once a second; cells coming into range allocate their content, as they do
 * headless.
 *
 * <p>Still outside the engine's way, for want of the parts: the HUD's fonts and theme come from the classpath
 * ({@link BundledFonts}, {@link Tss#defaultTheme()}) rather than as assets, there being no font or style sheet asset
 * types yet.
 */
final class StreamWindow implements Application, PlatformListener {

    private static final int COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int DEPTH = VK_FORMAT_D32_SFLOAT;
    private static final String HUD_STYLE = """
        panel.stream-hud { layout: flex; direction: column; justify-content: end; align-items: start; padding: 12px;
          width: 100%; height: 100%; }
        label.stream-hud-line { font-size: 14px; color: #e6edf3; padding: 4px 8px; radius: 4px;
          background: box(rgba(#0d1117, 0.78)); }
        """;

    private double seconds; // demo.seconds; 0: until the window is closed
    private final Stream stream = new Stream();
    private Engine engine;
    private Ui ui;
    private Element line;
    private final StringBuilder text = new StringBuilder(160);
    private long started = -1;
    private com.truesim.engine.input.Player player;
    private double speed = StreamBench.speed;
    private boolean paused;
    private double x;
    private long counted;
    private int meshesAdded;
    private int meshesRemoved;
    private int texturesAdded;
    private int texturesRemoved;

    private StreamWindow() {
    }

    static void run(String[] args) {
        TrueSim.run(new StreamWindow(), args);
    }

    @Override
    public void configure(AppConfig app) {
        app.id("truesim-stream").name("true-sim stream")
            .use(new Desktop(), new Input().add(StreamActions.STREAM), new Rendering(), new Profiling());
        if (StreamBench.mode == StreamBench.Mode.SCENE) { // the one asset manager, sized for the cells in range
            app.use(new Assets().geometry(StreamBench.capacity()).materials(StreamBench.materialCapacity()));
        }
        app.use(stream, new UiPlugin(StreamWindow::ui).view("main"));
    }

    /** The UI: the default theme with the line's style, the bundled fonts. */
    private static Ui ui(UiTextures ignored) {
        NativeTextBackend backend = new NativeTextBackend();
        UiTextures textures = new UiTextures();
        TextSystem text = new TextSystem(BundledFonts.registry(backend), backend.newShaper(),
            new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings()));
        StyleSheet theme = Tss.defaultTheme();
        List<Rule> rules = new ArrayList<>(theme.rules());
        rules.addAll(Tss.compileString("stream-hud.tss", HUD_STYLE).rules());
        return new Ui(new StyleSheet(rules, theme.tokens(), theme.tokenSets()), text, textures);
    }

    @Override
    public void start(Engine started) {
        engine = started;
        seconds = engine.settings().get(DemoSettings.SECONDS);
        engine.get(Desktop.KEY).listen(this);
        player = engine.get(Input.KEY).player(0);
        UiPlugin uiPlugin = engine.get(UiPlugin.KEY);
        uiPlugin.profiler();
        engine.settings().set(ProfilerSettings.OVERLAY, true);
        ui = uiPlugin.ui();
        line = ui.label("stream-hud-line", "streaming");
        ui.layerStack().get(com.truesim.ui.core.Layers.HUD).root().add(ui.panel("stream-hud").add(line));
        System.out.println("GPU: " + engine.get(Rendering.KEY).device().gpu().name());
        System.out.println("""
            Keys: F3 the frame graph overlay (on), Shift+F3 the detailed profiler (wheel zooms, drag pans),
            + and - faster and slower, Space pauses. Close the window to stop.""");
    }

    @Override
    public void update(Frame frame) {
        long now = System.nanoTime();
        if (started < 0) {
            started = now;
            counted = now;
        }
        if (seconds > 0 && now - started >= seconds * 1e9) {
            engine.quit();
        }
        boolean changed = false;
        if (player.pressed(StreamActions.FASTER)) {
            speed = Math.min(2000, speed * 1.25);
            changed = true;
        }
        if (player.pressed(StreamActions.SLOWER)) {
            speed = Math.max(1, speed / 1.25);
            changed = true;
        }
        if (player.pressed(StreamActions.PAUSE)) {
            paused = !paused;
            changed = true;
        }
        if (!paused) {
            x += speed * Math.min(0.25, frame.realDelta()); // a long stall moves a quarter second, not a jump
        }
        stream.step(x);
        if (changed) {
            hud();
        }
        meshesAdded += stream.meshesAdded;
        meshesRemoved += stream.meshesRemoved;
        texturesAdded += stream.texturesAdded;
        texturesRemoved += stream.texturesRemoved;
        if (now - counted >= 1_000_000_000L) {
            hud();
            counted = now;
            meshesAdded = 0;
            meshesRemoved = 0;
            texturesAdded = 0;
            texturesRemoved = 0;
        }
    }

    /** The line: what is in range and what streamed in the last second. */
    private void hud() {
        text.setLength(0);
        text.append("cells in range ").append(stream.inRange).append(" (").append(stream.placed)
            .append(" placed, ").append(stream.cellCount() - stream.placed).append(" coming)   last second: meshes +")
            .append(meshesAdded).append(" -").append(meshesRemoved).append(", textures +").append(texturesAdded)
            .append(" -").append(texturesRemoved).append("   on the GPU: ").append(stream.meshCount())
            .append(" meshes, ").append(stream.textureCount()).append(" textures   x ")
            .append((int) stream.x).append(" m   speed ").append(Math.round(speed)).append(" m/s");
        if (paused) {
            text.append(" (paused)");
        }
        ui.text(line, text);
    }

    @Override
    public void windowCloseRequested(int id) {
        engine.requestQuit(QuitReason.WINDOW_CLOSED, false);
    }

    /**
     * The streamed world on the engine's device and graph: the window and its view (made in {@link Stage#GRAPH}, so
     * the UI finds the window's view), the flight ({@link StreamFlight}: the scene way on the engine's assets, or the
     * baseline), and its streaming each frame.
     */
    private static final class Stream implements Plugin {
        static final PluginKey<Stream> KEY = PluginKey.of("demo.stream", Stream.class);

        private StreamFlight flight;
        private Window window;
        double x;
        int inRange;
        int placed;
        int meshesAdded;
        int meshesRemoved;
        int texturesAdded;
        int texturesRemoved;

        @Override
        public PluginKey<Stream> key() {
            return KEY;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return StreamBench.mode == StreamBench.Mode.SCENE ? List.of(Rendering.KEY, Desktop.KEY, Assets.KEY)
                : List.of(Rendering.KEY, Desktop.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.BEFORE_INSTANCE) {
                DemoSettings.register(startup.registry()); // the console and the command line reach them
            }
            if (stage != Stage.GRAPH) {
                return;
            }
            Rendering rendering = startup.get(Rendering.KEY);
            VulkanDevice device = rendering.device();
            FrameGraph graph = rendering.graph();
            // more threads than the benchmark's 3: fast flights over wide ranges ask for hundreds of cells a second
            int threads = Math.max(3, Runtime.getRuntime().availableProcessors() / 2);
            Assets assets = StreamBench.mode == StreamBench.Mode.SCENE ? startup.get(Assets.KEY) : null;
            flight = new StreamFlight(device, graph.frameSlots(), startup.settings(), assets,
                startup.engine().tasks(), threads);
            ViewPort port = graph.addView("main");
            flight.draw(port, COLOR, DEPTH);
            window = startup.get(Desktop.KEY).platform().createWindow(
                WindowOptions.of("stream test", 1600, 900), startup.settings().scoped("main"));
            rendering.presenter().outputTransform(new DisplayTransform()).present(port, Keys.SCENE_COLOR, window);
        }

        /** A frame at {@code at} metres into the flight: the camera and the cells streamed as headless. */
        void step(double at) {
            flight.step(at, false, Math.max(1, window.pixelWidth()), Math.max(1, window.pixelHeight()));
            x = flight.x;
            inRange = flight.inRange;
            placed = flight.placed;
            meshesAdded = flight.meshesAdded;
            meshesRemoved = flight.meshesRemoved;
            texturesAdded = flight.texturesAdded;
            texturesRemoved = flight.texturesRemoved;
        }

        int cellCount() {
            return flight.cellCount();
        }

        int meshCount() {
            return flight.pool().meshCount();
        }

        int textureCount() {
            return flight.textures().count();
        }

        @Override
        public void stop() {
            if (flight != null) {
                flight.close();
                flight = null;
            }
        }
    }
}
