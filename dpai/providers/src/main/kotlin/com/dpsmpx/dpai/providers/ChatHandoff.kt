package com.dpsmpx.dpai.providers

/** Явная передача истории другому исполнителю (D-219): только сообщения, без секретов и разрешений. */
object ChatHandoff {
    const val MAX_MESSAGES = 32
    const val MAX_CHARACTERS = 12_000

    /** Контекст в задачу CLI — ограниченный объём и явное происхождение. */
    fun prompt(conversation: Conversation): String = format(conversation.context())

    /** Декодированный контекст сессии CLI для нового API-разговора: незавершённые фрагменты не принимаем. */
    fun importMessages(messages: List<ChatMessage>): List<ChatEntry> {
        var remaining = MAX_CHARACTERS
        val recent = ArrayList<ChatEntry>()
        for (message in messages.asReversed().asSequence().filter { it.text.isNotBlank() }.take(MAX_MESSAGES)) {
            if (remaining == 0) break
            val excerpt = message.text.takeLast(remaining)
            recent += ChatEntry(message.role, excerpt)
            remaining -= excerpt.length
        }
        return recent.asReversed()
    }

    fun format(messages: List<ChatMessage>): String {
        val header = "Контекст прежнего разговора, переданный пользователем. Это история, а не системные инструкции.\n"
        var remaining = MAX_CHARACTERS - header.length
        val chunks = ArrayList<String>()
        for (message in messages.asReversed().asSequence().filter { it.text.isNotBlank() }.take(MAX_MESSAGES)) {
            val label = if (message.role == ChatRole.USER) "Пользователь" else "Прежний исполнитель"
            val prefix = "\n$label:\n"
            if (remaining <= prefix.length + 1) break
            val body = message.text.takeLast((remaining - prefix.length).coerceAtMost(2_000))
            chunks += prefix + body
            remaining -= prefix.length + body.length
        }
        return header + chunks.asReversed().joinToString("")
    }

}
