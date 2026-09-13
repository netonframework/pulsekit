plugins {
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    kotlin("native.cocoapods") version "2.4.0" apply false
}
allprojects {
    group = "com.pulse"
    version = "0.0.1"
}
