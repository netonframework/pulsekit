plugins { kotlin("multiplatform"); kotlin("native.cocoapods") }
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { t ->
        // SQLDelight's native driver reaches the final executable/test through pulse-core, but
        // its system-library linker option is not propagated transitively.
        t.binaries.configureEach { linkerOpts("-lsqlite3") }
        t.binaries.executable("pulseSmoke") { entryPoint = "pulse.pulseSmokeMain" }
    }
    // iOS: the SDK ships as an Objective-C framework, not as a klib.
    //
    // A host app is free to be on a different Kotlin version than this SDK — mall-demo-app is
    // pinned to 2.1.21 by KuiklyUI while this stack is on 2.4.0 — and klibs are not compatible
    // across that gap. The framework boundary is a plain Objective-C binary interface, so the
    // host's Kotlin version stops mattering. Dynamic rather than static: each Kotlin/Native
    // framework carries its own runtime, and two static ones in one binary collide at link time.
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64(), iosX64())
    iosTargets.forEach { target ->
        target.binaries.configureEach {
            linkerOpts("-lsqlite3")
            // Kotlin 2.4 defaults Apple binaries to iOS 15. PulseKit follows the host app and
            // GearUI Kit minimum, so emit frameworks that are loadable on iOS 14 as documented
            // by Kotlin/Native's lower Apple target version override.
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=14.0"
        }
    }

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

// Kotlin/Native does not currently copy arbitrary source-set resources into a framework bundle.
// Apple requires a dynamic SDK which uses a required-reason API to carry its own manifest, so put
// the reviewed file into every produced PulseKit.framework after linking. syncFramework copies that
// completed framework into CocoaPods, preserving the manifest in the final app.
val applePrivacyManifest = layout.projectDirectory.file("src/appleMain/resources/PrivacyInfo.xcprivacy")
tasks.matching { it.name.startsWith("link") && it.name.contains("Framework") }.configureEach {
    inputs.file(applePrivacyManifest)
    doLast {
        outputs.files.files
            .asSequence()
            .flatMap { output ->
                when {
                    output.isDirectory && output.extension == "framework" -> sequenceOf(output)
                    output.isDirectory -> output.walkTopDown()
                        .filter { it.isDirectory && it.extension == "framework" }
                    else -> emptySequence()
                }
            }
            .distinctBy { it.absolutePath }
            .forEach { framework ->
                applePrivacyManifest.asFile.copyTo(framework.resolve("PrivacyInfo.xcprivacy"), overwrite = true)
            }
    }
}
