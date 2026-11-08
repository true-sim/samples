package com.truesim.samples.showcase.tour;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera move: keys of time, eye and target, the eye and the target each on a centripetal-free Catmull-Rom spline
 * through their keys (uniform in the keys), time eased in and out over the whole move so it starts and stops gently.
 * Heights may be given above the ground ({@link Key#onGround}), resolved once by the shot.
 */
public final class CameraSpline {

    /** A key: at {@code t} seconds of the shot, the eye and what it looks at. */
    public record Key(double t, double x, double y, double z, double tx, double ty, double tz) {
    }

    private final List<Key> keys = new ArrayList<>();
    private final boolean ease;

    public CameraSpline(boolean ease) {
        this.ease = ease;
    }

    public CameraSpline key(double t, double x, double y, double z, double tx, double ty, double tz) {
        keys.add(new Key(t, x, y, z, tx, ty, tz));
        return this;
    }

    public double duration() {
        return keys.getLast().t();
    }

    /** The eye and target at {@code t} seconds into the move: x, y, z, tx, ty, tz into {@code out}. */
    public void at(double t, double[] out) {
        double end = duration();
        double u = Math.clamp(t / end, 0, 1);
        if (ease) {
            u = u * u * (3 - 2 * u) * 0.6 + u * 0.4; // a gentle ease: never quite stopping mid-move
        }
        double time = u * end;
        int i = 0;
        while (i + 2 < keys.size() && keys.get(i + 1).t() <= time) {
            i++;
        }
        Key k1 = keys.get(i);
        Key k2 = keys.get(Math.min(i + 1, keys.size() - 1));
        Key k0 = keys.get(Math.max(i - 1, 0));
        Key k3 = keys.get(Math.min(i + 2, keys.size() - 1));
        double span = Math.max(k2.t() - k1.t(), 1e-9);
        double s = Math.clamp((time - k1.t()) / span, 0, 1);
        out[0] = cr(k0.x(), k1.x(), k2.x(), k3.x(), s);
        out[1] = cr(k0.y(), k1.y(), k2.y(), k3.y(), s);
        out[2] = cr(k0.z(), k1.z(), k2.z(), k3.z(), s);
        out[3] = cr(k0.tx(), k1.tx(), k2.tx(), k3.tx(), s);
        out[4] = cr(k0.ty(), k1.ty(), k2.ty(), k3.ty(), s);
        out[5] = cr(k0.tz(), k1.tz(), k2.tz(), k3.tz(), s);
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
