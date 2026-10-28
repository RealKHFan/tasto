import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

android {
    namespace = "io.github.realkhfan.tasto"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.realkhfan.tasto"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.2"
    }

    // Release key: TASTO_KEYSTORE, TASTO_KEYSTORE_PASSWORD, TASTO_KEY_ALIAS, TASTO_KEY_PASSWORD
    // environment variables, or a local keystore.properties. Neither is ever committed.
    // Without them, release builds are signed with the debug key and can't update a release install.
    signingConfigs {
        val storePath = signingValue("TASTO_KEYSTORE", "storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("TASTO_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("TASTO_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("TASTO_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    dependenciesInfo {
        // No Google-signed dependency metadata blob in the APK (F-Droid friendly).
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    // ExploreByTouchHelper: TalkBack for the keys and the suggestion bar, which are drawn by hand.
    implementation("androidx.customview:customview:1.2.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
