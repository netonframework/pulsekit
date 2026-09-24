plugins {
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    kotlin("native.cocoapods") version "2.4.0" apply false
    id("app.cash.sqldelight") version "2.3.2" apply false
}
allprojects {
    group = "com.netonstream"
    version = "0.1.0"
}

// ---------- Maven Central publishing ----------
//
// Same convention as Neton (see its RELEASING.md): publications are laid out, signed, under
// build/staging-repo by `publishAllPublicationsToStagingLocalRepository`, and that directory is
// zipped and uploaded as one Central Portal bundle. Kotlin/Native artifacts are klibs; a consumer
// must compile with the same Kotlin version as the publisher.
val unpublished = setOf<String>()
val pomDescriptions = mapOf(
    "pulsekit-core" to "PulseKit - the unified Event/Session/Identity model, bounded buffer, durable outbox and batch/flush pipeline; no transport dependency",
    "pulsekit-analytics" to "PulseKit - product analytics: business events, identify, automatic session and launch lifecycle",
    "pulsekit-apm" to "PulseKit - errors, crashes (signal and uncaught-exception capture), performance samples and gauges",
    "pulsekit-runtime" to "PulseKit - opt-in Apple runtime inventory and sensitive-API observation with caller attribution",
    "pulsekit-transport" to "PulseKit - the EventSink over the msgtrans long connection: batch upload, heartbeat, server-driven collection policy",
    "pulsekit-sdk" to "PulseKit - batteries-included SDK entry for Kotlin/Native and the PulseKit iOS framework: analytics, APM and runtime observation with automatic launch-time, hang, memory and lifecycle collection"
)

subprojects {
    if (name in unpublished) return@subprojects
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    afterEvaluate {
        val sub = this@subprojects
        val publishing = sub.extensions.getByType<org.gradle.api.publish.PublishingExtension>()

        // Only group / version / POM. The KMP plugin owns the artifactIds (one per target plus
        // the root metadata publication); overriding them would make the publications collide.
        publishing.publications.withType<MavenPublication>().configureEach {
            groupId = sub.group.toString()
            version = sub.version.toString()
            pom {
                name.set(sub.name)
                description.set(pomDescriptions[sub.name] ?: "PulseKit - ${sub.name}")
                url.set("https://github.com/netonframework/pulsekit")
                licenses { license { name.set("Apache-2.0"); url.set("https://opensource.org/licenses/Apache-2.0") } }
                developers { developer { name.set("Netonstream"); organization.set("Netonstream"); organizationUrl.set("https://netonstream.com") } }
                scm {
                    url.set("https://github.com/netonframework/pulsekit")
                    connection.set("scm:git:git://github.com/netonframework/pulsekit.git")
                    developerConnection.set("scm:git:ssh://git@github.com/netonframework/pulsekit.git")
                }
            }
        }

        publishing.repositories {
            // Local file repository: the tree a Central Portal bundle is zipped from.
            maven {
                name = "stagingLocal"
                url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
            }
        }

        // Central rejects unsigned artifacts. In-memory key first (CI / no keyring), else keyring.
        val signing = sub.extensions.getByType<org.gradle.plugins.signing.SigningExtension>()
        val inMemoryKey = sub.findProperty("signingInMemoryKey") as String?
        when {
            !inMemoryKey.isNullOrBlank() -> {
                signing.useInMemoryPgpKeys(inMemoryKey, sub.findProperty("signingInMemoryKeyPassword") as String? ?: "")
                signing.sign(publishing.publications)
            }
            sub.hasProperty("signing.keyId") -> signing.sign(publishing.publications)
        }
        // KMP publications share sign tasks across targets; without this ordering Gradle reports
        // an implicit dependency between publishX and signY and fails the build.
        sub.tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
            mustRunAfter(sub.tasks.withType<org.gradle.plugins.signing.Sign>())
        }
    }
}
