plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build the Rust compositor for one or both Android ABIs and copy the
// resulting .so into jniLibs/. Override with `-PtawcAbis=arm64-v8a[,x86_64]`.
val tawcAbis: List<String> = (project.findProperty("tawcAbis") as String?
    ?: "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Server-side Mesa GLES stack. Enabled for dev builds; release builds
// default it off. `-PtawcMesaServer=true|false` overrides either default.
val buildingRelease = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
val mesaServerBuildEnabled: Boolean = (project.findProperty("tawcMesaServer") as String?)
    ?.trim()?.lowercase()
    ?.let { it !in setOf("0", "false", "no", "off") }
    ?: !buildingRelease

fun booleanProjectPropertyOrNull(name: String): Boolean? {
    val raw = project.findProperty(name) as String? ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> error("Invalid $name=$raw (expected true or false)")
    }
}

// Debug builds are me.phie.tawc.dev so they install beside a
// release-signed me.phie.tawc; `-PtawcAppIdSuffix=` (empty) restores the
// plain ID.
val tawcAppIdSuffix: String = (project.findProperty("tawcAppIdSuffix") as String?) ?: ".dev"

android {
    namespace = "me.phie.tawc"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "me.phie.tawc"
        minSdk = 29
        targetSdk = 36
        // Plain release counter, single source of truth; see notes/release.md.
        versionName = "6"
        versionCode = versionName!!.toInt()
        ndk {
            abiFilters.addAll(tawcAbis)
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = tawcAppIdSuffix
            buildConfigField("boolean", "GRAPHICS_MESA_SERVER_ENABLED", "$mesaServerBuildEnabled")
            buildConfigField("boolean", "TINT_BUFFERS_BY_TYPE_DEFAULT", "true")
        }
        getByName("release") {
            // R8 keeps names readable (see proguard-rules.pro) while
            // shrinking the app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "GRAPHICS_MESA_SERVER_ENABLED", "$mesaServerBuildEnabled")
            buildConfigField("boolean", "TINT_BUFFERS_BY_TYPE_DEFAULT", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Keep native libraries on disk as real files for dlopen, and keep
    // symbols so device tombstones are readable.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }

    if (!mesaServerBuildEnabled) {
        androidResources {
            ignoreAssetsPatterns.add("mesa-server")
        }
    }
}

dependencies {
    // Extract the server Mesa asset tar in-process.
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

val rustTripleFor = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

// Map an Android ABI to the (script flag, builddir name) pair
// `scripts/build-libxkbcommon.sh` uses.
val xkbForAbi = mapOf(
    "arm64-v8a" to ("aarch64" to "builddir"),
    "x86_64" to ("x86_64" to "builddir-x86_64"),
)

val tawcRootForSmithay = rootProject.projectDir
val smithayCargoToml = "$tawcRootForSmithay/deps/smithay/Cargo.toml"

// Working-tree fingerprint (HEAD + tracked-edit hash) for the named deps.
fun depTreeState(vararg deps: String): Provider<String> = providers.exec {
    workingDir(rootDir)
    commandLine(listOf("scripts/ensure-deps.sh", "--tree-state") + deps)
}.standardOutput.asText

fun reproQuery(flag: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("scripts/lib/repro.sh", flag)
}.standardOutput.asText.get().trim()
val sourceDateEpoch: String = reproQuery("--epoch")

tasks.withType<Exec>().configureEach {
    environment("SOURCE_DATE_EPOCH", sourceDateEpoch)
}

// Ensure the smithay checkout exists before cargo runs.
val setupSmithayTask = tasks.register<Exec>("setupSmithay") {
    workingDir = tawcRootForSmithay
    commandLine(
        "scripts/ensure-deps.sh",
        "--patches", "smithay", "deps/smithay-patches/smithay",
    )
    inputs.file("$tawcRootForSmithay/scripts/ensure-deps.sh")
    inputs.file("$tawcRootForSmithay/deps/deps.list")
    inputs.file("$tawcRootForSmithay/scripts/lib/deps.sh")
    inputs.dir("$tawcRootForSmithay/deps/smithay-patches")
    outputs.file(smithayCargoToml)
}

// The compositor's Cargo.toml still references the patched rutabaga
// checkout by path, so it must exist before cargo parses the manifest.
val rutabagaPatchSentinel = "$tawcRootForSmithay/deps/rutabaga_gfx/kumquat/server/src/lib.rs"
val setupRutabagaTask = tasks.register<Exec>("setupRutabaga") {
    workingDir = tawcRootForSmithay
    commandLine("scripts/ensure-deps.sh", "--patches", "rutabaga_gfx", "deps/rutabaga-patches/rutabaga_gfx")
    inputs.file("$tawcRootForSmithay/scripts/ensure-deps.sh")
    inputs.dir("$tawcRootForSmithay/deps/rutabaga-patches")
    inputs.file("$tawcRootForSmithay/deps/deps.list")
    inputs.file("$tawcRootForSmithay/scripts/lib/deps.sh")
    outputs.file(rutabagaPatchSentinel)
}

tawcAbis.forEach { abi ->
    val triple = rustTripleFor[abi] ?: error("Unsupported ABI: $abi")
    val capAbi = abi.replaceFirstChar { it.uppercase() }
    val tawcRoot = rootProject.projectDir

    // Cross-build the static libxkbcommon the Rust compositor links.
    val (xkbAbiFlag, xkbBuilddir) = xkbForAbi[abi]
        ?: error("Unsupported ABI for libxkbcommon: $abi")
    val xkbStaticLib = "$tawcRoot/deps/libxkbcommon/$xkbBuilddir/libxkbcommon.a"
    val buildLibxkbcommonTask = tasks.register<Exec>("buildLibxkbcommon$capAbi") {
        workingDir = tawcRoot
        environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
        commandLine("scripts/build-libxkbcommon.sh", "--abi=$xkbAbiFlag")
        inputs.file("$tawcRoot/scripts/build-libxkbcommon.sh")
        inputs.file("$tawcRoot/deps/deps.list")
        inputs.file("$tawcRoot/scripts/lib/deps.sh")
        inputs.property("depTreeState", depTreeState("libxkbcommon"))
        outputs.file(xkbStaticLib)
    }

    val buildTask = tasks.register<Exec>("buildRustLibrary$capAbi") {
        dependsOn(buildLibxkbcommonTask, setupSmithayTask, setupRutabagaTask)
        workingDir = file("${rootProject.projectDir}/compositor")
        environment("ANDROID_NDK_HOME", "${android.ndkDirectory}")
        commandLine(
            "cargo", "ndk",
            "--target", abi,
            "--platform", "29",
            "--",
            "build", "--release", "--no-default-features",
        )
        inputs.files(
            "${rootProject.projectDir}/compositor/Cargo.toml",
            "${rootProject.projectDir}/compositor/Cargo.lock",
            "${rootProject.projectDir}/compositor/build.rs",
            "$tawcRoot/deps/deps.list",
            xkbStaticLib,
        )
        inputs.dir("${rootProject.projectDir}/compositor/src")
        inputs.dir("${rootProject.projectDir}/compositor/protocols")
        inputs.dir("${rootProject.projectDir}/compositor/native")
        inputs.files(fileTree("$tawcRoot/deps/smithay") {
            exclude(".git/**", "target/**")
        })
        outputs.file("${rootProject.projectDir}/compositor/target/$triple/release/libcompositor.so")
    }

    val copyTask = tasks.register<Copy>("copyRustLibrary$capAbi") {
        dependsOn(buildTask)
        from("${rootProject.projectDir}/compositor/target/$triple/release/libcompositor.so")
        into("src/main/jniLibs/$abi/")
    }

    tasks.named("preBuild") {
        dependsOn(copyTask)
    }

    // Server-side Mesa GLES stack (private libEGL/libGLESv2 + Zink, Turnip
    // ICD and loader shim). aarch64 only; x86_64 keeps the vendor renderer.
    val mesaServerEnabled = mesaServerBuildEnabled && abi == "arm64-v8a"
    val mesaServerTar = "$tawcRoot/build/mesa-server-aarch64/mesa-server.tar"
    val mesaServerAssetDir = "src/main/assets/mesa-server/$abi"
    if (mesaServerEnabled) {
        val buildMesaServerTask = tasks.register<Exec>("buildMesaServer$capAbi") {
            workingDir = tawcRoot
            commandLine("bash", "scripts/build-mesa-server.sh")
            inputs.file("$tawcRoot/scripts/build-mesa-server.sh")
            inputs.files(
                "$tawcRoot/compositor/native/mesa_ahb.c",
                "$tawcRoot/compositor/native/vulkan_shim.c",
            )
            inputs.dir("$tawcRoot/deps/mesa-patches")
            inputs.file("$tawcRoot/deps/deps.list")
            inputs.file("$tawcRoot/scripts/lib/deps.sh")
            inputs.property("depTreeState", depTreeState("mesa"))
            outputs.file(mesaServerTar)
        }
        val packMesaServerTask = tasks.register<Copy>("packMesaServer$capAbi") {
            dependsOn(buildMesaServerTask)
            into("${project.projectDir}/$mesaServerAssetDir")
            from(mesaServerTar) {
                rename { "mesa-server.tar" }
            }
        }
        tasks.named("preBuild") { dependsOn(packMesaServerTask) }
    } else {
        tasks.register<Delete>("packMesaServer$capAbi") {
            delete("${project.projectDir}/$mesaServerAssetDir")
        }
        tasks.named("preBuild") { dependsOn("packMesaServer$capAbi") }
    }
}
