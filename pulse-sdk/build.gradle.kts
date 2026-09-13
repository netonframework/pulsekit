plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { t ->
        t.binaries.executable("pulseSmoke") { entryPoint = "pulse.pulseSmokeMain" }
    }
    // iOS client targets (library only; the SDK is consumed as a framework).
    iosArm64(); iosSimulatorArm64(); iosX64()
    sourceSets {
        commonMain.dependencies {
            api(project(":pulse-core"))
            api(project(":pulse-analytics"))
            api(project(":pulse-apm"))
            api(project(":pulse-transport"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}
