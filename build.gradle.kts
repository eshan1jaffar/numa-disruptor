// Shared configuration for every module. Per-module files only declare dependencies.
subprojects {
    apply(plugin = "java-library")

    group = "com.numadisruptor"
    version = "0.1.0"

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-restricted", "-Werror"))
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Tests needing real NUMA hardware are opt-in: ./gradlew test -PhardwareTests
            if (!project.hasProperty("hardwareTests")) excludeTags("hardware")
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        maxHeapSize = "1g"
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
