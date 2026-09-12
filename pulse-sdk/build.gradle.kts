plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64())
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
