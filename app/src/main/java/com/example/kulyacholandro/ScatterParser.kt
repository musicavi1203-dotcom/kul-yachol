package com.example.kulyacholandro

data class ScatterPartition(
    val name: String,
    val fileName: String,
    val isDownload: Boolean,
    val startAddr: Long,
    val sizeBytes: Long,
    val operation: String
)

/**
 * מפענח קובץ scatter של MTK (טקסט בפורמט YAML-like).
 * לא כותב כלום — קריאה בלבד.
 */
object ScatterParser {

    fun platform(text: String): String? =
        Regex("platform:\\s*(\\S+)").find(text)?.groupValues?.get(1)

    fun parse(text: String): List<ScatterPartition> {
        val result = mutableListOf<ScatterPartition>()
        var current: MutableMap<String, String>? = null

        fun flush() {
            val m = current ?: return
            val name = m["partition_name"] ?: return
            result += ScatterPartition(
                name = name,
                fileName = m["file_name"] ?: "NONE",
                isDownload = m["is_download"] == "true",
                startAddr = parseHex(m["linear_start_addr"]),
                sizeBytes = parseHex(m["partition_size"]),
                operation = m["operation_type"] ?: ""
            )
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("- partition_index:")) {
                flush()
                current = mutableMapOf()
                continue
            }
            val m = current ?: continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            m[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        flush()
        return result
    }

    private fun parseHex(s: String?): Long =
        s?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
}
