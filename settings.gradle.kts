pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "pulsekit-build"
// The transport foundation. Consumed as sibling composite builds during development so everything
// compiles with one Kotlin/Native toolchain; the coordinates match the published artifacts, so
// removing these two lines resolves them from Maven Central instead.
includeBuild("../neton-io")
includeBuild("../msgtrans-kotlin")
// One module, one artifact: com.netonstream:pulsekit. The root is named differently only so the
// module can carry the product name (Gradle does not allow a subproject named like its root).
include(":pulsekit")
