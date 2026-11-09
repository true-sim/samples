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

## The showcase

`showcase` (module `samples-showcase`) is the full demo: an Alpine village over one day in Lauterbrunnen, every
effect of the engine and its libraries on at once, in physical units from noon to a moonlit night.

- **Content, fetched on demand.** Poly Haven's CC0 textures (4K where the camera comes close: the square's
  cobbles, the walls, the planks, the roofs, the café's parquet; 2K elsewhere) and models (street lamps, café
  furniture, a mirror, barrels, crates, benches, rocks; bark, a rock face for the terrain's cliffs), pinned in
  [showcase/polyhaven.tsv](showcase/polyhaven.tsv) (URL, size, SHA-256) and credited in
  [showcase/CREDITS.md](showcase/CREDITS.md): `./gradlew :samples-showcase:fetchAssets` downloads what the cache
  (`~/.truesim/polyhaven`) lacks and checks every file. The terrain is effects-terrain's Lauterbrunnen site (swissALTI3D
  2 m, Copernicus GLO-30/90 to the horizon): `./gradlew :samples-showcase:fetchTerrain` fills its cache
  (`~/.truesim/terrain`). Nothing downloaded is committed; without the caches the village stands in plain colours in
  a valley made from functions. Everything loads through the one asset manager (`ShowcaseAssets`: `ph://`,
  `terrain://`, the star catalogue, post-processing's tables) and is drawn through the scene.
- **The village**, made in code on the meadow below the Staubbach falls: chalets of stone and weathered planks with
  wide eaves, balconies, shutters and chimneys, their ground floors rooms seen through framed glass (a hearth with a
  fire, an armchair, a lamp; an indoor zone keeps the outdoor fog out), a café on a cobbled square with tall shop
  windows, a polished parquet floor, a wall mirror and Poly Haven's ornate one (screen-space reflections, the probes'
  and the environment's behind them), a church with a bell tower and spire, street lamps, lanterns, a gravel road,
  fences and rocks. Placement asks the ground (`Ground`: effects-terrain's `heightAt`, or the made-up valley), never a
  constant.
- **The vegetation** (`Flora`): effects-vegetation's GPU grass over the valley floor (a `GrassField` on a ground map
  cleared from the square, the buildings and the road, swaying in the day's wind, pressed by the dusk's snow), and
  common-procedural's trees, shrubs, dwarf shrubs and flowers where its ecology puts them on Lauterbrunnen's terrain
  (`Ecology` over effects-terrain's surface, meadows kept open below the forest; 21 species, three variants each,
  generated for late September: rowan berries, ripe apples, the first leaves turning), one `PlantScatter` per species
  and variant holding the ecology's instances. Trees are cluster hierarchies rebuilt from the generator's level 1
  (`FloraModels`); their leaf atlases are drawn in code, their bark Poly Haven's. Generated meshes are cached in
  `~/.truesim/procedural` (`-Pprocedural=<dir>`).
- **Streaming** through scene-world: the terrain's own pyramid of tiles to 120 km, 64 m cells of buildings, lamps and
  lights within 3 km and 32 m cells of small props within 250 m, one world, interest following the camera.
- **The day** (`DayDriver`, 26 September 2026, a full moon): the sun, the moon and the stars from effects-atmosphere's
  ephemeris for Lauterbrunnen; effects-weather's presets (fair, scattered cumulus with their shadows, rain under a
  storm cell with lightning, broken cloud at dusk, clear), the weather's ground the valley floor
  (`WeatherDomain.flat(ground)`); height fog and the valley's mist in fog volumes at dawn; snow falling (lit flakes that
  flutter, `showcase.flutter`, a particle module of the showcase's own) and settling (`weather.snow`); rain; lamps,
  rooms and hearths coming on; effects-particles-fireworks' show at night. Light is in the atmosphere's scene units:
  the EV100 pre-exposure follows the day's light (eased), effects-post's histogram auto exposure takes the units
  (`luminanceScale`, Krawczyk's key, -1.5 stops) as both libraries document; lamps, glowing glass and flames are set
  from cd and cd/m² every frame.
- **The chain** (`ShowcaseRenderer.view`): forward shading (or the visibility buffer, `-Pshading=visibility`),
  sorted transparency with glass, the captured sky's light, clustered lights with PCSS-soft shadows (the sun's
  four cascades), ambient occlusion, screen-space reflections, global illumination (the probe volume, or radiance
  cascades with `-Pgi=cascades`), particles, the Hillaire sky with aerial perspective, volumetric fog in froxels,
  snow, volumetric clouds with their shadow and lightning, then depth of field, bloom, a natural vignette, grading,
  auto exposure, AgX (punchy) and subtle film grain. One shading-hook listing (`ShowcaseShadingHooks`) rebuilds the
  libraries' shaders with every hook (the probes, aerial perspective, fog, snow) and the terrain's material.
