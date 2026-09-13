plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    // <mach-o/dyld.h> is not in the platform.darwin klib, so the image-enumeration API is bound
    // here. Apple targets only; Linux reads /proc/self/maps and needs no interop.
    listOf(macosArm64(), macosX64(), iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("dyld") {
            definitionFile.set(file("src/nativeInterop/cinterop/dyld.def"))
        }
    }

    sourceSets {
        commonMain.dependencies { api(project(":pulse-core")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
