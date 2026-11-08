package com.truesim.samples.showcase;

import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.samples.showcase.assets.ShowcaseAssets;
import com.truesim.samples.showcase.day.DayState;
import com.truesim.samples.showcase.render.FrameCapture;
import com.truesim.samples.showcase.render.SceneStats;
import com.truesim.samples.showcase.tour.CameraRig;
import com.truesim.samples.showcase.tour.TourScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The scripted tour, rendered offline into PNG frames (as the effect repositories' screenshot films; made into a film
 * with ffmpeg by hand): the day's five segments ({@link TourScript}), each a few camera moves, simulated at 60 frames a
 * second and written every {@code showcase.every}-th frame. Before each segment the camera waits at its first shot
 * until what it streams has loaded, then four seconds of the segment's start settle (smoke rising, flakes falling,
 * the probes lit).
 *
 * <p>{@code ./gradlew :samples-showcase:tour -Pout=<folder> [-Psize=1920x1080] [-Psegments=dawn,noon]
 * [-Pevery=2] [-Ppace=1] [-Pstills=12.5,40]}; with {@code -Pmeasure=true} (and no {@code out}) it writes nothing and
 * reports each segment's frame times: the wall clock from one frame's start to the next (frames in flight, nothing
 * presented), the CPU's update and recording, and the GPU's span from its first pass to its last.
 */
public final class ShowcaseTour {

    private static final double HZ = 60;
    private static final double SETTLE = 4;

    private ShowcaseTour() {
    }

