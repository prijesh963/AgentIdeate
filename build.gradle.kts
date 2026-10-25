plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.agentideate"
version = "0.1.0"

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
