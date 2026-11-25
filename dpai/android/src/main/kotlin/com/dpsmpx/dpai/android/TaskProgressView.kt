package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpai.ui.TaskProgress

/** Прогресс живого процесса; неизвестный объём остаётся неопределённой полосой. */
@Composable
internal fun TaskProgressView(label: String, output: String) {
    val step = remember(output) { TaskProgress.latest(output) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        step?.let {
            val phase = stringResource(when (it.phase) {
                TaskProgress.Phase.DOWNLOAD -> R.string.progress_download
                TaskProgress.Phase.VERIFY -> R.string.progress_verify
                TaskProgress.Phase.CACHE -> R.string.progress_cache
                TaskProgress.Phase.INSTALLED -> R.string.progress_installed
                TaskProgress.Phase.UNPACK -> R.string.progress_unpack
                TaskProgress.Phase.CONFIGURE -> R.string.progress_configure
                TaskProgress.Phase.READY -> R.string.progress_component_ready
                TaskProgress.Phase.WAITING -> R.string.progress_waiting
                TaskProgress.Phase.TASK -> R.string.progress_task
                TaskProgress.Phase.PACKAGES -> R.string.progress_packages
            })
            Text("$phase: ${it.detail}", style = MaterialTheme.typography.bodySmall)
            val current = it.current
            val total = it.total
            if (current != null && total != null) Text(stringResource(R.string.progress_count, current, total), style = MaterialTheme.typography.labelSmall)
        }
        val fraction = step?.fraction
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.progress_percent, (fraction * 100).toInt()), style = MaterialTheme.typography.labelSmall)
        }
    }
}
