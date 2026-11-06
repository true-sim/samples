// The versions the samples build on: one engine and one ui release for every sample.
val engineVersion by extra("0.186.0")
val sceneVersion by extra("0.66.0")
val uiVersion by extra("0.10.0")

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
