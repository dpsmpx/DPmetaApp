package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Настройки — одним документом (`DPmetaApp:D-112`): поиск сверху, оглавление
 * разделами, ниже — разделы с заголовками и своими полями, переключателями и
 * кнопками. Поиск оставляет разделы, в названии или словах которых есть запрос.
 * Блок пульта («Проекты и GitHub») приходит слотом [projects]: подсистема от
 * модулей пульта не зависит. [onAiSettings] — «Настройки AI» (Linux, targets,
 * маршруты, журнал, копии), пока они не перенесены сюда целиком; [initialQuery] —
 * поиск по ссылке из справки.
 */
@Composable
fun SettingsDocument(projects: (@Composable () -> Unit)?, initialQuery: String = "", onAiSettings: () -> Unit) {
    // Запрос из справки (ссылка «Настройки → тема») — сразу в поиске; новый запрос — новый поиск.
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sections = remember(projects != null) { sections(projects != null) }
    val shown = sections.filter { it.matches(query) }
    LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Найти настройку") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Оглавление (D-112): разделы по смыслу; касание — к разделу.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEachIndexed { i, s -> SuggestionChip(onClick = { scope.launch { list.animateScrollToItem(i + 1) } }, label = { Text(s.title) }) }
                }
                if (shown.isEmpty()) Text("Ничего не найдено. Попробуйте другое слово: «тема», «токен», «Linux», «место».", style = MaterialTheme.typography.bodyMedium)
            }
        }
        for (s in shown) {
            item(key = s.key) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(s.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (s.key) {
                        LOOK -> LookCard()
                        PROJECTS -> projects?.let { block -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { block() } } }
                        STORAGE -> StorageSecretsCard()
                        AI -> Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Linux в приложении, targets и инструкция, PR на GitHub, маршруты Claude Code, журнал, ключ доступа, копии и восстановление, окно запросов поверх приложений.", style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = onAiSettings) { Text("Открыть настройки AI") }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                AppDiskCard()
                            }
                        }
                    }
                }
            }
        }
        item(key = "top") { TextButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) { Text("К оглавлению") } }
    }
}

/** Раздел документа: ключ, заголовок, одна строка «что здесь» и слова для поиска. */
private class Section(val key: String, val title: String, val about: String, val words: String) {
    fun matches(q: String): Boolean {
        val t = q.trim().lowercase()
        return t.isEmpty() || t.split(' ').filter { it.isNotEmpty() }.all { w -> title.lowercase().contains(w) || about.lowercase().contains(w) || words.contains(w) }
    }
}

private const val LOOK = "look"
private const val PROJECTS = "projects"
private const val STORAGE = "storage"
private const val AI = "ai"

private fun sections(pult: Boolean): List<Section> = listOfNotNull(
    Section(LOOK, "Оформление", "Тема, акцент, размер текста, плотность и шрифт редактора — сразу во всём приложении.",
        "тема тёмная светлая цвет акцент обои размер текст шрифт плотность редактор оформление"),
    if (pult) {
        Section(PROJECTS, "Проекты и GitHub", "Токен GitHub, синхронизация, клоны, история метрик, время, журнал действий, команды.",
            "github токен синхронизация обновить клоны репозитории история метрики csv json выгрузка часовой пояс время журнал действия спросить claude команды о программе версия")
    } else {
        null
    },
    Section(STORAGE, "Хранилище и секреты", "Предел объёма, свободное место, карта ~/STORAGE.md и секреты для ИИ.",
        "хранилище место память гигабайт предел карта storage секреты токен ключ api hugging face github давать ии"),
    Section(AI, "AI", "Linux, targets, маршруты, журнал, копии и диск приложения.",
        "ai linux alpine targets пк агент маршруты журнал копия восстановление ключ доступа диск файлы менеджер окно запросов"),
)
