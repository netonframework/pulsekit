plugins { kotlin("multiplatform"); kotlin("native.cocoapods") }
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { t ->
        t.binaries.executable("pulseSmoke") { entryPoint = "pulse.pulseSmokeMain" }
    }
    // iOS: the SDK ships as an Objective-C framework, not as a klib.
    //
    // A host app is free to be on a different Kotlin version than this SDK — mall-demo-app is
    // pinned to 2.1.21 by KuiklyUI while this stack is on 2.4.0 — and klibs are not compatible
    // across that gap. The framework boundary is a plain Objective-C binary interface, so the
    // host's Kotlin version stops mattering. Dynamic rather than static: each Kotlin/Native
    // framework carries its own runtime, and two static ones in one binary collide at link time.
    iosArm64(); iosSimulatorArm64(); iosX64()

    cocoapods {
        name = "PulseKit"
        summary = "PulseKit analytics, APM and runtime SDK"
        homepage = "https://github.com/netonstream/pulsekit"
        version = "0.0.1"
        ios.deploymentTarget = "14.0"
        framework {
            baseName = "PulseKit"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pulse-core"))
            api(project(":pulse-analytics"))
            api(project(":pulse-apm"))
            api(project(":pulse-runtime"))
            api(project(":pulse-transport"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}

// The simulator runs the test binary in its own process and inherits nothing from Gradle, so the
// integration-test host has to be forwarded explicitly. simctl only passes through variables
// prefixed SIMCTL_CHILD_, stripping the prefix on the way in.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    for (key in listOf("PULSE_IT_HOST", "PULSE_IT_PORT")) {
        providers.environmentVariable(key).orNull?.let { environment("SIMCTL_CHILD_$key", it) }
    }
}
