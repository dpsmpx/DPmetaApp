package com.dpsmpx.dpmeta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats

/**
 * Плитки сводки (UX-6; F-04; D-112): сетка в две колонки — только числа: значение
 * и изменение за срок, где есть история; графиков и спарклайнов нет. Касание —
 * экран плитки, у плитки — одна подпись для TalkBack.
 */
@Composable
fun SummaryTiles(tiles: List<Screens.Tile>, history: Map<String, Stats.Tile>, onOpen: (Screens.Goes) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) {
                    val change = t.metric?.let(history::get)?.change.orEmpty()
                    // Одно описание на плитку (UX-15): дочерние тексты не читаются второй раз.
                    Card(
                        modifier = Modifier.weight(1f).clearAndSetSemantics {
                            contentDescription = "${t.title}: ${t.value}${if (change.isEmpty()) "" else ", $change"}"
                            role = androidx.compose.ui.semantics.Role.Button
                            onClick(label = "открыть «${t.goes.title}»") { onOpen(t.goes); true }
                        }.clickable { onOpen(t.goes) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (change.isNotEmpty()) Text(change, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
