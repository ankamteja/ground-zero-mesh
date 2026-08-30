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

/** The whole stack over SimNetwork, printed. `--json <path>` also writes the twin snapshot. */
tasks.register<JavaExec>("runSim") {
    group = "verification"
    description = "Run the mesh simulation and print the report"
    mainClass.set("org.groundzero.mesh.simulation.SimulationRunner")
    classpath = sourceSets["main"].runtimeClasspath
    // Relative --json paths should mean what they say from the repo root, not core/.
    workingDir = rootProject.projectDir
    args = (project.findProperty("simArgs") as String?)?.split(" ") ?: emptyList()
}

/** This machine as a real relay node — see TcpRelayMain's doc. `-PrelayArgs="7778"` for a
 *  non-default port. */
tasks.register<JavaExec>("runRelay") {
    group = "application"
    description = "Run this machine as a LAN relay node phones can connect to instead of Nearby"
    mainClass.set("org.groundzero.mesh.relay.TcpRelayMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    standardInput = System.`in`
    args = (project.findProperty("relayArgs") as String?)?.split(" ") ?: emptyList()
}

/** The responder laptop's RAG advisor — see AdvisorMain's doc for the flags.
 *  `-PadvisorArgs="--gateway http://192.168.43.1:8080 --model qwen3:8b"`. */
tasks.register<JavaExec>("runAdvisor") {
    group = "application"
    description = "Run the local LLM advisor the responder dashboard queries (needs `ollama serve`)"
    mainClass.set("org.groundzero.mesh.advisor.AdvisorMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    standardInput = System.`in`
    args = (project.findProperty("advisorArgs") as String?)?.split(" ") ?: emptyList()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
