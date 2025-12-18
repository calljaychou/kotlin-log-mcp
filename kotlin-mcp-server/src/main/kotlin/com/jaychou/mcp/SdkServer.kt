package com.jaychou.mcp

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * @author JayCHou <a href="calljaychou@qq.com">Email</a>
 */
private val json = Json { ignoreUnknownKeys = true }
private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val validLevels = setOf("INFO", "WARN", "ERROR")
private const val defaultLimit = 2000


private fun parseTime(s: String?): LocalDateTime? {
    if (s.isNullOrBlank()) return null
    return try { LocalDateTime.parse(s.trim(), timeFmt) } catch (e: Exception) { null }
}

private fun extractTimeLevel(line: String): Pair<LocalDateTime?, String?> {
    val m = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(?:[,\\.]\\d+)?\\s+").matcher(line)
    val ts = if (m.find()) parseTime(m.group(1)) else null
    val m2 = Pattern.compile("\\b(INFO|WARN|ERROR)\\b").matcher(line)
    val lv = if (m2.find()) m2.group(1) else null
    return Pair(ts, lv)
}

private fun validateParams(p: JsonObject): String? {
    val path = p["log_path"]?.toString()?.trim('"')
    if (path.isNullOrBlank()) return "log_path 必须为非空字符串"
    p["log_level"]?.let {
        val lv = it.toString().trim('"')
        if (lv.isNotBlank() && !validLevels.contains(lv)) return "log_level 仅支持 INFO/WARN/ERROR"
    }
    p["start_time"]?.let {
        if (parseTime(it.toString().trim('"')) == null) return "start_time 格式错误，应为 YYYY-MM-DD HH:MM:SS"
    }
    p["end_time"]?.let {
        if (parseTime(it.toString().trim('"')) == null) return "end_time 格式错误，应为 YYYY-MM-DD HH:MM:SS"
    }
    return null
}

private fun handler(p: JsonObject, file: File): JsonObject {
    val kw = p["keyword"]?.toString()?.trim('"')?.lowercase()?.takeIf { it.isNotBlank() }
    val lv = p["log_level"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
    val st = parseTime(p["start_time"]?.toString()?.trim('"'))
    val et = parseTime(p["end_time"]?.toString()?.trim('"'))
    val limit = p["limit"]?.toString()?.toIntOrNull() ?: defaultLimit
    val offset = p["offset"]?.toString()?.toIntOrNull() ?: 0

    var total = 0
    val entries = mutableListOf<JsonObject>()
    file.useLines { seq ->
        var idx = 0
        seq.forEach { line ->
            idx += 1
            val (ts, detLv) = extractTimeLevel(line)
            if (kw != null && !line.lowercase().contains(kw)) return@forEach
            if (lv != null && detLv != lv) return@forEach
            if (st != null && ts != null && ts.isBefore(st)) return@forEach
            if (et != null && ts != null && ts.isAfter(et)) return@forEach
            total += 1
            if (total > offset && entries.size < limit) {
                entries.add(
                    buildJsonObject {
                        put("lineNumber", idx)
                        put("timestamp", ts?.format(timeFmt))
                        put("level", detLv)
                        put("text", line)
                    }
                )
            }
        }
    }

    return buildJsonObject {
        put("count", entries.size)
        put("totalMatched", total)
        put("offset", offset)
        put("limit", limit)
        put("entries", Json.parseToJsonElement(json.encodeToString(ListSerializer(JsonObject.serializer()), entries)))
    }
}

fun runMcpServer() {
    // 声明服务
    val server = Server(
        Implementation(
            name = "serviceLogQuery", // Tool name is "weather"
            version = "1.0.0", // Version of the implementation
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    // 声明服务工具
    server.addTool(
        name = "call",
        description = """
            中文:查询服务器日志文件，支持按关键词、时间范围、日志级别筛选;
            English: Query server log files, support filtering by keywords, time range, and log level;
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("log_path", buildJsonObject { put("type", "string"); put("description", "服务器日志文件的绝对路径") })
                put("keyword", buildJsonObject { put("type", "string"); put("description", "筛选日志的关键词（可选）") })
                put("start_time", buildJsonObject { put("type", "string"); put("description", "开始时间（格式：YYYY-MM-DD HH:MM:SS，可选）") })
                put("end_time", buildJsonObject { put("type", "string"); put("description", "结束时间（格式：YYYY-MM-DD HH:MM:SS，可选）") })
                put("log_level", buildJsonObject { put("type", "string"); put("enum", Json.parseToJsonElement(json.encodeToString(ListSerializer(serializer<String>()), validLevels.toList()))); put("description", "日志级别（可选）") })
                put("limit", buildJsonObject { put("type", "number"); put("description", "返回的最大条数（可选）") })
                put("offset", buildJsonObject { put("type", "number"); put("description", "从匹配结果的偏移量开始返回（可选）") })
            },
            required = listOf("log_path"),
        ),
    ){ request ->
        val p = request.arguments!!
        val err = validateParams(p)

        if (err != null) return@addTool CallToolResult(
            content = listOf(TextContent(err)),
            isError = true,
            structuredContent = buildJsonObject { put("error", buildJsonObject { put("code", "INVALID_PARAMS"); put("message", err) }) }
        )

        val path = p["log_path"]!!.toString().trim('"')
        val file = File(path)
        if (!file.exists()) return@addTool CallToolResult(
            content = listOf(TextContent("日志文件不存在")),
            isError = true,
            structuredContent = buildJsonObject { put("error", buildJsonObject { put("code", "NOT_FOUND"); put("message", "日志文件不存在") }) }
        )
        if (!file.canRead()) return@addTool CallToolResult(
            content = listOf(TextContent("没有读取日志文件的权限")),
            isError = true,
            structuredContent = buildJsonObject { put("error", buildJsonObject { put("code", "PERMISSION_DENIED"); put("message", "没有读取日志文件的权限") }) }
        )

        val resultJson = handler(p, file)
        CallToolResult(
            content = listOf(TextContent(resultJson.toString())),
            isError = false,
            structuredContent = buildJsonObject { put("data", resultJson) }
        )
    }

    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose {
            done.complete()
        }
        done.join()
    }
}

fun main() = runMcpServer()