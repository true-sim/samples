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
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Keys;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hitches while content streams: a camera flies over a grid of cells; cells coming into range get meshes of their own
 * (made on worker threads, as a game would), a texture, materials and 40 rocks on a ground tile, and cells leaving
 * range lose them, while frames run as an application's do (frames in flight, paced at 90 Hz as a headset's). Every
 * frame's work is timed (streaming, recording, waiting for the GPU); the report gives the percentiles and the slowest
 * frames with what happened in them. {@code ./gradlew :samples-demo:streamBench}.
 *
 * <p>The world is a {@link StreamFlight}: by default the engine's way, cells as scene nodes whose content is generated
 * assets loaded through the asset cache and uploaded by the scene's resources ({@link Mode#SCENE}). With
 * {@code baseline} among the arguments, the hand-written path it replaced, kept as the comparison
 * ({@link Mode#BASELINE}): run both and compare their reports. Other arguments: {@code direct} (meshes and textures
 * staged on the frame's thread, not made ready on workers), {@code inframe} (copies in the frame, not on the transfer
 * queue), {@code streamed} (the scene way: rocks drawn as soon as their meshes are there, materials and textures as
 * they come, instead of each part of a cell drawn once all it uses is there), {@code large} and {@code heavy} (terrain
 * tiles of 131,000 and 295,000 triangles, textures of 2048 and 4096 texels), {@code rays} (bottom levels for ray
 * tracing as meshes come), {@code grow} (the pool starts small and grows), {@code window} ({@link StreamWindow}).
 * Developer options are settings ({@link DemoSettings}, {@code --set name=value}): {@code demo.frames},
 * {@code demo.range}, {@code demo.speed}, {@code demo.memory} (a memory line every n frames).
 */
public final class StreamBench {

    /** How cells reach the GPU. */
    enum Mode {
        /**
         * The engine's way: scene nodes with renderables of generated assets, loaded through the asset cache, made
         * ready on workers and uploaded by the scene's resources, kept on the GPU by a scene sync.
         */
        SCENE,
        /**
         * The comparison ({@code baseline}): the hand-written path the scene way replaced, cells built on threads of
         * its own and put straight into the geometry pool, textures and a material table, instances set by id. Not
         * how to write an application: kept only to measure the scene way against.
         */
        BASELINE
    }

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    static final int FRAMES = 2700; // 30 seconds at 90 Hz: the benchmark's default (demo.frames)
    static int frameCount = FRAMES;
    static boolean paced = true; // frames start at 90 Hz; unpaced (tests), each frame waits for the builds it began
    static int builderThreads = 3;
    static int memoryEvery; // demo.memory: every n frames, memory by kind (GPU by category, LWJGL's native, ...)
    private static final long BUDGET = 1_000_000_000L / 90; // a headset's frame
    static final double CELL = 24;
    static final int RANGE = 3; // cells each way: the benchmark's default (demo.range)
    static final int WINDOW_RANGE = 10; // the window's default: 21 x 21 cells, the ground 250 m ahead
    static int range = RANGE;
    static final double SPEED = 10; // metres a second: the benchmark's default (demo.speed)
    static final double WINDOW_SPEED = 60; // the window's default: 2.5 cells a second
    static double speed = SPEED;
    /** The settings of a headless run: defaults, then {@code --set name=value} from the arguments. */
    static EngineSettings settings = DemoSettings.standalone();

    /**
     * Cells' content made and not freed yet: the baseline's built cells, the scene way's generated meshes and textures
     * in the asset cache.
     */
    static final AtomicInteger UNFREED = new AtomicInteger();

    static Mode mode = Mode.SCENE;
    static boolean direct; // meshes and textures staged on the frame's thread, as before preparing existed
    static boolean large; // big meshes and textures: terrain tiles of 131,000 triangles, 2048 x 2048 texels
    static boolean heavy; // larger still: terrain tiles of 295,000 triangles, 4096 x 4096 texels
    static boolean inFrame; // copies in the frame, not on the transfer queue
    static boolean streamed; // the scene way: rocks drawn before their materials and textures arrive
    static boolean rays; // bottom levels for ray tracing too, as meshes come
    static boolean grow; // the pool starts small and grows as meshes come
    static VulkanDevice device; // for the memory line's heaps

    /** What happened in a frame, for the report; its GPU time once known (NaN until then). */
    private static final class Frame {
        final int index;
        final double millis;
        final int meshesAdded;
        final int meshesRemoved;
        final int textures;
        final int builds;
        double gpu = Double.NaN;

        Frame(int index, double millis, int meshesAdded, int meshesRemoved, int textures, int builds) {
            this.index = index;
            this.millis = millis;
            this.meshesAdded = meshesAdded;
            this.meshesRemoved = meshesRemoved;
            this.textures = textures;
            this.builds = builds;
        }

        double millis() {
            return millis;
        }

        int meshesAdded() {
            return meshesAdded;
        }

        int meshesRemoved() {
            return meshesRemoved;
        }

        int builds() {
            return builds;
        }
    }

    private StreamBench() {
    }

    /**
     * Runs the benchmark headless and prints its report; with {@code window} among {@code args}, shows the same
     * fly-over in a window instead ({@link StreamWindow}).
     */
    public static void main(String[] args) throws Exception {
        com.truesim.engine.platform.NativeMemory.install(); // before LWJGL loads, so its allocations are counted
        options(args);
        if (List.of(args).contains("window")) {
            StreamWindow.run(args);
            return;
        }
        headless(memoryEvery <= 0 ? null : (f, flight) -> {
            if (f % memoryEvery == 0) {
                memory(f, flight);
            }
        });
    }

    /** Called every frame of a headless run, before the frame is submitted: what is streamed in now. */
    interface Sampler {
        void sample(int frame, StreamFlight flight);
    }

    /** The headless benchmark with the options set: its frames, then its report; {@code sampler} may be null. */
    static void headless(Sampler sampler) throws Exception {
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("stream bench").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(true); // GPU time per frame; no rebuilds here
             StreamFlight flight = new StreamFlight(device, graph.frameSlots(), settings, null, null,
                 builderThreads)) {
            StreamBench.device = device;
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                VK_IMAGE_ASPECT_COLOR_BIT);
            ViewPort port = graph.addView("main").size(WIDTH, HEIGHT);
            flight.draw(port, color, depth);
            port.output(Keys.SCENE_COLOR, new ExternalImage("target", WIDTH, HEIGHT, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
            System.out.println("Stream benchmark on " + device.gpu().name() + ": " + describe());
            List<Frame> frames = new ArrayList<>();
            long next = System.nanoTime();
            for (int f = 0; f < frameCount; f++) {
                // Paced as a headset paces frames: each starts at its time; its work is timed, the rest idles.
                while (paced && System.nanoTime() < next) {
                    java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1, next - System.nanoTime() - 200_000));
                }
                long start = System.nanoTime();
                next = Math.max(next + BUDGET, start);
                int builds = graph.builds();
                flight.step(f * speed / 90.0, !paced, WIDTH, HEIGHT);
                graph.beginFrame();
                graph.submit(0, 0);
                if (sampler != null) {
                    sampler.sample(f, flight);
                }
                long now = System.nanoTime();
                frames.add(new Frame(f, (now - start) / 1e6, flight.meshesAdded, flight.meshesRemoved,
                    flight.texturesAdded, graph.builds() - builds));
                com.truesim.engine.graph.GpuTimings gpu = graph.timings();
                if (gpu != null && gpu.frame() >= 1 && gpu.frame() <= frames.size()) {
                    double frameStart = gpu.frameStartMillis(); // the frame start's uploads (textures, mips) too
                    frames.get((int) gpu.frame() - 1).gpu = gpu.totalMillis()
                        + (Double.isNaN(frameStart) ? 0 : frameStart);
                }
            }
            device.waitIdle();
            System.out.println("Cells kept " + flight.cellCount() + " (in range " + flight.inRange + ", placed "
                + flight.placed + "), meshes " + flight.pool().meshCount() + ", camera x "
                + (frameCount * speed / 90.0));
            report(frames, flight);
            target.close();
        }
    }

    /** The options run, for the report's first line. */
    private static String describe() {
        StringBuilder text = new StringBuilder(mode == Mode.SCENE ? "the scene way" : "the baseline (by hand)");
        text.append(String.format(Locale.ROOT, ", %d frames, %d cells each way, %.0f m/s", frameCount, range, speed));
        for (String option : List.of(direct ? "direct" : "", inFrame ? "inframe" : "",
            streamed && mode == Mode.SCENE ? "streamed" : "", heavy ? "heavy" : large ? "large" : "",
            rays ? "rays" : "", grow ? "grow" : "")) {
            if (!option.isEmpty()) {
                text.append(", ").append(option);
            }
        }
        return text.toString();
    }

    /** One line of memory by kind, in MB, with what is streamed in now. */
    static void memory(int frame, StreamFlight flight) {
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "memory frame %6d cells %4d meshes %5d "
            + "textures %4d materials %4d assets %.0f |", frame, flight.cellCount(), flight.pool().meshCount(),
            flight.textures().count(), flight.materials().count(), flight.cachedBytes() / 1e6));
        for (com.truesim.engine.vulkan.GpuMemory.Category c : com.truesim.engine.vulkan.GpuMemory.Category.values()) {
            line.append(String.format(Locale.ROOT, " %s %.0f", c.name().toLowerCase(Locale.ROOT),
                com.truesim.engine.vulkan.GpuMemory.bytes(c) / 1e6));
        }
        long direct = 0;
        for (java.lang.management.BufferPoolMXBean b : java.lang.management.ManagementFactory
            .getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            direct += b.getMemoryUsed();
        }
        Runtime rt = Runtime.getRuntime();
        long committed = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
            .getOperatingSystemMXBean()).getCommittedVirtualMemorySize();
        line.append(String.format(Locale.ROOT, " | lwjgl %.0f (%d) direct %.0f heap %.0f process %.0f",
            com.truesim.engine.platform.NativeMemory.bytes() / 1e6, com.truesim.engine.platform.NativeMemory.count(),
            direct / 1e6, (rt.totalMemory() - rt.freeMemory()) / 1e6, committed / 1e6));
        if (device != null) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.util.vma.VmaBudget.Buffer budgets = org.lwjgl.util.vma.VmaBudget.calloc(
                    org.lwjgl.vulkan.VK10.VK_MAX_MEMORY_HEAPS, stack);
                org.lwjgl.util.vma.Vma.vmaGetHeapBudgets(device.allocator(), budgets);
                for (int h = 0; h < 3; h++) {
                    org.lwjgl.util.vma.VmaBudget b = budgets.get(h);
                    line.append(String.format(Locale.ROOT, " | heap%d usage %.0f blocks %.0f (%d) allocs %.0f (%d)", h,
                        b.usage() / 1e6, b.statistics().blockBytes() / 1e6, b.statistics().blockCount(),
                        b.statistics().allocationBytes() / 1e6, b.statistics().allocationCount()));
                }
            }
        }
        System.out.println(line);
    }

    /**
     * Reads the options both modes share from {@code args}: the words (baseline, rays, large, heavy, direct, inframe,
     * streamed, grow, window) and the settings ({@code --set demo.range=5}; 0 or unset: the mode's default).
     */
    static void options(String... args) {
        List<String> words = List.of(args);
        settings = DemoSettings.standalone(args);
        boolean window = words.contains("window");
        mode = words.contains("baseline") ? Mode.BASELINE : Mode.SCENE;
        rays = words.contains("rays");
        heavy = words.contains("heavy");
        large = heavy || words.contains("large");
        direct = words.contains("direct");
        inFrame = words.contains("inframe");
        streamed = words.contains("streamed");
        grow = words.contains("grow");
        frameCount = DemoSettings.or(settings.get(DemoSettings.FRAMES), FRAMES);
        memoryEvery = settings.get(DemoSettings.MEMORY);
        range = DemoSettings.or(settings.get(DemoSettings.RANGE), window ? WINDOW_RANGE : RANGE);
        float metres = settings.get(DemoSettings.SPEED);
        speed = metres > 0 ? metres : window ? WINDOW_SPEED : SPEED;
    }

    /** The most cells the range keeps: a column more while one leaves. */
    private static int cells() {
        return (2 * range + 2) * (2 * range + 1);
    }

    /** Instances to start with: every cell in range, its ground tile and rocks; the default's 8192 at least. */
    static int instanceCapacity() {
        return Math.max(8192, cells() * (StreamFlight.INSTANCES_PER_CELL + 1) + 1);
    }

    /** Materials to start with: two a cell in range; the default's 1024 at least. */
    static int materialCapacity() {
        return Math.max(1024, cells() * 2 + 16);
    }

    /**
     * The geometry pool's capacity for the options: the default's, or room for every cell in range where that is more
     * (about 6,000 vertices a small cell, 70,000 a large one, 150,000 a heavy one, with a third to spare); it grows
     * past that if it must.
     */
    static GeometryPool.Capacity capacity() {
        if (grow) {
            return GeometryPool.START;
        }
        long perCell = heavy ? 200_000 : large ? 95_000 : 8_000;
        int vertices = (int) Math.min(1 << 30, Math.max(large ? 1 << 24 : 1 << 22, perCell * cells()));
        return GeometryPool.Capacity.of(vertices, vertices / (large ? 16 : 32), Math.max(512, cells() * 4 + 64));
    }

    private static void report(List<Frame> frames, StreamFlight flight) {
        List<Frame> timed = frames.subList(Math.min(60, frames.size() - 1), frames.size()); // after start-up
        double[] millis = timed.stream().mapToDouble(Frame::millis).sorted().toArray();
        System.out.printf(Locale.ROOT, "Frame work, %d frames at 90 Hz: p50 %.2f ms, p95 %.2f, p99 %.2f, max %.2f, "
                + "standard deviation %.3f; over the 11.1 ms budget: %d, over 4 ms: %d%n", millis.length,
            at(millis, 0.5), at(millis, 0.95), at(millis, 0.99), millis[millis.length - 1], deviation(millis),
            Arrays.stream(millis).filter(m -> m > 11.1).count(), Arrays.stream(millis).filter(m -> m > 4).count());
        int added = timed.stream().mapToInt(Frame::meshesAdded).sum();
        int removed = timed.stream().mapToInt(Frame::meshesRemoved).sum();
        int builds = timed.stream().mapToInt(Frame::builds).sum();
        System.out.printf(Locale.ROOT, "Streamed: %d meshes added, %d removed, %d graph rebuilds; at the end %d meshes, "
            + "%d textures, %d materials%n", added, removed, builds, flight.pool().meshCount(),
            flight.textures().count(), flight.materials().count());
        double plain = timed.stream().filter(f -> f.meshesAdded() == 0 && f.meshesRemoved() == 0 && f.builds() == 0)
            .mapToDouble(Frame::millis).average().orElse(0);
        double adding = timed.stream().filter(f -> f.meshesAdded() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        double removing = timed.stream().filter(f -> f.meshesRemoved() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        double rebuilding = timed.stream().filter(f -> f.builds() > 0).mapToDouble(Frame::millis).average()
            .orElse(0);
        System.out.printf(Locale.ROOT, "Mean ms: frames streaming nothing %.2f, adding %.2f, removing %.2f, rebuilding "
            + "%.2f%n", plain, adding, removing, rebuilding);
        double[] gpu = timed.stream().mapToDouble(f -> f.gpu).filter(g -> !Double.isNaN(g)).sorted().toArray();
        if (gpu.length > 0) {
            System.out.printf(Locale.ROOT, "GPU per frame (graphics queue, frame start included), %d timed: p50 %.2f "
                    + "ms, p95 %.2f, p99 %.2f, max %.2f, standard deviation %.3f; over 11.1 ms: %d%n", gpu.length,
                at(gpu, 0.5), at(gpu, 0.95), at(gpu, 0.99), gpu[gpu.length - 1], deviation(gpu),
                Arrays.stream(gpu).filter(g -> g > 11.1).count());
        }
        double gpuAdding = timed.stream().filter(f -> f.meshesAdded() > 0 && !Double.isNaN(f.gpu))
            .mapToDouble(f -> f.gpu).average().orElse(0);
        double gpuQuiet = timed.stream().filter(f -> f.meshesAdded() == 0 && f.meshesRemoved() == 0
            && !Double.isNaN(f.gpu)).mapToDouble(f -> f.gpu).average().orElse(0);
        double gpuAddingMax = timed.stream().filter(f -> f.meshesAdded() > 0 && !Double.isNaN(f.gpu))
            .mapToDouble(f -> f.gpu).max().orElse(0);
        System.out.printf(Locale.ROOT, "GPU mean ms: frames adding %.3f (max %.3f), streaming nothing %.3f; stream "
            + "queue: %s%n", gpuAdding, gpuAddingMax, gpuQuiet,
            inFrame || direct ? "none, copies in the frame" : "transfer");
        System.out.println("Slowest GPU frames:");
        timed.stream().filter(f -> !Double.isNaN(f.gpu)).sorted((a, b) -> Double.compare(b.gpu, a.gpu)).limit(8)
            .forEach(f -> System.out.printf(Locale.ROOT, "  frame %4d  GPU %7.2f ms (CPU %5.2f)  meshes +%d -%d, "
                + "textures +%d%n", f.index, f.gpu, f.millis, f.meshesAdded, f.meshesRemoved, f.textures));
        if (flight.mode() == Mode.SCENE) {
            System.out.printf(Locale.ROOT, "Frame thread taking in what arrived (the asset cache's drain: uploads): "
                + "%.1f ms in all, %.2f at worst; in the scene's update (nodes, instances, cells removed): %.1f in "
                + "all, %.2f at worst%n", flight.takeMillis[0], flight.takeMillis[1], flight.syncMillis[0],
                flight.syncMillis[1]);
        } else {
            System.out.printf(Locale.ROOT, "Frame thread in pool.add: %.1f ms in all, %.2f at worst; in adding "
                + "textures: %.1f in all, %.2f at worst%n", flight.meshMillis[0], flight.meshMillis[1],
                flight.textureMillis[0], flight.textureMillis[1]);
        }
        System.out.println("Slowest frames:");
        timed.stream().sorted((a, b) -> Double.compare(b.millis(), a.millis())).limit(12).forEach(f ->
            System.out.printf(Locale.ROOT, "  frame %4d  %7.2f ms  meshes +%d -%d, textures +%d, rebuilds %d%n",
                f.index, f.millis, f.meshesAdded, f.meshesRemoved, f.textures, f.builds));
    }

    private static double deviation(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0));
    }

    private static double at(double[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) (fraction * sorted.length))];
    }
}
