plugins { kotlin("multiplatform"); kotlin("plugin.serialization"); kotlin("native.cocoapods"); id("app.cash.sqldelight") }
repositories { mavenCentral() }

// One artifact. The capabilities keep their packages (pulse.core / analytics / apm / runtime /
// transport) but ship together: the only consumer shape is "the whole SDK" — the iOS framework
// already bundled everything — and the linker strips unused code from one klib exactly as well as
// from six. Whether a capability runs is PulseConfig's decision, not the dependency list's.
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { t ->
        // SQLDelight's native driver needs the system library at link time and does not
        // propagate the option; the smoke binary exists for the integration test.
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
    val appleTargets = iosTargets + listOf(macosArm64(), macosX64())
    appleTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.apply {
            // See src/nativeInterop/cinterop/dyld.def — image slide for the crash handler and the
            // image/class enumeration for the runtime inventory.
            create("dyld") { definitionFile.set(file("src/nativeInterop/cinterop/dyld.def")) }
            // See src/nativeInterop/cinterop/procmetrics.def — process start time and footprint.
            create("procmetrics") { definitionFile.set(file("src/nativeInterop/cinterop/procmetrics.def")) }
        }
    }
    iosTargets.forEach { target ->
        target.binaries.configureEach {
            linkerOpts("-lsqlite3")
            // Kotlin 2.4 defaults Apple binaries to iOS 15. PulseKit follows the host app and
            // GearUI Kit minimum, so emit frameworks loadable on iOS 14.
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=14.0"
        }
    }

    cocoapods {
        name = "PulseKit"
        summary = "PulseKit analytics, APM and runtime SDK"
        homepage = "https://github.com/netonframework/pulsekit"
        version = project.version.toString()
        ios.deploymentTarget = "14.0"
        framework {
            baseName = "PulseKit"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("com.netonstream:msgtrans:${project.version}")
        }
        nativeMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.3.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

sqldelight {
    linkSqlite.set(true)
    databases {
        create("PulseDatabase") {
            packageName.set("pulse.db")
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
