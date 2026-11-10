package com.truesim.samples.showcase.render;

import com.truesim.common.math.Maths;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Locale;

/**
 * A look at a view's scene color in scene light, before post-processing: copied into host memory each frame it is
 * asked for, then counted (not-a-number and infinite pixels, the log-average and the largest luminance). For checking
 * exposure and finding where a frame goes wrong; not part of what the showcase shows.
 */
public final class SceneStats implements Technique, AutoCloseable {

    private final GpuBuffer pixels;
    private final int width;
    private final int height;

    public SceneStats(VulkanDevice device, int width, int height) {
        this.width = width;
        this.height = height;
        pixels = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.POST).before(com.truesim.effects.post.DepthOfField.ID, com.truesim.effects.post.Bloom.ID);
    }

    @Override
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        g.pass("scene stats").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
            try (var stack = stackPush()) {
                var region = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
                region.bufferRowLength(width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
            }
        });
        g.provide(Keys.SCENE_COLOR, color);
    }

    /** The last frame's numbers, in scene units; the GPU must have finished it. */
    public String summary() {
        MemorySegment m = pixels.memory();
        long nan = 0;
        long inf = 0;
        double logSum = 0;
        long counted = 0;
        double max = 0;
        for (int i = 0; i < width * height; i++) {
            float r = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i));
            float gr = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2));
            float b = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 4));
            if (Float.isNaN(r) || Float.isNaN(gr) || Float.isNaN(b)) {
                nan++;
                continue;
            }
            if (Float.isInfinite(r) || Float.isInfinite(gr) || Float.isInfinite(b)) {
                inf++;
                continue;
            }
            double y = 0.2126 * r + 0.7152 * gr + 0.0722 * b;
            max = Math.max(max, y);
            logSum += Maths.log(Math.max(y, 1e-9));
            counted++;
        }
        return String.format(Locale.ROOT, "scene color: %d NaN, %d inf, log-average %.4g, max %.4g", nan, inf,
            counted > 0 ? Maths.exp(logSum / counted) : Double.NaN, max);
    }

    /** The log-average luminance of the last frame, scene units (NaN and infinite pixels left out). */
    public double logAverage() {
        MemorySegment m = pixels.memory();
        double logSum = 0;
        long counted = 0;
        for (int i = 0; i < width * height; i += 7) {
            float r = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i));
            float gr = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2));
            float b = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 4));
            double y = 0.2126 * r + 0.7152 * gr + 0.0722 * b;
            if (Double.isFinite(y)) {
                logSum += Maths.log(Math.max(y, 1e-9));
                counted++;
            }
        }
        return counted > 0 ? Maths.exp(logSum / counted) : Double.NaN;
    }

    @Override
    public void close() {
        pixels.close();
    }
}
