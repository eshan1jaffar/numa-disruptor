plugins { application }

dependencies {
    implementation(project(":runtime"))
    implementation(libs.hdrhistogram)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.processor)
}

application {
    mainClass = "com.numadisruptor.benchmarks.HarnessMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Stamp the build with the git commit so a detached distribution still reports its provenance.
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("unknown")

tasks.processResources {
    inputs.property("gitCommit", gitCommit)
    filesMatching("build-info.properties") { expand("gitCommit" to gitCommit.get().ifEmpty { "unknown" }, "version" to project.version) }
}
