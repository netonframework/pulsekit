plugins { id("com.android.library") }
repositories { google(); mavenCentral() }

// The Android platform layer and facade. The SDK itself is :pulsekit compiled for the JVM — the
// same code as on iOS, over neton-io's NIO reactor, no native libraries. This module adds what only
// the Android framework knows (lifecycle, main thread, PackageManager facts, SharedPreferences, the
// system SQLite) behind pulse.host.HostEnvironment, and the public Java API.
//
// Java rather than Kotlin on purpose, for the reason the iOS SDK's surface is Objective-C: the host
// compiles against Java signatures only, whatever its own Kotlin version.
android {
    namespace = "com.netonstream.pulsekit"
    compileSdk = 36
    defaultConfig {
        // The SDK, msgtrans, neton-io and SQLDelight's Android driver all stay within API 21.
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    // implementation, not api: a host sees this module's Java API and nothing of the SDK behind it.
    implementation(project(":pulsekit"))
    implementation("app.cash.sqldelight:android-driver:2.2.1")
}

afterEvaluate {
    extensions.getByType<PublishingExtension>().publications.create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "pulsekit-android"
    }
}
