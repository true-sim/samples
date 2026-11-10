package com.truesim.samples.showcase.assets;

import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Materials made in code under paths, for generators whose recipes name their materials by path (common-procedural's
 * trees, plants, fences): a mount of its own ({@code mat://}) serving them through an importer of {@code .mat}
 * paths.
 */
public final class NamedMaterials {

    /** The mount. */
    public static final String MOUNT = "mat";

    private final Map<String, MaterialData> materials = new ConcurrentHashMap<>();

    public NamedMaterials(ShowcaseAssets assets) {
        assets.paths().mounts().add(MOUNT, path -> materials.containsKey(path) ? new ByteArrayInputStream(new byte[0])
            : null);
        assets.paths().importers().add(RenderAssets.MATERIAL, "mat", input -> {
            MaterialData m = materials.get(input.path().path());
            if (m == null) {
                throw new IllegalArgumentException("No material " + input.path());
            }
            return m;
        });
    }

    /** Names {@code material} {@code name} (a path in the mount, ending {@code .mat}); its full path. */
    public String name(String name, MaterialData material) {
        materials.putIfAbsent(name, material);
        return MOUNT + "://" + name;
    }
}
