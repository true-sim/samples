package com.truesim.samples.showcase.tour;

import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.app.profile.ZoneSink;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.GpuClock;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.profiler.ChromeTrace;
import com.truesim.ui.profiler.LaneKind;
import com.truesim.ui.profiler.ProfileFile;
import com.truesim.ui.profiler.Recording;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The measured tour recorded with the engine's profiler, as the UI's profiler records it ({@code UiProfiler}, which
 * needs an engine and a UI the headless tour has not): the frame clock ({@link Profiler}'s frames, its waits marked by
 * the frame graph), every thread's CPU zones, the GPU's passes on the CPU's clock ({@link GpuClock}), hitches as the
 * {@code Profiling} plugin finds them ({@code profiler.hitch.*}), into ui-profiler's {@link Recording}. Each segment's
 * recording is exported as the own format ({@code .tsprof}) and Chrome trace JSON ({@code .json}: chrome://tracing,
 * Perfetto) into {@code profiler.exportFolder}, and summed up: per GPU pass and per CPU zone, mean, p99 and share.
 */
public final class TourProfile implements ZoneSink {

    /** Passes or zones under this share of the frame are summed as "other". */
    private static final double TINY = 0.015;

    private final EngineSettings settings;
    private final FrameGraph graph;
    private final GpuClock clock;
    private final Path folder;
    private Recording recording;
    private String segment;
    // the recording's lanes and names
    private int graphicsLane;
    private int computeLane;
    private int gpuFrameName;
    private int[] threadLane = new int[0];
    private int[] zoneName = new int[0];
    private long[][] open = new long[0][];
    private long[][] openStart = new long[0][];
    private int[][] openZone = new int[0][];
    private int[] openDepth = new int[0];
    // the frames: submits for the GPU clock's bounds, the hitch detector's mean
    private final long[] submitFrame = new long[16];
    private final long[] submitAt = new long[16];
    private long seenFrame = -1;
    private long seenGpu = -1;
    private double meanInterval = Double.NaN;
    // the segment's numbers: per frame, each pass's and each zone's milliseconds
    private final Map<String, List<Double>> passes = new LinkedHashMap<>();
    private final Map<String, double[]> zoneThisFrame = new HashMap<>();
    private final Map<String, List<Double>> zones = new LinkedHashMap<>();
    private final List<Double> gpuFrames = new ArrayList<>();
    private final List<Double> cpuWork = new ArrayList<>();
    private final List<Double> cpuWait = new ArrayList<>();
    private int hitches;
    private final List<String> hitchList = new ArrayList<>();
    private final List<String> csv = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();

    /** Profiling {@code graph} on {@code device}: the frame clock, zones and GPU timings switched on. */
    public TourProfile(VulkanDevice device, FrameGraph graph, EngineSettings settings) {
        this.settings = settings;
        this.graph = graph;
        this.clock = new GpuClock(device);
        String f = settings.get(ProfilerSettings.EXPORT_FOLDER);
        this.folder = Path.of(f.isBlank() ? "build/showcase-profiles" : f);
        clock.calibrate();
        Profiler.frames(true);
        Profiler.zones(true);
        Profiler.ringEvents(1 << 16);
        graph.timings(true);
        Arrays.fill(submitFrame, -1);
        csv.add("segment,kind,name,mean_ms,p99_ms,share");
    }

    /** A new recording for {@code name}'s frames (after its pre-roll: the first-sight stalls are in it). */
    public void segment(String name) {
        segment = name;
        recording = new Recording(1 << 20, 1 << 16, 1 << 14);
        graphicsLane = recording.lane("GPU graphics", LaneKind.GPU);
        computeLane = recording.lane("GPU compute", LaneKind.GPU);
        gpuFrameName = recording.name("GPU frame");
        threadLane = new int[0];
        zoneName = new int[0];
        open = new long[0][];
        openStart = new long[0][];
        openZone = new int[0][];
        openDepth = new int[0];
        passes.clear();
        zones.clear();
        gpuFrames.clear();
        cpuWork.clear();
        cpuWait.clear();
        hitches = 0;
        hitchList.clear();
        meanInterval = Double.NaN;
        Profiler.discard();
    }

    /** A frame starts. */
    public void frameStart() {
        Profiler.frameStart(System.nanoTime());
    }

