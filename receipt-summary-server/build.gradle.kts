val kotlin_version: String by project
val koin_version: String by project
val kotest_version: String by project
val logback_version: String by project
val ktor_version: String by project

plugins {
    kotlin("jvm") version "2.2.20" // use kotlin_version
    id("io.ktor.plugin") version "3.6.0" // use ktor_version
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" // use kotlin_version
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
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    implementation("com.openai:openai-java:4.65.0")
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("io.ktor:ktor-client-java:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.github.pdvrieze.xmlutil:serialization-jvm:0.90.1")
    implementation(project.dependencies.platform("io.insert-koin:koin-bom:$koin_version"))
    implementation("io.insert-koin:koin-core")
    implementation("io.insert-koin:koin-ktor")
    testImplementation("io.kotest:kotest-assertions-core:$kotest_version")
}

ktor {
    docker {
        localImageName.set(rootProject.name)
        jreVersion.set(JavaVersion.VERSION_21)
        portMappings.set(
            listOf(
                io.ktor.plugin.features.DockerPortMapping(
                    3000,
                    3000, // same Application ktor server
                    io.ktor.plugin.features.DockerPortMappingProtocol.TCP
                )
            )
        )
    }
}
