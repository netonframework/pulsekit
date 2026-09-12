pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "pulsekit"
// The transport foundation (sibling composite builds, not published).
includeBuild("../neton-io")
includeBuild("../msgtrans-kotlin")
include(":pulse-core", ":pulse-analytics", ":pulse-apm", ":pulse-transport", ":pulse-sdk")
