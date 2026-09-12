plugins { kotlin("multiplatform"); kotlin("plugin.serialization") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") }
    }
}
