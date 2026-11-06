pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.15.0"
}

rootProject.name = "samples"

include("samples-demo", "samples-bench")
project(":samples-demo").projectDir = file("demo")
project(":samples-bench").projectDir = file("bench")

// Sibling checkouts build with these as one composite (a change across repos at once): ../engine, ../scene, ../ui and
// the rest when present; -Ptruesim.<repo>=<path> points at another checkout (-Ptruesim.engine=../engine-split1),
// -Ptruesim.<repo>=false uses the published artifact.
val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "ui",
    "engine")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
