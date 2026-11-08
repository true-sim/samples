package com.truesim.samples.showcase;

import com.truesim.effects.weather.WeatherPreset;
import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.TrueSim;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.input.Input;
import com.truesim.engine.input.InputContext;
import com.truesim.engine.input.MouseMode;
import com.truesim.engine.input.Player;
import com.truesim.engine.platform.Desktop;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.tour.CameraRig;
import java.util.List;
import java.util.Locale;

/**
 * The showcase to fly through: the village in a window, the camera free (W A S D, Q E, the right mouse button to
 * look, Shift faster; a gamepad's sticks), the day under the keys: [ and ] an hour earlier or later, T time passing a
 * hundred times as fast, 1 to 8 the weather (clear, fair, scattered, broken, overcast, altostratus, rain, storm),
 * B snowfall, M the valley's mist, F the fireworks; G global illumination (probes, radiance cascades, off), P the
 * quality preset, J depth of field. Escape quits. {@code ./gradlew :samples-showcase:run} (the content first:
 * {@code ./gradlew :samples-showcase:fetchAssets}); {@code -Phour=7 -Pweather=fair -Pquality=ultra}.
 */
public final class ShowcaseApp implements Application, PlatformListener {

    private static final List<String> QUALITIES = List.of("low", "medium", "high", "ultra");
    private static final List<String> GI = List.of("probes", "cascades", "off");

    private final World world = new World();
    private Engine engine;
    private Player player;
    private Input input;
    private InputContext looking;
    private final DayState state = new DayState();
    private double yaw = Math.PI;
    private double pitch = -0.05;
    private double x = 0;
    private double y = 3;
    private double z = 34;
    private boolean lapse;
    private boolean snowing;
    private boolean misty;
    private double titleAt;

    public static void main(String[] args) {
        TrueSim.run(new ShowcaseApp(), args);
    }

    @Override
    public void configure(AppConfig app) {
        app.id("truesim-showcase").name("true-sim showcase")
            .use(new Desktop(), new Input().add(ShowcaseActions.SHOWCASE), new Rendering(), world);
    }

    @Override
    public void start(Engine started) {
        engine = started;
        engine.get(Desktop.KEY).listen(this);
        input = engine.get(Input.KEY);
        player = input.player(0);
        looking = input.context("looking", 10).mouse(MouseMode.CAPTURED);
        EngineSettings settings = engine.settings();
        state.hour = settings.get(ShowcaseSettings.HOUR);
        state.weather = WeatherPreset.builtIn(settings.get(ShowcaseSettings.WEATHER));
        state.weatherTransition = 90;
        state.weatherRate = 1;
        state.fog = 0.0004;
        state.fires = true;
        y = world.world.ground().height(x, z) + 1.8;
        System.out.println("""
            W A S D, Q E: fly (Shift: faster); right mouse button: look; a gamepad's sticks and shoulders too
            [ ]: an hour earlier, later; T: time passing a hundred times as fast; 1-8: clear, fair, scattered, broken,
            overcast, altostratus, rain, storm; B: snowfall; M: mist; F: fireworks
            G: global illumination (probes, cascades, off); P: quality (low, medium, high, ultra); J: depth of field
            Escape: quit""");
    }

    @Override
    public void update(Frame frame) {
        double dt = Math.min(frame.realDelta(), 0.1);
        controls(dt);
        // the day follows: lamps on from dusk to dawn, rooms lit in the evening, fires burning when it is cold
        double h = state.hour;
        double dark = h < 7.3 || h > 17.6 ? 1 : h < 7.8 ? (7.8 - h) / 0.5 : h > 17.1 ? (h - 17.1) / 0.5 : 0;
        state.lamps = dark;
        state.rooms = Math.max(dark, h > 16 && h < 23 ? 0.7 : 0.25);
        state.storm = state.weather == WeatherPreset.STORM;
        state.rain = state.weather == WeatherPreset.RAIN || state.storm ? 1 : 0;
        state.snowfall = snowing ? 1 : 0;
        state.snowCover = Math.clamp(state.snowCover + (snowing ? dt / 60 : -dt / 600), 0, 0.85);
        state.mist = misty ? 0.02 : 0;
        state.fog = misty ? 0.003 : 0.0004;
        if (lapse) {
            state.hour = (state.hour + dt * 100 / 3600) % 24;
        }
        CameraRig rig = world.rig;
        rig.place(x, y, z, yaw, pitch);
        world.world.renderer().scene().update();
        rig.update();
        world.world.frame(dt, state, rig.camera(), world.window.pixelHeight());
        titleAt += frame.realDelta();
        if (titleAt > 0.5) {
            titleAt = 0;
            world.window.setTitle(String.format(Locale.ROOT, "true-sim showcase  %02d:%02d  %s  %.0f fps  EV %.1f",
                (int) state.hour, (int) (state.hour % 1 * 60), state.weather.name(), 1 / Math.max(dt, 1e-6),
                world.world.day().ev100()));
        }
    }

