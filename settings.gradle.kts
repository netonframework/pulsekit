pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "pulsekit"
// The transport foundation. Consumed as sibling composite builds during development so everything
// compiles with one Kotlin/Native toolchain; the coordinates match the published artifacts, so
// removing these two lines resolves them from Maven Central instead.
includeBuild("../neton-io")
includeBuild("../msgtrans-kotlin")

// Directories keep their short names; the published artifactIds carry the product prefix so
// `com.netonstream:pulsekit-sdk` reads as what it is next to `neton-*` and `msgtrans`.
val modules = listOf("core", "analytics", "apm", "runtime", "transport", "sdk")
modules.forEach { m ->
    include(":pulsekit-$m")
    project(":pulsekit-$m").projectDir = file("pulse-$m")
}
