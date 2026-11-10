package com.truesim.samples.showcase;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis1Action;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Filters;
import com.truesim.engine.platform.GamepadButton;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;

/**
 * The interactive app's actions, rebindable in the settings file ({@code input.showcase.*}): the free-flight camera,
 * the time of day, the weather, the quality preset and the effects switched.
 */
final class ShowcaseActions {

    static final ActionGroup SHOWCASE = ActionGroup.root("showcase");

    static final ActionGroup FLY = SHOWCASE.child("fly");
    /** Flying: W A S D, the left stick. */
    static final Axis2Action MOVE = FLY.axis2("move", Binding.keys(Key.A, Key.D, Key.S, Key.W), Binding.stick(false));
    /** Rising and sinking: E and Q, the shoulders. */
    static final Axis1Action RISE = FLY.axis1("rise", Binding.axis(Controls.key(Key.Q), Controls.key(Key.E)),
        Binding.axis(Controls.pad(GamepadButton.LEFT_SHOULDER), Controls.pad(GamepadButton.RIGHT_SHOULDER)));
    /** Faster: shift. */
    static final BinaryAction FAST = FLY.binary("fast", Binding.of(Controls.SHIFT),
        Binding.pad(GamepadButton.LEFT_STICK));
    /** Holding to look around: the right mouse button. */
    static final BinaryAction LOOKING = FLY.binary("looking", Binding.mouse(MouseButton.RIGHT));
    /** Looking: the mouse while the right button is held, radians. */
    static final Axis2Action LOOK = FLY.axis2("look", Binding.axes2d(Controls.MOUSE_X, Controls.MOUSE_Y)
        .with(Controls.mouse(MouseButton.RIGHT)).filter(Filters.SCALE, 0.003f));
    /** Turning: the right stick, radians a second. */
    static final Axis2Action TURN = FLY.axis2("turn", Binding.stick(true).filter(Filters.CURVE, 2)
        .filter(Filters.SCALE, 2.5f));

    static final ActionGroup DAY = SHOWCASE.child("day");
    /** An hour earlier, later: [ and ]. */
    static final BinaryAction EARLIER = DAY.binary("earlier", Binding.key(Key.LEFT_BRACKET));
    static final BinaryAction LATER = DAY.binary("later", Binding.key(Key.RIGHT_BRACKET));
    /** Time passing a hundred times as fast, or stopped: T. */
    static final BinaryAction TIME_LAPSE = DAY.binary("timeLapse", Binding.key(Key.T));
    /** The weather's presets: 1 clear to 8 storm. */
    static final BinaryAction[] WEATHER = {
        DAY.binary("clear", Binding.key(Key.DIGIT_1)), DAY.binary("fair", Binding.key(Key.DIGIT_2)),
        DAY.binary("scattered", Binding.key(Key.DIGIT_3)), DAY.binary("broken", Binding.key(Key.DIGIT_4)),
        DAY.binary("overcast", Binding.key(Key.DIGIT_5)), DAY.binary("altostratus", Binding.key(Key.DIGIT_6)),
        DAY.binary("rain", Binding.key(Key.DIGIT_7)), DAY.binary("storm", Binding.key(Key.DIGIT_8))};
    /** Snowfall on and off: B. */
    static final BinaryAction SNOW = DAY.binary("snow", Binding.key(Key.B));
    /** Fog and mist: M. */
    static final BinaryAction MIST = DAY.binary("mist", Binding.key(Key.M));
    /** The fireworks: F. */
    static final BinaryAction FIREWORKS = DAY.binary("fireworks", Binding.key(Key.F));

    static final ActionGroup VIEW = SHOWCASE.child("view");
    /** Global illumination: probes, cascades, off: G. */
    static final BinaryAction GI = VIEW.binary("gi", Binding.key(Key.G));
    /** The quality preset: low, medium, high, ultra: P. */
    static final BinaryAction QUALITY = VIEW.binary("quality", Binding.key(Key.P));
    /** Depth of field on and off: J. */
    static final BinaryAction DOF = VIEW.binary("dof", Binding.key(Key.J));
    static final BinaryAction QUIT = VIEW.binary("quit", Binding.key(Key.ESCAPE));

    private ShowcaseActions() {
    }
}
