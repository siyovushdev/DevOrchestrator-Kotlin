plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "dev.orchestrator"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.5.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

application {
    mainClass.set("dev.orchestrator.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
