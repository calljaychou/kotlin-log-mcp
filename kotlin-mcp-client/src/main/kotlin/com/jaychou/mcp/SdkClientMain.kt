package com.jaychou.mcp

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.util.Scanner
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val childJar = "kotlin-mcp-server/build/libs/kotlin-mcp-server-all.jar"
private const val logPath = "sample_logs/app.log"

fun main() = runBlocking {
    val projectRoot = File(System.getProperty("user.dir"))
    // Ensure we are at the root or find the jar relative to current dir
    val serverJarPath = if (projectRoot.name == "kotlin-mcp-client") {
        File(projectRoot.parentFile, childJar)
    } else {
        File(projectRoot, childJar)
    }.absolutePath

    val logPath = if (projectRoot.name == "kotlin-mcp-client") {
        File(projectRoot.parentFile, logPath)
    } else {
        File(projectRoot, logPath)
    }.absolutePath

    println("Starting MCP Client...")
    println("Server Jar: $serverJarPath")
    println("Log Path: $logPath")

    if (!File(serverJarPath).exists()) {
        System.err.println("Server Jar not found! Please build the server first.")
        return@runBlocking
    }

    val process = ProcessBuilder("java", "-jar", serverJarPath)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val transport = StdioClientTransport(
        input = process.inputStream.asInput(),
        output = process.outputStream.asSink().buffered()
    )

    val client = Client(
        clientInfo = Implementation(
            name = "kotlin-mcp-client",
            version = "1.0.0"
        ),
        options = ClientOptions(
            capabilities = ClientCapabilities()
        )
    )

    try {
        println("Connecting to server...")
        client.connect(transport)
        println("Connected!")

        // List tools
        println("\nListing tools:")
        val tools = client.listTools()
        tools.tools.forEach { tool ->
            println("- ${tool.name}: ${tool.description}")
        }

        val scanner = Scanner(System.`in`)
        while (true) {
            println("\n---------------------------------------------------------")
            println("Enter 'exit' to quit, or press Enter to start a new search.")
            val cmd = scanner.nextLine().trim()
            if (cmd.equals("exit", ignoreCase = true)) {
                break
            }
            print("Enter Input ->")
            val inputList = scanner.nextLine().trim().takeIf { it.isNotBlank() }?.split(",")
            val keyword = inputList?.get(0)
            val logLevel = inputList?.get(1)
            val startTime = inputList?.get(3)
            val endTime = inputList?.get(4)

            println("\nSending request to server...")

            try {
                val result = client.callTool(
                    name = "call",
                    arguments = buildJsonObject {
                        put("log_path", logPath)
                        if (!keyword.isNullOrBlank()) put("keyword", keyword)
                        if (!logLevel.isNullOrBlank()) put("log_level", logLevel)
                        if (!startTime.isNullOrBlank()) put("start_time", startTime)
                        if (!endTime.isNullOrBlank()) put("end_time", endTime)
                    }
                )

                println("Tool execution result:")
                result.content.forEach { content ->
                    if (content is TextContent) {
                        println(content.text)
                    }
                }

                // Also print structured content if available
                result.structuredContent?.let {
                    println("Structured Content: $it")
                }
            } catch (e: Exception) {
                System.err.println("Error calling tool: ${e.message}")
            }
        }

    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace()
    }
}
