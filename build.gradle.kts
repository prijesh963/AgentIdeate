import java.security.MessageDigest

plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.5"
    // Build-time only: writes the software bill of materials published with each release.
    id("org.cyclonedx.bom") version "1.10.0"
}

group = "dev.agentideate"
version = "0.6.0"

repositories {
    // Inside an office network, point the build at an Artifactory/Nexus mirror with
    //   ./gradlew build -Pagentideate.mavenMirror=https://artifactory.example.com/maven-remote
    // No build-file edit needed.
    val mirror = providers.gradleProperty("agentideate.mavenMirror").orNull
    if (mirror != null) {
        maven { url = uri(mirror) }
    } else {
        mavenCentral()
    }
}

dependencies {
    // Kept deliberately short: every dependency here ships inside the jar and has to pass review.
    implementation("com.github.javaparser:javaparser-core:3.26.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

application {
    mainClass = "dev.agentideate.Main"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.shadowJar {
    archiveFileName = "agentideate.jar"
    manifest {
        attributes(
            "Main-Class" to "dev.agentideate.Main",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("agentideate.samples", layout.projectDirectory.dir("samples").asFile.absolutePath)
}

// SHA-256 of the jar, published next to it so a copy in Artifactory can be verified against the release.
val checksum by tasks.registering {
    description = "Writes build/libs/agentideate.jar.sha256"
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jar)
    val out = layout.buildDirectory.file("libs/agentideate.jar.sha256")
    outputs.file(out)
    doLast {
        val bytes = jar.get().asFile.readBytes()
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
        out.get().asFile.writeText("$hex  agentideate.jar\n")
    }
}

tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setProjectType("application")
    setSchemaVersion("1.5")
    setDestination(layout.buildDirectory.dir("sbom").get().asFile)
    setOutputName("agentideate-sbom")
    setOutputFormat("json")
    setIncludeLicenseText(false)
}

// Everything a release publishes: the jar, its checksum and its SBOM.
tasks.register("release") {
    description = "Builds the jar and its SHA-256 (build/libs) and its SBOM (build/sbom)"
    dependsOn(tasks.shadowJar, checksum, tasks.cyclonedxBom)
}
