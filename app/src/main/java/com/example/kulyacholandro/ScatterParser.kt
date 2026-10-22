package com.example.kulyacholandro

data class ScatterPartition(
    val index: String,
    val name: String,
    val fileName: String,
    val isDownload: Boolean,
    val startAddr: Long,
    val sizeBytes: Long
)

object ScatterParser {

    fun platform(text: String): String {
        val rx = Regex("""platform:\s*(\S+)""", RegexOption.IGNORE_CASE)
        return rx.find(text)?.groupValues?.get(1) ?: "לא ידוע"
    }

    fun parse(text: String): List<ScatterPartition> {
        val result = mutableListOf<ScatterPartition>()
        val blocks = text.split(Regex("(?=- partition_index:)"))
        for (block in blocks) {
            val index    = field(block, "partition_index") ?: continue
            val name     = field(block, "partition_name") ?: continue
            val fileName = field(block, "file_name") ?: "NONE"
            val isDl     = field(block, "is_download")?.lowercase() == "true"
            val addrStr  = field(block, "linear_start_addr") ?: field(block, "physical_start_addr") ?: "0x0"
            val sizeStr  = field(block, "partition_size") ?: "0x0"
            val addr     = parseLong(addrStr)
            val size     = parseLong(sizeStr)
            result.add(ScatterPartition(index, name, fileName, isDl, addr, size))
        }
        return result
    }

    private fun field(block: String, key: String): String? {
        val rx = Regex("""$key:\s*(.+)""")
        return rx.find(block)?.groupValues?.get(1)?.trim()
    }

    private fun parseLong(s: String): Long {
        return try {
            if (s.startsWith("0x") || s.startsWith("0X"))
                s.substring(2).toLong(16)
            else
                s.toLong()
        } catch (_: Exception) { 0L }
    }

    /** Strip -verified suffix for display/matching */
    fun baseName(fileName: String): String {
        return fileName.replace("-verified", "")
    }
}
