plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ytmusic.clone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ytmusic.clone"
        minSdk = 26
        targetSdk = 34
        versionCode = 97
        versionName = "7.67"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ---- Permanent signing key (read ONLY from environment variables) ----
    // The keystore file and passwords are NEVER stored in the project.
    // The Colab build cell sets: YT_KEYSTORE_PATH, YT_KEYSTORE_PASSWORD,
    // YT_KEY_ALIAS, YT_KEY_PASSWORD (or RELEASE_*).
    val ksPath: String? = System.getenv("YT_KEYSTORE_PATH") ?: System.getenv("RELEASE_KEYSTORE_PATH")
    val ksPass: String? = System.getenv("YT_KEYSTORE_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val ksAlias: String? = System.getenv("YT_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
    val keyPass: String? = System.getenv("YT_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
    val hasPermanentKey = !ksPath.isNullOrBlank() && file(ksPath).exists() &&
        !ksPass.isNullOrBlank() && !ksAlias.isNullOrBlank() && !keyPass.isNullOrBlank()

    signingConfigs {
        if (hasPermanentKey) {
            create("permanent") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasPermanentKey) {
                signingConfig = signingConfigs.getByName("permanent")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            // Debug build intentionally stays on default Android debug keystore
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.testLogging {
                    events("passed", "skipped", "failed", "standardError")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStandardStreams = true
                }
            }
        }
    }

    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module"
            )
        }
    }
}

// Fail release builds immediately if permanent release keystore is missing
gradle.taskGraph.whenReady {
    val isReleaseTask = allTasks.any { task ->
        val name = task.name.lowercase()
        name.contains("release") && (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package"))
    }
    val ksPathVal = System.getenv("YT_KEYSTORE_PATH") ?: System.getenv("RELEASE_KEYSTORE_PATH")
    val ksPassVal = System.getenv("YT_KEYSTORE_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val ksAliasVal = System.getenv("YT_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
    val keyPassVal = System.getenv("YT_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
    val permanentKeyReady = !ksPathVal.isNullOrBlank() && file(ksPathVal).exists() &&
        !ksPassVal.isNullOrBlank() && !ksAliasVal.isNullOrBlank() && !keyPassVal.isNullOrBlank()

    if (isReleaseTask && !permanentKeyReady) {
        throw GradleException(
            "RELEASE BUILD REJECTED: Missing permanent release keystore credentials.\n" +
            "Environment variables YT_KEYSTORE_PATH, YT_KEYSTORE_PASSWORD, YT_KEY_ALIAS, YT_KEY_PASSWORD (or RELEASE_*) must be configured\n" +
            "and point to an existing keystore file. Release builds must never be signed with a debug key."
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Media3 (ExoPlayer + MediaSession for lockscreen & background play)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)

    // Image loading & Network
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.bcprov)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed", "standardError")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
