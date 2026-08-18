import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Baked-in TMDB v4 read token so the app works out-of-the-box without entering it.
// Priority: local.properties `TMDB_TOKEN` > `TMDB_TOKEN` env var > the bundled default
// below. NOTE: the bundled token below is committed to the repo — it is a *read-only*
// TMDB token (scope: api_read). Rotate it on themoviedb.org if you don't want it public,
// or override it via local.properties (git-ignored).
val bundledTmdbToken =
    "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJmN2MwZGJkMjg0NzI1NGRlMmExYmE5ODQ0MDYwZGQ4NCIsIm5iZiI6MTc3ODc5NDkwMy43NjcsInN1YiI6IjZhMDY0MTk3MWJkYmI1OTNkNzIwMjI0MiIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.HydrwkZvHc2OIXbCqnqe9sNhHsLt_FXhf98VqI7j4gQ"
val tmdbDefaultToken: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    (props.getProperty("TMDB_TOKEN") ?: System.getenv("TMDB_TOKEN") ?: bundledTmdbToken).trim()
}

// API keys. Priority: local.properties > env var > bundled default. Prefer
// putting real keys in local.properties (git-ignored) instead of committing them.
fun apiKeyProp(name: String, bundledDefault: String = ""): String {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    return (props.getProperty(name) ?: System.getenv(name) ?: bundledDefault).trim()
}
// MDBList ratings key — committed default (a rate-limited ratings key; rotate on
// mdblist.com or override via local.properties `MDBLIST_KEY` if you want it private).
val mdblistDefaultKey = apiKeyProp("MDBLIST_KEY", "uatgtmuqbig5mlw92tl10fdld")
// Gemini key — deliberately NOT committed. Supply via local.properties `GEMINI_KEY`
// (git-ignored) or enter it in the in-app Settings screen.
val geminiDefaultKey = apiKeyProp("GEMINI_KEY", "")

android {
    namespace = "com.streambert.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streambert.tv"
        minSdk = 23          // Android TV / Google TV minimum
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "TMDB_DEFAULT_TOKEN", "\"$tmdbDefaultToken\"")
        buildConfigField("String", "MDBLIST_DEFAULT_KEY", "\"$mdblistDefaultKey\"")
        buildConfigField("String", "GEMINI_DEFAULT_KEY", "\"$geminiDefaultKey\"")

        // Ship native libraries (mainly libmpv) ONLY for the CPU architectures
        // real Android TV / Google TV devices use. Dropping x86/x86_64 — which
        // essentially only exist on emulators — roughly halves the bundled .so
        // payload and shrinks the APK noticeably. (ExoPlayer, the default engine,
        // needs no native libs, so ARM-only never affects normal playback.)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 2024.12.01 == Compose 1.7.6. Bumped from 2024.09.02 (1.7.2) to pick up the
    // focus-system fixes for the "Dispatching intercepted soft keyboard event while
    // focus system is invalidated" crash — a 1.7.x regression triggered by
    // Modifier.focusRestorer() across nested lazy containers (our LazyColumn of
    // LazyRows) on a D-pad key press.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Compose for TV
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // MPV (libmpv) native player engine — bundles the is.xyz.mpv classes AND the
    // native .so binaries (arm64/armeabi/x86), so no NDK build is needed. This is
    // the same artifact NuvioTV uses. MPV handles the Dolby Vision / HEVC 10-bit /
    // DTS / TrueHD content that black-screens on plain ExoPlayer.
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Addon system: embedded HTTP server (QR phone-management) + QR code generation
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}
