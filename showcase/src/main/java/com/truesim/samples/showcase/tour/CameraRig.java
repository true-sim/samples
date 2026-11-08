package com.truesim.samples.showcase.tour;

import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.scene.CameraSync;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.SceneGraph;

/**
 * The camera as the scene has it: a node with a perspective lens, the renderer's {@link Camera} following it
 * ({@link CameraSync}, and the view's size). Placed by eye and target, or by position, heading and pitch.
 */
public final class CameraRig {

    private final SceneGraph scene;
    private final long node;
    private final Camera camera = new Camera();
    private final CameraSync sync;

    public CameraRig(SceneGraph scene, ViewPort port, double fovY, double near) {
        this.scene = scene;
        node = scene.create();
        scene.components().set(node, CameraLens.TYPE, CameraLens.perspective(fovY, near));
        sync = port == null ? new CameraSync(scene, node, camera) : new CameraSync(scene, node, camera, port);
    }

    /** The renderer's camera. */
    public Camera camera() {
        return camera;
    }

    public long node() {
        return node;
    }

    /** At ({@code x}, {@code y}, {@code z}) looking at ({@code tx}, {@code ty}, {@code tz}), level. */
    public void look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        place(x, y, z, Math.atan2(dx, dz), Math.asin(dy / length));
    }

    /** At ({@code x}, {@code y}, {@code z}) facing {@code yaw} (radians from +z towards +x) and {@code pitch} up. */
    public void place(double x, double y, double z, double yaw, double pitch) {
        double a = (yaw + Math.PI) / 2;
        double b = pitch / 2;
        double cy = Math.cos(a);
        double sy = Math.sin(a);
        double cx = Math.cos(b);
        double sx = Math.sin(b);
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, cy * sx, sy * cx, -sy * sx, cy * cx);
    }

    /** The renderer's camera follows the node (after the scene's update), at the view's size. */
    public void update() {
        sync.update();
    }

    /** As {@link #update()}, for a camera without a view: at {@code width} by {@code height}. */
    public void update(int width, int height) {
        sync.update(width, height);
    }
}
