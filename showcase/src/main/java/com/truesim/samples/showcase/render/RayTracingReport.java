package com.truesim.samples.showcase.render;

import com.truesim.engine.render.RayTracing;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * What the ray tracing's acceleration structures hold, read from the engine's private state for a failure report
 * (true-sim/engine#478): the bottom levels' bytes, the pool's deformed copies, their build and refit scratch strides
 * and their triangles. Diagnostics only: nothing else reads the engine this way.
 */
final class RayTracingReport {

    private RayTracingReport() {
    }

    static String of(RayTracing rays) {
        if (rays == null) {
            return "no ray tracing";
        }
        try {
            Object bottoms = get(rays, "bottoms");
            long bytes = (long) get(bottoms, "bytes");
            Object deformed = get(bottoms, "deformed");
            int copies = (int) get(deformed, "copyCount");
            long build = (long) get(deformed, "buildStride");
            long update = (long) get(deformed, "updateStride");
            Object[] list = (Object[]) get(deformed, "copies");
            long triangles = 0;
            long vertices = 0;
            int built = 0;
            int maxTriangles = 0;
            java.util.Map<Integer, Integer> perSource = new java.util.HashMap<>();
            for (int i = 0; i < copies; i++) {
                Object c = list[i];
                Object t = get(c, "triangles");
                int n = (int) get(t, "count");
                triangles += n;
                maxTriangles = Math.max(maxTriangles, n);
                vertices += (int) get(c, "vertexCount");
                built += (boolean) get(c, "built") ? 1 : 0;
                perSource.merge(System.identityHashCode(t), 1, Integer::sum);
            }
            return String.format(Locale.ROOT, "bottom levels %.1f MB in all; %d deformed copies (%d built) of %d "
                    + "source meshes, %d triangles and %d vertices in all (largest copy %d triangles); scratch per "
                    + "build %.2f MB (x32 builds a frame), per refit %.2f MB (x every copy): %.1f MB asked",
                bytes / 1e6, copies, built, perSource.size(), triangles, vertices, maxTriangles, build / 1e6,
                update / 1e6, (build * 32 + update * (double) copies) / 1e6);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "could not read the acceleration structures: " + e;
        }
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
