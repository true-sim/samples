// The versions the samples build on: one engine and one ui release for every sample.
val engineVersion by extra("0.187.0")
val sceneVersion by extra("0.68.0")
val uiVersion by extra("0.10.0")
val effectsParticlesVersion by extra("0.1.0")

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
