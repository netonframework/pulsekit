plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    // See src/nativeInterop/cinterop/dyld.def — the image slide the crash handler records.
    listOf(macosArm64(), macosX64(), iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("dyld") {
            definitionFile.set(file("src/nativeInterop/cinterop/dyld.def"))
        }
    }
    sourceSets {
        commonMain.dependencies { api(project(":pulse-core")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
