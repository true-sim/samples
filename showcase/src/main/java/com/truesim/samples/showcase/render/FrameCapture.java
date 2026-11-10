package com.truesim.samples.showcase.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A view's frames as a window shows them, written to PNG files: its scene color through the engine's
 * {@link DisplayTransform} made with the post chain's display stage ({@code displayEffects()}: auto exposure's input,
 * film grain, the flash), encoded for an SDR display into an sRGB image, then copied out. As the effect repositories'
 * screenshot films: PNG frames, made into a film with ffmpeg by hand.
 */
public final class FrameCapture implements AutoCloseable {

    private static final Key<Image> ENCODED = Key.image("showcase.captureEncoded");

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final GpuImage target;
    private final GpuBuffer pixels;
    private final BufferedImage image;

    /** Captures what {@code port} renders, at its size: the port's output is the encoded image. */
    public FrameCapture(VulkanDevice device, ViewPort port) {
        this.device = device;
        width = port.outputWidth();
        height = port.outputHeight();
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        target = new GpuImage(device, width, height, VK_FORMAT_R8G8B8A8_SRGB, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, 4L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var technique : new DisplayTransform().displayEffects().transforms(Keys.SCENE_COLOR, ENCODED,
            DisplayEncoding.SDR_SRGB)) {
            port.add(technique);
        }
        port.output(ENCODED, new ExternalImage("capture of " + port.name(), width, height, Format.R8G8B8A8_SRGB,
                usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(target.handle(), target.view()));
    }

    /** The last frame as an image; the GPU must have finished it ({@code device.waitIdle()}). */
    public BufferedImage read() {
        device.submitAndWait(device.graphics(), commands -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(width, height, 1);
                VK10.vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < rgb.length; i++) {
            int rgba = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i); // R, G, B, A bytes: little-endian ABGR
            rgb[i] = (rgba & 0xff) << 16 | (rgba & 0xff00) | (rgba >> 16 & 0xff);
        }
        return image;
    }

    /** Writes the last frame to {@code file}; the GPU must have finished it. */
    public void write(Path file) throws IOException {
        javax.imageio.ImageIO.write(read(), "png", file.toFile());
    }

    @Override
    public void close() {
        pixels.close();
        target.close();
    }
}
