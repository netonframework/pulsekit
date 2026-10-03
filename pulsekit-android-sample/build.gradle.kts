plugins { id("com.android.application") }
repositories { google(); mavenCentral() }

// A host app reduced to what PulseKit needs, for end-to-end runs against pulseIngestStub:
//
//   ./gradlew :pulsekit-android-sample:installDebug
//   adb shell am start -n com.netonstream.pulsekit.sample/.MainActivity --es action <action>
//
// See MainActivity for the actions. The ingest host defaults to 10.0.2.2, the emulator's address
// for the development machine; pass -Ppulse.sample.host=<host> for a device.
android {
    namespace = "com.netonstream.pulsekit.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.netonstream.pulsekit.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 42
        versionName = "1.2.0"
        // The emulator reaches the Mac at 10.0.2.2; pulseIngestStub listens on 9600.
        buildConfigField("String", "PULSE_ENDPOINT", "\"${findProperty("pulse.sample.endpoint") ?: "tcp://10.0.2.2:9600"}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies { implementation(project(":pulsekit-android")) }
