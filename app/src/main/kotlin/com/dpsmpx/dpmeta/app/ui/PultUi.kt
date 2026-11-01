package com.dpsmpx.dpmeta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.metrics.EcosystemState

/**
 * Токены оформления пульта (D-15; ТЗ, §9): свои цвета, светлая и тёмная
 * схемы по системе. Цвета — здесь и только здесь.
 */
object Tokens {
    val light: ColorScheme = lightColorScheme(primary = Color(0xFF2F5D8A), secondary = Color(0xFF5B6B7A), error = Color(0xFFB3261E))
    val dark: ColorScheme = darkColorScheme(primary = Color(0xFF9CC3EA), secondary = Color(0xFFB7C4D1), error = Color(0xFFF2B8B5))
    val gap = 12.dp
}

@Composable
fun PultTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Tokens.dark else Tokens.light, content = content)

/** Состояние экрана, которое держит активность: срез, строка о синхронизации, есть ли токен. */
data class PultView(val state: EcosystemState?, val status: String, val hasToken: Boolean, val busy: Boolean)

private enum class Tab(val title: String) { SUMMARY("Сводка"), PROJECTS("Проекты"), QUESTIONS("Вопросы"), SETTINGS("Настройки") }

@Composable
fun PultApp(view: PultView, onSync: () -> Unit, onSaveToken: (String) -> Unit, onForgetToken: () -> Unit) {
    var tab by remember { mutableStateOf(if (view.hasToken) Tab.SUMMARY else Tab.SETTINGS) }
    var open by remember { mutableStateOf<String?>(null) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(selected = tab == t && open == null, onClick = { tab = t; open = null }, icon = {}, label = { Text(t.title) })
                }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).padding(Tokens.gap).fillMaxSize()) {
            val s = view.state
            when {
                open != null && s != null -> Screens.card(s, open!!)?.let { CardScreen(it) { open = null } }
                tab == Tab.SETTINGS -> Settings(view, onSync, onSaveToken, onForgetToken)
                s == null -> Text(if (view.hasToken) "Среза ещё нет: «Обновить» в настройках." else "Нужен токен GitHub: вкладка «Настройки».")
                tab == Tab.SUMMARY -> Summary(Screens.summary(s), view, onSync)
                tab == Tab.PROJECTS -> Projects(Screens.projects(s)) { open = it }
                tab == Tab.QUESTIONS -> Questions(Screens.questions(s))
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label)
    Text(value, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Summary(s: Screens.Summary, view: PultView, onSync: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    Text("Срез ${s.asOf}", style = MaterialTheme.typography.titleMedium)
    Line("Работают", s.active)
    Line("Вопросов автору", s.questions)
    Line("Открытых PR", s.pullsWaiting)
    Line("Проектов", s.projects.toString())
    Line("Ошибок источников", s.sourceErrors.toString())
    Button(onClick = onSync, enabled = !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) Text(view.status, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Projects(groups: List<Screens.Group>, onOpen: (String) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    for (g in groups) {
        item { Text(g.title, style = MaterialTheme.typography.titleMedium) }
        items(g.rows) { r ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(r.name) }) {
                Column(Modifier.padding(Tokens.gap)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text("${r.working}; решений ${r.decisions}, TODO ${r.todo}, ROADMAP ${r.roadmap}, PR ${r.pulls}" + if (r.errors > 0) "; ошибок ${r.errors}" else "")
                }
            }
        }
    }
}

@Composable
private fun CardScreen(c: Screens.Card, onBack: () -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { TextButton(onClick = onBack) { Text("← Проекты") } }
    item { Text("${c.name} — ${c.place}, ${c.working}", style = MaterialTheme.typography.titleMedium) }
    item { Text("Решений: ${c.decisions}", fontWeight = FontWeight.SemiBold) }
    items(c.lastDecisions) { Text("  $it") }
    item { Text("TODO: ${c.todo}, за автором ${c.forAuthor}", fontWeight = FontWeight.SemiBold) }
    item { Text("ROADMAP: ${c.roadmap}", fontWeight = FontWeight.SemiBold) }
    items(c.stages) { s -> Text("  [${s.flag}] ${s.title}" + if (s.steps.isEmpty()) "" else " — ${s.steps}") }
    item { Text("PR: ${c.pulls.size}", fontWeight = FontWeight.SemiBold) }
    items(c.pulls) { p -> Text("  #${p.number} ${p.title}" + (if (p.draft) " (черновик)" else "") + " — ${p.head}, ${p.updated}") }
    item { Text("Ветки: ${c.branches.size}", fontWeight = FontWeight.SemiBold) }
    items(c.branches) { b -> Text("  ${if (b.recent) "●" else "○"} ${b.name} — ${b.updated}") }
    if (c.errors.isNotEmpty()) {
        item { Text("Ошибки источников", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        items(c.errors) { Text("  $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Questions(q: List<Screens.Question>) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { Text("Вопросов автору: ${q.size}", style = MaterialTheme.typography.titleMedium) }
    items(q) { i -> Text("${i.project}: " + (i.label?.let { "[$it] " } ?: "") + i.title) }
}

@Composable
private fun Settings(view: PultView, onSync: () -> Unit, onSaveToken: (String) -> Unit, onForget: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    var token by remember { mutableStateOf("") }
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Text(
        if (view.hasToken) {
            "Токен сохранён под ключом Keystore."
        } else {
            "Fine-grained PAT только для чтения: Contents, Pull requests, Metadata (D-17)."
        },
    )
    OutlinedTextField(value = token, onValueChange = { token = it.trim() }, label = { Text("Токен") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
        Button(onClick = { onSaveToken(token); token = "" }, enabled = token.isNotEmpty()) { Text("Сохранить") }
        if (view.hasToken) TextButton(onClick = onForget) { Text("Забыть токен") }
    }
    Spacer(Modifier.height(Tokens.gap))
    Button(onClick = onSync, enabled = view.hasToken && !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) Text(view.status, style = MaterialTheme.typography.bodySmall)
}
