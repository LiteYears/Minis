import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Build-time customization values that must NOT ship in the public
// open-source mirror. `provider-customization.properties` is tracked in the PRIVATE
// MinisApp repo (with real values) and listed under `private:` in
// PUBLISH_MANIFEST.yml so it is never synced; the public repo ships only
// `provider-customization.properties.example` (empty values). A build without a
// configured value compiles fine but fails at runtime the first time the
// value is required (see ClaudeOAuthManager). See docs/PUBLISH_POLICY.md.
val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.openminis.app"
    // [T-android-dynamic-island] Bumped 35→36 so the Android 16 (Baklava)
    // Live Updates APIs — Notification.ProgressStyle, FLAG_PROMOTED_ONGOING,
    // NotificationManager.canPostPromotedNotifications(), setShortCriticalText —
    // are available to compile against. targetSdk stays 35 to avoid pulling in
    // Android 16 behavior changes; the Live Updates path is runtime-gated on
    // Build.VERSION.SDK_INT >= 36 (see DynamicIslandSupport / AgentForegroundService).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openminis.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // DEV_TOOLS gates the on-device developer tooling (127.0.0.1:5321
        // debug server, LLM request log, StreamJitterProbe markers). It is
        // true for `debug` AND for the `perf` build type below, false for
        // `release`. Kept separate from BuildConfig.DEBUG so a non-debuggable
        // build can still carry the tooling.
        buildConfigField("boolean", "DEV_TOOLS", "false")

        // System prompt prefix required by Anthropic for Claude Code OAuth
        // credentials. Empty in the public mirror (see provider-customization.properties).
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        // [T15-channels] In-app update. Every build and every update channel
        // checks GitHub Releases of this one repository: stable reads only
        // prerelease=false, beta also reads the prereleases, which are tagged
        // `0-beta-build<N>` so that 1.12-1.15 clients (which ignore the
        // prerelease flag and cut a tag at its first '-') see them as version
        // 0 and never offer them. UPDATE_CHANNEL is the channel a fresh
        // install starts on; the user can switch to beta in About.
        buildConfigField("String", "RELEASE_REPO", "\"OpenMinis/OpenMinis\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")

        buildConfigField("boolean", "SELF_UPDATE", "true")
        buildConfigField("boolean", "AUTO_UPDATE_CHECK", "true")
        buildConfigField("boolean", "IN_APP_INSTALL", "true")

    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEV_TOOLS", "true")
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    // [T15-channels] Where a build is distributed. Same applicationId, same
    // signing and same versionCode for both, so either installs over the other.
    // - github: the APK attached to GitHub Releases. Checks for updates on its
    //   own (ON_START, throttled) and shows the update badges.
    // - store: the app-store build (AAB). Never checks on its own and never
    //   shows a badge; About's manual "Check for Updates" still works and
    //   downloads + installs from GitHub like the github build.

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

    androidResources {
        noCompress += listOf("tar.gz", "proot-aarch64")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // [T-android-downgrade-compat] MigrationTestHelper loads the exported
    // schema JSON from the TEST APK's assets, not from the project directory —
    // without this it fails with "Cannot find the schema file in the assets
    // folder" no matter that the files exist on disk.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    lint {
        checkReleaseBuilds = false
        disable += "NonNullableMutableLiveData"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class WriteBuildInfoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile.resolve("build_info")
        dir.mkdirs()
        dir.resolve("build_time.txt").writeText(System.currentTimeMillis().toString())
        dir.resolve("commit.txt").writeText("1.15")
    }
}
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<WriteBuildInfoTask>("write${cap}BuildInfo") {
            outputs.upToDateWhen { false }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, WriteBuildInfoTask::outputDir)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // [T-android-tablet-split] Adaptive list-detail layout for tablets/large
    // windows. Version pinned explicitly rather than left to the BOM: the
    // 2025.09.00 BOM does not manage the material3.adaptive group at all, so
    // an unversioned coordinate fails to resolve.
    //
    // 1.2.0, not the newer 1.3.0: 1.3.0 hard-requires compileSdk 37 AND
    // Android Gradle Plugin 9.1.0, and this module is on compileSdk 36 /
    // AGP 8.7.3 — it fails at configuration time, not with a warning. Moving
    // either is a separate, much larger change. 1.2.0 is stable and carries
    // NavigableListDetailPaneScaffold, which is all this needs.
    //
    // The adaptive-navigation3 artifact is NOT used — still alpha, and would
    // require migrating off classic Navigation Compose.
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
    implementation("androidx.compose.ui:ui")
    // [T-android-jankdiag] ProfileVerifier (ART compile state in the jank log).
    // Already in the APK through Compose at this version; declared to compile against it.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used by XAIOAuthManager to detect Custom
    // Tab dismissal (T-xai-oauth-stop-resume port iOS d1dbdd5d).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp
    // [T-android-vad] Silero v5 VAD (ONNX Runtime + WebRTC APM). The Android
    // build of the exact library iOS uses via SPM, from the same author, so
    // both platforms share one model and one set of thresholds. Carries
    // native .so payloads for ONNX Runtime and the APM — see the abiFilters
    // note in `ndk`; we ship arm64-v8a only.
    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")


    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Markdown rendering (mikepenz multiplatform-markdown-renderer)
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    // Chrome Custom Tabs (in-app browser for OAuth)
    implementation("androidx.browser:browser:1.8.0")

    // T-pwa-1: WebViewAssetLoader serves pinned PWA HTML under
    // https://appassets.androidplatform.net/ inside PwaActivity, so
    // sibling CSS/JS resolve against the file's parent dir without
    // granting WebView raw file:// access.
    implementation("androidx.webkit:webkit:1.12.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // T283: ACRA — local crash report capture. acra-core only (no http
    // sender, no network permission). CrashFileSender writes reports to
    // filesDir/logs/ where LogManagementScreen surfaces them.
    implementation("ch.acra:acra-core:5.12.0")

    // T322: Shizuku SDK — offloads privileged Android system APIs (PackageManager,
    // PermissionManager, ActivityManager, AppOps, IInputManager, …) through a
    // user-installed Shizuku app running as adb shell (uid=2000) or root. The CLI
    // surface `android-shizuku-cli` is a NativeOffloadHandler that forwards argv into
    // these hidden APIs via Shizuku's binder. `api` provides the manager binder
    // proxy + permission flow, `provider` registers the in-process content
    // provider that hosts the user-app side of the binder.
    //
    // [T-android-privileged-backend] AXManager (Axeron) needs NO extra
    // dependency: its server is a drop-in Shizuku-protocol implementation that
    // `sendBinder`s into the standard `<applicationId>.shizuku` ShizukuProvider
    // (verified against the installed APK). AxeronBackend therefore rides the
    // same `rikka.shizuku.Shizuku` client + provider declared above. The
    // earlier Axeron-API SDK route was dropped — it duplicated the
    // `moe.shizuku.*` classes (AGP checkDuplicateClasses failure) and pulled an
    // incompatible androidx.core / minSdk for zero added capability.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    // [T-android-search-visible-only] Runs the session-search SQL (SQLite JSON functions)
    // against a real SQLite in JVM tests; the same driver Room's compiler already uses.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testImplementation("org.robolectric:robolectric:4.17")

    // Testing — Instrumented (on-device) tests
    // [T-android-downgrade-compat] MigrationTestHelper replays the committed
    // schema json to prove every migration — upgrade AND the no-op downgrade —
    // still lands on the schema the entities expect.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
