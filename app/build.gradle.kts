import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

/** Single version number for the app, kept in version.properties (bumped for every delivered APK). */
val appVersion: Int = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("VERSION").toInt()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "ir.kalapod.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.kalapod.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = "0.$appVersion"
        // Build stamp shown in Settings, so the shop can tell which APK is installed.
        buildConfigField("String", "BUILD_STAMP", "\"" + SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date()) + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Exported Room schemas feed MigrationTestHelper in instrumented tests.
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Bundled model: scanning works fully offline (SPEC §4).
    implementation(libs.mlkit.barcode)

    // Offline Persian speech recognition; the model is downloaded once inside the app.
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    implementation("${libs.jna.get()}@aar")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.datastore)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}

// After every build, put the installable APK in the project's main folder (e.g. Kalapod/Kalapod-debug.apk),
// so it's easy to find and copy to a phone. Covers assemble*, install* and any task that packages an APK.
listOf("debug", "release").forEach { type ->
    val cap = type.replaceFirstChar { it.uppercase() }
    val copy = tasks.register("copy${cap}ApkToRoot") {
        val apkDir = layout.buildDirectory.dir("outputs/apk/$type")
        val target = rootProject.layout.projectDirectory.file("Kalapod-$type.apk").asFile
        doLast {
            val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() }
            if (apk != null) {
                apk.copyTo(target, overwrite = true)
                println("APK → ${target.absolutePath}")
            }
        }
    }
    afterEvaluate { tasks.findByName("package$cap")?.finalizedBy(copy) }
}