    private void controls(double dt) {
        if (player.pressed(ShowcaseActions.LOOKING)) {
            input.push(looking);
        }
        if (player.released(ShowcaseActions.LOOKING)) {
            input.pop(looking);
        }
        if (player.pressed(ShowcaseActions.QUIT)) {
            engine.quit();
        }
        yaw -= player.x(ShowcaseActions.LOOK) + player.x(ShowcaseActions.TURN) * dt;
        pitch = Math.clamp(pitch - player.y(ShowcaseActions.LOOK) + player.y(ShowcaseActions.TURN) * dt, -1.5, 1.5);
        double speed = (player.down(ShowcaseActions.FAST) ? 60 : 8) * dt;
        double forward = player.y(ShowcaseActions.MOVE);
        double right = player.x(ShowcaseActions.MOVE);
        x += (Math.sin(yaw) * forward - Math.cos(yaw) * right) * speed;
        z += (Math.cos(yaw) * forward + Math.sin(yaw) * right) * speed;
        y = Math.max(y + player.value(ShowcaseActions.RISE) * speed, world.world.ground().height(x, z) + 0.4);
        if (player.pressed(ShowcaseActions.EARLIER)) {
            state.hour = (state.hour + 23) % 24;
        }
        if (player.pressed(ShowcaseActions.LATER)) {
            state.hour = (state.hour + 1) % 24;
        }
        if (player.pressed(ShowcaseActions.TIME_LAPSE)) {
            lapse = !lapse;
        }
        WeatherPreset[] presets = {WeatherPreset.CLEAR, WeatherPreset.FAIR, WeatherPreset.SCATTERED,
            WeatherPreset.BROKEN, WeatherPreset.OVERCAST, WeatherPreset.ALTOSTRATUS, WeatherPreset.RAIN,
            WeatherPreset.STORM};
        for (int i = 0; i < presets.length; i++) {
            if (player.pressed(ShowcaseActions.WEATHER[i])) {
                state.weather = presets[i];
            }
        }
        if (player.pressed(ShowcaseActions.SNOW)) {
            snowing = !snowing;
        }
        if (player.pressed(ShowcaseActions.MIST)) {
            misty = !misty;
        }
        if (player.pressed(ShowcaseActions.FIREWORKS)) {
            state.fireworks = !state.fireworks;
        }
        EngineSettings settings = engine.settings();
        if (player.pressed(ShowcaseActions.GI)) {
            String now = settings.get(ProbeVolumeLighting.SLOT.setting());
            String next = GI.get((GI.indexOf(now) + 1) % GI.size());
            settings.set(ProbeVolumeLighting.SLOT.setting(), next);
            System.out.println("global illumination: " + next);
        }
        if (player.pressed(ShowcaseActions.QUALITY)) {
            String now = settings.get(ShowcaseSettings.QUALITY);
            String next = QUALITIES.get((QUALITIES.indexOf(now) + 1) % QUALITIES.size());
            settings.set(ShowcaseSettings.QUALITY, next);
            ShowcaseRenderer.presets(settings, ShowcaseRenderer.quality(next));
            System.out.println("quality: " + next + " (the atmosphere's and the clouds' tiers; the rest at start)");
        }
        if (player.pressed(ShowcaseActions.DOF)) {
            state.fStop = state.fStop > 12 ? 4 : 16;
        }
    }

    @Override
    public void windowCloseRequested(int id) {
        engine.requestQuit(QuitReason.WINDOW_CLOSED, false);
    }

    /** The world on the engine's device and graph, and the window showing it: made in {@link Stage#GRAPH}. */
    private static final class World implements Plugin {
        static final PluginKey<World> KEY = PluginKey.of("showcase.world", World.class);

        private ShowcaseAssets assets;
        private ShowcaseWorld world;
        private CameraRig rig;
        private Window window;

        @Override
        public PluginKey<World> key() {
            return KEY;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return List.of(Rendering.KEY, Desktop.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.BEFORE_INSTANCE) {
                ShowcaseSettings.register(startup.registry());
            }
            if (stage != Stage.GRAPH) {
                return;
            }
            Rendering rendering = startup.get(Rendering.KEY);
            VulkanDevice device = rendering.device();
            FrameGraph graph = rendering.graph();
            assets = ShowcaseAssets.standalone(device, graph.frameSlots(), startup.settings());
            try {
                world = new ShowcaseWorld(device, graph, assets);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            ViewPort port = graph.addView("main");
            window = startup.get(Desktop.KEY).platform().createWindow(WindowOptions.of("true-sim showcase", 1600,
                900), startup.settings().scoped("main"));
            rig = new CameraRig(world.renderer().scene(), port, Math.toRadians(60), 0.1);
            world.view(port, rig.camera());
            rendering.presenter().outputTransform(new DisplayTransform().displayEffects()).present(port,
                Keys.SCENE_COLOR, window);
        }

        @Override
        public void stop() {
            if (world != null) {
                world.close();
                world = null;
            }
            if (assets != null) {
                assets.close();
                assets = null;
            }
        }
    }
}
