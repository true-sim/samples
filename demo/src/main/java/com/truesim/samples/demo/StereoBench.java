package com.truesim.samples.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.CameraSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a stereo frame's GPU time goes: the demo's scene from two eyes 64 mm apart, each a view of its own at a Quest
 * 3's eye size, offscreen, passes timed and averaged over many frames, then grouped by what they do (which work each
 * eye repeats). {@code ./gradlew :samples-demo:stereoBench}; {@code --args="visibility"} shades through the visibility
 * buffer, {@code shared} gives both eyes one set of shadow cascades, {@code rebuild} times what rebuilds cost the
 * render thread, {@code capture} writes frame 100 for RenderDoc (launched through {@code renderdoccmd capture}), {@code noshadows}
 * leaves the sun's cascades out, {@code rays} traces the sun's shadows instead (inline ray queries from visibility buffer
 * shading, on devices with ray tracing): every mesh has a bottom level, skinned copies' refitted each frame they move
 * (the pass "bottom level refits"). With {@code -Peffect=x,y,z} ({@code --set demo.effect=x,y,z}) the demo's effects play there, drawn in both eyes as a
 * pair: one particle sort for both, billboards facing their center.
 */
public final class StereoBench {

    private static final int WIDTH = 2064;
    private static final int HEIGHT = 2208;

    private StereoBench() {
    }

