plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    sourceSets {
        commonMain.dependencies { api(project(":pulse-core")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
