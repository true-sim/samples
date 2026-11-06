package com.truesim.samples.demo;

import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.scene.Animation;
import com.truesim.engine.scene.SceneSync;
import com.truesim.scene.animation.AnimationCurve;
import com.truesim.scene.animation.AnimationEvent;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.BlendSpace1;
import com.truesim.scene.animation.Clip;
import com.truesim.scene.animation.FabrikChain;
import com.truesim.scene.animation.FootPlacement;
import com.truesim.scene.animation.Joint;
import com.truesim.scene.animation.LookAt;
import com.truesim.scene.animation.Rig;
import com.truesim.scene.animation.TwoBoneIk;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.MorphWeights;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;

/**
 * The Khronos fox ({@code --set demo.character=demo://Fox/glTF/Fox.gltf}, with {@link DemoSettings#ASSETS} the
 * glTF-Sample-Assets' {@code Models} folder) on the demo's terrain, loaded through the shared assets, through the whole animation path: its clips in a rig, played by an animator, skinned on the GPU. A loop of a
 * few seconds: surveying, cross-fading into a walk that speeds up into a run (a blend space), and back; all the while its
 * head looks at a circling light (a look-at shared down its neck and spine), its tail follows a wagging point (a FABRIK
 * chain with limits), and its four feet stand on the uneven ground (foot placement on both pairs of legs). Its walk and
 * run carry an event as each foot comes down ({@link #FEET}, for dust), and its survey a flickering curve
 * ({@link #FLICKER}).
 */
final class DemoCharacter implements AutoCloseable {

    /** How long the loop lasts, seconds. */
    static final double LOOP = 12;
    /** A foot coming down: back left, back right, front left, front right, in that order of {@link #feet()}. */
    static final AnimationEvent[] FEET = {AnimationEvent.of("back left foot down"),
        AnimationEvent.of("back right foot down"), AnimationEvent.of("front left foot down"),
        AnimationEvent.of("front right foot down")};
    /** A curve flickering between 0 and 1 while the fox surveys: drives the torch beside it. */
    static final AnimationCurve FLICKER = AnimationCurve.of("flicker");
    /** The joints of the feet, in the order of {@link #FEET}. */
    static final String[] FOOT_JOINTS = {"b_LeftFoot01_017", "b_RightFoot01_021", "b_LeftHand_011", "b_RightHand_08"};
    /** When each foot comes down in the walk and the run (seconds into them; found from the feet's heights). */
    private static final double[] WALK_STEPS = {0.5, 0.692, 0.017, 0.375};
    private static final double[] RUN_STEPS = {1.0, 1.092, 0.525, 0.417};

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
    private final Animators animators; // run by the animation plugin, in ANIMATE
    private final SceneSync sync;
    private final long holder;
    private long body; // the node drawing its skinned mesh
    private final double x;
    private final double z;
    private final Animator animator;
    private final Rig.Layer base;
    private final Clip survey;
    private final BlendSpace1 move;
    private final LookAt look;
    private final FabrikChain tail;
    private final double[] tailRoot = new double[3];
    private final Joint[] feet = new Joint[4];
    private boolean moving;

