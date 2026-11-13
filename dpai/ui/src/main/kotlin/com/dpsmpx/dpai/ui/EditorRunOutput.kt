package com.dpsmpx.dpai.ui

/** Ограниченный хвост только первого запуска IDE: последующая интерактивная оболочка не анализируется (D-139). */
class EditorRunOutput(private val file: String = "main.cpp") {
    private val bytes = ByteArray(MAX_BYTES)
    private var end = 0
    private var size = 0
    private var done = false
    private val status = Regex("(?:^|[\\r\\n])\\[Код выхода: ([0-9]{1,3})]\\r?\\n")
    @Synchronized fun feed(input: ByteArray, count: Int = input.size): RunDiagnosis? {
        require(count in 0..input.size)
        if (done || count == 0) return null
        val kept = count.coerceAtMost(MAX_BYTES)
        val prefix = tail(size.coerceAtMost(160))
        val scan = ByteArray(prefix.size + kept)
        prefix.copyInto(scan)
        input.copyInto(scan, prefix.size, count - kept, count)
        val first = (MAX_BYTES - end).coerceAtMost(kept)
        input.copyInto(bytes, end, count - kept, count - kept + first)
        if (first < kept) input.copyInto(bytes, 0, count - kept + first, count)
        end = (end + kept) % MAX_BYTES
        size = (size + kept).coerceAtMost(MAX_BYTES)
        val code = status.find(scan.toString(Charsets.UTF_8))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        done = true
        val text = tail(size).toString(Charsets.UTF_8)
        // Места в коде — и при успехе: предупреждения компилятора тоже ведут в код (D-153).
        return RunDiagnostics.analyze(RunLanguage.of(file), text, code).copy(problems = BuildProblems.parse(text))
    }
    private fun tail(count: Int): ByteArray {
        val start = (end - count + MAX_BYTES) % MAX_BYTES
        val first = count.coerceAtMost(MAX_BYTES - start)
        return ByteArray(count).also {
            bytes.copyInto(it, 0, start, start + first)
            if (first < count) bytes.copyInto(it, first, 0, count - first)
        }
    }
    companion object { const val MAX_BYTES = 131_072 }
}
