package com.dpsmpx.dpai.core.git

/** Общая проверка имён и SHA для JGit, Git CLI и GitHub; секреты и транспорт сюда не входят. */
object GitNames {
    private val SHA = Regex("[0-9a-f]{40}")
    private val CLI_BRANCH = Regex("[A-Za-z0-9._/-]{1,200}")

    fun sha(value: String): Boolean = SHA.matches(value)

    /** refs/heads/<имя>; правила check-ref-format, без ограничения алфавита JGit. */
    fun branchRef(value: String): Boolean = value.isNotEmpty() && !value.endsWith('.') && ".." !in value && "@{" !in value &&
        value.none { it.code <= 32 || it.code == 127 || it in "~^:?*[\\" } &&
        value.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }

    /** Прежний ограниченный алфавит команд target плюс отказ на несуществующих в Git именах. */
    fun cliBranch(value: String): Boolean = CLI_BRANCH.matches(value) && !value.startsWith('-') && value != "HEAD" && branchRef(value)
}
