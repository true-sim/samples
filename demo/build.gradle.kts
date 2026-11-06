plugins {
    id("truesim.application-conventions")
}

description = "The true-sim demo: a scene to try settings, HDR, dynamic resolution, several windows and VR."

val engineVersion: String by rootProject.extra
val sceneVersion: String by rootProject.extra
val uiVersion: String by rootProject.extra
val effectsParticlesVersion: String by rootProject.extra

dependencies {
    implementation("com.truesim.engine:engine-xr:$engineVersion")
    implementation("com.truesim.engine:engine-scene:$engineVersion")
    implementation("com.truesim.effects:effects-particles:$effectsParticlesVersion") // the demo's particle effects
    implementation("com.truesim.engine:engine-audio:$engineVersion")
    implementation("com.truesim.scene:scene-meshbuild:$sceneVersion")
    implementation("com.truesim.scene:scene-gltf:$sceneVersion")
    // The UI gallery (UiGallery): the engine's UI over the galleries of ui's test fixtures
    implementation("com.truesim.engine:engine-ui:$engineVersion") // the UI gallery, and the stream window's profiler
    implementation("com.truesim.ui:ui-charts:$uiVersion")
    implementation("com.truesim.ui:ui-text-native:$uiVersion")
    implementation("com.truesim.ui:ui-fonts:$uiVersion")
    implementation("com.truesim.ui:ui-fonts-cjk:$uiVersion")
    implementation("com.truesim.ui:ui-fonts-emoji:$uiVersion")
    implementation(testFixtures("com.truesim.ui:ui-controls:$uiVersion"))
    implementation(testFixtures("com.truesim.ui:ui-charts:$uiVersion"))
    implementation(testFixtures("com.truesim.ui:ui-props:$uiVersion"))
    implementation(testFixtures("com.truesim.ui:ui-profiler:$uiVersion"))
    testImplementation(testFixtures("com.truesim.engine:engine-render:$engineVersion")) // TestGpu, ReferenceImages
    testImplementation("org.lwjgl:lwjgl-sdl") // the gallery's test pushes mouse and key events through SDL
}

