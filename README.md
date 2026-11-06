# true-sim samples

Programs built on the [true-sim engine](https://github.com/true-sim/engine): the demo, the effects showcase, the
stream and stereo benchmarks, the UI gallery, and the UI and profiler benchmark suites. They moved out of the engine
repository (engine 0.186.0), so the engine's CI runs the engine's tests only, and they build on the published engine,
scene and ui modules like any game would.

## The way they are written

Everything a sample shows loads through the scene's asset manager and is drawn through the scene:

- **One asset manager** (`DemoAssets`): the engine's `Assets` plugin in an application, or a cache of its own in a
  program without an engine. Meshes, materials and textures are asset references, generated in code
  (`AssetRef.generated(RenderAssets.MESH, ...)`) or imported (glTF, images); sample models are found by asset path
  under the `demo://` mount (`demo.assets`), never by file.
- **Everything is a node**: rocks, props, the terrain and streamed cells are nodes with `Renderable` components kept on
  the GPU by `SceneSync`; lights are nodes with `LightSource` (`LightSync`); cameras are nodes with `CameraLens`, the
  renderer's cameras following them (`CameraSync`); the car's engine is a `SoundEmitter` on the camera's node, torches'
  flames `EffectEmitter`s on theirs.
- **Options are settings** (`DemoSettings`): developer settings set with `--set name=value`, listed by the engine's
  settings registry; the Gradle tasks turn their `-P` properties into these.

Still open, waiting for engine or scene features (the engine split plan's section 9):

- Particles name their textures' and meshes' GPU slots themselves (`Particles.texture`, `Particles.mesh`): the content
  is an asset, the slot registration is by hand until the engine resolves assets for techniques (plan 7.5).
- UI themes and fonts come from the classpath (no stylesheet or font asset types yet); world panels are placed by
  transform (no WorldPanel component yet); the headset's eye cameras follow the tracking origin (no XR rig component).
- The stream benchmark's scene mode evicts generated meshes on the main thread (closing their arenas there), which
  costs frames that remove cells about 3 ms more than the hand-written baseline mode (`--args=baseline`).
- `-Pglass=true` in the showcase is still a system property: the effects' definitions are constants that take it.

## The demo

`./gradlew :samples-demo:run` opens rolling terrain with 800 rocks (some spinning, some glowing), the sun with cascaded
shadows and colored lights circling the middle, to try what the engine does live: the keys print at start and the
title shows the frame rate, GPU time, render size and display encoding. Settings persist in
`~/.truesim/demo.properties`.

- With a sound device, six rocks drop ahead of the camera and knock where they land (the `Audio` plugin; without a
  device it plays silently).
- `-Pcharacter=<glTF-Sample-Assets>/Models/Fox/glTF/Fox.gltf` (the settings `demo.assets`, the folder mounted as
  `demo://`, and `demo.character=demo://Fox.gltf`) puts the Khronos fox in a clearing at (26, 26), through
  the whole animation path: surveying, cross-fading into a walk that speeds up into a run (a blend space) and back, its
  head looking at a circling light, its tail after a wagging point (FABRIK with limits), its four feet on the terrain
  (foot placement), skinned on the GPU; its animator runs in the `Animation` plugin. The stereo bench takes it too,
  with `-Peye=x,y,z,tx,ty,tz` for a camera of its own (`g+h` for heights above the ground), `-Pframes=780` and
  `-PpngFrames=108,612 -Ppng=fox.png` for images.
- `-Peffect=x,y,z` (`g+h` for heights above the ground) plays the demo's particle effects there in the stereo bench:
  a fountain of sparks and trailed streaks over a column of smoke, an orb circling it with a ribbon, and a beam.
- `./gradlew :samples-demo:stereoBench -Pfoxes=1000 -Pcrowd=<path to Fox.gltf> [-PfoxLod=true]` adds a crowd of foxes,
  each with the whole rig and a GPU-skinned copy, and reports what animating them costs per frame. On a Ryzen 9
  9950X3D and RX 9070 XT (2026-11-02), 1000 foxes: CPU 1.75 ms in the animators and 1.69 ms in the scene sync, GPU
  0.27 ms skinning 1000 copies; with level of detail by distance (every frame within 15 m, every second to 40 m, every
  fourth beyond) 0.77 ms and 0.70 ms, GPU 0.12 ms skinning 350. The scene sync poses only the copies whose joints
  moved, found in the scene's log (or every copy, when most of what they watch moved). With `--args="rays"` (the sun's
  shadows ray-traced inline, visibility buffer shading) every copy's bottom level is refitted as it is skinned: 1000
  foxes 0.30 to 0.37 ms a frame on the GPU ("bottom level refits"; the top level 0.04 ms), with level of detail 0.13 ms
  for 350 (2026-11-02). `-PrebuildEvery=n` rebuilds each copy's level every n refits.
- `--args="--vr"` adds a headset through OpenXR (SteamVR, Quest Link, ...): both eyes render the scene, the left
  thumbstick walks, triggers buzz, `P` shows the left eye on the desktop for people watching.
- `--args="--validation"` turns Vulkan validation on and prints the error count at exit; `--seconds=10` quits by
  itself; `--probe` prints what the main view's scene color holds now and then.
- `./gradlew :samples-demo:streamBench` flies over a grid of cells for 30 s at 90 Hz: cells coming into range (3 each
  way, 7 x 7; `-Prange=n` for n) get meshes built on worker threads (three rocks and the cell's ground tile, 24 x 24
  quads at the world's heights, so the ground stays put under the rocks), a texture, materials and instances, those
  leaving lose them. It reports frame work percentiles, GPU time per frame and the slowest frames with what happened
  in them (`--args=rays` builds bottom levels too, `large` streams 131,000-triangle ground tiles and 2048 x 2048
  textures, `heavy` 295,000-triangle tiles and 4096 x 4096 textures, `direct` stages them on the frame's thread
  instead of preparing them on the loaders', `inframe` copies them in the frame instead of on the transfer queue). GPU
  time per frame includes the frame start, where textures' copies and mip chains are recorded
  (`GpuTimings.frameStartMillis`). `--args=window` (with any of the others, e.g. `--args="window large"`) shows the
  same fly-over, camera path and streaming in a resizable window instead, 10 cells each way (21 x 21, the ground 250
  m ahead) unless `-Prange` says otherwise, at 60 m/s rather than 10 (`-Pspeed=n` sets either; + and - change it
  live, Space pauses: the `stream` actions, rebindable in the settings), paced by the display, until the window is closed (`-Pseconds=n`: for n
  seconds), with the profiler's frame graph overlay on (F3 toggles it, Shift+F3 the detailed profiler) and a line at
  the bottom: cells in range, meshes and textures added and removed in the last second, what is on the GPU, the
  speed; it prints no report. `-Pmemory=n` prints memory by kind every n frames (GPU by category, VMA's heaps, LWJGL's
  native memory, direct buffers, the heap, the process); `-Pframes=n` runs n frames instead of 2,700.
- `./gradlew :samples-demo:stereoBench` times the scene drawn for two eyes at a Quest 3's eye size, offscreen, pass by
  pass and grouped by kind (`--args="visibility"` for the visibility buffer, `-Procks=20000` for more rocks,
  `shared` for one set of shadow cascades, `rebuild` for what rebuilds and resizes cost the render thread, `noshadows`
  without the sun's cascades, `-Ppng=<file>` to write the left eye as a window shows it: through `DisplayTransform`'s
  exposure and ACES curve on the GPU, sRGB-encoded). `capture` writes frame 100 for RenderDoc through its
  in-application API, offscreen; launch the benchmark's `java` with `renderdoccmd capture -c <file>`.
- `./gradlew :samples-demo:showcase -Pcrowd=<path to Fox.gltf>` is the effects showcase: 100 foxes roaming an arena at
  dusk (a third surveying, the rest walking and running on rings, animation level of detail by distance), every one
  kicking up dust where its feet come down and ten of them burning over their skinned meshes (upward-facing skin the
  most, through a mask baked from the mesh); a bonfire and eight swaying torches; a fireworks show of twelve launchers
  firing in waves, each burst a flash lighting the ground and the foxes; blasts throwing rocks that bounce and cast
  shadows, hail and sparks settling, swirls, a dust devil and gusting wind, ribbons and beams circling above. Fire is
  content only: flipbooks of flame tongues, embers and smoke puffs (with normals) made from noise when it starts, in
  layers (a hot core, tongues cooling from white to red as they rise, embers, lit smoke, a flickering particle light).
  `-Pout=<folder> -Pframes=900 -Pfirst=300 -Pevery=2 [-Psize=1920x1080] [-Pcamera=orbit|keys:t,x,y,z,tx,ty,tz;...|
  follow:fox,distance,height,side] [-Pstills=360,720]` writes tone-mapped PNG frames (60 Hz simulated, every second
  frame: 30 fps). `--args=measure` gives complete frame times in stereo at 2064 x 2208 per eye, offscreen without
  present: on a Ryzen 9 9950X3D and RX 9070 XT (2026-11-02), about 12,000 particles alive, a frame takes 2.79 ms from
  start to start (99th percentile 3.94, worst 4.08); the main thread 2.14 ms (scene update 0.24, animation 0.21,
  effects sync 0.28, recording and submitting the graph 1.42), the GPU 2.61 ms from its first pass to its last; 401 of
  1080 frames waited for the GPU.
- Keys, all input actions (`DemoActions`, rebound in the settings file as `input.demo.*`): `1`/`2` forward or
  visibility buffer shading, `H` HDR, `T` tone curve, `[`/`]` exposure, `V` present mode, `F`/`F11` window mode, `R`
  dynamic resolution, `-`/`=` render scale, `G` GPU-timed dynamic resolution (`,`/`.` its target), `O` occlusion
  culling, `N` a second window, `F5` or `Ctrl+S` save, `Escape` quit. A gamepad moves and looks with its sticks,
  rises and sinks with its shoulders and switches HDR, the tone curve, exposure and the render scale.

## Benchmarks

- `./gradlew :samples-bench:uiBench [-Pquick=true]` runs the UI's section 11 suite (GPU scenes from engine-ui's test
  fixtures' `UiScenes`, a streaming chart, the controls gallery idle, the console flooded) and writes
  [docs/benchmarks-ui.md](docs/benchmarks-ui.md).
- `./gradlew :samples-bench:profilerBench [-Pquick=true]` measures the profiler's overhead and writes
  [docs/benchmarks-profiler.md](docs/benchmarks-profiler.md).

## Building

Needs Java 27 and a Vulkan 1.3 driver, as the engine does.

```bash
./gradlew build                  # pull requests: everything but the soak tests
./gradlew build -Psoak=true      # with the soak tests (@Tag("soak")), as the nightly run
```

Sibling checkouts (`../engine`, `../scene`, `../ui`, `../build-logic`, `../common-*`) build with the samples as one
composite when present; `-Ptruesim.<repo>=<path>` points at another checkout (`-Ptruesim.engine=../engine-split1`),
`-Ptruesim.<repo>=false` takes the published artifact. Otherwise everything comes from GitHub Packages (set
`GITHUB_ACTOR` and `GITHUB_TOKEN`, or `gpr.user` and `gpr.key`). The engine version is in `build.gradle.kts`.

Sample content (glTF sample models, Sponza) is never committed: point `demo.assets` at a folder outside the repository,
or keep it under `assets/`, which git ignores.

## Licence

MIT, see [LICENSE](LICENSE).
