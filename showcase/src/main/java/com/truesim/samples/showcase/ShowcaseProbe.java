package com.truesim.samples.showcase;

import com.truesim.effects.atmosphere.ephemeris.Observer;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.sky.SkyComponents;
import com.truesim.effects.atmosphere.sky.SkySettings;
import com.truesim.effects.atmosphere.sky.SkyTime;
import com.truesim.effects.post.AutoExposure;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.assets.Surfaces;
import com.truesim.samples.showcase.render.FrameCapture;
import com.truesim.samples.showcase.render.ShowcaseRenderer;
import com.truesim.samples.showcase.world.GroundTiles;
import com.truesim.samples.showcase.world.ValleyGround;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A first look: ground, a lamp, the sky at a time; one PNG. Development only. */
public final class ShowcaseProbe {

    private ShowcaseProbe() {
    }

    public static void main(String[] args) throws Exception {
        EngineSettings settings = ShowcaseSettings.standalone(args);
        double hour = settings.get(ShowcaseSettings.HOUR);
        String out = settings.get(ShowcaseSettings.OUT);
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("showcase probe").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2)) {
            graph.settings(settings);
            ValleyGround ground = new ValleyGround();
            try (ShowcaseAssets assets = ShowcaseAssets.standalone(device, graph.frameSlots(), settings);
                 ShowcaseRenderer world = new ShowcaseRenderer(device, graph, assets, ground)) {
                Surfaces surfaces = new Surfaces(assets);
                SceneGraph scene = world.scene();
                GroundTiles tiles = new GroundTiles(ground, 2, 2, 3);
                var meadow = surfaces.scan(Surfaces.MEADOW);
                var forest = surfaces.scan(Surfaces.FOREST_FLOOR);
                var rock = surfaces.scan(Surfaces.MOSSY_ROCK);
                var high = Surfaces.plain(0.5f, 0.5f, 0.52f, 0.9f);
                for (int i = -3; i < 3; i++) {
                    for (int j = -3; j < 3; j++) {
                        long node = scene.create();
                        scene.setTranslation(node, i * 64, 0, j * 64);
                        scene.components().set(node, Renderable.TYPE,
                            Renderable.of(tiles.tile(i * 64, j * 64, 64, 1), List.of(meadow, forest, rock, high)));
                    }
                }
                AssetRef<ModelData> lamp = assets.ref(RenderAssets.MODEL, "ph://street_lamp_01/street_lamp_01_2k.gltf");
                assets.cache().acquire(lamp);
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (assets.cache().get(lamp) == null && System.nanoTime() < deadline) {
                    assets.drain();
                    Thread.sleep(5);
                }
                long lampNode = scene.create();
                scene.setTranslation(lampNode, 2, ground.height(2, -6), -6);
                assets.cache().get(lamp).spawn(scene, lampNode);

                scene.components().set(world.skyNode(), SkyComponents.SKY,
                    SkySettings.forQuality(world.quality(), new Observer(47.04, 12.84, 1290)));
                Instant midnight = Instant.parse("2026-10-23T22:00:00Z"); // local midnight, CEST
                scene.components().set(world.skyNode(), SkyComponents.SKY_TIME, SkyTime.of(midnight, 1));
                settings.set(AtmosphereSky.ATMOSPHERE.setting("ev100"), 13f);
                settings.set(AutoExposure.HISTOGRAM.setting("luminanceScale"), (float) (1.2 * Math.pow(2, 13)));
                settings.set(AutoExposure.HISTOGRAM.setting("key"), AutoExposure.KeyValue.KRAWCZYK);

                Camera camera = new Camera();
                ViewPort port = graph.addView("main").size(1280, 720);
                world.view(port, camera);
                com.truesim.samples.showcase.render.SceneStats stats =
                    new com.truesim.samples.showcase.render.SceneStats(device, 1280, 720);
                port.add(stats);
                try (FrameCapture capture = new FrameCapture(device, port); stats) {
                    double h0 = ground.height(0, 20);
                    camera.position(0, h0 + 1.7, 20).set(0.1, 0.02, -1, 0, 1, 0, Math.toRadians(60), 1280, 720,
                        0.1);
                    for (int f = 0; f < 240; f++) {
                        world.update(1 / 60.0, 1 / 60.0, hour * 3600 + f / 60.0, camera);
                        graph.beginFrame();
                        graph.submit(0, 0);
                        if (f == 30) {
                            assets.awaitLoaded(Duration.ofMinutes(2));
                            var tile = tiles.tile(0, 0, 64, 1);
                            System.out.println("tile: " + assets.cache().state(tile) + " "
                                + assets.cache().failure(tile) + ", instances " + world.sync().instanceCount());
                            if (assets.cache().failure(tile) != null) {
                                assets.cache().failure(tile).printStackTrace();
                            }
                        }
                    }
                    device.waitIdle();
                    double[] sunAt = new double[3];
                    world.atmosphere().lightAtCamera(0, sunAt);
                    double[] dir = new double[3];
                    world.atmosphere().sunDirection(dir);
                    System.out.printf("units %.3g, sun at camera %.3g %.3g %.3g, sun dir %.3f %.3f %.3f, ev %s%n",
                        world.atmosphere().sceneUnits(), sunAt[0], sunAt[1], sunAt[2], dir[0], dir[1], dir[2],
                        settings.get(AtmosphereSky.ATMOSPHERE.setting("ev100")));
                    System.out.println(stats.summary());
                    capture.write(Path.of(out.isEmpty() ? "probe.png" : out));
                }
            }
        }
    }
}
