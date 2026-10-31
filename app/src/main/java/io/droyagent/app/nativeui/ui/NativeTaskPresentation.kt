package io.droyagent.app.nativeui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.NativeRuntimeHost

/** The existing pet presenter must know when this task already has a foreground conversation. */
@Composable
fun NativeTaskPresentation(sessionId: String?) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, sessionId) {
        val lease = Any()
        fun update() = NativeRuntimeHost.presentConversation(context, lease, sessionId,
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            NativeRuntimeHost.presentConversation(context, lease, sessionId, false)
        }
    }
}
