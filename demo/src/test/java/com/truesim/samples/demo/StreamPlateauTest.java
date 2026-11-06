package com.truesim.samples.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.vulkan.VulkanException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Every growable structure on the streaming path reaches a plateau: the stream benchmark's flight, cells streamed in
 * and out over and over, samples what each holds or has room for ({@link StreamFlight#footprint()}: the geometry pool's
 * room, meshes and spares, the instance table, materials, textures, the resources' tables and queues, the cache's assets
 * and bytes, the mesh memory kept, the scene's slots, the sync's items, GPU memory by category), and none of them is
 * higher in the flight's second half than in its first: freed entries are reused, nothing only grows. A short flight
 * runs with the build; a long one is a soak test (TRUESIM_SOAK=true, TRUESIM_SOAK_FRAMES frames, 20,000 by default).
 */
class StreamPlateauTest {

    @AfterEach
    void restore() {
        StreamBench.options();
        StreamBench.paced = true;
        StreamBench.builderThreads = 3;
    }

    @Test
    void aShortFlightKeepsEveryStructureOnAPlateau() throws Exception {
        fly(800, "the scene way");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_SOAK", matches = "true")
    void aLongFlightKeepsEveryStructureOnAPlateau() throws Exception {
        String frames = System.getenv("TRUESIM_SOAK_FRAMES");
        fly(frames == null ? 20_000 : Integer.parseInt(frames), "the scene way");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRUESIM_SOAK", matches = "true")
    void theBaselinesLongFlightKeepsItsStructuresOnAPlateau() throws Exception {
        String frames = System.getenv("TRUESIM_SOAK_FRAMES");
        fly(frames == null ? 20_000 : Integer.parseInt(frames), "the baseline", "baseline");
    }

    private static void fly(int frames, String what, String... args) throws Exception {
        StreamBench.options(args);
        StreamBench.speed = 270; // 3 m a frame: a column of 7 cells every 8 frames
        StreamBench.frameCount = frames;
        StreamBench.paced = false;
        StreamBench.builderThreads = Math.max(3, Runtime.getRuntime().availableProcessors() - 1);
        List<Map<String, Long>> samples = new ArrayList<>();
        int every = 25;
        try {
            StreamBench.headless((f, flight) -> {
                if (f % every == 0) {
                    samples.add(flight.footprint());
                }
            });
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
        }
        // From a quarter of the way (the range long full): the first half against the second
        int from = samples.size() / 4;
        int middle = (from + samples.size()) / 2;
        Map<String, long[]> peaks = new LinkedHashMap<>();
        for (int i = from; i < samples.size(); i++) {
            for (Map.Entry<String, Long> e : samples.get(i).entrySet()) {
                long[] peak = peaks.computeIfAbsent(e.getKey(), k -> new long[2]);
                int half = i < middle ? 0 : 1;
                peak[half] = Math.max(peak[half], e.getValue());
            }
        }
        StringBuilder report = new StringBuilder(what + ", peaks in the first and second half:");
        List<String> grew = new ArrayList<>();
        for (Map.Entry<String, long[]> e : peaks.entrySet()) {
            long first = e.getValue()[0];
            long second = e.getValue()[1];
            report.append(String.format("%n  %-28s %14d %14d", e.getKey(), first, second));
            // GPU memory, the cache's bytes and the pool's vertices vary by what is in flight and by the cells' rock
            // detail: an eighth of slack. Spares come and go up to their limit (render.meshSpares). Counts: none.
            String key = e.getKey();
            long slack = key.startsWith("GPU") || key.endsWith("bytes") || key.equals("pool vertices") ? first >> 3 : 0;
            if (key.equals("pool spares")) {
                slack = com.truesim.engine.render.RenderSettings.MESH_SPARES.defaultValue() - first;
            }
            if (second > first + slack) {
                grew.add(e.getKey() + " " + first + " -> " + second);
            }
        }
        System.out.println(report);
        assertThat(grew).as("structures still growing in the second half").isEmpty();
    }
}
