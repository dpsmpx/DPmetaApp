package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.dpsmpx.dpai.ui.ProfileColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Цвет профиля Claude (`DPmetaApp:D-83`; ТЗ DPmetaApp, F-17) — отметка «id
 * профиля → ключ цвета»: у API-профиля и у профиля подписки. Это оформление,
 * а не данные профиля: форматы профилей и подписок не меняются; хранится в
 * настройках приложения (вне копий, как остальные настройки «AI»).
 */
object ProfileMarks {
    private const val PREFS = "dpai.profile-colors"

    private val flow = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Отметки: id профиля (`acc_…`, `sub_…`) → ключ цвета ([ProfileColors]). */
    val colors: StateFlow<Map<String, String>> = flow

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            flow.value = prefs.all.mapNotNull { (k, v) -> (v as? String)?.takeIf { ProfileColors.of(it) != null }?.let { k to it } }.toMap()
            loaded = true
        }
    }

    /** Цвет профиля [id]; без цвета — `null`. */
    fun of(id: String?): ProfileColors.Color? = id?.let { ProfileColors.of(flow.value[it]) }

    /** Задать цвет профиля [id]; `null` — без цвета. */
    fun set(context: Context, id: String, key: String?) {
        init(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = key?.takeIf { ProfileColors.of(it) != null }
        prefs.edit { if (known == null) remove(id) else putString(id, known) }
        flow.value = if (known == null) flow.value - id else flow.value + (id to known)
    }
}

/** Кружок цвета профиля у имени (`DPmetaApp:D-83`): не фон и не текст; без цвета — ничего. Для TalkBack — не элемент. */
@Composable
fun ProfileDot(color: ProfileColors.Color?, size: Int = 10) {
    if (color == null) return
    val dark = isSystemInDarkTheme()
    Box(Modifier.size(size.dp).background(Color(color.of(dark)), CircleShape).clearAndSetSemantics { })
}

/** Выбор цвета профиля: «без цвета» и набор [ProfileColors]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfileColorChips(selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("без цвета") })
        for (c in ProfileColors.ALL) {
            FilterChip(
                selected = selected == c.key,
                onClick = { onSelect(c.key) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileDot(c)
                        Text(c.title)
                    }
                },
            )
        }
    }
}