    /** The frame ended (submitted): what the engine measured since the last frame into the recording. */
    public void frameEnd() {
        Profiler.frameEnd();
        long frame = Profiler.lastFrame();
        if (frame >= 0 && frame != seenFrame) {
            seenFrame = frame;
            long start = Profiler.lastFrameStart();
            long submit = Profiler.lastFrameSubmit();
            long wait = Profiler.lastFrameWait();
            int s = (int) Math.floorMod(frame, (long) submitFrame.length);
            submitFrame[s] = frame;
            submitAt[s] = submit;
            boolean hitch = hitch(Profiler.lastFrameInterval());
            recording.frame(frame, start, submit, wait, hitch);
            cpuWork.add((submit - start - wait) / 1e6);
            cpuWait.add(wait / 1e6);
            if (hitch) {
                hitches++;
                if (hitchList.size() < 8) {
                    hitchList.add(String.format(Locale.ROOT, "%.0f ms", Profiler.lastFrameInterval() / 1e6));
                }
            }
        }
        gpu();
        zoneThisFrame.clear();
        Profiler.drain(this);
        for (Map.Entry<String, double[]> e : zoneThisFrame.entrySet()) {
            zones.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()[0]);
        }
        recording.commit();
    }

    /** Whether a frame {@code interval} nanoseconds after the one before is a hitch (the Profiling plugin's test). */
    private boolean hitch(long interval) {
        if (interval <= 0) {
            return false;
        }
        double ms = interval / 1e6;
        double factor = settings.get(ProfilerSettings.HITCH_FACTOR);
        double floor = settings.get(ProfilerSettings.HITCH_MILLIS);
        boolean hitch = ms >= settings.get(ProfilerSettings.HITCH_MIN)
            && (meanInterval == meanInterval && ms > factor * meanInterval || floor > 0 && ms >= floor);
        if (!hitch) {
            meanInterval = meanInterval != meanInterval ? ms : meanInterval * 0.95 + ms * 0.05;
        }
        return hitch;
    }

    /** The GPU's last finished frame: its passes on the CPU's clock into the GPU lanes and the pass statistics. */
    private void gpu() {
        GpuTimings t = graph.timings();
        if (t == null || t.frame() < 0 || t.frame() == seenGpu) {
            return;
        }
        int n = t.count();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (t.millis(i) == t.millis(i)) {
                first = Math.min(first, t.startTicks(i));
                last = Math.max(last, t.endTicks(i));
            }
        }
        if (first == Long.MAX_VALUE) {
            return;
        }
        int s = (int) Math.floorMod(t.frame(), (long) submitFrame.length);
        if (submitFrame[s] == t.frame()) {
            clock.observe(first, submitAt[s]);
        }
        if (!clock.ready()) {
            return;
        }
        seenGpu = t.frame();
        long start = clock.toCpu(first);
        long end = clock.toCpu(last);
        recording.zone(graphicsLane, start, end, gpuFrameName, 0);
        for (int i = 0; i < n; i++) {
            double ms = t.millis(i);
            if (ms != ms) {
                continue;
            }
            boolean compute = t.family(i) != t.graphicsFamily();
            recording.zone(compute ? computeLane : graphicsLane, clock.toCpu(t.startTicks(i)),
                clock.toCpu(t.endTicks(i)), recording.name(t.pass(i)), compute ? 0 : 1);
            passes.computeIfAbsent(t.pass(i), k -> new ArrayList<>()).add(ms);
        }
        recording.gpuFrame(t.frame(), start, end);
        gpuFrames.add(t.spanMillis());
    }

    /** The segment's recording exported, its tables summed up; returns the files written. */
    public String finish() throws IOException {
        Profiler.discard();
        Files.createDirectories(folder);
        Path own = folder.resolve("showcase-" + segment + ".tsprof");
        try (OutputStream out = Files.newOutputStream(own)) {
            ProfileFile.write(recording, out);
        }
        Path chrome = folder.resolve("showcase-" + segment + ".json");
        try (Writer out = Files.newBufferedWriter(chrome, StandardCharsets.UTF_8)) {
            ChromeTrace.write(recording, out);
        }
        double gpuMean = mean(gpuFrames);
        double workMean = mean(cpuWork);
        summary.add(String.format(Locale.ROOT,
            "%s: GPU frame %.2f ms mean (p99 %.2f), %d frames; CPU work %.2f ms mean (p99 %.2f), waiting %.2f ms mean;"
                + " %d hitches%s", segment, gpuMean, p99(gpuFrames), gpuFrames.size(), workMean, p99(cpuWork),
            mean(cpuWait), hitches, hitchList.isEmpty() ? "" : " (" + String.join(", ", hitchList) + ")"));
        table("GPU pass", passes, gpuMean);
        table("CPU zone", zones, workMean + mean(cpuWait));
        return own + ", " + chrome;
    }

    /** {@code kind}'s rows sorted by mean, the tiny ones summed as "other", into the summary and the CSV. */
    private void table(String kind, Map<String, List<Double>> values, double frame) {
        record Row(String name, double mean, double p99) {
        }
        List<Row> rows = new ArrayList<>();
        int frames = Math.max(1, kind.startsWith("GPU") ? gpuFrames.size() : cpuWork.size());
        for (Map.Entry<String, List<Double>> e : values.entrySet()) {
            // a mean per frame (a pass or zone missing from a frame counts as 0 there)
            double sum = 0;
            for (double v : e.getValue()) {
                sum += v;
            }
            rows.add(new Row(e.getKey(), sum / frames, p99(e.getValue())));
        }
        rows.sort((a, b) -> Double.compare(b.mean(), a.mean()));
        double other = 0;
        int others = 0;
        summary.add(String.format(Locale.ROOT, "  %-10s %-40s %8s %8s %6s", kind, "", "mean", "p99", "share"));
        for (Row r : rows) {
            double share = frame > 0 ? r.mean() / frame : 0;
            if (share < TINY) {
                other += r.mean();
                others++;
                continue;
            }
            summary.add(String.format(Locale.ROOT, "  %-10s %-40s %8.2f %8.2f %5.1f%%", "", r.name(), r.mean(),
                r.p99(), 100 * share));
            csv.add(String.format(Locale.ROOT, "%s,%s,\"%s\",%.3f,%.3f,%.4f", segment, kind, r.name(), r.mean(),
                r.p99(), share));
        }
        if (others > 0) {
            summary.add(String.format(Locale.ROOT, "  %-10s %-40s %8.2f %8s %5.1f%%", "",
                "other (" + others + ")", other, "", frame > 0 ? 100 * other / frame : 0));
            csv.add(String.format(Locale.ROOT, "%s,%s,\"other (%d)\",%.3f,,%.4f", segment, kind, others, other,
                frame > 0 ? other / frame : 0));
        }
    }

    /** Every segment's tables, for the log; the CSV written beside the recordings. */
    public List<String> report() throws IOException {
        Files.createDirectories(folder);
        Files.write(folder.resolve("showcase-passes.csv"), csv, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(summary);
        out.add("Recordings and showcase-passes.csv in " + folder.toAbsolutePath());
        return out;
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.isEmpty() ? Double.NaN : s / v.size();
    }

    private static double p99(List<Double> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[Math.min(a.length - 1, (int) Math.ceil(0.99 * a.length) - 1)];
    }

    // ---------------------------------------------------------------- zones

    @Override
    public void begin(int thread, long nanos, int zone, int depth) {
        if (thread >= threadLane.length) {
            int old = threadLane.length;
            threadLane = Arrays.copyOf(threadLane, thread + 1);
            open = Arrays.copyOf(open, thread + 1);
            openStart = Arrays.copyOf(openStart, thread + 1);
            openZone = Arrays.copyOf(openZone, thread + 1);
            openDepth = Arrays.copyOf(openDepth, thread + 1);
            for (int i = old; i <= thread; i++) {
                threadLane[i] = -1;
                open[i] = new long[64];
                openStart[i] = new long[64];
                openZone[i] = new int[64];
            }
        }
        if (threadLane[thread] < 0) {
            threadLane[thread] = recording.lane(Profiler.threadName(thread), LaneKind.THREAD);
        }
        if (zone >= zoneName.length) {
            int old = zoneName.length;
            zoneName = Arrays.copyOf(zoneName, zone + 1);
            Arrays.fill(zoneName, old, zone + 1, -1);
        }
        if (zoneName[zone] < 0) {
            zoneName[zone] = recording.name(Profiler.zoneName(zone));
        }
        if (depth >= open[thread].length) {
            open[thread] = Arrays.copyOf(open[thread], depth * 2);
            openStart[thread] = Arrays.copyOf(openStart[thread], depth * 2);
            openZone[thread] = Arrays.copyOf(openZone[thread], depth * 2);
        }
        open[thread][depth] = recording.open(threadLane[thread], nanos, zoneName[zone], depth);
        openStart[thread][depth] = nanos;
        openZone[thread][depth] = zone;
        openDepth[thread] = depth + 1;
    }

    @Override
    public void end(int thread, long nanos, int depth) {
        if (thread < open.length && depth < openDepth[thread]) {
            recording.close(threadLane[thread], open[thread][depth], nanos);
            String name = Profiler.threadName(thread) + ": " + Profiler.zoneName(openZone[thread][depth]);
            zoneThisFrame.computeIfAbsent(name, k -> new double[1])[0] += (nanos - openStart[thread][depth]) / 1e6;
            openDepth[thread] = depth;
        }
    }

    @Override
    public void lost(int thread, long nanos) {
        if (thread < open.length) {
            for (int d = 0; d < openDepth[thread]; d++) {
                recording.close(threadLane[thread], open[thread][d], nanos);
            }
            openDepth[thread] = 0;
        }
    }
}