    public static void main(String[] args) throws Exception {
        EngineSettings settings = ShowcaseSettings.standalone(args);
        String[] size = settings.get(ShowcaseSettings.SIZE).split("x");
        int width = Integer.parseInt(size[0]);
        int height = Integer.parseInt(size[1]);
        String out = settings.get(ShowcaseSettings.OUT);
        boolean measure = settings.get(ShowcaseSettings.MEASURE);
        int every = settings.get(ShowcaseSettings.EVERY);
        double pace = settings.get(ShowcaseSettings.PACE);
        List<String> wanted = settings.get(ShowcaseSettings.SEGMENTS).isBlank() ? List.of()
            : List.of(settings.get(ShowcaseSettings.SEGMENTS).split(","));
        List<Double> stills = new ArrayList<>();
        for (String s : settings.get(ShowcaseSettings.STILLS).split(",")) {
            if (!s.isBlank()) {
                stills.add(Double.parseDouble(s.trim()));
            }
        }
        boolean validation = "true".equals(System.getenv("TRUESIM_GPU_ASSISTED_VALIDATION"))
            || "true".equals(System.getenv("TRUESIM_VALIDATION"));
        VulkanInstance.Builder builder = VulkanInstance.builder().applicationName("showcase tour").surfaces(false);
        try (VulkanInstance instance = builder.build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(measure)) {
            graph.settings(settings);
            System.out.println("GPU: " + device.gpu().name() + (validation ? " (validation asked by environment)" : ""));
            try (ShowcaseAssets assets = ShowcaseAssets.standalone(device, graph.frameSlots(), settings);
                 ShowcaseWorld world = new ShowcaseWorld(device, graph, assets)) {
                ViewPort port = graph.addView("main").size(width, height);
                CameraRig rig = new CameraRig(world.renderer().scene(), port, Math.toRadians(55), 0.1);
                world.view(port, rig.camera());
                SceneStats stats = new SceneStats(device, width, height);
                port.add(stats);
                TourScript script = new TourScript(world.ground());
                DayState state = new DayState();
                double[] eye = new double[6];
                double tourTime = 0;
                List<String> report = new ArrayList<>();
                try (FrameCapture capture = new FrameCapture(device, port); stats) {
                    for (TourScript.Segment segment : script.segments()) {
                        if (!wanted.isEmpty() && !wanted.contains(segment.name())) {
                            tourTime += segment.duration();
                            continue;
                        }
                        Path folder = out.isEmpty() ? null : Files.createDirectories(Path.of(out, segment.name()));
                        // pre-roll: the camera at the first shot, the segment's start, until loaded, then settling
                        long started = System.nanoTime();
                        int waited = 0;
                        int settled = 0;
                        while (settled < SETTLE * HZ) {
                            segment.state(0, state);
                            TourScript.Shot shot = segment.shot(0);
                            shot.spline().at(0, eye);
                            step(world, rig, state, eye, height);
                            graph.beginFrame();
                            graph.submit(0, 0);
                            if (world.ready() || System.nanoTime() - started > 120_000_000_000L) {
                                settled++;
                            } else {
                                waited++;
                                Thread.sleep(1);
                            }
                        }
                        System.out.printf(Locale.ROOT, "%s: loaded in %d frames (%.1f s)%n", segment.name(), waited,
                            (System.nanoTime() - started) / 1e9);
                        int frames = (int) Math.round(segment.duration() * HZ / pace);
                        double[] wall = new double[frames];
                        double[] cpu = new double[frames];
                        double[] gpu = new double[frames];
                        Arrays.fill(gpu, Double.NaN);
                        long lastTimed = -1;
                        long previous = System.nanoTime();
                        int written = 0;
                        for (int f = 0; f < frames; f++) {
                            long start = System.nanoTime();
                            wall[f] = (start - previous) / 1e6;
                            previous = start;
                            double t = f * pace / HZ;
                            segment.state(t, state);
                            TourScript.Shot shot = segment.shot(t);
                            shot.spline().at(t - shot.start(), eye);
                            step(world, rig, state, eye, height);
                            graph.beginFrame();
                            graph.submit(0, 0);
                            cpu[f] = (System.nanoTime() - start) / 1e6;
                            GpuTimings timings = graph.timings();
                            if (timings != null && timings.frame() != lastTimed) {
                                lastTimed = timings.frame();
                                gpu[f] = timings.spanMillis();
                            }
                            boolean clip = folder != null && f % every == 0;
                            boolean still = folder != null && stills.stream()
                                .anyMatch(s -> Math.abs(s - (t + tourTimeOf(script, segment))) < 0.5 * pace / HZ);
                            if (clip || still) {
                                device.waitIdle();
                                if (clip) {
                                    capture.write(folder.resolve(String.format(Locale.ROOT, "f_%05d.png", written++)));
                                }
                                if (still) {
                                    capture.write(folder.resolve(String.format(Locale.ROOT, "still_%s_%05.1f.png",
                                        segment.name(), t)));
                                }
                            }
                            if (f % 240 == 0) {
                                device.waitIdle();
                                System.out.printf(Locale.ROOT, "  %s %5.1f s %-18s ev100 %5.2f, %s, %d particles%n",
                                    segment.name(), t, shot.name(), world.day().ev100(), stats.summary(),
                                    world.renderer().particles().alive());
                            }
                        }
                        device.waitIdle();
                        if (measure) {
                            report.add(row(segment.name(), width, height, wall, cpu, gpu));
                        }
                        tourTime += segment.duration();
                    }
                }
                if (measure) {
                    System.out.printf(Locale.ROOT, "Frame times at %d x %d (%s), ms: mean, 99th percentile, worst%n",
                        width, height, settings.get(ShowcaseSettings.QUALITY));
                    report.forEach(System.out::println);
                }
            }
        }
    }

    /** The camera at the shot's eye looking at its target, the scene updated, the world's frame. */
    private static void step(ShowcaseWorld world, CameraRig rig, DayState state, double[] eye, int height) {
        rig.look(eye[0], eye[1], eye[2], eye[3], eye[4], eye[5]);
        world.renderer().scene().update();
        rig.update();
        world.frame(1 / HZ, state, rig.camera(), height);
    }

    private static double tourTimeOf(TourScript script, TourScript.Segment segment) {
        double t = 0;
        for (TourScript.Segment s : script.segments()) {
            if (s == segment) {
                return t;
            }
            t += s.duration();
        }
        return t;
    }

    private static String row(String name, int width, int height, double[] wall, double[] cpu, double[] gpu) {
        return String.format(Locale.ROOT, "  %-6s wall %s | cpu %s | gpu %s", name, stats(wall), stats(cpu),
            stats(gpu));
    }

    /** Mean, 99th percentile, worst of the finite values, frames after the first second. */
    private static String stats(double[] values) {
        double[] v = Arrays.stream(values).skip((long) HZ).filter(Double::isFinite).sorted().toArray();
        if (v.length == 0) {
            return "n/a";
        }
        double mean = Arrays.stream(v).average().orElse(Double.NaN);
        return String.format(Locale.ROOT, "%6.2f %6.2f %6.2f", mean, v[Math.min(v.length - 1, (int) (0.99 * v.length))],
            v[v.length - 1]);
    }
}