    public static void main(String[] args) {
        EngineSettings settings = DemoSettings.standalone(args); // --set demo.frames=780 and the others
        int frames = DemoSettings.or(settings.get(DemoSettings.FRAMES), 300);
        boolean visibility = List.of(args).contains("visibility");
        boolean shared = List.of(args).contains("shared"); // one set of cascades for both eyes
        boolean rebuilds = List.of(args).contains("rebuild"); // what a rebuild costs the render thread
        boolean capture = List.of(args).contains("capture"); // one frame for RenderDoc, launched by renderdoccmd
        boolean shadows = !List.of(args).contains("noshadows"); // without the sun's cascades: what they cost shading
        boolean rays = List.of(args).contains("rays"); // the sun's shadows ray-traced
        visibility |= rays; // shading that traces inline
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("stereo bench").surfaces(false)
                 .validation(List.of(args).contains("capture")) // for its debug labels: passes named in the capture
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(true);
             DemoScene world = DemoScene.standalone(device, graph.frameSlots(), color, depth, settings, rays,
                 null)) {
            if (rays && world.rayTracing() == null) {
                throw new IllegalStateException("The device has no ray tracing");
            }
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            List<AutoCloseable> owned = new ArrayList<>();
            List<GpuImage> targets = new ArrayList<>();
            // The cameras are nodes of the world: the head, the eyes its children 64 mm apart; the renderer's cameras
            // follow them (CameraSync). The head's camera is the eyes' center: particles sort once for both, facing it
            Camera[] eyes = {new Camera(), new Camera()};
            Camera head = new Camera();
            SceneGraph scene = world.scene();
            long headNode = world.cameraNode(Math.PI / 2, 0.05);
            long[] eyeNodes = new long[2];
            CameraSync[] eyeSyncs = new CameraSync[2];
            CameraSync headSync = new CameraSync(scene, headNode, head);
            List<ViewPort> ports = new ArrayList<>();
            Capture png = null; // the left eye's PNGs, with -Ppng
            if (rebuilds) {
                graph.timings(false); // timings make a rebuild wait for the GPU
            }
            double[] cpu = new double[6]; // ordinary, rebuilt as is, resized: total ms, count
            // The sun's shadows by one owner: traced (rays), its cascades (one set per eye, or with shared one set
            // fitted to the left eye, its margin the distance between the eyes), or none
            Shadows owner = new Shadows(world.geometry(), world.lights()).rayTracing(rays ? world.rayTracing() : null);
            owned.add(owner);
            world.sunShadows(rays ? ShadowSpec.rayTraced() : shadows ? DemoScene.SUN_SHADOWS : ShadowSpec.NONE);
            Technique leftShadows = null;
            // --set demo.effect=x,y,z (-Peffect): the demo's effects there (g+h: h above the ground); with a fox the
            // fox's feet kick up dust
            String effect = settings.get(DemoSettings.EFFECT);
            DemoEffects effects = null;
            if (!effect.isEmpty()) {
                String[] v = effect.split(",");
                double fx = Double.parseDouble(v[0]);
                double fz = Double.parseDouble(v[2]);
                DemoCharacter fox = world.character();
                effects = new DemoEffects(device, graph.frameSlots(), fox == null ? world.scene() : fox.scene(), fx,
                    height(v[1], fx, fz), fz);
                if (fox != null) {
                    effects.dust(fox); // the fox kicks up dust; its survey flickers the torch
                    effects.burn(fox, world.renderer()); // and burns all over as it moves
                }
                owned.add(effects);
                effects.assets(world.assets().resources()).debris(world.renderer(), 5, -3);
                effects.sparkLights(world.lights(), 6.5, -1); // sparks lighting the ground and the rocks
                graph.add(effects.simulation());
            }
            for (int e = 0; e < 2; e++) {
                GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                    VK_IMAGE_ASPECT_COLOR_BIT);
                owned.add(target);
                targets.add(target);
                ViewPort port = graph.addView(e == 0 ? "left" : "right").size(WIDTH, HEIGHT);
                ports.add(port);
                eyeNodes[e] = world.cameraNode(Math.PI / 2, 0.05);
                scene.setParent(eyeNodes[e], headNode);
                scene.setTranslation(eyeNodes[e], e == 0 ? -0.032 : 0.032, 0, 0);
                eyeSyncs[e] = new CameraSync(scene, eyeNodes[e], eyes[e], port);
                SceneView view = world.geometry().createView(port, eyes[e], ViewOptions.of(1 << 14, 1 << 22)
                    .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
                port.add(visibility ? new VisibilityBufferShading(world.geometry(), view, color, depth)
                        : new ClusteredForwardShading(world.geometry(), view, color, depth))
                    .add(new ClusteredLights(world.lights(), view));
                if (effects != null) {
                    port.add(new com.truesim.effects.particles.ParticleDraw(effects.particles(), view, head));
                }
                if (rays || shadows) {
                    Technique part = shared && leftShadows != null ? owner.view(view, leftShadows, 0.064)
                        : owner.view(view);
                    leftShadows = leftShadows == null ? part : leftShadows;
                    port.add(part);
                }
                if (e == 0 && !settings.get(DemoSettings.PNG).isEmpty()) {
                    png = new Capture(device, port); // the left eye as a window shows it: tone mapped, sRGB
                    owned.add(png);
                } else {
                    port.output(Keys.SCENE_COLOR, new ExternalImage("eye " + e, WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
                            usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                            .set(target.handle(), target.view()));
                }
            }
            String detail = settings.get(DemoSettings.CASTER_DETAIL);
            if (!detail.isEmpty()) {
                graph.settings().set(com.truesim.engine.render.shadows.ShadowSettings.CASTER_DETAIL,
                    Float.parseFloat(detail));
            }
            Map<String, double[]> sums = new LinkedHashMap<>(); // per pass: total, frames timed
            RenderDocCapture renderDoc = capture ? RenderDocCapture.attach(instance.handle().address()) : null;
            if (capture && renderDoc == null) {
                System.out.println("RenderDoc is not loaded: launch with renderdoccmd capture");
            }
            // --set demo.eye=x,y,z,tx,ty,tz: a camera of its own, looking from (x, y, z) at (tx, ty, tz)
            String eye = settings.get(DemoSettings.EYE);
            if (eye.isEmpty()) {
                DemoScene.look(scene, headNode, 30, 6, 30, -0.7, -0.15, -0.7);
            } else {
                String[] v = eye.split(",");
                double ex = Double.parseDouble(v[0]);
                double ez = Double.parseDouble(v[2]);
                double ey = height(v[1], ex, ez);
                double tx = Double.parseDouble(v[3]);
                double tz = Double.parseDouble(v[5]);
                DemoScene.look(scene, headNode, ex, ey, ez, tx - ex, height(v[4], tx, tz) - ey, tz - ez);
                for (long node : List.of(headNode, eyeNodes[0], eyeNodes[1])) {
                    scene.components().set(node, CameraLens.TYPE, CameraLens.perspective(Math.PI / 3, 0.05));
                }
            }
            String pngFrames = settings.get(DemoSettings.PNG_FRAMES);
            String pngFile = settings.get(DemoSettings.PNG);
            for (int frame = 0; frame < frames; frame++) {
                world.update(frame / 72.0); // the scene updated and drawn: the camera nodes' places final
                headSync.update(WIDTH, HEIGHT);
                eyeSyncs[0].update();
                eyeSyncs[1].update();
                int kind = 0;
                if (rebuilds && frame > 30 && frame % 20 == 0) {
                    kind = frame % 40 == 0 ? 1 : 2;
                    for (ViewPort port : ports) {
                        if (kind == 1) {
                            graph.invalidate();
                        } else {
                            port.size(port.outputWidth() == WIDTH ? WIDTH - 16 : WIDTH, HEIGHT); // new images
                        }
                    }
                }
                long started = System.nanoTime();
                boolean captured = renderDoc != null && frame == 100;
                if (captured) {
                    renderDoc.start();
                }
                graph.beginFrame();
                if (effects != null) {
                    effects.update(1 / 72.0);
                }
                graph.submit(0, 0);
                world.recorded();
                // --set demo.pngFrames=60,180: the left eye of those frames too, next to the png named
                if (!pngFrames.isEmpty() && !pngFile.isEmpty()
                    && List.of(pngFrames.split(",")).contains(Integer.toString(frame))) {
                    device.waitIdle();
                    java.nio.file.Path file = java.nio.file.Path.of(pngFile.replace(".png", "_" + frame + ".png"));
                    png.write(file);
                    System.out.println("Left eye written to " + file);
                }
                if (captured) {
                    device.waitIdle();
                    System.out.println("RenderDoc capture written: " + renderDoc.end());
                }
                if (rebuilds && frame > 30) {
                    cpu[2 * kind] += (System.nanoTime() - started) / 1e6;
                    cpu[2 * kind + 1]++;
                }
                GpuTimings timings = graph.timings();
                if (frame > 30 && timings != null) {
                    Map<String, Integer> seen = new java.util.HashMap<>(); // both eyes have passes of one name
                    for (int i = 0; i < timings.count(); i++) {
                        double ms = timings.millis(i);
                        if (!Double.isNaN(ms)) {
                            int n = seen.merge(timings.pass(i), 1, Integer::sum);
                            String name = n == 1 ? timings.pass(i) : timings.pass(i) + " (#" + n + ")";
                            double[] sum = sums.computeIfAbsent(name, k -> new double[2]);
                            sum[0] += ms;
                            sum[1]++;
                        }
                    }
                }
            }
            device.waitIdle();
            if (world.crowdReport() != null) {
                System.out.println(world.crowdReport());
            }
            if (png != null) {
                png.write(java.nio.file.Path.of(pngFile));
                System.out.println("Left eye written to " + pngFile);
            }
            if (rebuilds) {
                System.out.printf(Locale.ROOT, "CPU ms per frame, beginFrame and submit: ordinary %.3f, rebuilt as it "
                    + "is %.3f, resized (new images) %.3f%n", cpu[0] / cpu[1], cpu[2] / cpu[3], cpu[4] / cpu[5]);
            } else {
                report(sums, visibility);
            }
            for (AutoCloseable resource : owned) {
                resource.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A height: a number, or g+h for h above the terrain at (x, z). */
    private static double height(String value, double x, double z) {
        return value.startsWith("g") ? DemoMeshes.height(x, z) + Double.parseDouble(value.substring(1))
            : Double.parseDouble(value);
    }

    private static void report(Map<String, double[]> sums, boolean visibility) {
        Map<String, Double> groups = new LinkedHashMap<>();
        double total = 0;
        System.out.printf(Locale.ROOT, "Stereo, %d x %d per eye, %s shading, mean GPU ms per pass:%n", WIDTH, HEIGHT,
            visibility ? "visibility buffer" : "forward");
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double ms = entry.getValue()[0] / entry.getValue()[1];
            total += ms;
            System.out.printf(Locale.ROOT, "  %-50s %7.3f%n", entry.getKey(), ms);
            groups.merge(group(entry.getKey()), ms, Double::sum);
        }
        System.out.println("By kind:");
        for (Map.Entry<String, Double> entry : groups.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-30s %7.3f ms  %4.1f%%%n", entry.getKey(), entry.getValue(),
                100 * entry.getValue() / total);
        }
        System.out.printf(Locale.ROOT, "  %-30s %7.3f ms%n", "total", total);
    }

    private static String group(String pass) {
        if (pass.startsWith("meshlet depth") || pass.contains("cascade")) {
            return "shadow cascades (per eye)";
        }
        if (pass.startsWith("meshlet culling") || pass.contains("late culling") || pass.contains("selection")) {
            return "culling";
        }
        if (pass.startsWith("depth pyramid")) {
            return "depth pyramid (per eye)";
        }
        if (pass.startsWith("meshlet") || pass.startsWith("classic")) {
            return "camera draws (per eye)";
        }
        if (pass.startsWith("bottom level") || pass.startsWith("top level")) {
            return "ray tracing structures";
        }
        if (pass.startsWith("light clusters")) {
            return "light clusters (per eye)";
        }
        if (pass.contains("visibility") || pass.contains("material")) {
            return "visibility shading (per eye)";
        }
        if (pass.startsWith("output")) {
            return "output copies";
        }
        if (pass.startsWith("particle sort")) {
            return "particle sort (per pair)";
        }
        if (pass.startsWith("particle cull") || pass.startsWith("particle draw")) {
            return "particle draws (per eye)";
        }
        if (pass.startsWith("particle")) {
            return "particle simulation";
        }
        return "other (" + pass + ")";
    }
}
