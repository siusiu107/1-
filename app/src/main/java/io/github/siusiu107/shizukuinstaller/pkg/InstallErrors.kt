package io.github.siusiu107.shizukuinstaller.pkg

/** pm 출력(스택트레이스 포함)을 사람이 읽을 한두 줄로 줄인다. */
object InstallErrors {

    private val FAILURE = Regex("""Failure \[([^\]]+)]""")

    fun summarize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        FAILURE.find(text)?.let { return it.groupValues[1].trim() }
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("at ") && !it.startsWith("...") }
            .map { it.removePrefix("Exception occurred while executing 'install':").trim() }
            .filter { it.isNotEmpty() }
        return lines.take(2).joinToString("\n").take(300)
    }
}
