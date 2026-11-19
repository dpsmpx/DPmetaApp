package com.dpsmpx.dpmeta.model

/** D-176: v1 — личные авторы, v2 — все авторы с фильтром веток, v3 — все ветки пользователя. */
object HistorySchema {
    const val LEGACY = "clone.history.v1"
    const val FILTERED_BRANCHES = "clone.history.v2"
    const val CURRENT = "clone.history.v3"
}
