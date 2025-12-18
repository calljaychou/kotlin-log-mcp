plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jaychou.mcp.SdkServerKt"
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.jaychou.mcp.SdkServerKt"
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

repositories {
    mavenCentral()
}

dependencies {
    api("io.modelcontextprotocol:kotlin-sdk:0.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

}

application {
    mainClass.set("com.jaychou.mcp.SdkServerKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