- **Two ways to run.** `./gradlew :samples-showcase:run [-Phour=7] [-Pweather=fair] [-Pquality=ultra]` flies
  freely: W A S D, Q E, the right mouse button to look; `[` `]` the hour, `T` a time-lapse, `1`-`8` the weather, `B`
  snow, `M` mist, `F` fireworks, `G` the global illumination, `P` the quality preset, `J` depth of field.
  `./gradlew :samples-showcase:tour -Pout=<folder> [-Psize=1920x1080] [-Psegments=dawn,noon]` renders the scripted
  tour (`TourScript`: dawn, noon, storm, dusk, night; spline cameras low through the meadow, into the square,
  through the café's door to its mirror, up the Staubbach cliff, towards the Jungfrau, the fireworks) as PNG frames at
  60 Hz simulated, every second one written (30 fps; `ffmpeg -framerate 30 -i f_%05d.png ...` by hand);
  `-Pmeasure=true` without `-Pout` reports each segment's frame times instead.
- **What it costs** (`-Pmeasure=true`; RX 9070 XT, Ryzen 9 9950X3D, 2026-11-09, quality high, probe GI, forward
  shading, the Lauterbrunnen terrain and its vegetation; the tour at 60 Hz simulated, two frames in flight, nothing presented; ms, mean
  and 99th percentile of each segment after its first second; GPU from the first pass to the last). The frames are
  GPU-bound: the main thread's time is mostly waiting for a frame slot.

  | Segment | 1920 x 1080 GPU | wall | 3840 x 2160 GPU | wall |
  |---|---|---|---|---|
  | dawn (mist, low sun, alpenglow) | 90.6 / 114.8 | 93.8 / 115.3 | 146.0 / 188.8 | 149.6 / 189.8 |
  | noon (cumulus, Jungfrau, square, café) | 89.7 / 105.4 | 92.2 / 105.7 | 123.4 / 158.9 | 126.2 / 159.2 |
  | storm (nimbostratus, rain, lightning) | 76.8 / 102.6 | 79.9 / 103.0 | 124.7 / 164.0 | 128.0 / 164.6 |
  | dusk (snowfall, lamps, fires) | 81.3 / 103.1 | 82.8 / 103.4 | 119.5 / 149.0 | 121.2 / 149.9 |
  | night (stars, moon, fireworks) | 103.5 / 240.4 | 105.1 / 243.9 | 194.3 / 476.1 | 196.0 / 478.0 |

  The vegetation dominates since it came in (the frames took 16 to 20 ms at 1080p without it): some 32,000 trees
  and as many shrubs, dwarf shrubs and flowers, all always in the scene (scatters following the viewer hit
  true-sim/effects-vegetation#2), the trees cluster hierarchies from the generator's level 1 without impostors for
  the far forest (true-sim/common-procedural#11), and the GPU grass. Each segment's first frames after a cut stall up
  to 3.2 s (pipelines made as new effects first show). The night's fireworks are the heaviest effect: hundreds of
  burst lights and their smoke lit per vertex.

## Benchmarks

- `./gradlew :samples-bench:uiBench [-Pquick=true]` runs the UI's section 11 suite (GPU scenes from engine-ui's test
  fixtures' `UiScenes`, a streaming chart, the controls gallery idle, the console flooded) and writes
  [docs/benchmarks-ui.md](docs/benchmarks-ui.md).
- `./gradlew :samples-bench:profilerBench [-Pquick=true]` measures the profiler's overhead and writes
  [docs/benchmarks-profiler.md](docs/benchmarks-profiler.md).

## Building

Needs Java 27 and a Vulkan 1.3 driver, as the engine does.

```bash
./gradlew build                      # pull requests: everything but the soak tests
TRUESIM_SOAK=true ./gradlew build    # with the soak tests (StreamBenchMemoryTest, the gallery's mouse sweep): nightly
```

Sibling checkouts (`../engine`, `../scene`, `../ui`, `../build-logic`, `../common-*`) build with the samples as one
composite when present; `-Ptruesim.<repo>=<path>` points at another checkout (`-Ptruesim.engine=../engine-split1`),
`-Ptruesim.<repo>=false` takes the published artifact. Otherwise everything comes from GitHub Packages (set
`GITHUB_ACTOR` and `GITHUB_TOKEN`, or `gpr.user` and `gpr.key`). The engine version is in `build.gradle.kts`.

Sample content (glTF sample models, Sponza) is never committed: point `demo.assets` at a folder outside the repository,
or keep it under `assets/`, which git ignores.

## Licence

MIT, see [LICENSE](LICENSE).
