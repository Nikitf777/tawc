plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Debug builds are me.phie.tawc.dev so they install beside a
// release-signed me.phie.tawc; `-PtawcAppIdSuffix=` (empty) restores the
// plain ID.
val tawcAppIdSuffix: String = (project.findProperty("tawcAppIdSuffix") as String?) ?: ".dev"

android {
    namespace = "me.phie.tawc"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.phie.tawc"
        minSdk = 29
        targetSdk = 36
        // Plain release counter, single source of truth; see notes/release.md.
        versionName = "6"
        versionCode = versionName!!.toInt()
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = tawcAppIdSuffix
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
        }
    }

    buildFeatures {
        buildConfig = true
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

    // Keep native libraries on disk as real files for dlopen, and keep
    // symbols so device tombstones are readable.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }
}

dependencies {
    implementation(project(":compositor-android"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
