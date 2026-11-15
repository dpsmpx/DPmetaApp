package com.dpsmpx.dpmeta.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Окно знакомства с приложением (D-150): само — при первом запуске, затем — из
 * «Справки» (`app:intro`). Страницы — данными: их проверяет тест, экран только рисует.
 */
object Intro {
    /** Страница: заголовок и абзацы. */
    data class Page(val title: String, val lines: List<String>)

    /** Что можно сделать с последней страницы. */
    enum class Action(val label: String) {
        NEW_PROJECT("Создать проект"),
        LINUX("Linux приложения"),
        GITHUB("Подключить GitHub — по желанию"),
    }

    val PAGES: List<Page> = listOf(
        Page(
            "DPmetaApp — студия разработки в телефоне",
            listOf(
                "Проекты, редактор, сборка, запуск и ИИ — в одном приложении.",
                "Данные хранятся на устройстве. В сеть уходит только то, что вы включили сами: GitHub, облачная модель, ПК по SSH.",
            ),
        ),
        Page(
            "Разделы",
            listOf(
                "Кнопка ≡ слева вверху открывает панель разделов.",
                "«Сводка» — что происходит во всех проектах. «Проекты» — проекты на устройстве и, если подключён GitHub, репозитории с PR и вопросами.",
                "«IDE» — файлы, редактор и терминал. «AI» — сессии ИИ, задачи и запросы на подтверждение. «Настройки» — всё одним документом с поиском.",
            ),
        ),
        Page(
            "Проекты без GitHub",
            listOf(
                "Мастер создаёт проект на C++, Kotlin или Python: файлы, сборку, тесты и локальный Git с первым коммитом.",
                "По умолчанию — в Linux приложения на телефоне, в ~/projects/local; можно выбрать Termux или ПК. Android-приложения собираются на ПК.",
                "GitHub — по желанию: опубликовать проект можно позже, а токен нужен только для карточек репозиториев, клонов и PR.",
            ),
        ),
        Page(
            "IDE и сборка",
            listOf(
                "Рабочий проект в IDE: файлы с подсветкой и подсказками, вкладки «Сборка», «Проверки» и «Запуск», отладчик и терминал Linux.",
                "Тяжёлую сборку можно отдать ПК: файлы, команды и APK — по SSH, подпись APK — вашим ключом.",
            ),
        ),
        Page(
            "ИИ",
            listOf(
                "Claude, Codex и Gemini работают в Linux приложения или на ПК; локальные модели — без сети.",
                "Каждая задача ИИ идёт в своём рабочем дереве Git и проверяется командами проекта; опасные действия ждут вашего подтверждения.",
            ),
        ),
        Page(
            "С чего начать",
            listOf(
                "1. Поставьте Linux приложения — в нём работают терминал, сборка и ИИ.",
                "2. Создайте проект мастером.",
                "3. Если нужно — подключите GitHub и войдите в слот ИИ.",
                "Это окно открывается снова из «Справки».",
            ),
        ),
    )

    private const val PREFS = "dpmeta.intro"
    private const val SEEN = "seen"

    /** Окно уже показывали (закрыто «Пропустить» или «Готово»). */
    fun seen(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()
    }
}

/**
 * Окно знакомства: страницы по одной, «Далее» и «Назад», «Пропустить» — сразу
 * закрыть; на последней — действия [actions] (нет обработчика — нет кнопки).
 */
@Composable
fun IntroDialog(actions: Map<Intro.Action, () -> Unit>, onClose: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = Intro.PAGES.lastIndex
    val p = Intro.PAGES[page.coerceIn(0, last)]
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${page + 1} из ${last + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    for (line in p.lines) Text(line, style = MaterialTheme.typography.bodyLarge)
                    if (page == last) {
                        Spacer(Modifier.height(8.dp))
                        for (a in Intro.Action.entries) actions[a]?.let { act ->
                            OutlinedButton(onClick = { onClose(); act() }, Modifier.fillMaxWidth()) { Text(a.label) }
                        }
                    }
                }
                Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (page < last) TextButton(onClick = onClose) { Text("Пропустить") }
                    if (page > 0) TextButton(onClick = { page-- }) { Text("Назад") }
                    if (page < last) Button(onClick = { page++ }) { Text("Далее") } else Button(onClick = onClose) { Text("Готово") }
                }
            }
        }
    }
}
