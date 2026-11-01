plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

// Fail every build when any existing dep checkout drifted from its pin in
// deps/deps.list. Never up-to-date: out-of-band drift (HEAD moved, manifest
// unchanged) leaves no Gradle-visible input, so caching would mask it.
// Root-level and hooked into every module's preBuild so building a
// termux-derived module alone still verifies — with a reused configuration
// cache the settings-eval `dep_ensure termux-app` doesn't run, and an
// :app-only task wouldn't be in that task graph.
val verifyDepsTask = tasks.register<Exec>("verifyDeps") {
    workingDir = rootDir
    commandLine("scripts/ensure-deps.sh", "--verify-all")
    outputs.upToDateWhen { false }
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(verifyDepsTask)
    }
}
