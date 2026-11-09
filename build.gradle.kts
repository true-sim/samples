// The versions the samples build on: one engine and one ui release for every sample.
val engineVersion by extra("0.201.0")
val sceneVersion by extra("0.70.0")
val uiVersion by extra("0.10.0")
val effectsParticlesVersion by extra("0.4.0")
val effectsAtmosphereVersion by extra("0.4.0")
val effectsWeatherVersion by extra("0.6.0")
val effectsPostVersion by extra("0.2.0")
val effectsTerrainVersion by extra("0.6.0")
val effectsVegetationVersion by extra("0.1.0")
val commonProceduralVersion by extra("0.11.0")
val effectsWaterVersion by extra("0.2.0")

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
