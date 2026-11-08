// The showcase's particle module: snowflakes fluttering as they fall. A flake is a plate tumbling in the air, swinging
// side to side and spiralling as it drops (Nakaya's "zigzag" and "spiral" falls); here a horizontal swing on a circle
// of `radius` metres at `rate` turns a second, each flake at its own phase and direction from its seed, the swing's
// velocity added to the flake's position (its own velocity, gravity and drag, untouched). The project's own module
// also makes the build generate the particle modules' dispatch the particle library's kernel includes, rebuilt here
// with every shading hook (ShowcaseParticleModules, ShowcaseShadingHooks).
module flutter;

import particles.particle;

/** A horizontal swing of `radius` metres at `rate` turns a second, phase and direction per flake. */
[ParticleModule("showcase.flutter")]
public struct Flutter
{
    public float radius = 0.15;
    public float rate = 0.6;

    public void update(inout ParticleStep s, inout Particle p)
    {
        float phase = float(p.seed & 0xffffu) / 65536.0 * 6.2831853;
        float spin = (p.seed & 0x10000u) != 0 ? 1.0 : -1.0;
        float rateHere = rate * (0.6 + 0.8 * float((p.seed >> 17) & 0xffu) / 255.0);
        float w = 6.2831853 * rateHere * spin;
        float a0 = phase + w * (p.age - s.h);
        float a1 = phase + w * p.age;
        // The circle's displacement over the step: radius (cos, sin) at the end less at the start
        p.position.x += radius * (cos(a1) - cos(a0));
        p.position.z += radius * (sin(a1) - sin(a0));
    }
};
