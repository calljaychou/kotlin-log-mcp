plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(project(":kotlin-mcp-server"))
}

application {
    mainClass.set("com.jaychou.mcp.SdkClientMainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jaychou.mcp.SdkClientMainKt"
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.jaychou.mcp.SdkClientMainKt"
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
