package io.droyagent.app.nativeui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R

/** Capture metadata chooses geometry once; decoding a legacy recording never resizes it. */
internal fun voiceBubbleWidthDp(durationMs: Long?): Int = durationMs?.takeIf { it > 0 }?.let {
    156 + ((it + 999) / 1000).coerceIn(1, 60).toInt() * 2
} ?: 196

@Composable
internal fun VoiceMessageContent(
    durationMs: Long?, onPlay: () -> Unit, enabled: Boolean = true, playing: Boolean = false,
    time: String? = null, failed: Boolean = false, labelDurationMs: Long? = durationMs,
) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.width(voiceBubbleWidthDp(durationMs).dp).height(48.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPlay, enabled = enabled) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) .12f else .06f),
                modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    DroyIcon(if (playing) R.drawable.native_work_pause else R.drawable.native_audio_play,
                        stringResource(if (playing) R.string.native_work_pause else R.string.native_companion_play_voice), Modifier.size(20.dp))
                }
            }
        }
        Canvas(Modifier.width(20.dp).height(20.dp)) {
            listOf(.35f, .7f, 1f, .6f, .3f).forEachIndexed { index, fraction ->
                val x = size.width * (index + .5f) / 5f
                drawLine(tint.copy(alpha = .65f), Offset(x, size.height * (1f - fraction) / 2f),
                    Offset(x, size.height * (1f + fraction) / 2f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (failed) DroyIcon(R.drawable.native_ui_warning, stringResource(R.string.native_work_audio_failed), Modifier.size(18.dp))
        else Text(labelDurationMs?.takeIf { it > 0 }?.let {
            stringResource(R.string.native_companion_voice_seconds, ((it + 999) / 1000).toInt())
        } ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
            if (time != null) Text(time, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