    /**
     * The fox of model {@code model} (an asset path) from {@code assets}, at world ({@code x}, {@code z}) on the
     * terrain, drawn into {@code gpu} (traced where the assets' resources trace); its animator run by {@code animation}.
     */
    DemoCharacter(DemoAssets assets, GpuScene gpu, String model, double x, double z, Animation animation)
        throws InterruptedException {
        animators = animation.animators(scene);
        this.x = x;
        this.z = z;
        sync = new SceneSync(assets.resources(), scene, gpu);
        ModelData fox = assets.await(assets.ref(RenderAssets.MODEL, model));
        // The fox is about 80 units tall: a small dog's size, facing the camera.
        holder = scene.create();
        scene.setScale(holder, 0.012, 0.012, 0.012);
        long[] nodes = fox.spawn(scene, holder);
        for (long node : nodes) {
            if (body == 0 && renderables.has(node) && renderables.get(node).skin() != null) {
                body = node;
            }
        }
        animators.morphSink((node, weights, offset, count) -> MorphWeights.write(scene, node, weights, offset, count));

        Rig.Builder r = Rig.builder(fox.skeleton());
        survey = r.clip(assets.await(fox.animation("Survey")));
        Clip walk = r.clip(assets.await(fox.animation("Walk")));
        Clip run = r.clip(assets.await(fox.animation("Run")));
        for (int f = 0; f < 4; f++) {
            feet[f] = r.joint(FOOT_JOINTS[f]);
        }
        footEvents(walk, run);
        float[] times = new float[25];
        float[] values = new float[25];
        for (int k = 0; k < times.length; k++) {
            times[k] = (float) (survey.duration() * 0.999 * k / (times.length - 1)); // within it: the loop keeps its length
            values[k] = (float) (0.5 + 0.5 * Maths.sin(k * 2.7) * Maths.cos(k * 1.3));
        }
        survey.curve(FLICKER, times, values);
        move = r.blendSpace1().at(0, walk).at(1, run);
        base = r.layer();
        look = r.lookAt(r.joint("b_Head_05"), 0, 0, 1).chain(r.joint("b_Neck_04"), 0.4)
            .chain(r.joint("b_Spine02_03"), 0.2).limit(1.2);
        tail = r.fabrik(r.joint("b_Tail01_012"), r.joint("b_Tail03_014"));
        for (String joint : new String[] {"b_Tail01_012", "b_Tail02_013"}) {
            tail.limit(r.joint(joint), 0.6);
        }
        TwoBoneIk leftBack = r.twoBoneIk(r.joint("b_LeftLeg01_015"), r.joint("b_LeftLeg02_016"),
            r.joint("b_LeftFoot01_017"));
        TwoBoneIk rightBack = r.twoBoneIk(r.joint("b_RightLeg01_019"), r.joint("b_RightLeg02_020"),
            r.joint("b_RightFoot01_021"));
        TwoBoneIk leftFront = r.twoBoneIk(r.joint("b_LeftUpperArm_09"), r.joint("b_LeftForeArm_010"),
            r.joint("b_LeftHand_011"));
        TwoBoneIk rightFront = r.twoBoneIk(r.joint("b_RightUpperArm_06"), r.joint("b_RightForeArm_07"),
            r.joint("b_RightHand_08"));
        r.modifier(new FootPlacement(leftBack, rightBack, r.joint("b_Hip_01"), DemoMeshes::height));
        r.modifier(new FootPlacement(leftFront, rightFront, r.joint("b_Spine02_03"), DemoMeshes::height));
        Rig rig = r.build();
        animator = animators.add(rig, nodes);
        animator.layer(base).play(survey);
        update(0, 0, 0, 0);
    }

    /** Puts the {@link #FEET} events on the fox's walk and run clips, where each foot comes down. */
    static void footEvents(Clip walk, Clip run) {
        for (int f = 0; f < 4; f++) {
            walk.event(WALK_STEPS[f], FEET[f]);
            run.event(RUN_STEPS[f], FEET[f]);
        }
    }

    SceneGraph scene() {
        return scene;
    }

    /** Its sync, which draws it: what particles' mesh shapes emit from. */
    SceneSync sync() {
        return sync;
    }

    /** The node drawing its skinned mesh. */
    long body() {
        return body;
    }

    Animators animators() {
        return animators;
    }

    Animator animator() {
        return animator;
    }

    /** Its feet's joints, in the order of {@link #FEET}. */
    Joint[] feet() {
        return feet;
    }

    /**
     * The fox at {@code seconds}, looking at world ({@code lx}, {@code ly}, {@code lz}): what plays and where, in UPDATE;
     * the animation plugin poses it in ANIMATE, {@link #draw} draws it.
     */
    void update(double seconds, double lx, double ly, double lz) {
        double t = seconds % LOOP;
        // Surveying, then walking faster into a run, then surveying again: cross-fades between.
        boolean shouldMove = t >= 3 && t < 10;
        if (shouldMove != moving) {
            moving = shouldMove;
            animator.layer(base).play(moving ? move : survey, 0.5);
        }
        animator.input(move, Math.max(0, Math.min(1, (t - 4) / 4)));
        // Turning slowly where it stands, so the camera sees it from the sides as well.
        double yaw = 0.3 * Maths.sin(seconds * 0.4) + Math.PI * 0.25;
        scene.setTranslation(holder, x, DemoMeshes.height(x, z), z);
        scene.setRotation(holder, 0, Maths.sin(yaw / 2), 0, Maths.cos(yaw / 2));
        animator.lookAt(look).target(lx, ly, lz).weight(1);
        // The tail after a point wagging behind it, up and to the sides.
        double behind = 1.2;
        double side = 0.5 * Maths.sin(seconds * 3);
        tailRoot[0] = x - behind * Maths.sin(yaw) + side * Maths.cos(yaw);
        tailRoot[1] = DemoMeshes.height(x, z) + 0.9;
        tailRoot[2] = z - behind * Maths.cos(yaw) - side * Maths.sin(yaw);
        animator.fabrik(tail).target(tailRoot[0], tailRoot[1], tailRoot[2]).weight(0.8);
    }

    /** Draws the fox as animation posed it: after the frame's ANIMATE (the animation plugin's), before it records. */
    void draw() {
        scene.update();
        sync.update();
    }

    @Override
    public void close() {
        sync.close();
    }
}
