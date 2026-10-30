package io.droyagent.app.nativeui.productsettings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.droyagent.app.NativeLocale
import io.droyagent.app.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun NotificationOrganizationStatus(state: JSONObject, busy: Boolean, action: (() -> Unit)?, refresh: () -> Unit) {
    val processing = state.optJSONObject("processing")
    val status = processing?.optString("state") ?: if (state.optBoolean("enabled")) "preparing" else "off"
    val title = when (status) {
        "off" -> R.string.nps_organization_off
        "setup_required" -> R.string.nps_organization_setup
        "ready" -> R.string.nps_organization_ready
        "limited" -> R.string.nps_organization_limited
        "paused" -> R.string.nps_organization_paused
        "restoration_required" -> R.string.nps_organization_restore
        else -> R.string.nps_organization_preparing
    }
    val message = processing?.optString("message").orEmpty()
    val actionName = processing?.optString("action").orEmpty()
    val actionLabel = when (actionName) {
        "apps" -> R.string.nps_choose_apps
        "permissions" -> R.string.nps_permissions
        "model" -> R.string.nps_configure_model
        "resume" -> R.string.nps_resume_organization
        "restore" -> R.string.nps_restore_notifications
        else -> null
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("native-notification-status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("native-notification-status-title").semantics { liveRegion = LiveRegionMode.Polite })
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-notification-status-message"))
            if (status == "preparing") LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val model = state.optJSONObject("model")
            val modelLabel = when {
                model?.optBoolean("configured") == true -> R.string.nps_model_configured
                model?.optString("code") == "DROY_NOTIFICATION_MODEL_REQUIRED" -> R.string.nps_model_missing
                else -> R.string.nps_model_unchecked
            }
            Text(stringResource(modelLabel), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("native-notification-model-status"))
            val last = state.optJSONObject("lastResult")
            val at = last?.optLong("atMs") ?: 0
            if (last != null && at > 0) {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    Locale.forLanguageTag(NativeLocale.active())).format(Date(at))
                val success = last.optBoolean("success")
                Text(stringResource(if (success) R.string.nps_last_success else R.string.nps_last_failure, time),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("native-notification-last-result"))
                if (!success && last.optString("message").isNotBlank()) Text(last.getString("message"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("native-notification-result-message"))
            }
            val failure = state.optJSONObject("lastFailure")
            val failureAt = failure?.optLong("atMs") ?: 0
            if (failure != null && failureAt > 0 && failureAt != at) {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    Locale.forLanguageTag(NativeLocale.active())).format(Date(failureAt))
                Text(stringResource(R.string.nps_last_failure, time), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-notification-last-failure"))
                if (failure.optString("message").isNotBlank()) Text(failure.getString("message"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("native-notification-failure-message"))
            }
            val pending = state.optJSONObject("autoSilence")?.optInt("pendingRestoreCount") ?: 0
            if (pending > 0) Text(stringResource(R.string.nps_pending_restore, pending),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("native-notification-pending-restore"))
            if (action != null && actionLabel != null) TextButton(onClick = action, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-notification-status-action:$actionName")) {
                Text(stringResource(actionLabel))
            }
            TextButton(onClick = refresh, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-notification-refresh-status")) {
                Text(stringResource(R.string.nps_refresh_status))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NotificationReminderChoices(minutes: Int, enabled: Boolean, select: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nps_reminder), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.nps_reminder_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(5 to R.string.nps_five_minutes, 10 to R.string.nps_ten_minutes).forEachIndexed { index, (value, label) ->
                ToggleButton(checked = minutes == value, onCheckedChange = { select(value) }, enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).testTag("native-notification-reminder:$value")
                        .semantics { role = Role.RadioButton; selected = minutes == value },
                    shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                    Text(stringResource(label), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
