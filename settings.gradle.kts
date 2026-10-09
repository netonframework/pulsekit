pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "pulsekit-build"
// The transport foundation. Consumed as sibling composite builds during development (~/projects/Neton/io and
// msgtrans-kotlin) so everything compiles with one Kotlin/Native toolchain; the coordinates match the published
// artifacts, so where the siblings are absent (CI, a lone checkout) they resolve from Maven Central instead.
for (sibling in listOf("../io", "../msgtrans-kotlin")) {
    if (file(sibling).isDirectory) includeBuild(sibling)
}
// The SDK itself, one artifact: com.netonstream:pulsekit. The root is named differently only so the
// module can carry the product name (Gradle does not allow a subproject named like its root).
include(":pulsekit")
// The Android facade: a Java API over libpulsekit.so, shipped as an AAR — what the CocoaPods
// framework is for iOS. Published as com.netonstream:pulsekit-android.
include(":pulsekit-android")
// A minimal host app over the AAR, used for emulator/device end-to-end runs. Not published.
include(":pulsekit-android-sample")
