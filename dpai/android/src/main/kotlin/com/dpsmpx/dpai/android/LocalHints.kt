package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.localai.LocalAI
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.ui.DashboardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подсказки локального ИИ (D-105): какой профиль спрашивать — выбор
 * человека среди профилей на этом устройстве или в частной сети; по
 * умолчанию — «Телефон: llama.cpp», если он есть. Ничего не делает сам:
 * ответ — непроверенный текст на экране.
 */
object LocalHints {
    private const val PREFS = "localai"
    private const val KEY = "profile"

    /** Профили, которые годятся локальному ИИ. */
    fun candidates(all: List<ApiProfile>): List<ApiProfile> = all.filter { LocalAI.local(it) is Outcome.Success }

    fun chosen(context: Context, all: List<ApiProfile>): ApiProfile? {
        val local = candidates(all)
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return local.firstOrNull { it.id.value == id } ?: local.firstOrNull { it.name == com.dpsmpx.dpai.accounts.ApiPresets.phoneLlama().name } ?: local.firstOrNull()
    }

    fun choose(context: Context, profile: ApiProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, profile.id.value).apply()
    }

    /**
     * Подсказки по сводке: отклонения в состоянии и счётчиках, сводка того,
     * что ждёт внимания. Блокирующий вызов — с фонового потока.
     */
    fun dashboard(context: Context, screen: DashboardScreen, cancel: Cancel): String {
        ModelsStore.init(context)
        val profile = chosen(context, ModelsStore.profiles.value) ?: return "Нет локальной модели: запустите llama.cpp во вкладке Linux или добавьте профиль ПК (Ollama, llama.cpp) во вкладке «Модели»."
        val ai = when (val r = LocalAI.of(profile, ModelsStore.provider(profile))) {
            is Outcome.Failure -> return "Локальный ИИ: ${r.errors.first().message}"
            is Outcome.Success -> r.value
        }
        val metrics = listOf("состояние" to screen.health) + screen.reasons.map { "причина" to it } + screen.counters.map { it.label to it.value }
        val out = mutableListOf<String>()
        when (val h = ai.anomalies(metrics, cancel)) {
            is Outcome.Success -> out += if (h.value.hints.isEmpty()) "Отклонений модель не видит." else h.value.hints.joinToString("\n") { "· $it" }
            is Outcome.Failure -> out += "Отклонения: ${h.errors.first().message}"
        }
        if (screen.attention.isNotEmpty()) {
            when (val s = ai.notifications(screen.attention, cancel)) {
                is Outcome.Success -> out += "Внимание: ${s.value.text}"
                is Outcome.Failure -> out += "Сводка «Внимание»: ${s.errors.first().message}"
            }
        }
        return out.joinToString("\n")
    }
}

/** Карточка сводки (D-105): «Спросить модель» — подсказки по кнопке, не фоном. */
@Composable
fun LocalHintsCard(screen: DashboardScreen) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by ModelsStore.profiles.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    val profile = remember(profiles) { LocalHints.chosen(context, profiles) }
    Card {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Подсказки локальной модели", style = MaterialTheme.typography.titleSmall)
            Text(
                profile?.let { "Модель: ${it.name} (${it.model}). Подсказка — непроверенная, решает человек." }
                    ?: "Локальной модели нет: llama.cpp — вкладка Linux; ПК — профиль Ollama или llama.cpp во вкладке «Модели».",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    answer = withContext(Dispatchers.IO) { LocalHints.dashboard(context, screen, Cancel()) }
                    busy = false
                }
            }) { Text(if (busy) "Спрашиваю…" else "Спросить модель") }
            answer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
