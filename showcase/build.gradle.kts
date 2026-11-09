plugins {
    id("truesim.application-conventions")
    id("truesim.slang")
}

description = "The true-sim showcase: an Alpine village over one day, every effect on, with Poly Haven's CC0 content."

val engineVersion = rootProject.extra["engineVersion"] as String
val sceneVersion = rootProject.extra["sceneVersion"] as String
val effectsParticlesVersion = rootProject.extra["effectsParticlesVersion"] as String
val effectsAtmosphereVersion = rootProject.extra["effectsAtmosphereVersion"] as String
val effectsWeatherVersion = rootProject.extra["effectsWeatherVersion"] as String
val effectsPostVersion = rootProject.extra["effectsPostVersion"] as String
val effectsTerrainVersion = rootProject.extra["effectsTerrainVersion"] as String
val effectsVegetationVersion = rootProject.extra["effectsVegetationVersion"] as String
val commonProceduralVersion = rootProject.extra["commonProceduralVersion"] as String

dependencies {
    implementation("com.truesim.engine:engine-scene:$engineVersion")
    implementation("com.truesim.engine:engine-input:$engineVersion") // the interactive app's actions
    implementation("com.truesim.engine:engine-platform:$engineVersion") // its window
    implementation("com.truesim.scene:scene-meshbuild:$sceneVersion")
    implementation("com.truesim.scene:scene-gltf:$sceneVersion")
    implementation("com.truesim.scene:scene-world:$sceneVersion")
    implementation("com.truesim.effects:effects-particles:$effectsParticlesVersion")
    implementation("com.truesim.effects:effects-particles-fireworks:$effectsParticlesVersion") // the night's show
    implementation("com.truesim.effects:effects-terrain:$effectsTerrainVersion") // Lauterbrunnen's real terrain
    implementation("com.truesim.effects:atmosphere-render:$effectsAtmosphereVersion")
    implementation("com.truesim.effects:weather-render:$effectsWeatherVersion")
    implementation("com.truesim.effects:weather-snow:$effectsWeatherVersion")
    implementation("com.truesim.effects:effects-post:$effectsPostVersion")
    implementation("com.truesim.effects:effects-vegetation:$effectsVegetationVersion") // GPU grass, plants in the wind
    implementation("com.truesim.common:common-procedural-assets:$commonProceduralVersion") // trees, shrubs, flowers
    implementation("com.truesim.common:common-procedural-ecology-terrain:$commonProceduralVersion") // which grow where
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17") // the engine's log (validation messages) on the console
}

// Every library's shading hooks (aerial perspective, fog, snow, the probe volume) in one listing: the libraries know
// nothing of each other, so the showcase rebuilds their shaders that shade with all of them. The particles' simulation
// is among those shaders and includes the particle modules' dispatch, which the build generates only for a project
// declaring modules of its own: the showcase's snowflakes flutter (src/main/slang/showcase/flutter.slang).
slang {
    shadingHooks = "com.truesim.samples.showcase.ShowcaseShadingHooks"
    particleModules = "com.truesim.samples.showcase.ShowcaseParticleModules"
}

application {
    mainClass = "com.truesim.samples.showcase.ShowcaseApp"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xmx6g")
}

// A program, not a library: nothing to cover.
coverage {
    minimum.set(0.0)
}

// Poly Haven's content (CC0) is fetched on demand, never committed: polyhaven.tsv pins every file (its URL and
// SHA-256), CREDITS.md credits them. ./gradlew :samples-showcase:fetchAssets downloads what the cache lacks or holds
// changed and checks each file (PolyHavenFetch); the cache is ~/.truesim/polyhaven unless -PpolyhavenCache=<dir> says
// otherwise, and the showcase reads it from there (the setting showcase.assets).
val polyhavenCache: String = providers.gradleProperty("polyhavenCache").orNull
    ?: "${System.getProperty("user.home")}/.truesim/polyhaven"

