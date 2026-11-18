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
 * модулей пульта не зависит. Разделы «AI» (D-151) — те же карточки, что в окне
 * «AI», на общих контроллере и модели; экраны со своей навигацией (Linux,
 * инструкция, PR, маршруты, журнал) открывает [onAiSettings] сразу на нужном
 * подэкране (`null` — корень «Настроек AI»). [initialQuery] — поиск по ссылке
 * из справки.
 */
@Composable
fun SettingsDocument(projects: (@Composable () -> Unit)?, initialQuery: String = "", onAiSettings: (sub: String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Карточки «AI» работают с тем же контроллером и моделью, что окно «AI»: одно приложение — одно состояние (D-151).
    val controller = remember { Controller.get(context.applicationContext.filesDir) }
    val model = remember { DemoModel.get(context.applicationContext) }
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
                        REVOKE -> EmergencyRevokeCard()
                        APP_BACKUP -> AppBackupCard()
                        AI_LINUX -> {
                            Entry("Linux в приложении", "Alpine, пакеты, слоты ИИ") { onAiSettings(SETTINGS_LINUX) }
                            Entry("Инструкция", "Linux приложения и ПК по SSH — по шагам") { onAiSettings(SETTINGS_GUIDE) }
                            AppDiskCard()
                        }
                        AI_ACCESS -> {
                            ControlCard(model)
                            AppKeyCard(controller)
                            CliCard()
                            OverlayCard()
                        }
                        AI_SERVICES -> {
                            Entry("PR на GitHub", "открытые PR проекта, проверки, отзывы — через gh") { onAiSettings(SETTINGS_GITHUB) }
                            Entry("Маршруты Claude Code", "облачные сессии по токену маршрута — для поручений из пульта") { onAiSettings(SETTINGS_ROUTINES) }
                        }
                        AI_COPIES -> Card(Modifier.fillMaxWidth()) {
                            Column {
                                BackupCard(controller, framed = false) {}
                                HorizontalDivider()
                                CloudCard(model, framed = false)
                            }
                        }
                        AI_LOG -> {
                            Entry("Журнал", "строки журнала, выгрузка, отладка") { onAiSettings(SETTINGS_LOG) }
                            Entry("Все настройки AI", "окно «AI»: targets и остальные экраны") { onAiSettings(null) }
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
private const val REVOKE = "revoke"
private const val APP_BACKUP = "app-backup"
private const val AI_LINUX = "ai-linux"
private const val AI_ACCESS = "ai-access"
private const val AI_SERVICES = "ai-services"
private const val AI_COPIES = "ai-copies"
private const val AI_LOG = "ai-log"

private fun sections(pult: Boolean): List<Section> = listOfNotNull(
    Section(LOOK, "Оформление", "Тема, акцент, размер текста, плотность и шрифт редактора — сразу во всём приложении.",
        "тема тёмная светлая цвет акцент обои размер текст шрифт плотность редактор оформление"),
    if (pult) {
        Section(PROJECTS, "Проекты и GitHub", "Токен GitHub, синхронизация, клоны, уведомления и правила, история метрик, время, журнал действий, команды.",
            "github токен синхронизация обновить клоны репозитории история метрики csv json выгрузка часовой пояс время журнал действия спросить claude команды о программе версия уведомления правила тихие часы приватность лента импорт экспорт regex")
    } else {
        null
    },
    Section(STORAGE, "Хранилище и секреты", "Предел объёма, свободное место, карта ~/STORAGE.md и секреты для ИИ.",
        "хранилище место память гигабайт предел карта storage секреты токен ключ api hugging face github давать ии"),
    Section(REVOKE, "Аварийный отзыв", "Все секреты устройства: где каждый отозвать и «Стереть отмеченные» — если телефон потерян или секрет утёк.",
        "аварийный отзыв секреты стереть удалить потерян украден утечка токен ключ api ssh подпись отозвать"),
    Section(APP_BACKUP, "Резервная копия приложения", "Копия разделов в файл — с секретами только под паролем; восстановление выбранных разделов; сама раз в сутки без секретов.",
        "резервная копия бэкап backup восстановить восстановление новый телефон перенос пароль секреты загрузки разделы"),
    Section(AI_LINUX, "Linux и диск приложения", "Linux в приложении, инструкция по шагам, диск приложения в менеджере файлов.",
        "ai linux alpine пакеты слоты агент инструкция шаги диск файлы менеджер targets пк"),
    Section(AI_ACCESS, "Управление и доступ", "Пауза и автономность контура, ключ приложения для targets, команда dpai, окно запросов поверх приложений.",
        "ai управление пауза автономный режим контур ключ доступа ssh публичный targets dpai cli команда канал окно запросов поверх приложений подтверждение"),
    Section(AI_SERVICES, "GitHub и маршруты", "PR проекта через gh и маршруты Claude Code для поручений.",
        "ai github pr gh проверки отзывы маршруты claude code routine облачные сессии поручения токен"),
    Section(AI_COPIES, "Копии и восстановление", "Копия настроек в «Загрузках», загрузка сохранённой копии, облачная копия контура.",
        "ai копия настроек загрузки backup восстановление доступа переустановка облако облачная копия пароль токен репозиторий"),
    Section(AI_LOG, "Журнал и остальное", "Журнал приложения и окно «AI» целиком.",
        "ai журнал лог отладка выгрузка ошибки настройки targets"),
)
