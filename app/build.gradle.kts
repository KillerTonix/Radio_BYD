plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.wheelradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.wheelradio"
        minSdk = 29 // Android 10, as on the BYD Yuan Plus / Atto 3 DiLink head unit
        targetSdk = 34
        versionCode = 9
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // "car": full app for the head unit. "phone": same app without the notification listener
    // (Bluetooth fallback), because Google Play Protect blocks sideloading apps that have one.
    flavorDimensions += "target"
    productFlavors {
        create("car") { dimension = "target" }
        create("phone") { dimension = "target" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing so the release APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Robolectric reads the merged assets (stations.json, stations_am.json) and resources.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.guava)

    // JVM unit tests (Robolectric), run with ./gradlew testCarDebugUnitTest
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.media3.test.utils)

    // Instrumented tests on a device/emulator, run with ./gradlew connectedCarDebugAndroidTest
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// Robolectric normally downloads its Android jar from Maven Central at test time, on every fresh CI runner, with no
// retry; a failed download fails every unit test. Let Gradle fetch it instead (retried, and cached by
// gradle/actions/setup-gradle between runs) and run Robolectric offline from that copy.
// The version must match the Robolectric release and `sdk=` in src/test/resources/robolectric.properties
// (Robolectric 4.13 + SDK 34 = 14-robolectric-10818077-i6); a mismatch fails fast with "Unable to locate dependency".
val robolectricAndroidAll: Configuration by configurations.creating
dependencies {
    robolectricAndroidAll("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Copy>("copyRobolectricJars") {
    from(robolectricAndroidAll)
    into(robolectricJarsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.absolutePath)
}
