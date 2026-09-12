plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    // Aligned with msgtrans-kotlin's targets (the transport is native-only). iOS follows once
    // neton-io/msgtrans add Apple client targets (nw/kqueue) — a later step.
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64())
    sourceSets {
        commonMain.dependencies {
            api(project(":pulse-core"))
            api("com.netonstream.msgtrans:msgtrans-transport")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
