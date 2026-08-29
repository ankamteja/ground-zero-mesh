plugins {
    id("org.jetbrains.kotlin.jvm")
}

// core is pure JVM: no Android dependency, no third-party runtime dependency.
// Its tests run on any JDK 21 with no Android SDK. Keep it that way.

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
