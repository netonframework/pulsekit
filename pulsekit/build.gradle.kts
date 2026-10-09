import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { kotlin("multiplatform"); kotlin("plugin.serialization"); kotlin("native.cocoapods"); id("app.cash.sqldelight") }
repositories { mavenCentral() }

// One artifact. The capabilities keep their packages (pulse.core / analytics / apm / runtime /
// transport) but ship together: the only consumer shape is "the whole SDK" — the iOS framework
// already bundled everything — and the linker strips unused code from one klib exactly as well as
// from six. Whether a capability runs is PulseConfig's decision, not the dependency list's.
val generateSdkVersion = tasks.register("generateSdkVersion") {
    val version = project.version.toString()
    val dir = layout.buildDirectory.dir("generated/sdkVersion")
    inputs.property("version", version)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("pulse/core/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package pulse.core\n\ninternal const val PULSEKIT_VERSION: String = \"$version\"\n")
    }
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { t ->
        // SQLDelight's native driver needs the system library at link time and does not
        // propagate the option; the smoke binary exists for the integration test.
        t.binaries.configureEach { linkerOpts("-lsqlite3") }
        // Kotlin/Native links Linux binaries against its own sysroot, which has no libsqlite3: on a Linux
        // host, also search the distribution's multiarch directory (libsqlite3-dev).
        val multiarch = mapOf("linuxX64" to "x86_64-linux-gnu", "linuxArm64" to "aarch64-linux-gnu")[t.name]
        if (multiarch != null && file("/usr/lib/$multiarch/libsqlite3.so").exists()) {
            t.binaries.configureEach { linkerOpts("-L/usr/lib/$multiarch") }
        }
        t.binaries.executable("pulseSmoke") { entryPoint = "pulse.pulseSmokeMain" }
        // A stand-in ingest server for device and emulator end-to-end runs; see IngestStub.kt.
        t.binaries.executable("pulseIngestStub") { entryPoint = "pulse.pulseIngestStubMain" }
    }
    // iOS: the SDK ships as an Objective-C framework, not as a klib.
    //
    // A host app is free to be on a different Kotlin version than this SDK — mall-demo-app is
    // pinned to 2.1.21 by KuiklyUI while this stack is on 2.4.20 — and klibs are not compatible
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
        license = "Apache-2.0"
        authors = "{ 'zoujiaqing' => 'zoujiaqing@gmail.com' }"
        version = project.version.toString()
        ios.deploymentTarget = "14.0"
        framework {
            baseName = "PulseKit"
            isStatic = false
        }
    }

    // Android, through the JVM: the same SDK compiled for the JVM (no native code). The Android
    // platform layer — lifecycle, main-thread watchdog, PackageManager, SharedPreferences, the
    // system SQLite — is the pulsekit-android module, written in Java the way the iOS platform
    // layer is the Objective-C framework surface. Bytecode 1.8 for Android minSdk 21.
    // The JVM artifact must work in Android apps built with an older Kotlin (KuiklyUI pins hosts to
    // 2.1). Such a host never compiles against these classes, but Gradle aligns its whole classpath
    // to the highest kotlin-stdlib anything asks for, and its compiler reads stdlib metadata at most
    // one version ahead. So the JVM build asks for stdlib 2.2.21 and uses no stdlib API newer than
    // 2.2 (API 2.1 is deprecated); native klibs are unaffected (they follow the compiler version).
    coreLibrariesVersion = "2.2.21"
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
    }

    // See src/nativeInterop/cinterop/posixshim.def — fixed-width POSIX calls for shared code.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("posixshim") {
            definitionFile.set(file("src/nativeInterop/cinterop/posixshim.def"))
        }
    }

    // Two shared layers. "client" is the host-app SDK — the PulseSDK facade and automatic
    // instrumentation — which Apple and the JVM (Android) share line for line. "systemSqlite" is
    // everywhere SQLDelight's native driver can link the operating system's SQLite.
    applyDefaultHierarchyTemplate {
        common {
            group("client") { group("apple"); withJvm() }
            group("native") {
                group("systemSqlite") { group("apple"); group("linux") }
            }
        }
    }

    sourceSets {
        // The SDK reports its own version on connect and on every batch; generated from the Gradle
        // version so the two cannot drift apart.
        commonMain { kotlin.srcDir(generateSdkVersion) }
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("com.netonstream:msgtrans:0.2.0")
        }
        getByName("systemSqliteMain").dependencies {
            implementation("app.cash.sqldelight:native-driver:2.2.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        // The JVM has no SQLite of its own: Android hands the SDK its framework driver (see
        // pulse.host.HostEnvironment); tests use the JDBC driver.
        jvmTest.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.2.1")
        }
    }
}

sqldelight {
    // Each Apple/Linux binary above links -lsqlite3 itself, explicitly.
    linkSqlite.set(false)
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
