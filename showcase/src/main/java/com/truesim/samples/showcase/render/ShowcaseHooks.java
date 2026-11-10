package com.truesim.samples.showcase.render;

import com.truesim.engine.render.ShadingHookListing;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.samples.showcase.ShowcaseShadingHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The showcase's own shading-hook listing installed on a device, built against the engine it runs on. The engine
 * would otherwise take whichever listing on the classpath holds every hook, and between listings holding the same
 * hooks the last found: a library's, built on an older engine, would load that engine's shaders in place of the
 * current ones.
 */
public final class ShowcaseHooks {

    private ShowcaseHooks() {
    }

    /** Installs {@link ShowcaseShadingHooks} on {@code device} before its first shader loads; returns the device. */
    public static VulkanDevice installed(VulkanDevice device) {
        List<String> found = new ArrayList<>();
        ShadingHooks engineChoice = null;
        for (ShadingHookListing listing : ServiceLoader.load(ShadingHookListing.class,
            ShadingHooks.class.getClassLoader())) {
            ShadingHooks h = listing.hooks();
            found.add(h.name() + " (" + h.count() + " hooks)");
            if (engineChoice == null || h.hooks().containsAll(engineChoice.hooks())) {
                engineChoice = h; // as the engine chooses: the last holding every hook of the one before
            }
        }
        String installed;
        try {
            installed = ShadingHooks.use(device, ShowcaseShadingHooks.HOOKS).name();
        } catch (IllegalStateException e) { // shaders loaded already (the app's device comes made): what it took
            installed = ShadingHooks.of(device).name() + " (too late to install the showcase's: " + e.getMessage()
                + ")";
        }
        System.out.println("Shading hooks: listings " + found + "; the engine alone would take "
            + (engineChoice == null ? "its own" : engineChoice.name()) + "; installed " + installed);
        return device;
    }
}
