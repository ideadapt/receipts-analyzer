val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
}

group = "net.ideadapt"
version = "0.0.1"

application {
    mainClass.set("net.ideadapt.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    implementation("com.aallam.openai:openai-client:3.8.2")
    implementation("io.ktor:ktor-client-java")
    implementation("io.ktor:ktor-client-content-negotiation")
}

ktor {
    docker {
        localImageName.set(rootProject.name)
        jreVersion.set(JavaVersion.VERSION_21)
        portMappings.set(listOf(
                io.ktor.plugin.features.DockerPortMapping(
                        3000,
                        3000,
                        io.ktor.plugin.features.DockerPortMappingProtocol.TCP
                )
        ))
    }
}