application {
    mainClass = "com.truesim.samples.demo.Demo"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The samples' options are settings (DemoSettings), given as --set name=value after the program's own arguments.
// The -P properties below become those; a model named by file (-Pcharacter=<path to Fox.gltf>) becomes its folder
// mounted as demo:// and the model's asset path in it. Providers, not args: --args replaces the task's args.
// -Pset=name=value[,name=value...]: any setting, the engine's too, as --set on the command line (e.g.
// -Pset=render.interpolation=off)
fun anySettings(): List<String> = providers.gradleProperty("set").orNull?.split(",")?.filter { it.isNotBlank() }
    ?.flatMap { listOf("--set", it.trim()) } ?: listOf()

fun settingsOf(vararg names: String): List<String> = names.flatMap { name ->
    providers.gradleProperty(name).orNull?.let { value ->
        if (name == "character" || name == "crowd") {
            val model = file(value)
            listOf("--set", "demo.assets=${model.parentFile.absolutePath}", "--set", "demo.$name=demo://${model.name}")
        } else {
            listOf("--set", "demo.$name=$value")
        }
    } ?: listOf()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC") // generational ZGC: pauses the audio queue covers
    // -Pcharacter=<glTF-Sample-Assets>/Models/Fox/glTF/Fox.gltf: an animated fox among the rocks
    val settings = settingsOf("character", "rocks", "lights")
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// A demo to run, not a library: nothing to cover.
coverage {
    minimum.set(0.0)
}

// Where a stereo frame's GPU time goes (StereoBench): ./gradlew :samples-demo:stereoBench [--args="visibility"]
tasks.register<JavaExec>("stereoBench") {
    group = "application"
    description = "Times the demo's scene rendered for two eyes, per pass."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.demo.StereoBench"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // -Peffect=x,y,z: the demo's particle effects at a point (g+h: h above the ground); -Plights=1024: that many point
    // lights, the circling six and the rest scattered near the middle; -Pfoxes=1000 -Pcrowd=<path to Fox.gltf>
    // [-PfoxLod=true]: a crowd of animated foxes, what animating it costs; -Peye, -Ppng, -PpngFrames, -Pframes,
    // -PcasterDetail, -Procks, -PrebuildEvery: the settings demo.eye and the others
    // -Pset=name=value,...: any other setting (-Pset=render.interpolation=off)
    val settings = settingsOf("rocks", "png", "casterDetail", "character", "eye", "pngFrames", "frames", "effect",
        "lights", "foxes", "crowd", "foxLod", "rebuildEvery") + anySettings()
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// Hitches while content streams (StreamBench): ./gradlew :samples-demo:streamBench
// [--args=window]: the same fly-over in a window with the profiler (F3, Shift+F3), until closed or -Pseconds=n
// [--args=baseline]: the hand-written path the scene way replaced, as the comparison (with window too: "window baseline")
// -Prange=n: cells in range each way (default 3, the window 10); -Pspeed=n: metres a second (10, the window 60)
// -Pframes=n: frames headless (2700); -Pmemory=n: a memory line every n frames. Each is a setting (demo.range, ...),
// passed as --set demo.range=n after the --args.
tasks.register<JavaExec>("streamBench") {
    group = "application"
    description = "Streams cells of meshes, textures and materials while frames run, and reports frame times."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.demo.StreamBench"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    providers.gradleProperty("jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    // providers, not args: --args replaces the task's args, and these come on top of it
    val settings = listOf("seconds", "range", "speed", "frames", "memory").flatMap { name ->
        providers.gradleProperty(name).orNull?.let { listOf("--set", "demo.$name=$it") } ?: listOf()
    } + anySettings() // -Pset=render.streamFrameBudget=0.5,...
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// The effects showcase (Showcase): ./gradlew :samples-demo:showcase -Pcrowd=<path to Fox.gltf> [--args=measure]
tasks.register<JavaExec>("showcase") {
    group = "application"
    description = "A hundred foxes, fire, fireworks and debris at once: frames to PNG files, or frame times measured."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.demo.Showcase"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    val settings = settingsOf("crowd", "character", "foxes", "rocks", "camera", "size", "frames", "first", "every",
        "out", "stills", "exposure", "torches", "sparks", "async")
    argumentProviders.add(CommandLineArgumentProvider { settings })
    // -Pglass=true: windows before the bonfire; a system property, read as the effects' constants are made
    providers.gradleProperty("glass").orNull?.let { systemProperty("truesim.demo.glass", it) }
}

// The UI gallery (UiGallery): ./gradlew :samples-demo:uiGallery -- every gallery of the UI in a window, a 3D scene with
// picking, selection and gizmos, and the live profiler (F3, Shift+F3) over the app's own frames
tasks.register<JavaExec>("uiGallery") {
    group = "application"
    description = "Opens the UI gallery: every control gallery, docking, the console, the profiler and a 3D scene."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.demo.UiGallery"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xmx2g")
    systemProperty("slf4j.provider", "com.truesim.engine.ui.console.ConsoleLogProvider") // the log in the console
    // -Pseconds=n quits after n seconds; -Ptour=true visits every page and opens the profiler's views (a smoke run)
    providers.gradleProperty("seconds").orNull?.let { args("--seconds=$it") }
    providers.gradleProperty("tour").orNull?.let { if (it == "true") args("--tour") }
    // -Pshots=<dir>: during a tour, what the window shows of each page as PNGs (the setting gallery.shots)
    providers.gradleProperty("shots").orNull?.let { args("--set", "gallery.shots=$it") }
    // -Procks=n: rocks in the 3D scene (the setting gallery.rocks)
    providers.gradleProperty("rocks").orNull?.let { args("--set", "gallery.rocks=$it") }
}
