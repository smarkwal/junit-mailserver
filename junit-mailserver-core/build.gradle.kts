import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `java-library`
    jacoco
}

// Java version check ----------------------------------------------------------

if (!JavaVersion.current().isJava11Compatible) {
    val error = "Build requires Java 11 and does not run on Java ${JavaVersion.current().majorVersion}."
    throw GradleException(error)
}

// dependencies ----------------------------------------------------------------

repositories {
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

dependencies {

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
    testImplementation("org.mockito:mockito-core:4.6.1")
    testImplementation("org.assertj:assertj-core:3.23.1")
    testImplementation("com.sun.mail:jakarta.mail:2.0.1")
    testImplementation("commons-net:commons-net:3.8.0")

}

// build -----------------------------------------------------------------------

java {

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }

    // automatically package source code as artifact -sources.jar
    withSourcesJar()

    // automatically package Javadoc as artifact -javadoc.jar
    withJavadocJar()
}

jacoco {
    toolVersion = "0.8.8"
}

tasks.withType<JavaCompile> {
    options.encoding = "ASCII"
}

tasks.register<Test>("testOnJava17") {
    group = "verification"
    description = "Runs the test suite on Java 17."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.withType<Test> {

    // use JUnit 5
    useJUnitPlatform()

    // settings
    maxHeapSize = "1G"

    // test task output
    testLogging {
        events = mutableSetOf(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.FAILED,
            TestLogEvent.SKIPPED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR
        )
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

}

// disable generation of Gradle module metadata file
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

tasks.register("createKeystore") {
    group = "other"
    description = "Creates a keystore with an RSA keypair and self-signed certificate for localhost."
    doLast {
        val keystore = file("src/main/resources/junit-mailserver.pfx")
        if (keystore.exists()) keystore.delete()
        exec {
            commandLine(
                "keytool", "-genkey",
                "-alias", "localhost",
                "-dname", "CN=localhost",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256WithRSA",
                "-validity", "3653", // 10 years
                "-keypass", "changeit",
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keystore", keystore.absolutePath
            )
        }
    }
}