tasks.register<JavaExec>("fetchAssets") {
    group = "showcase"
    description = "Downloads the showcase's Poly Haven content (CC0) into the cache, checked against polyhaven.tsv."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.showcase.assets.PolyHavenFetch"
    args(layout.projectDirectory.file("polyhaven.tsv").asFile.absolutePath, polyhavenCache)
}

// Development: a first look at one frame (ShowcaseProbe), -Pout=<png> -Phour=12.5
tasks.register<JavaExec>("probe") {
    group = "showcase"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.showcase.ShowcaseProbe"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx6g")
    val settings = listOf("out", "hour", "quality", "gi", "shading").flatMap { name ->
        providers.gradleProperty(name).orNull?.let { listOf("--set", "showcase.$name=$it") } ?: listOf()
    } + (providers.gradleProperty("set").orNull?.split(",")?.filter { it.isNotBlank() }
        ?.flatMap { listOf("--set", it.trim()) } ?: listOf())
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// The tour (ShowcaseTour): the day filmed into PNG frames, or its frame times measured.
// ./gradlew :samples-showcase:tour -Pout=<folder> [-Psize=1920x1080] [-Psegments=dawn,noon] [-Pevery=2] [-Ppace=1]
// [-Pstills=12.5,40] [-Pquality=high] [-Pgi=probes] [-Pmeasure=true]; -Pset=name=value,... any other setting
fun showcaseSettings(vararg names: String): List<String> = names.flatMap { name ->
    providers.gradleProperty(name).orNull?.let { listOf("--set", "showcase.$name=$it") } ?: listOf()
} + (providers.gradleProperty("set").orNull?.split(",")?.filter { it.isNotBlank() }
    ?.flatMap { listOf("--set", it.trim()) } ?: listOf())

tasks.register<JavaExec>("tour") {
    group = "showcase"
    description = "Renders the showcase's day as a film of PNG frames, or measures its frame times per segment."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.showcase.ShowcaseTour"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xmx8g")
    val settings = showcaseSettings("out", "size", "segments", "every", "pace", "stills", "quality", "gi", "shading",
        "measure", "assets", "terrain", "procedural", "sunShadows", "skip")
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// The interactive app (ShowcaseApp): ./gradlew :samples-showcase:run [-Phour=7] [-Pweather=fair] [-Pquality=ultra]
// [-Pgi=cascades] [-Pseconds=n]
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xmx8g")
    val settings = showcaseSettings("hour", "weather", "quality", "gi", "shading", "seconds", "assets", "terrain",
        "procedural", "sunShadows", "skip")
    argumentProviders.add(CommandLineArgumentProvider { settings })
}

// Development: a shaded relief map of the terrain site round the origin (TerrainMap), heights at points.
// ./gradlew :samples-showcase:terrainMap -Phalf=3000 -Pout=<png> -Ppoints="x,z x,z"
tasks.register<JavaExec>("terrainMap") {
    group = "showcase"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.samples.showcase.world.TerrainMap"
    val cache = providers.gradleProperty("terrainCache").orNull ?: "${System.getProperty("user.home")}/.truesim/terrain"
    args(listOf(cache, "lauterbrunnen", providers.gradleProperty("half").orNull ?: "3000",
        providers.gradleProperty("out").orNull ?: "terrain.png") +
        (providers.gradleProperty("points").orNull?.split(" ")?.filter { it.isNotBlank() } ?: listOf()))
}

// Lauterbrunnen's elevation data (effects-terrain's pinned manifest: swissALTI3D, Copernicus GLO-30 and GLO-90),
// fetched and imported into effects-terrain's cache (~/.truesim/terrain, -PterrainCache=<dir>), never committed; the
// showcase reads it from there (the setting showcase.terrain). Without it the village stands in a made-up valley.
tasks.register<JavaExec>("fetchTerrain") {
    group = "showcase"
    description = "Fetches and imports the Lauterbrunnen terrain site into effects-terrain's cache."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.effects.terrain.data.FetchTerrain"
    args("lauterbrunnen", providers.gradleProperty("terrainCache").orNull
        ?: "${System.getProperty("user.home")}/.truesim/terrain")
}
